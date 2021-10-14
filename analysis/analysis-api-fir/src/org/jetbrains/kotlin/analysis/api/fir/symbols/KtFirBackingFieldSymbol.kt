/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.symbols

import org.jetbrains.kotlin.analysis.api.fir.KtSymbolByFirBuilder
import org.jetbrains.kotlin.analysis.api.fir.symbols.pointers.KtFirBackingFieldSymbolPointer
import org.jetbrains.kotlin.analysis.api.fir.utils.cached
import org.jetbrains.kotlin.analysis.api.symbols.KtBackingFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirModuleResolveState
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol

internal class KtFirBackingFieldSymbol(
    private val propertyFirSymbol: FirPropertySymbol,
    override val token: ValidityToken,
    private val builder: KtSymbolByFirBuilder
) : KtBackingFieldSymbol() {
    override val annotatedType: KtTypeAndAnnotations by cached { propertyFirSymbol.returnTypeAnnotated(builder, token) }

    override val owningProperty: KtKotlinPropertySymbol
        get() = withValidityAssertion {
            builder.variableLikeBuilder.buildPropertySymbol(propertyFirSymbol)
        }

    override fun createPointer(): KtSymbolPointer<KtBackingFieldSymbol> {
        return KtFirBackingFieldSymbolPointer(owningProperty.createPointer())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KtFirBackingFieldSymbol

        if (this.token != other.token) return false
        return this.propertyFirSymbol == other.propertyFirSymbol
    }

    override fun hashCode(): Int {
        return propertyFirSymbol.hashCode() * 31 + token.hashCode()
    }
}