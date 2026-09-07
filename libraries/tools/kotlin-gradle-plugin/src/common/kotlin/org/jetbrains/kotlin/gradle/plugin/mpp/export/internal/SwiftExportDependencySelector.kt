/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.export.internal

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.internal.ProjectByPath
import org.jetbrains.kotlin.gradle.plugin.internal.ProjectDependencyAccessor
import org.jetbrains.kotlin.gradle.plugin.internal.compatAccessor
import org.jetbrains.kotlin.gradle.plugin.variantImplementationFactoryProvider
import java.io.Serializable

/**
 * Selects a dependency in the Swift Export graph, for the consumer overrides declared via
 * `xcodeIntegration { configure(dependency) { } }`.
 *
 * Matching runs against the [SwiftExportResolvedComponent] of a resolved artifact rather than against the
 * coordinates the build script requested, so an override survives conflict resolution selecting a different
 * version.
 */
internal sealed interface SwiftExportDependencySelector : Serializable {

    /** How this selector is rendered in diagnostics. */
    val displayName: String

    fun matches(component: SwiftExportResolvedComponent): Boolean

    /**
     * Matches a project by its Gradle path.
     *
     * Known limitation, shared with the legacy `swiftExport { export(...) }` DSL: a path alone does not
     * disambiguate projects coming from different included builds in a composite build.
     */
    data class ProjectPath(val projectPath: String) : SwiftExportDependencySelector {
        override val displayName: String get() = projectPath

        override fun matches(component: SwiftExportResolvedComponent): Boolean =
            (component.id as? ProjectComponentIdentifier)?.projectPath == projectPath
    }

    /**
     * Matches an external module by group and module name. The version is deliberately ignored.
     *
     * A module substituted with a project (an included build, or `dependencySubstitution`) selects a
     * [ProjectComponentIdentifier] but still resolves to the module's coordinates, so those are matched too:
     * the override was written against the coordinates the build script declares.
     */
    data class Module(val group: String, val name: String) : SwiftExportDependencySelector {
        override val displayName: String get() = "$group:$name"

        override fun matches(component: SwiftExportResolvedComponent): Boolean {
            val id = component.id
            if (id is ModuleComponentIdentifier && id.group == group && id.module == name) return true
            val moduleVersion = component.moduleVersion ?: return false
            return moduleVersion.group == group && moduleVersion.name == name
        }
    }
}

/**
 * Converts the dependency notations accepted by
 * [org.jetbrains.kotlin.gradle.plugin.mpp.export.SwiftExportIntegration.configure] into selectors.
 *
 * Accepts the same notations as the legacy `swiftExport { export(...) }` DSL, minus [Provider]s: unwrapping a
 * provider is the caller's job, because doing it here would force eager realization.
 *
 * Bundles the Gradle services the conversion needs so the DSL objects carry one collaborator instead of
 * threading three services through every layer.
 */
internal class SwiftExportDependencySelectorFactory(
    private val dependencyHandler: DependencyHandler,
    private val projectDependencyAccessorFactory: Provider<ProjectDependencyAccessor.Factory>,
    private val projectByPath: ProjectByPath,
) {
    fun fromNotation(dependency: Any): SwiftExportDependencySelector = when (dependency) {
        is Project -> SwiftExportDependencySelector.ProjectPath(dependency.path)
        is ProjectDependency -> dependency.pathSelector()
        is Dependency -> dependency.moduleSelector()
        else -> when (val created = dependencyHandler.create(dependency)) {
            is ProjectDependency -> created.pathSelector()
            else -> created.moduleSelector()
        }
    }

    private fun ProjectDependency.pathSelector() = SwiftExportDependencySelector.ProjectPath(
        compatAccessor(projectDependencyAccessorFactory, projectByPath).dependencyProject().path
    )

    private fun Dependency.moduleSelector(): SwiftExportDependencySelector.Module {
        val group = group ?: throw InvalidUserDataException(
            "Cannot configure Swift Export for dependency '$name': it has no group, so it cannot be matched " +
                    "against a resolved component. Use full 'group:name' coordinates or a project dependency."
        )
        return SwiftExportDependencySelector.Module(group = group, name = name)
    }
}

internal fun Project.swiftExportDependencySelectorFactory() = SwiftExportDependencySelectorFactory(
    dependencyHandler = dependencies,
    projectDependencyAccessorFactory = variantImplementationFactoryProvider<ProjectDependencyAccessor.Factory>(),
    projectByPath = ::project,
)
