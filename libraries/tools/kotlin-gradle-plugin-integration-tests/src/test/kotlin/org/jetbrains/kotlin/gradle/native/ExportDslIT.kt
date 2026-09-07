/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.native

import org.gradle.kotlin.dsl.kotlin
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.export.ExperimentalExportDsl
import org.jetbrains.kotlin.gradle.plugin.diagnostics.KotlinToolingDiagnostics
import org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl
import org.jetbrains.kotlin.gradle.testbase.*
import org.jetbrains.kotlin.gradle.uklibs.applyMultiplatform
import org.jetbrains.kotlin.gradle.uklibs.include
import org.jetbrains.kotlin.gradle.util.swiftExportEmbedAndSignEnvVariables
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OsCondition(supportedOn = [OS.MAC], enabledOnCI = [OS.MAC])
@DisplayName("Tests for the export DSL")
@SwiftExportGradlePluginTests
@OptIn(ExperimentalExportDsl::class, ExperimentalSwiftExportDsl::class)
class ExportDslIT : KGPBaseTest() {

    @DisplayName("embedSwiftExportForXcode is registered when the Xcode integration is activated")
    @GradleTest
    fun testXcodeIntegrationRegistersEmbedTask(
        gradleVersion: GradleVersion,
    ) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    iosArm64()
                    sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
                }
                export.swift {
                    moduleName.set("Shared")
                    xcodeIntegration()
                }
            }

            assertTrue(isEmbedSwiftExportTaskRegistered())
        }
    }

    @DisplayName("embedSwiftExportForXcode is not registered when the export DSL is used without the Xcode integration")
    @GradleTest
    fun testExportDslWithoutXcodeIntegrationDoesNotRegisterEmbedTask(
        gradleVersion: GradleVersion,
    ) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    iosArm64()
                    sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
                }
                export.swift {
                    moduleName.set("Shared")
                }
            }

            assertFalse(isEmbedSwiftExportTaskRegistered())
        }
    }

    @DisplayName("The Xcode integration can be activated independently of the module configuration order")
    @GradleTest
    fun testXcodeIntegrationActivationIsOrderIndependent(
        gradleVersion: GradleVersion,
    ) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                export.swift {
                    xcodeIntegration()
                }
                project.applyMultiplatform {
                    iosArm64()
                    sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
                }
                export.swift {
                    moduleName.set("Shared")
                }
            }

            assertTrue(isEmbedSwiftExportTaskRegistered())
        }
    }

    @DisplayName("embedSwiftExportForXcode fails with an actionable error outside of Xcode in an activated project")
    @GradleTest
    fun testActivatedProjectFailsOutsideOfXcode(
        gradleVersion: GradleVersion,
    ) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    iosArm64()
                    sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
                }
                export.swift {
                    xcodeIntegration()
                }
            }

            buildAndFail(":$EMBED_SWIFT_EXPORT_TASK_NAME") {
                assertOutputContains("Please run the $EMBED_SWIFT_EXPORT_TASK_NAME task from Xcode")
            }
        }
    }

    @DisplayName("embedSwiftExportForXcode executes normally in an activated project")
    @GradleTest
    fun testActivatedProjectExecutesEmbedTask(
        gradleVersion: GradleVersion,
        @TempDir testBuildDir: Path,
    ) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("multiplatform")
            }
            settingsBuildScriptInjection {
                settings.rootProject.name = "shared"
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    iosArm64()
                    sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
                }
                export.swift {
                    xcodeIntegration()
                }
            }

            build(
                ":$EMBED_SWIFT_EXPORT_TASK_NAME",
                environmentVariables = swiftExportEmbedAndSignEnvVariables(testBuildDir)
            ) {
                assertTasksExecuted(":iosArm64DebugSwiftExport")
                assertTasksExecuted(":$EMBED_SWIFT_EXPORT_TASK_NAME")
            }
        }
    }

    @DisplayName("A legacy swiftExport build still configures and reports the deprecation")
    @GradleTest
    fun testLegacyDslStillWorksAndReportsDeprecation(
        gradleVersion: GradleVersion,
    ) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    iosArm64()
                    sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
                }
                swiftExport.moduleName.set("Legacy")
            }

            build("help") {
                assertHasDiagnostic(KotlinToolingDiagnostics.DeprecatedSwiftExportDsl)
                assertNoDiagnostic(KotlinToolingDiagnostics.ConflictingSwiftExportDsls)
            }

            assertTrue(isEmbedSwiftExportTaskRegistered())
        }
    }

    @DisplayName("Configuring both Swift Export DSLs fails the build")
    @GradleTest
    fun testConfiguringBothDslsFailsTheBuild(
        gradleVersion: GradleVersion,
    ) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    iosArm64()
                    sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
                }
                swiftExport.moduleName.set("Legacy")
                export.swift {
                    moduleName.set("Shared")
                    xcodeIntegration()
                }
            }

            buildAndFail(":compileKotlinIosArm64") {
                assertHasDiagnostic(KotlinToolingDiagnostics.ConflictingSwiftExportDsls)
                assertNoDiagnostic(KotlinToolingDiagnostics.DeprecatedSwiftExportDsl)
            }
        }
    }

    @DisplayName("An override colliding with the root module name fails the build with the diagnostic")
    @GradleTest
    fun testDuplicateModuleNameFailsTheBuild(
        gradleVersion: GradleVersion,
        @TempDir testBuildDir: Path,
    ) {
        project("empty", gradleVersion) {
            plugins {
                kotlin("multiplatform")
            }
            settingsBuildScriptInjection {
                settings.rootProject.name = "shared"
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    iosArm64()
                    sourceSets.commonMain {
                        compileStubSourceWithSourceSetName()
                        dependencies {
                            api(project.project(":sub"))
                        }
                    }
                }
                export.swift {
                    moduleName.set("Shared")
                    xcodeIntegration {
                        // Collides with the root module's own name, set above.
                        configure(project.project(":sub")) {
                            moduleName.set("Shared")
                        }
                    }
                }
            }

            val subproject = project("empty", gradleVersion) {
                buildScriptInjection {
                    project.applyMultiplatform {
                        iosArm64()
                        sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
                    }
                }
            }

            include(subproject, "sub")

            // The final module names are only known once the export graph is assembled, which happens after
            // checkKotlinGradlePluginConfigurationErrors (the task that fails the build on ERROR diagnostics) has
            // already run. The diagnostic is therefore FATAL, so that it fails the build by itself; the Swift Export
            // tool must never be handed two same-named modules, whatever it would do with them.
            buildAndFail(
                ":$EMBED_SWIFT_EXPORT_TASK_NAME",
                environmentVariables = swiftExportEmbedAndSignEnvVariables(testBuildDir)
            ) {
                assertHasDiagnostic(KotlinToolingDiagnostics.SwiftExportDuplicateModuleNames)
                assertOutputDoesNotContain("Collection contains more than one matching element")
            }
        }
    }

    private fun TestProject.isEmbedSwiftExportTaskRegistered(): Boolean = buildScriptReturn {
        project.tasks.findByName(EMBED_SWIFT_EXPORT_TASK_NAME) != null
    }.buildAndReturn()

    private companion object {
        const val EMBED_SWIFT_EXPORT_TASK_NAME = "embedSwiftExportForXcode"
    }
}
