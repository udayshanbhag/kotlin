/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.symbols

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.fir.KtSymbolByFirBuilder
import org.jetbrains.kotlin.analysis.api.fir.findPsi
import org.jetbrains.kotlin.analysis.api.fir.symbols.annotations.KtFirAnnotationCall
import org.jetbrains.kotlin.analysis.api.fir.symbols.annotations.containsAnnotation
import org.jetbrains.kotlin.analysis.api.fir.symbols.annotations.getAnnotationClassIds
import org.jetbrains.kotlin.analysis.api.fir.symbols.annotations.toAnnotationsList
import org.jetbrains.kotlin.analysis.api.fir.utils.cached
import org.jetbrains.kotlin.analysis.api.symbols.KtValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtAnnotationCall
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtPsiBasedSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirModuleResolveState
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.getAnnotationsByClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.renderWithType
import org.jetbrains.kotlin.fir.symbols.ensureResolved
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.arrayElementType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

internal class KtFirValueParameterSymbol(
    override val firSymbol: FirValueParameterSymbol,
    override val resolveState: FirModuleResolveState,
    override val token: ValidityToken,
    private val builder: KtSymbolByFirBuilder
) : KtValueParameterSymbol(), KtFirSymbol<FirValueParameterSymbol> {
    override val psi: PsiElement? by cached { firSymbol.findPsi() }

    override val name: Name by cached { firSymbol.getNameByAnnotation() }

    override val isVararg: Boolean get() = withValidityAssertion { firSymbol.isVararg }

    override val annotatedType: KtTypeAndAnnotations by cached {
        firSymbol.ensureResolved(FirResolvePhase.TYPES)
        if (firSymbol.isVararg) {
            val fir = firSymbol.fir
            val annotations = fir.annotations.map { annotation ->
                KtFirAnnotationCall(firSymbol, annotation, token)
            }
            // There SHOULD always be an array element type (even if it is an error type, e.g., unresolved).
            val arrayElementType = fir.returnTypeRef.coneType.arrayElementType()
                ?: error("No array element type for vararg value parameter: ${fir.renderWithType()}")
            KtFirTypeAndAnnotations(arrayElementType, annotations, builder, token)
        } else {
            KtFirLazyTypeAndAnnotations(firSymbol.resolvedReturnTypeRef, firSymbol, builder, token)
        }
    }

    override val hasDefaultValue: Boolean get() = withValidityAssertion { firSymbol.hasDefaultValue }

    override val annotations: List<KtAnnotationCall> by cached { firSymbol.toAnnotationsList(token) }
    override fun containsAnnotation(classId: ClassId): Boolean = withValidityAssertion { firSymbol.containsAnnotation(classId) }
    override val annotationClassIds: Collection<ClassId> by cached { firSymbol.getAnnotationClassIds() }

    override fun createPointer(): KtSymbolPointer<KtValueParameterSymbol> {
        KtPsiBasedSymbolPointer.createForSymbolFromSource(this)?.let { return it }
        TODO("Creating pointers for functions parameters from library is not supported yet")
    }

    override fun equals(other: Any?): Boolean = symbolEquals(other)
    override fun hashCode(): Int = symbolHashCode()
}


private fun FirValueParameterSymbol.getNameByAnnotation(): Name {
    val defaultName = name
    if (fir.psi != null) return defaultName

    // The case where PSI is null is when calling `invoke()` on a variable with functional type, e.g. `x(1)` below:
    //
    //   fun foo(x: (item: Int) -> Unit) { x(1) }
    //   fun bar(x: Function1<@ParameterName("item") Int, Unit>) { x(1) }
    //
    // The function being called is `invoke(p1: Int)` on `Function1<Int, Unit>` which is from the stdlib, and therefore no source
    // PSI for the function or its parameters. In that case, we use the `@ParameterName` annotation on the parameter type if present
    // and fall back to the parameter names from the `invoke()` function (`p1`, `p2`, etc.).
    //
    // Note: During type resolution, `@ParameterName` type annotations are added based on the names (which are optional) in the
    // function type notation. Therefore the `x` parameter in both example functions above have the same type and type annotations.
    val parameterNameAnnotation =
        resolvedReturnType.attributes.customAnnotations
            .getAnnotationsByClassId(StandardNames.FqNames.parameterNameClassId)
            .singleOrNull()?.safeAs<FirAnnotation>() ?: return defaultName

    // Resolve to ARGUMENTS_OF_ANNOTATIONS phase to get `name` argument from mapping.
    ensureResolved(FirResolvePhase.ARGUMENTS_OF_ANNOTATIONS)
    val parameterNameFromAnnotation = run {
        val nameArgument =
            parameterNameAnnotation.argumentMapping.mapping[StandardClassIds.Annotations.ParameterNames.parameterNameName]
        nameArgument?.unwrapArgument()?.safeAs<FirConstExpression<*>>()?.value as? String
    }
    return parameterNameFromAnnotation?.let { Name.identifier(it) } ?: defaultName
}