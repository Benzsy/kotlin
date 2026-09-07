/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftexport.internal

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.diagnostics.KotlinToolingDiagnostics
import org.jetbrains.kotlin.gradle.plugin.diagnostics.reportDiagnostic
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportDependencySelector
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportModuleMetadataSource
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
    /** Declared metadata layers, highest precedence first. KT-87987 appends one entry. */
    val metadataSources: List<SwiftExportModuleMetadataSource>,
    /**
     * Selectors of the consumer overrides, used only to report the ones that matched nothing. Kept separate
     * from [metadataSources] because "matched nothing" is specific to the consumer override layer: producer
     * metadata comes from the graph and so always matches by construction.
     */
    val overrideSelectors: Set<SwiftExportDependencySelector>,
    /** The Swift module name of the module being exported, for collision detection. */
    val rootModuleName: String,
)

internal fun Project.collectModules(
    input: Provider<SwiftExportModulesInput>,
): Provider<List<SwiftExportedModule>> = input.map { findAndCreateSwiftExportedModules(it) }

private class ResolvedArtifactWithVersionIdentifier(
    val moduleVersion: ModuleVersionIdentifier,
    val artifact: ResolvedArtifactResult,
    /**
     * The [ComponentIdentifier] of the resolved dependency graph node, as opposed to
     * [ResolvedArtifactResult.getId]'s owner. Kotlin Multiplatform libraries publish "available-at" variants
     * that redirect a root module (e.g. `kotlinx-io-bytestring`) to a per-target one (e.g.
     * `kotlinx-io-bytestring-iossimulatorarm64`) for the physical artifact, so the artifact's own component
     * identifier can carry a different, target-suffixed module name than the one a consumer override refers
     * to. This identifier is the one consumer overrides and declared metadata are matched against.
     */
    val componentId: ComponentIdentifier,
) : Serializable {
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
        return when (val id = componentId) {
            is ProjectComponentIdentifier -> id.projectPath
            is ModuleComponentIdentifier -> moduleVersion.inheritedName
            else -> error("Unexpected component identifier type: ${id::class}")
        }
    }
}

private fun LazyResolvedConfigurationWithArtifacts.filteredArtifacts(
    dependenciesSelector: LazyResolvedConfigurationWithArtifacts.() -> Iterable<ResolvedDependencyResult>
): Set<ResolvedArtifactWithVersionIdentifier> {
    return dependenciesSelector().mapNotNullTo(mutableSetOf()) { dependency ->
        val artifacts = getArtifacts(dependency.selected).filterNot {
            it.file.isCinteropKlib || it.file.isJavaJar
        }

        val moduleVersion = dependency.selected.moduleVersion

        if (artifacts.isNotEmpty() && moduleVersion != null) {
            ResolvedArtifactWithVersionIdentifier(moduleVersion, artifacts.single(), dependency.selected.id)
        } else {
            null
        }
    }
}

private val File.isCinteropKlib get() = name.contains("-cinterop-") || name.contains("Cinterop-")
private val File.isJavaJar get() = extension == "jar"

private const val LEGACY_EXPORT_DSL = "swiftExport { export() }"
private const val XCODE_INTEGRATION_CONFIGURE_DSL = "export { swift { xcodeIntegration { configure() } } }"

private fun Project.findAndCreateSwiftExportedModules(
    input: SwiftExportModulesInput,
): List<SwiftExportedModule> {
    val resolvedExportArtifacts = input.exportConfiguration.filteredArtifacts { allResolvedDependencies }
    val resolvedDirectApiArtifacts = input.apiConfiguration
        ?.filteredArtifacts {
            root.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .filterNot { it.isConstraint }
        }
        ?: emptySet()

    val sources = input.metadataSources
    val result = mutableListOf<SwiftExportedModule>()
    val processedComponents = mutableSetOf<ResolvedArtifactWithVersionIdentifier>()
    val missingModules = mutableListOf<SwiftExportedDependency>()

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
            result.add(
                createFullyExportedSwiftExportedModule(
                    explicitModule.moduleName.orElse(
                        normalizedAndValidatedModuleName(explicitModule.inheritedName)
                    ).get(),
                    explicitModule.flattenPackage.orNull,
                    matchingArtifact.artifact.file
                )
            )

            // Track which components we've processed
            processedComponents.add(matchingArtifact)
        } else {
            missingModules.add(explicitModule)
        }
    }

    if (missingModules.isNotEmpty()) {
        reportDiagnostic(
            KotlinToolingDiagnostics.SwiftExportModuleResolutionError(
                missingModules.map { it.name },
                LEGACY_EXPORT_DSL,
            )
        )
    }

    for (artifact in resolvedDirectApiArtifacts) {
        if (artifact in processedComponents) continue
        val componentId = artifact.componentId
        result.add(
            createFullyExportedSwiftExportedModule(
                moduleName = declaredOrDerivedModuleName(sources, componentId) {
                    artifact.defaultExportedModuleName().normalizedSwiftExportModuleName
                },
                flattenPackage = sources.declaredRootPackage(componentId),
                artifact = artifact.artifact.file,
            )
        )
        // Track which components we've processed
        processedComponents.add(artifact)
    }

    // Then process remaining components as transitive
    resolvedExportArtifacts
        .filterNot { artifact -> artifact in processedComponents }
        .forEach { artifact ->
            val componentId = artifact.componentId
            result.add(
                createTransitiveSwiftExportedModule(
                    // `rootPackage` is deliberately not read here: createTransitiveSwiftExportedModule
                    // hardcodes flattenPackage = null, so a declared root package has no effect on a module
                    // that is only transitively exported.
                    declaredOrDerivedModuleName(sources, componentId) {
                        artifact.moduleVersion.inheritedName.normalizedSwiftExportModuleName
                    },
                    artifact.artifact.file
                )
            )
        }

    val allComponents = (resolvedExportArtifacts + resolvedDirectApiArtifacts)
        .map { it.componentId }
    val unmatchedOverrides = input.overrideSelectors.filterNot { selector ->
        allComponents.any { selector.matches(it) }
    }
    if (unmatchedOverrides.isNotEmpty()) {
        reportDiagnostic(
            KotlinToolingDiagnostics.SwiftExportModuleResolutionError(
                unmatchedOverrides.map { it.displayName },
                XCODE_INTEGRATION_CONFIGURE_DSL,
            )
        )
    }

    return result
}

/**
 * The module name declared by the highest precedence layer that declares one, or [derived] otherwise.
 *
 * A declared name is used verbatim — normalizing a name the user wrote would silently rewrite it — but is
 * still validated, unlike the legacy explicit `swiftExport { export(...) }` path.
 */
private fun Project.declaredOrDerivedModuleName(
    sources: List<SwiftExportModuleMetadataSource>,
    component: ComponentIdentifier,
    derived: () -> String,
): String = sources.declaredModuleName(component)
    ?.also { validateSwiftExportModuleName(it) }
    ?: derived()

private data class SwiftExportedModuleImp(
    override val moduleName: String,
    override val flattenPackage: String?,
    override val artifact: File,
    override val shouldBeFullyExported: Boolean,
) : SwiftExportedModule

private fun Project.normalizedAndValidatedModuleName(moduleName: String) =
    moduleName.normalizedSwiftExportModuleName.also { validateSwiftExportModuleName(it) }
