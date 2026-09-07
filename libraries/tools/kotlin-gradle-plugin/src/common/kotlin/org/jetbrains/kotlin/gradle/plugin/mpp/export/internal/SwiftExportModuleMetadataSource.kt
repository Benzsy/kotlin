/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.export.internal

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import java.io.Serializable

/**
 * A node of the resolved Swift Export dependency graph, as seen by the metadata layers.
 *
 * Carries both the [ComponentIdentifier] Gradle selected and the module coordinates it resolved to, because
 * the two can disagree: a `group:name` dependency substituted with a project (composite builds,
 * `dependencySubstitution`) selects a project component that still resolves to the `group:name` coordinates.
 * A layer keyed by coordinates must match through [moduleVersion], one keyed by project path through [id].
 *
 * Serializable, as it is held by the provider backing `SwiftExportTask.parameters.swiftModules`.
 */
internal data class SwiftExportResolvedComponent(
    val id: ComponentIdentifier,
    /** `null` for the rare components Gradle resolves without module coordinates. */
    val moduleVersion: ModuleVersionIdentifier?,
) : Serializable {
    val displayName: String get() = id.displayName
}

/**
 * Swift Export metadata that was explicitly declared for a module, as opposed to a name derived from the
 * module's coordinates or project path. A `null` property means this layer does not declare it, so a lower
 * precedence layer or the derived default applies.
 *
 * Not to be confused with [SwiftExportMetadata], which is the payload a producer publishes.
 */
internal data class SwiftExportDeclaredModuleMetadata(
    val moduleName: String?,
    val rootPackage: String?,
) : Serializable

/**
 * One layer of declared module metadata.
 *
 * Layers are consulted in precedence order and the first non-null value wins, independently per property:
 *
 *  1. consumer overrides declared via `xcodeIntegration { configure(dependency) { } }` (KT-87990)
 *  2. producer metadata published by the dependency (KT-87987)
 *  3. the derived default, which is not a layer
 *
 * Implementations must capture only already-resolved, serializable data: instances are held by the provider
 * backing `SwiftExportTask.parameters.swiftModules`.
 */
internal fun interface SwiftExportModuleMetadataSource {
    fun metadataFor(component: SwiftExportResolvedComponent): SwiftExportDeclaredModuleMetadata?
}

/**
 * The declared Swift module name for the module all of [components] resolved to, or `null` when no layer
 * declares one and the derived default should be used instead.
 *
 * [components] are the graph nodes that resolved to one artifact — a Kotlin Multiplatform library reached both
 * through its root coordinates and through an "available-at" per-target variant is one module with two nodes,
 * and a layer may know it by either.
 */
internal fun List<SwiftExportModuleMetadataSource>.declaredModuleName(
    components: List<SwiftExportResolvedComponent>,
): String? = firstDeclared(components) { it.moduleName }

/**
 * The declared root package for the module all of [components] resolved to, or `null` when no layer declares
 * one. See [declaredModuleName] for what [components] is.
 */
internal fun List<SwiftExportModuleMetadataSource>.declaredRootPackage(
    components: List<SwiftExportResolvedComponent>,
): String? = firstDeclared(components) { it.rootPackage }

private inline fun List<SwiftExportModuleMetadataSource>.firstDeclared(
    components: List<SwiftExportResolvedComponent>,
    property: (SwiftExportDeclaredModuleMetadata) -> String?,
): String? = firstNotNullOfOrNull { layer ->
    components.firstNotNullOfOrNull { component -> layer.metadataFor(component)?.let(property) }
}
