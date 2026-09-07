/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftexport.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.diagnostics.KotlinToolingDiagnostics
import org.jetbrains.kotlin.gradle.plugin.diagnostics.KotlinToolingDiagnosticsSeverity
import org.jetbrains.kotlin.gradle.plugin.diagnostics.reportDiagnostic
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.ConsumerOverridesMetadataSource
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportDeclaredModuleMetadata
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportDependencySelector
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportModuleMetadataSource
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportResolvedComponent
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.declaredModuleName
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.declaredRootPackage
import org.jetbrains.kotlin.gradle.utils.LazyResolvedConfigurationWithArtifacts
import java.io.File
import java.io.Serializable

/**
 * Represents a module that will be exported to Swift.
 *
 * @property moduleName The name of the module in Swift
 * @property flattenPackage Optional package flattening configuration
 * @property artifact The artifact file containing the module
 * @property shouldBeFullyExported Whether this module was explicitly requested for export through the swiftExport { export("foo:bar") } DSL
 */
internal interface SwiftExportedModule : Serializable {
    val moduleName: String
    val flattenPackage: String?
    val artifact: File
    val shouldBeFullyExported: Boolean
}

internal fun createFullyExportedSwiftExportedModule(
    moduleName: String,
    flattenPackage: String?,
    artifact: File,
): SwiftExportedModule {
    return SwiftExportedModuleImp(
        moduleName,
        flattenPackage,
        artifact,
        true
    )
}

internal fun createTransitiveSwiftExportedModule(
    moduleName: String,
    artifact: File,
): SwiftExportedModule {
    return SwiftExportedModuleImp(
        moduleName,
        null,
        artifact,
        false
    )
}

/**
 * Everything [collectModules] needs, resolved.
 *
 * Assembled in one place so adding an input doesn't grow a chain of binary [Provider.zip] calls.
 */
internal class SwiftExportModulesInput(
    val exportConfiguration: LazyResolvedConfigurationWithArtifacts,
    val apiConfiguration: LazyResolvedConfigurationWithArtifacts?,
    /** Modules requested through the legacy `swiftExport { export(...) }` DSL. */
    val legacyExportedModules: Set<SwiftExportedDependency>,
    /** Overrides declared via `xcodeIntegration { configure(dependency) { } }`, keyed by the dependency they select. */
    val consumerOverrides: Map<SwiftExportDependencySelector, SwiftExportDeclaredModuleMetadata>,
    /** The Swift module name of the module being exported, for collision detection. */
    val rootModuleName: String,
)

internal fun Project.collectModules(
    input: Provider<SwiftExportModulesInput>,
): Provider<List<SwiftExportedModule>> = input.map { findAndCreateSwiftExportedModules(it) }

/**
 * One artifact of the resolved graph together with every graph node that resolved to it.
 *
 * Kotlin Multiplatform libraries publish "available-at" variants that redirect a root module (e.g.
 * `kotlinx-io-bytestring`) to a per-target one (e.g. `kotlinx-io-bytestring-iossimulatorarm64`) for the physical
 * artifact, so one klib can be reached through several nodes with different module names. Consumer overrides and
 * declared metadata are matched against all of them; the derived name comes from the first one encountered.
 */
private class ResolvedArtifactWithVersionIdentifier(
    val moduleVersion: ModuleVersionIdentifier,
    val artifact: ResolvedArtifactResult,
    firstComponent: SwiftExportResolvedComponent,
) : Serializable {
    val components: MutableList<SwiftExportResolvedComponent> = mutableListOf(firstComponent)

    val component: SwiftExportResolvedComponent get() = components.first()

    private val artifactFilePath: String get() = artifact.file.absolutePath

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ResolvedArtifactWithVersionIdentifier

        return artifactFilePath == other.artifactFilePath
    }

    override fun hashCode(): Int {
        return 31 * artifactFilePath.hashCode()
    }

    fun defaultExportedModuleName(): String {
        return when (val id = component.id) {
            is ProjectComponentIdentifier -> id.projectPath
            is ModuleComponentIdentifier -> moduleVersion.inheritedName
            else -> error("Unexpected component identifier type: ${id::class}")
        }
    }
}

private val ResolvedDependencyResult.component: SwiftExportResolvedComponent
    get() = SwiftExportResolvedComponent(selected.id, selected.moduleVersion)

private fun LazyResolvedConfigurationWithArtifacts.filteredArtifacts(
    dependencies: Iterable<ResolvedDependencyResult>,
): Set<ResolvedArtifactWithVersionIdentifier> {
    val byArtifactPath = LinkedHashMap<String, ResolvedArtifactWithVersionIdentifier>()
    for (dependency in dependencies) {
        val artifacts = getArtifacts(dependency.selected).filterNot {
            it.file.isCinteropKlib || it.file.isJavaJar
        }
        val moduleVersion = dependency.selected.moduleVersion
        if (artifacts.isEmpty() || moduleVersion == null) continue

        val artifact = artifacts.single()
        val existing = byArtifactPath[artifact.file.absolutePath]
        if (existing == null) {
            byArtifactPath[artifact.file.absolutePath] =
                ResolvedArtifactWithVersionIdentifier(moduleVersion, artifact, dependency.component)
        } else {
            existing.components += dependency.component
        }
    }
    return byArtifactPath.values.toSet()
}

private val LazyResolvedConfigurationWithArtifacts.directDependencies: List<ResolvedDependencyResult>
    get() = root.dependencies
        .filterIsInstance<ResolvedDependencyResult>()
        .filterNot { it.isConstraint }

private val File.isCinteropKlib get() = name.contains("-cinterop-") || name.contains("Cinterop-")
private val File.isJavaJar get() = extension == "jar"

private const val EXPORTED_MODULE_ITSELF = "the module being exported"

private fun Project.findAndCreateSwiftExportedModules(
    input: SwiftExportModulesInput,
): List<SwiftExportedModule> {
    val resolvedExportArtifacts = input.exportConfiguration.filteredArtifacts(input.exportConfiguration.allResolvedDependencies)
    val resolvedDirectApiArtifacts = input.apiConfiguration
        ?.let { it.filteredArtifacts(it.directDependencies) }
        ?: emptySet()

    // Declared metadata layers, highest precedence first. KT-87987 appends its producer metadata source here.
    val sources = listOf(ConsumerOverridesMetadataSource(input.consumerOverrides))

    // The api configuration is only walked one level deep, so its artifacts know just the node the build script
    // declared. The export configuration is walked fully and also sees the "available-at" nodes; the artifact is
    // the same, so its nodes are the union of both.
    fun ResolvedArtifactWithVersionIdentifier.allComponents(): List<SwiftExportResolvedComponent> =
        (components + resolvedExportArtifacts.find { it == this }?.components.orEmpty()).distinct()

    val result = mutableListOf<SwiftExportedModule>()
    val processedComponents = mutableSetOf<ResolvedArtifactWithVersionIdentifier>()
    val missingModules = mutableListOf<SwiftExportedDependency>()

    // Final module name to the components that produced it, for collision detection. Seeded with the
    // exported module's own name: a dependency colliding with it breaks the Swift build just as hard.
    val moduleNameOwners = mutableMapOf(input.rootModuleName to mutableListOf(EXPORTED_MODULE_ITSELF))

    fun export(module: SwiftExportedModule, artifact: ResolvedArtifactWithVersionIdentifier) {
        result += module
        moduleNameOwners.getOrPut(module.moduleName) { mutableListOf() } += artifact.component.displayName
        processedComponents += artifact
    }

    // Process all explicitly exported modules
    for (explicitModule in input.legacyExportedModules) {
        val matchingArtifact = resolvedExportArtifacts.find { artifact ->
            val componentId = artifact.artifact.id.componentIdentifier

            when (explicitModule) {
                is SwiftExportedDependency.External -> {
                    // It's a regular external dependency. Match by group and name.
                    artifact.moduleVersion.group == explicitModule.coordinates.group &&
                            artifact.moduleVersion.name == explicitModule.coordinates.name
                }
                is SwiftExportedDependency.Project -> {
                    // For project dependencies, we match by project path.
                    if (componentId is ProjectComponentIdentifier) {
                        // Check if the artifact's project path matches the path stored in our module's name.
                        componentId.projectPath == explicitModule.projectPath
                    } else {
                        // This artifact is not from a project, so it cannot be a match.
                        false
                    }
                }
            }
        }

        if (matchingArtifact != null) {
            export(
                createFullyExportedSwiftExportedModule(
                    explicitModule.moduleName.orElse(
                        normalizedAndValidatedModuleName(explicitModule.inheritedName)
                    ).get(),
                    explicitModule.flattenPackage.orNull,
                    matchingArtifact.artifact.file
                ),
                matchingArtifact,
            )
        } else {
            missingModules.add(explicitModule)
        }
    }

    if (missingModules.isNotEmpty()) {
        reportDiagnostic(KotlinToolingDiagnostics.SwiftExportModuleResolutionError(missingModules.map { it.name }))
    }

    for (artifact in resolvedDirectApiArtifacts) {
        if (artifact in processedComponents) continue
        export(
            createFullyExportedSwiftExportedModule(
                moduleName = declaredOrDerivedModuleName(sources, artifact.allComponents()) {
                    artifact.defaultExportedModuleName().normalizedSwiftExportModuleName
                },
                flattenPackage = sources.declaredRootPackage(artifact.allComponents()),
                artifact = artifact.artifact.file,
            ),
            artifact,
        )
    }

    // Then process remaining components as transitive
    for (artifact in resolvedExportArtifacts) {
        if (artifact in processedComponents) continue
        export(
            createTransitiveSwiftExportedModule(
                // `rootPackage` is deliberately not read here: createTransitiveSwiftExportedModule
                // hardcodes flattenPackage = null, so a declared root package has no effect on a module
                // that is only transitively exported.
                declaredOrDerivedModuleName(sources, artifact.allComponents()) {
                    artifact.moduleVersion.inheritedName.normalizedSwiftExportModuleName
                },
                artifact.artifact.file
            ),
            artifact,
        )
    }

    reportOverridesNotApplied(
        input,
        exportedComponents = (resolvedExportArtifacts + resolvedDirectApiArtifacts).flatMap { it.allComponents() },
    )

    val duplicateModuleNames = moduleNameOwners.filterValues { owners -> owners.size > 1 }
    if (duplicateModuleNames.isNotEmpty()) {
        reportDiagnostic(KotlinToolingDiagnostics.SwiftExportDuplicateModuleNames(duplicateModuleNames))
    }

    return result
}

/**
 * Fails on consumer overrides that had nothing to apply to.
 *
 * An override is checked against the whole resolved graph, not only against [exportedComponents], so that the
 * user is told the difference between a dependency that is not there at all and one that is there but is never
 * exported to Swift (a JVM jar, a cinterop klib): both look the same from [exportedComponents] alone, and "not
 * found" would be wrong for the latter.
 */
private fun Project.reportOverridesNotApplied(
    input: SwiftExportModulesInput,
    exportedComponents: List<SwiftExportResolvedComponent>,
) {
    val notApplied = input.consumerOverrides.keys.filterNot { selector -> exportedComponents.any(selector::matches) }
    if (notApplied.isEmpty()) return

    val graph = (input.exportConfiguration.allResolvedDependencies + input.apiConfiguration?.directDependencies.orEmpty())
        .map { it.component }
    val (notExported, absent) = notApplied.partition { selector -> graph.any(selector::matches) }
    reportDiagnostic(
        KotlinToolingDiagnostics.SwiftExportDependencyOverrideNotApplied(
            absent = absent.map { it.displayName },
            notExported = notExported.map { it.displayName },
        )
    )
}

/**
 * The module name declared by the highest precedence layer that declares one, or [derived] otherwise.
 *
 * A declared name is used verbatim — normalizing a name the user wrote would silently rewrite it — but is
 * still validated, unlike the legacy explicit `swiftExport { export(...) }` path. The validation is FATAL: it
 * runs once the export graph is assembled, after `checkKotlinGradlePluginConfigurationErrors`, so an ERROR
 * would be logged and the invalid name would still reach the Swift compiler.
 */
private fun Project.declaredOrDerivedModuleName(
    sources: List<SwiftExportModuleMetadataSource>,
    components: List<SwiftExportResolvedComponent>,
    derived: () -> String,
): String = sources.declaredModuleName(components)
    ?.also { declared ->
        if (!declared.matches(Regex(SWIFT_EXPORT_MODULE_NAME_VALIDATION_PATTERN))) {
            reportDiagnostic(KotlinToolingDiagnostics.SwiftExportInvalidModuleName(declared, KotlinToolingDiagnosticsSeverity.FATAL))
        }
    }
    ?: derived()

private data class SwiftExportedModuleImp(
    override val moduleName: String,
    override val flattenPackage: String?,
    override val artifact: File,
    override val shouldBeFullyExported: Boolean,
) : SwiftExportedModule

private fun Project.normalizedAndValidatedModuleName(moduleName: String) =
    moduleName.normalizedSwiftExportModuleName.also { validateSwiftExportModuleName(it) }
