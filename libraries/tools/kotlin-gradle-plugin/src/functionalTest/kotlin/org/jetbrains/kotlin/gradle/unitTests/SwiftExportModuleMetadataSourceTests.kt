/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.unitTests

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportDeclaredModuleMetadata
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportModuleMetadataSource
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.SwiftExportResolvedComponent
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.declaredModuleName
import org.jetbrains.kotlin.gradle.plugin.mpp.export.internal.declaredRootPackage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The seam only ever reads what a layer returns for a component, so opaque component identifiers are enough.
 * Matching a selector against a real identifier is covered end-to-end in [ExportExtensionSwiftExportTests].
 */
class SwiftExportModuleMetadataSourceTests {

    private fun component(name: String) = SwiftExportResolvedComponent(ComponentIdentifier { name }, moduleVersion = null)

    private val component = listOf(component("test-component"))

    private fun layer(moduleName: String?, rootPackage: String?) =
        SwiftExportModuleMetadataSource { SwiftExportDeclaredModuleMetadata(moduleName, rootPackage) }

    @Test
    fun `the highest precedence layer wins for module name`() {
        val layers = listOf(
            layer(moduleName = "Higher", rootPackage = null),
            layer(moduleName = "Lower", rootPackage = null),
        )

        assertEquals("Higher", layers.declaredModuleName(component))
    }

    @Test
    fun `properties resolve independently of each other`() {
        val layers = listOf(
            layer(moduleName = "Higher", rootPackage = null),
            layer(moduleName = "Lower", rootPackage = "org.example.lower"),
        )

        assertEquals("Higher", layers.declaredModuleName(component))
        assertEquals("org.example.lower", layers.declaredRootPackage(component))
    }

    @Test
    fun `a layer with no metadata for the component is skipped`() {
        val layers = listOf(
            SwiftExportModuleMetadataSource { null },
            layer(moduleName = "Lower", rootPackage = "org.example.lower"),
        )

        assertEquals("Lower", layers.declaredModuleName(component))
        assertEquals("org.example.lower", layers.declaredRootPackage(component))
    }

    @Test
    fun `nothing declared falls through to the derived default`() {
        val layers = listOf(
            layer(moduleName = null, rootPackage = null),
            SwiftExportModuleMetadataSource { null },
        )

        assertNull(layers.declaredModuleName(component))
        assertNull(layers.declaredRootPackage(component))
    }

    @Test
    fun `no layers at all falls through to the derived default`() {
        val layers = emptyList<SwiftExportModuleMetadataSource>()

        assertNull(layers.declaredModuleName(component))
        assertNull(layers.declaredRootPackage(component))
    }

    @Test
    fun `a layer that knows the module by any of its graph nodes applies`() {
        val root = component("org.example:foo")
        val availableAt = component("org.example:foo-iosarm64")
        val layers = listOf(
            SwiftExportModuleMetadataSource { component ->
                if (component == availableAt) SwiftExportDeclaredModuleMetadata("Foo", null) else null
            },
        )

        assertEquals("Foo", layers.declaredModuleName(listOf(root, availableAt)))
    }

    @Test
    fun `a higher precedence layer wins even if it knows the module by a later graph node`() {
        val root = component("org.example:foo")
        val availableAt = component("org.example:foo-iosarm64")
        val layers = listOf(
            SwiftExportModuleMetadataSource { component ->
                if (component == availableAt) SwiftExportDeclaredModuleMetadata("Higher", null) else null
            },
            SwiftExportModuleMetadataSource { component ->
                if (component == root) SwiftExportDeclaredModuleMetadata("Lower", null) else null
            },
        )

        assertEquals("Higher", layers.declaredModuleName(listOf(root, availableAt)))
    }
}
