/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.symbols.annotations

import org.jetbrains.kotlin.analysis.api.fir.evaluate.KtFirConstantValueConverter
import org.jetbrains.kotlin.analysis.api.fir.findPsi
import org.jetbrains.kotlin.analysis.api.fir.utils.cached
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtAnnotationCall
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtNamedConstantValue
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirModuleResolveState
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.ensureResolved
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallElement

internal class KtFirAnnotationCall(
    private val containingDeclaration: FirBasedSymbol<*>,
    private val annotation: FirAnnotation,
    override val token: ValidityToken,
) : KtAnnotationCall() {

    override val psi: KtCallElement? = withValidityAssertion {
        annotation.findPsi(containingDeclaration.moduleData.session) as? KtCallElement
    }

    override val classId: ClassId? get() = withValidityAssertion { annotation.getClassId(containingDeclaration.moduleData.session) }

    override val useSiteTarget: AnnotationUseSiteTarget? get() = withValidityAssertion { annotation.useSiteTarget }

    override val arguments: List<KtNamedConstantValue> by cached {
        containingDeclaration.ensureResolved(FirResolvePhase.ARGUMENTS_OF_ANNOTATIONS)
        val session = containingDeclaration.moduleData.session
        KtFirConstantValueConverter.toNamedConstantValue(
            mapAnnotationParameters(annotation, session),
            session,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (other !is KtFirAnnotationCall) return false
        if (this.token != other.token) return false
        return annotation == other.annotation
    }

    override fun hashCode(): Int {
        return token.hashCode() * 31 + annotation.hashCode()
    }
}
