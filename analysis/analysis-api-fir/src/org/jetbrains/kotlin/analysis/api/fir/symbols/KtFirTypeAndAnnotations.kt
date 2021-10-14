/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.symbols

import org.jetbrains.kotlin.analysis.api.fir.KtSymbolByFirBuilder
import org.jetbrains.kotlin.analysis.api.fir.symbols.annotations.KtFirAnnotationCall
import org.jetbrains.kotlin.analysis.api.fir.utils.cached
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtAnnotationCall
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirModuleResolveState
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.resolve.transformers.resolveSupertypesInTheAir
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneType

internal class KtFirLazyTypeAndAnnotations(
    private val typeRef: FirResolvedTypeRef,
    private val containingDeclaration: FirCallableSymbol<*>,
    private val builder: KtSymbolByFirBuilder,
    override val token: ValidityToken,
) : KtTypeAndAnnotations() {

    override val type: KtType by cached { builder.typeBuilder.buildKtType(typeRef) }

    override val annotations: List<KtAnnotationCall> by cached {
        typeRef.annotations.map {
            KtFirAnnotationCall(containingDeclaration, it, token)
        }
    }
}


internal class KtFirTypeAndAnnotations(
    private val coneType: ConeKotlinType,
    private val _annotations: List<KtAnnotationCall>,
    private val builder: KtSymbolByFirBuilder,
    override val token: ValidityToken
) : KtTypeAndAnnotations() {
    override val type: KtType by cached { builder.typeBuilder.buildKtType(coneType) }

    override val annotations: List<KtAnnotationCall> get() = withValidityAssertion { _annotations }
}

internal fun FirCallableSymbol<*>.returnTypeAnnotated(
    builder: KtSymbolByFirBuilder,
    token: ValidityToken
): KtTypeAndAnnotations {
    return KtFirLazyTypeAndAnnotations(resolvedReturnTypeRef, this, builder, token)
}

internal fun FirCallableSymbol<*>.receiverTypeAnnotated(
    builder: KtSymbolByFirBuilder,
    token: ValidityToken
): KtTypeAndAnnotations? {
    return resolvedReceiverTypeRef?.let { receiver ->
        return KtFirLazyTypeAndAnnotations(receiver, this, builder, token)
    }
}


internal fun FirClassSymbol<*>.superTypesAndAnnotationsList(
    builder: KtSymbolByFirBuilder,
    token: ValidityToken
): List<KtTypeAndAnnotations> =
    resolvedSuperTypeRefs.mapToTypeAndAnnotations(this, builder, token)


internal fun FirRegularClassSymbol.superTypesAndAnnotationsListForRegularClass(
    builder: KtSymbolByFirBuilder,
    token: ValidityToken
): List<KtTypeAndAnnotations> {
    val annotations =
        if (fir.resolvePhase >= FirResolvePhase.SUPER_TYPES) fir.superTypeRefs
        else fir.resolveSupertypesInTheAir(builder.rootSession)
    return annotations.mapToTypeAndAnnotations(this, builder, token)

}

private fun List<FirTypeRef>.mapToTypeAndAnnotations(
    containingDeclaration: FirClassSymbol<*>,
    builder: KtSymbolByFirBuilder,
    token: ValidityToken,
): List<KtFirTypeAndAnnotations> = map { typeRef ->
    val annotations = typeRef.annotations.map { annotation ->
        KtFirAnnotationCall(containingDeclaration, annotation, token)
    }
    KtFirTypeAndAnnotations(typeRef.coneType, annotations, builder, token)
}
