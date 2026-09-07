/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.export

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.jetbrains.kotlin.gradle.dsl.KotlinGradlePluginDsl
import org.jetbrains.kotlin.gradle.export.ExperimentalExportDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportDeclaredModuleMetadata
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportDependencySelector
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportDependencySelectorFactory
import org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl
import javax.inject.Inject

internal const val EXPORT_EXTENSION_NAME = "export"

/**
 * An *experimental* plugin DSL extension to configure platform-specific export functionality.
 *
 * This extension is available inside the `kotlin {}` block in your build script:
 *
 * ```kotlin
 * kotlin {
 *     export {
 *         // Platform-specific export configuration
 *     }
 * }
 * ```
 *
 * Note that this DSL is experimental, and it will likely change in future versions until it is stable.
 *
 * @since 2.5.0
 */
/*
We can't mark top level extensions with @ExperimentalExportDsl because
in buildSrc Gradle always creates accessors for these extensions which cause the opt-in error,
which cannot be suppressed.

See Gradle issue https://github.com/gradle/gradle/issues/32019
 */
@KotlinGradlePluginDsl
abstract class ExportExtension @Inject internal constructor(
    objectFactory: ObjectFactory,
    providerFactory: ProviderFactory,
    dependencySelectorFactory: SwiftExportDependencySelectorFactory,
) {
    private val defaultSwiftExportConfiguration = DefaultSwiftExportConfiguration(
        objectFactory = objectFactory,
        providerFactory = providerFactory,
        dependencySelectorFactory = dependencySelectorFactory,
    )

    internal val swiftExportConfiguration: SwiftExportConfiguration get() = defaultSwiftExportConfiguration

    /**
     * Whether the [swift] block was configured at least once in this project.
     */
    internal var isSwiftExportConfigured: Boolean = false
        private set

    /**
     * Configure Swift Export.
     */
    @ExperimentalExportDsl
    fun swift(configure: SwiftExportConfigurationDsl.() -> Unit) {
        isSwiftExportConfigured = true
        defaultSwiftExportConfiguration.configure()
    }

    /**
     * Configure Swift Export.
     */
    @ExperimentalExportDsl
    fun swift(configure: Action<SwiftExportConfigurationDsl>) = swift {
        configure.execute(this)
    }
}

/**
 * An *experimental* plugin DSL to configure Swift Export for an exported module.
 *
 * This DSL is available inside the `kotlin.export {}` block in your build script:
 *
 * ```kotlin
 * kotlin {
 *     export {
 *         swift {
 *             // Swift-specific export configuration
 *         }
 *     }
 * }
 * ```
 *
 * Note that this DSL is experimental, and it will likely change in future versions until it is stable.
 *
 * @since 2.5.0
 */
@ExperimentalSwiftExportDsl
@KotlinGradlePluginDsl
interface SwiftExportConfigurationDsl {
    /**
     * Configure the name of this module that will be used in Swift Export.
     */
    val moduleName: Property<String>

    /**
     * Configure this module's root package. If provided, the root package will be used for package flattening in Swift Export.
     */
    val rootPackage: Property<String>

    /**
     * Activate the Xcode integration for this module.
     *
     * Repeated calls configure the same integration, so the order of the calls doesn't matter.
     * 
     * The integration is only activated in the projects where this function is called.
     */
    fun xcodeIntegration()

    /**
     * Activate and configure the Xcode integration for this module.
     *
     * Repeated calls configure the same integration, so the order of the calls doesn't matter.
     *
     * The integration is only activated in the projects where this function is called.
     */
    fun xcodeIntegration(configure: SwiftExportXcodeIntegration.() -> Unit)

    /**
     * Activate and configure the Xcode integration for this module.
     *
     * Repeated calls configure the same integration, so the order of the calls doesn't matter.
     *
     * The integration is only activated in the projects where this function is called.
     */
    fun xcodeIntegration(configure: Action<SwiftExportXcodeIntegration>)
}

/**
 * Represents Swift Export configuration for an exported module.
 *
 * This API is experimental and may change in future versions.
 */
@ExperimentalSwiftExportDsl
internal interface SwiftExportConfiguration {
    /**
     * The name of this module that will be used in Swift Export.
     */
    val moduleName: Property<String>

    /**
     * This module's root package.
     */
    val rootPackage: Property<String>

    /**
     * The Xcode integration activated via [SwiftExportConfigurationDsl.xcodeIntegration],
     * or `null` if it was never activated for this module.
     */
    val activatedXcodeIntegration: SwiftExportXcodeIntegrationConfiguration?
}

/**
 * Represents the Xcode integration activated for an exported module.
 *
 * This API is experimental and may change in future versions.
 */
@ExperimentalSwiftExportDsl
internal interface SwiftExportXcodeIntegrationConfiguration {
    /**
     * The settings passed to Swift Export for this module.
     */
    val settings: Provider<Map<String, String>>

    /**
     * Consumer overrides declared via [SwiftExportIntegration.configure], keyed by the dependency they select.
     *
     * Folded in declaration order, so a later assignment wins independently per property.
     */
    val dependencyOverrides: Provider<Map<SwiftExportDependencySelector, SwiftExportDeclaredModuleMetadata>>
}

/**
 * An *experimental* plugin DSL to configure the Swift Export metadata of a single exported dependency.
 *
 * This DSL is available inside `xcodeIntegration { configure(dependency) { } }`.
 *
 * This API is experimental and may change in future versions.
 */
@ExperimentalSwiftExportDsl
@KotlinGradlePluginDsl
interface SwiftExportedDependencyDsl {
    /**
     * The Swift module name to use for this dependency.
     *
     * Takes precedence over the name derived from the dependency's coordinates, and over any name the
     * dependency itself publishes. The value is used verbatim, so it must already be a valid Swift module name.
     */
    val moduleName: Property<String>

    /**
     * The root package to flatten for this dependency.
     *
     * Takes precedence over any root package the dependency itself publishes. Has no effect on dependencies
     * that are only transitively exported.
     */
    val rootPackage: Property<String>
}

/**
 * Represents Swift Export integration for consumers.
 *
 * This API is experimental and may change in future versions.
 */
@ExperimentalSwiftExportDsl
@KotlinGradlePluginDsl
interface SwiftExportIntegration {
    /**
     * Configure the settings passed to Swift Export for this module.
     */
    val settings: MapProperty<String, String>

    /**
     * Override the Swift Export metadata of [dependency].
     *
     * [dependency] accepts the notations Gradle's dependency handler accepts: `"group:name:version"`
     * coordinates, a `project(":path")` dependency, a [org.gradle.api.Project], a version catalog accessor
     * such as `libs.foo`, or a [Provider] of any of those. The requested version is ignored: the override is
     * matched against the component the dependency graph actually resolved.
     *
     * [dependency] must already be part of the Swift Export graph — this function does not add it. An
     * override that matches nothing in the graph fails the build.
     *
     * A notation that cannot be converted to a dependency (for example, coordinates without a group) is
     * rejected right here, at the call site. A [Provider] is only realized when the export graph is
     * assembled, so a notation error inside one surfaces then.
     *
     * Repeated calls for the same dependency follow normal Gradle property semantics, so a later assignment
     * wins, independently per property.
     *
     * These overrides apply to this consumer only and are never published.
     */
    fun configure(dependency: Any, configure: SwiftExportedDependencyDsl.() -> Unit)

    /**
     * Override the Swift Export metadata of [dependency].
     *
     * @see configure
     */
    fun configure(dependency: Any, configure: Action<SwiftExportedDependencyDsl>)
}

/**
 * Represents Swift Export integration for Xcode consumers.
 *
 * This API is experimental and may change in future versions.
 */
@ExperimentalSwiftExportDsl
@KotlinGradlePluginDsl
interface SwiftExportXcodeIntegration : SwiftExportIntegration

private class DefaultSwiftExportConfiguration(
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
    private val dependencySelectorFactory: SwiftExportDependencySelectorFactory,
) : SwiftExportConfiguration, SwiftExportConfigurationDsl {
    override val moduleName: Property<String> = objectFactory.property(String::class.java)
    override val rootPackage: Property<String> = objectFactory.property(String::class.java)

    private var xcodeIntegrationConfiguration: DefaultSwiftExportXcodeIntegration? = null

    override val activatedXcodeIntegration: SwiftExportXcodeIntegrationConfiguration?
        get() = xcodeIntegrationConfiguration

    override fun xcodeIntegration() = xcodeIntegration { }

    override fun xcodeIntegration(configure: SwiftExportXcodeIntegration.() -> Unit) {
        val integration = xcodeIntegrationConfiguration
            ?: DefaultSwiftExportXcodeIntegration(
                objectFactory = objectFactory,
                providerFactory = providerFactory,
                dependencySelectorFactory = dependencySelectorFactory,
            ).also { xcodeIntegrationConfiguration = it }
        integration.configure()
    }

    override fun xcodeIntegration(configure: Action<SwiftExportXcodeIntegration>) = xcodeIntegration {
        configure.execute(this)
    }
}

private class DefaultSwiftExportXcodeIntegration(
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
    private val dependencySelectorFactory: SwiftExportDependencySelectorFactory,
) : SwiftExportXcodeIntegration, SwiftExportXcodeIntegrationConfiguration {

    override val settings: MapProperty<String, String> = objectFactory.mapProperty(String::class.java, String::class.java)

    /**
     * One `configure(dependency) { }` call. Each call gets its own DSL object rather than reusing one per
     * dependency, because the selector for a [Provider] notation is only known once the provider is realized,
     * and realizing it during configuration would break laziness. Every other notation is converted eagerly,
     * so a malformed one fails at the `configure` call site with a build script location, instead of later
     * while the task inputs are fingerprinted. Last-assignment-wins is produced by folding this list in
     * declaration order in [dependencyOverrides].
     */
    private class PendingOverride(
        val selector: Provider<SwiftExportDependencySelector>,
        val dsl: DefaultSwiftExportedDependencyDsl,
    )

    private val pendingOverrides = mutableListOf<PendingOverride>()

    override fun configure(dependency: Any, configure: SwiftExportedDependencyDsl.() -> Unit) {
        val dsl = DefaultSwiftExportedDependencyDsl(objectFactory)
        dsl.configure()
        pendingOverrides += PendingOverride(dependency.selectorProvider(), dsl)
    }

    override fun configure(dependency: Any, configure: Action<SwiftExportedDependencyDsl>) =
        configure(dependency) { configure.execute(this) }

    override val dependencyOverrides: Provider<Map<SwiftExportDependencySelector, SwiftExportDeclaredModuleMetadata>> =
        providerFactory.provider {
            pendingOverrides.fold(
                LinkedHashMap()
            ) { overrides, pending ->
                val selector = pending.selector.get()
                val previous = overrides[selector]
                overrides[selector] = SwiftExportDeclaredModuleMetadata(
                    moduleName = pending.dsl.moduleName.orNull ?: previous?.moduleName,
                    rootPackage = pending.dsl.rootPackage.orNull ?: previous?.rootPackage,
                )
                overrides
            }
        }

    private fun Any.selectorProvider(): Provider<SwiftExportDependencySelector> = when (this) {
        is Provider<*> -> map { resolved -> dependencySelectorFactory.fromNotation(resolved) }
        else -> {
            val selector = dependencySelectorFactory.fromNotation(this)
            providerFactory.provider { selector }
        }
    }
}

private class DefaultSwiftExportedDependencyDsl(objectFactory: ObjectFactory) : SwiftExportedDependencyDsl {
    override val moduleName: Property<String> = objectFactory.property(String::class.java)
    override val rootPackage: Property<String> = objectFactory.property(String::class.java)
}

internal fun ObjectFactory.ExportExtension(
    dependencySelectorFactory: SwiftExportDependencySelectorFactory,
): ExportExtension = newInstance(ExportExtension::class.java, dependencySelectorFactory)
