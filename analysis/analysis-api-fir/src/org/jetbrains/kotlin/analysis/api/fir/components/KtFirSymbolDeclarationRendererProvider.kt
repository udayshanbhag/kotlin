/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.components

import org.jetbrains.kotlin.analysis.api.components.KtDeclarationRendererOptions
import org.jetbrains.kotlin.analysis.api.components.KtSymbolDeclarationRendererProvider
import org.jetbrains.kotlin.analysis.api.components.KtTypeRendererOptions
import org.jetbrains.kotlin.analysis.api.fir.KtFirAnalysisSession
import org.jetbrains.kotlin.analysis.api.fir.renderer.ConeTypeIdeRenderer
import org.jetbrains.kotlin.analysis.api.fir.renderer.FirIdeRenderer
import org.jetbrains.kotlin.analysis.api.fir.symbols.KtFirSymbol
import org.jetbrains.kotlin.analysis.api.fir.types.KtFirType
import org.jetbrains.kotlin.analysis.api.symbols.KtPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithKind
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.symbols.ensureResolved

internal class KtFirSymbolDeclarationRendererProvider(
    override val analysisSession: KtFirAnalysisSession,
    override val token: ValidityToken,
) : KtSymbolDeclarationRendererProvider() {

    override fun render(type: KtType, options: KtTypeRendererOptions): String {
        require(type is KtFirType)
        return ConeTypeIdeRenderer(analysisSession.firResolveState.rootModuleSession, options).renderType(type.coneType)
    }

    override fun render(symbol: KtSymbol, options: KtDeclarationRendererOptions): String {
        return when (symbol) {
            is KtPackageSymbol -> {
                "package ${symbol.fqName.asString()}"
            }
            is KtFirSymbol<*> -> {
                val containingSymbol = with(analysisSession) {
                    (symbol as? KtSymbolWithKind)?.getContainingSymbol()
                }
                check(containingSymbol is KtFirSymbol<*>?)
                symbol.firSymbol.ensureResolved(FirResolvePhase.BODY_RESOLVE)

                val containingFir = containingSymbol?.firSymbol?.fir
                FirIdeRenderer.render(symbol.firSymbol.fir, containingFir, options, symbol.firSymbol.moduleData.session)

            }
            else -> {
                error("Unexpected Fir Symbol ${symbol::class.simpleName}")
            }
        }

    }
}
