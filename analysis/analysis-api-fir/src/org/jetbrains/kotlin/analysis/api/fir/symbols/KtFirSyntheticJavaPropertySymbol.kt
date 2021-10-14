/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.symbols

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.fir.KtSymbolByFirBuilder
import org.jetbrains.kotlin.analysis.api.fir.findPsi
import org.jetbrains.kotlin.analysis.api.fir.symbols.annotations.containsAnnotation
import org.jetbrains.kotlin.analysis.api.fir.symbols.annotations.getAnnotationClassIds
import org.jetbrains.kotlin.analysis.api.fir.symbols.annotations.toAnnotationsList
import org.jetbrains.kotlin.analysis.api.fir.symbols.pointers.KtFirJavaSyntheticPropertySymbolPointer
import org.jetbrains.kotlin.analysis.api.fir.utils.cached
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtAnnotationCall
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtConstantValue
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtTypeAndAnnotations
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirModuleResolveState
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.containingClass
import org.jetbrains.kotlin.fir.declarations.synthetic.FirSyntheticProperty
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.isStatic
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.resolve.calls.FirSyntheticPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.isExtension
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class KtFirSyntheticJavaPropertySymbol(
    override val firSymbol: FirAccessorSymbol,
    override val resolveState: FirModuleResolveState,
    override val token: ValidityToken,
    private val builder: KtSymbolByFirBuilder
) : KtSyntheticJavaPropertySymbol(), KtFirSymbol<FirAccessorSymbol> {
    override val psi: PsiElement? by cached { firSymbol.findPsi() }

    override val isVal: Boolean get() = withValidityAssertion { firSymbol.isVal }
    override val name: Name get() = withValidityAssertion { firSymbol.name }

    override val annotatedType: KtTypeAndAnnotations by cached { firSymbol.returnTypeAnnotated(builder, token) }
    override val receiverType: KtTypeAndAnnotations? by cached { firSymbol.receiverTypeAnnotated(builder, token) }

    override val dispatchType: KtType? by cached { firSymbol.dispatchReceiverType(builder) }


    override val isExtension: Boolean get() = withValidityAssertion { firSymbol.isExtension }

    override val initializer: KtConstantValue? by cached { firSymbol.getKtConstantInitializer(resolveState.rootModuleSession) }

    override val modality: Modality get() = withValidityAssertion { firSymbol.modality ?: firSymbol.invalidModalityError() }
    override val visibility: Visibility get() = withValidityAssertion { firSymbol.visibility }

    override val annotations: List<KtAnnotationCall> by cached { firSymbol.toAnnotationsList(token) }
    override fun containsAnnotation(classId: ClassId): Boolean = withValidityAssertion { firSymbol.containsAnnotation(classId) }
    override val annotationClassIds: Collection<ClassId> by cached { firSymbol.getAnnotationClassIds() }

    override val callableIdIfNonLocal: CallableId? get() = withValidityAssertion { firSymbol.getCallableIdIfNonLocal() }

    override val getter: KtPropertyGetterSymbol
        get() = withValidityAssertion {
            builder.callableBuilder.buildGetterSymbol(firSymbol.getterSymbol!!)
        }
    override val javaGetterSymbol: KtFunctionSymbol
        get() {
            val fir = firSymbol.fir as FirSyntheticProperty
            return builder.functionLikeBuilder.buildFunctionSymbol(fir.getter.delegate.symbol)
        }
    override val javaSetterSymbol: KtFunctionSymbol?
        get() {
            val fir = firSymbol.fir as FirSyntheticProperty
            return fir.setter?.delegate?.let { builder.functionLikeBuilder.buildFunctionSymbol(it.symbol) }
        }

    override val setter: KtPropertySetterSymbol?
        get() = withValidityAssertion {
            firSymbol.setterSymbol?.let { builder.callableBuilder.buildPropertyAccessorSymbol(it) } as? KtPropertySetterSymbol
        }

    override val isFromPrimaryConstructor: Boolean get() = withValidityAssertion { false }
    override val isOverride: Boolean get() = withValidityAssertion { firSymbol.isOverride }
    override val isStatic: Boolean get() = withValidityAssertion { firSymbol.isStatic }

    override val hasSetter: Boolean get() = withValidityAssertion { firSymbol.setterSymbol != null }

    override val origin: KtSymbolOrigin get() = withValidityAssertion { KtSymbolOrigin.JAVA_SYNTHETIC_PROPERTY }

    override fun createPointer(): KtSymbolPointer<KtSyntheticJavaPropertySymbol> {
        val containingClassId = firSymbol.containingClass()?.classId
            ?: error("Cannot find parent class for synthetic java property $callableIdIfNonLocal")

        return KtFirJavaSyntheticPropertySymbolPointer(containingClassId, name)
    }

    override fun equals(other: Any?): Boolean = symbolEquals(other)
    override fun hashCode(): Int = symbolHashCode()
}
