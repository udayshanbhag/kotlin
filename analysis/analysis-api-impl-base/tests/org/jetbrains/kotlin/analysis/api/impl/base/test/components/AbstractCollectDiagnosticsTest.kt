/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.components

import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.analysis.api.components.KtDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KtDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.impl.barebone.test.FrontendApiTestConfiguratorService
import org.jetbrains.kotlin.analysis.api.impl.base.test.test.framework.AbstractHLApiSingleFileTest
import org.jetbrains.kotlin.diagnostics.DiagnosticUtils.getLineAndColumnRangeInPsiFile
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions

abstract class AbstractCollectDiagnosticsTest(
    configurator: FrontendApiTestConfiguratorService
) : AbstractHLApiSingleFileTest(configurator) {
    override fun doTestByFileStructure(ktFile: KtFile, module: TestModule, testServices: TestServices) {
        super.doTestByFileStructure(ktFile, module, testServices)

        fun TextRange.asLineColumnRange(): String {
            return getLineAndColumnRangeInPsiFile(ktFile, this).toString()
        }

        val actual = buildString {
            analyseForTest(ktFile) {
                val diagnosticsInFile = ktFile.collectDiagnosticsForFile(KtDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                val diagnosticsFromElements = buildList {
                    ktFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitKtElement(element: KtElement) {
                            for (diagnostic in element.getDiagnostics(KtDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)) {
                                add(element to diagnostic)
                            }
                            super.visitKtElement(element)
                        }
                    })
                }

                val diagnosticKeysInFile = diagnosticsInFile.mapTo(mutableSetOf()) { it.getKey() }
                val diagnosticKeysFromElements = diagnosticsFromElements.mapTo(mutableSetOf()) { (_, diagnostic) -> diagnostic.getKey() }

                fun KtDiagnosticWithPsi<*>.print(indent: Int, missing: Boolean, missingFrom: String) {
                    val indentString = " ".repeat(indent)
                    append(indentString + factoryName)
                    if (missing) {
                        append(" (missing from $missingFrom)")
                    }
                    appendLine()
                    appendLine("$indentString  text ranges: $textRanges")
                    appendLine("$indentString  PSI: ${psi::class.simpleName} at ${psi.textRange.asLineColumnRange()}")
                }
                appendLine("Diagnostics in file:")
                for (diagnostic in diagnosticsInFile) {
                    diagnostic.print(2, diagnostic.getKey() !in diagnosticKeysFromElements, "KtElement.getDiagnostics")
                }
                appendLine()
                appendLine("Diagnostics from elements:")
                for ((element, diagnostic) in diagnosticsFromElements) {
                    appendLine("  for PSI element of type ${element::class.simpleName} at ${element.textRange.asLineColumnRange()}")
                    diagnostic.print(4, diagnostic.getKey() !in diagnosticKeysInFile, "KtFile.collectDiagnosticsForFile")
                }
            }
        }

        testServices.assertions.assertEqualsToTestDataFileSibling(actual)
    }

    fun KtDiagnosticWithPsi<*>.getKey() = listOf(factoryName, psi, textRanges)
}