/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.compiler.based

import org.jetbrains.kotlin.analysis.low.level.api.fir.api.DiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirModuleResolveState
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.collectDiagnosticsForFile
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.resolvedFirToPhase
import org.jetbrains.kotlin.fir.analysis.AbstractFirAnalyzerFacade
import org.jetbrains.kotlin.fir.analysis.diagnostics.*
import org.jetbrains.kotlin.fir.backend.Fir2IrResult
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.FirSealedClassInheritorsProcessor
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeVariableType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi2ir.generators.GeneratorExtensions
import org.jetbrains.kotlin.test.model.TestFile

class LowLevelFirAnalyzerFacade(
    val resolveState: FirModuleResolveState,
    val allFirFiles: Map<TestFile, FirFile>,
    private val diagnosticCheckerFilter: DiagnosticCheckerFilter,
) : AbstractFirAnalyzerFacade() {
    override val scopeSession: ScopeSession get() = shouldNotBeCalled()

    override fun runCheckers(): Map<FirFile, List<FirDiagnostic>> {
        findSealedInheritors()
        return allFirFiles.values.associateWith { firFile ->
            val ktFile = firFile.psi as KtFile

            @Suppress("UNCHECKED_CAST")
            val diagnostics = ktFile.collectDiagnosticsForFile(resolveState, diagnosticCheckerFilter).toList() as List<FirDiagnostic>
            DiagnosticChecker.checkDiagnosticsIsSuitableForFirIde(diagnostics)
            diagnostics
        }
    }


    private fun findSealedInheritors() {
        allFirFiles.values.forEach {
            it.resolvedFirToPhase(FirResolvePhase.SUPER_TYPES, resolveState)
        }
        val sealedProcessor = FirSealedClassInheritorsProcessor(allFirFiles.values.first().moduleData.session, ScopeSession())
        sealedProcessor.process(allFirFiles.values)
    }

    override fun runResolution(): List<FirFile> = shouldNotBeCalled()
    override fun convertToIr(extensions: GeneratorExtensions): Fir2IrResult = shouldNotBeCalled()
}

private object DiagnosticChecker {
    fun checkDiagnosticsIsSuitableForFirIde(diagnostics: List<FirDiagnostic>) {
        for (diagnostic in diagnostics) {
            checkDiagnosticIsSuitableForFirIde(diagnostic)
        }
    }

    private fun checkDiagnosticIsSuitableForFirIde(diagnostic: FirDiagnostic) {
        val parameters = diagnostic.allParameters()
        for (parameter in parameters) {
            checkDiagnosticParameter(diagnostic, parameter)
        }
    }

    private fun checkDiagnosticParameter(diagnostic: FirDiagnostic, parameter: Any?) {
        if (parameter is ConeTypeVariableType) {
            val rendered = FirDefaultErrorMessages.getRendererForDiagnostic(diagnostic).render(diagnostic)
            error(
                "ConeTypeVariableType should not be exposed from diagnostic. " +
                        "But it was for ${diagnostic.factoryName} $rendered"
            )
        }
        if (parameter is ConeKotlinType) {
            for (typeArgument in parameter.typeArguments) {
                checkDiagnosticParameter(diagnostic, typeArgument)
            }
        }
    }

    private fun FirDiagnostic.allParameters(): List<Any?> = when (this) {
        is FirPsiDiagnosticWithParameters1<*> -> listOf(a)
        is FirPsiDiagnosticWithParameters2<*, *> -> listOf(a, b)
        is FirPsiDiagnosticWithParameters3<*, *, *> -> listOf(a, b, c)
        is FirPsiDiagnosticWithParameters4<*, *, *, *> -> listOf(a, b, c, d)
        is FirPsiSimpleDiagnostic -> emptyList()
        else -> error("Unexpected diagnostic $this")
    }
}

private fun shouldNotBeCalled(): Nothing = error("Should not be called for LL test")
