/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import org.jetbrains.kotlin.gradle.HierarchicalStructureOptInMigrationArtifactContentIT.Mode.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
internal class HierarchicalStructureOptInMigrationArtifactContentIT : BaseGradleIT() {
    enum class Mode {
        FLIPPED_DEFAULT, FLIPPED_DISABLE, FLIPPED_BACK_DEFAULT
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun params() = Mode.values().map { arrayOf(it) }
    }

    @Parameterized.Parameter(0)
    lateinit var mode: Mode

    @ExperimentalStdlibApi
    @Test
    @Suppress("NON_EXHAUSTIVE_WHEN")
    fun testArtifactFormatAndContent() = with(transformProjectWithPluginsDsl("new-mpp-published")) {
        projectDir.resolve("gradle.properties").delete()

        build(
            *buildList {
                add("clean")
                add("publish")
                when (mode) {
                    FLIPPED_DISABLE, FLIPPED_DEFAULT -> {}
                    FLIPPED_BACK_DEFAULT -> { add("-Pkotlin.internal.mpp.hierarchicalStructureByDefault=false") }
                }
                when (mode) {
                    FLIPPED_DISABLE -> add("-Pkotlin.mpp.hierarchicalStructureSupport=false")
                    FLIPPED_DEFAULT, FLIPPED_BACK_DEFAULT -> {}
                }
            }.toTypedArray(),
        ) {
            assertSuccessful()
            val metadataJarEntries = ZipFile(
                projectDir.resolve("../repo/com/example/bar/my-lib-bar/1.0/my-lib-bar-1.0.jar")
            ).use { zip ->
                zip.entries().asSequence().toList().map { it.name }
            }

            if (mode != FLIPPED_BACK_DEFAULT) {
                assertTrue { metadataJarEntries.any { "commonMain" in it } }
            }

            val hasJvmAndJsMainEntries = metadataJarEntries.any { "jvmAndJsMain" in it }
            val shouldHaveJvmAndJsMainEntries = when (mode) {
                FLIPPED_DISABLE, FLIPPED_BACK_DEFAULT -> false
                FLIPPED_DEFAULT -> true
            }
            assertEquals(shouldHaveJvmAndJsMainEntries, hasJvmAndJsMainEntries)
        }
    }
}