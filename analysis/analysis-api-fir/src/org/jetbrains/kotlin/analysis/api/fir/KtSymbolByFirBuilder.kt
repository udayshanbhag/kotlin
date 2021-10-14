/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.analysis.api.KtStarProjectionTypeArgument
import org.jetbrains.kotlin.analysis.api.KtTypeArgument
import org.jetbrains.kotlin.analysis.api.KtTypeArgumentWithVariance
import org.jetbrains.kotlin.analysis.api.ValidityTokenOwner
import org.jetbrains.kotlin.analysis.api.fir.symbols.*
import org.jetbrains.kotlin.analysis.api.fir.types.*
import org.jetbrains.kotlin.analysis.api.fir.utils.weakRef
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtSubstitutor
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirModuleResolveState
import org.jetbrains.kotlin.analysis.providers.createPackageProvider
import org.jetbrains.kotlin.builtins.functions.FunctionClassKind
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirField
import org.jetbrains.kotlin.fir.declarations.impl.FirFieldImpl
import org.jetbrains.kotlin.fir.java.declarations.FirJavaField
import org.jetbrains.kotlin.fir.resolve.calls.FirSyntheticPropertySymbol
import org.jetbrains.kotlin.fir.resolve.calls.originalConstructorIfTypeAlias
import org.jetbrains.kotlin.fir.resolve.getSymbolByLookupTag
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.symbolProvider
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.Variance
import java.util.concurrent.ConcurrentMap
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Maps FirElement to KtSymbol & ConeType to KtType, thread safe
 */
internal class KtSymbolByFirBuilder private constructor(
    private val project: Project,
    resolveState: FirModuleResolveState,
    override val token: ValidityToken,
    val withReadOnlyCaching: Boolean,
    private val symbolsCache: BuilderCache<FirBasedSymbol<*>, KtSymbol>,
    private val filesCache: BuilderCache<FirFileSymbol, KtFileSymbol>,
    private val backingFieldCache: BuilderCache<FirBackingFieldSymbol, KtBackingFieldSymbol>,
    private val typesCache: BuilderCache<ConeKotlinType, KtType>,
) : ValidityTokenOwner {
    private val resolveState by weakRef(resolveState)

    private val firProvider get() = resolveState.rootModuleSession.symbolProvider
    val rootSession: FirSession = resolveState.rootModuleSession

    val classifierBuilder = ClassifierSymbolBuilder()
    val functionLikeBuilder = FunctionLikeSymbolBuilder()
    val variableLikeBuilder = VariableLikeSymbolBuilder()
    val callableBuilder = CallableSymbolBuilder()
    val anonymousInitializerBuilder = AnonymousInitializerBuilder()
    val typeBuilder = TypeBuilder()

    constructor(
        resolveState: FirModuleResolveState,
        project: Project,
        token: ValidityToken
    ) : this(
        project = project,
        token = token,
        resolveState = resolveState,
        withReadOnlyCaching = false,
        symbolsCache = BuilderCache(),
        typesCache = BuilderCache(),
        backingFieldCache = BuilderCache(),
        filesCache = BuilderCache(),
    )

    fun createReadOnlyCopy(newResolveState: FirModuleResolveState): KtSymbolByFirBuilder {
        check(!withReadOnlyCaching) { "Cannot create readOnly KtSymbolByFirBuilder from a readonly one" }
        return KtSymbolByFirBuilder(
            project,
            token = token,
            resolveState = newResolveState,
            withReadOnlyCaching = true,
            symbolsCache = symbolsCache.createReadOnlyCopy(),
            typesCache = typesCache.createReadOnlyCopy(),
            filesCache = filesCache.createReadOnlyCopy(),
            backingFieldCache = backingFieldCache.createReadOnlyCopy(),
        )
    }

    fun buildSymbol(fir: FirDeclaration): KtSymbol =
        buildSymbol(fir.symbol)

    fun buildSymbol(firSymbol: FirBasedSymbol<*>): KtSymbol {
        return when (firSymbol) {
            is FirClassLikeSymbol<*> -> classifierBuilder.buildClassLikeSymbol(firSymbol)
            is FirTypeParameterSymbol -> classifierBuilder.buildTypeParameterSymbol(firSymbol)
            is FirCallableSymbol<*> -> callableBuilder.buildCallableSymbol(firSymbol)
            else -> throwUnexpectedElementError(firSymbol)
        }
    }

    fun buildEnumEntrySymbol(firSymbol: FirEnumEntrySymbol) =
        symbolsCache.cache(firSymbol) { KtFirEnumEntrySymbol(firSymbol, resolveState, token, this) }

    fun buildFileSymbol(firSymbol: FirFileSymbol) = filesCache.cache(firSymbol) { KtFirFileSymbol(firSymbol, resolveState, token) }

    private val packageProvider = project.createPackageProvider(GlobalSearchScope.allScope(project))//todo scope

    fun createPackageSymbolIfOneExists(packageFqName: FqName): KtFirPackageSymbol? {
        val exists =
            packageProvider.doKotlinPackageExists(packageFqName)
                    || JavaPsiFacade.getInstance(project).findPackage(packageFqName.asString()) != null
        if (!exists) {
            return null
        }
        return createPackageSymbol(packageFqName)
    }

    fun createPackageSymbol(packageFqName: FqName): KtFirPackageSymbol {
        return KtFirPackageSymbol(packageFqName, project, token)
    }

    inner class ClassifierSymbolBuilder {
        fun buildClassifierSymbol(firSymbol: FirClassifierSymbol<*>): KtClassifierSymbol {
            return when (firSymbol) {
                is FirClassLikeSymbol<*> -> classifierBuilder.buildClassLikeSymbol(firSymbol)
                is FirTypeParameterSymbol -> buildTypeParameterSymbol(firSymbol)
                else -> throwUnexpectedElementError(firSymbol)
            }
        }


        fun buildClassLikeSymbol(firSymbol: FirClassLikeSymbol<*>): KtClassLikeSymbol {
            return when (firSymbol) {
                is FirClassSymbol<*> -> buildClassOrObjectSymbol(firSymbol)
                is FirTypeAliasSymbol -> buildTypeAliasSymbol(firSymbol)
                else -> throwUnexpectedElementError(firSymbol)
            }
        }

        fun buildClassOrObjectSymbol(firSymbol: FirClassSymbol<*>): KtClassOrObjectSymbol {
            return when (firSymbol) {
                is FirAnonymousObjectSymbol -> buildAnonymousObjectSymbol(firSymbol)
                is FirRegularClassSymbol -> buildNamedClassOrObjectSymbol(firSymbol)
                else -> throwUnexpectedElementError(firSymbol)
            }
        }

        fun buildNamedClassOrObjectSymbol(symbol: FirRegularClassSymbol): KtFirNamedClassOrObjectSymbol {
            return symbolsCache.cache(symbol) { KtFirNamedClassOrObjectSymbol(symbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }

        fun buildAnonymousObjectSymbol(symbol: FirAnonymousObjectSymbol): KtAnonymousObjectSymbol {
            return symbolsCache.cache(symbol) { KtFirAnonymousObjectSymbol(symbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }

        fun buildTypeAliasSymbol(symbol: FirTypeAliasSymbol): KtFirTypeAliasSymbol {
            return symbolsCache.cache(symbol) { KtFirTypeAliasSymbol(symbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }

        fun buildTypeParameterSymbol(firSymbol: FirTypeParameterSymbol): KtFirTypeParameterSymbol {
            return symbolsCache.cache(firSymbol) { KtFirTypeParameterSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }

        fun buildTypeParameterSymbolByLookupTag(lookupTag: ConeTypeParameterLookupTag): KtTypeParameterSymbol? {
            val firTypeParameterSymbol = firProvider.getSymbolByLookupTag(lookupTag) as? FirTypeParameterSymbol ?: return null
            return buildTypeParameterSymbol(firTypeParameterSymbol)
        }

        fun buildClassLikeSymbolByClassId(classId: ClassId): KtClassLikeSymbol? {
            val firClassLikeSymbol = firProvider.getClassLikeSymbolByClassId(classId) ?: return null
            return buildClassLikeSymbol(firClassLikeSymbol)
        }

        fun buildClassLikeSymbolByLookupTag(lookupTag: ConeClassLikeLookupTag): KtClassLikeSymbol? {
            val firClassLikeSymbol = firProvider.getSymbolByLookupTag(lookupTag) ?: return null
            return buildClassLikeSymbol(firClassLikeSymbol)
        }
    }

    inner class FunctionLikeSymbolBuilder {
        fun buildFunctionLikeSymbol(firSymbol: FirFunctionSymbol<*>): KtFunctionLikeSymbol {
            return when (firSymbol) {
                is FirNamedFunctionSymbol -> {
                    if (firSymbol.origin == FirDeclarationOrigin.SamConstructor) {
                        buildSamConstructorSymbol(firSymbol)
                    } else {
                        buildFunctionSymbol(firSymbol)
                    }
                }
                is FirConstructorSymbol -> buildConstructorSymbol(firSymbol)
                is FirAnonymousFunctionSymbol -> buildAnonymousFunctionSymbol(firSymbol)
                is FirPropertyAccessorSymbol -> buildPropertyAccessorSymbol(firSymbol)
                else -> throwUnexpectedElementError(firSymbol)
            }
        }

        fun buildFunctionSymbol(firSymbol: FirNamedFunctionSymbol): KtFirFunctionSymbol {
            check(firSymbol.origin != FirDeclarationOrigin.SamConstructor)
            return symbolsCache.cache(firSymbol) { KtFirFunctionSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }

        fun buildAnonymousFunctionSymbol(firSymbol: FirAnonymousFunctionSymbol): KtFirAnonymousFunctionSymbol {
            return symbolsCache.cache(firSymbol) { KtFirAnonymousFunctionSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }

        fun buildConstructorSymbol(firSymbol: FirConstructorSymbol): KtFirConstructorSymbol {
            val originalFirSymbol = firSymbol.fir.originalConstructorIfTypeAlias?.symbol ?: firSymbol
            return symbolsCache.cache(originalFirSymbol) {
                KtFirConstructorSymbol(originalFirSymbol, resolveState, token, this@KtSymbolByFirBuilder)
            }
        }

        fun buildSamConstructorSymbol(firSymbol: FirNamedFunctionSymbol): KtFirSamConstructorSymbol {
            check(firSymbol.origin == FirDeclarationOrigin.SamConstructor)
            return symbolsCache.cache(firSymbol) { KtFirSamConstructorSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }

        fun buildPropertyAccessorSymbol(firSymbol: FirPropertyAccessorSymbol): KtFunctionLikeSymbol {
            return symbolsCache.cache(firSymbol) {
                if (firSymbol.isGetter) {
                    KtFirPropertyGetterSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder)
                } else {
                    KtFirPropertySetterSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder)
                }
            }
        }
    }

    inner class VariableLikeSymbolBuilder {
        fun buildVariableLikeSymbol(firSymbol: FirVariableSymbol<*>): KtVariableLikeSymbol {
            return when (firSymbol) {
                is FirPropertySymbol -> buildVariableSymbol(firSymbol)
                is FirValueParameterSymbol -> buildValueParameterSymbol(firSymbol)
                is FirFieldSymbol -> buildFieldSymbol(firSymbol)
                is FirEnumEntrySymbol -> buildEnumEntrySymbol(firSymbol) // TODO enum entry should not be callable
                is FirBackingFieldSymbol -> buildBackingFieldSymbol(firSymbol)

                is FirErrorPropertySymbol -> throwUnexpectedElementError(firSymbol)
                is FirDelegateFieldSymbol -> throwUnexpectedElementError(firSymbol)
            }
        }

        fun buildVariableSymbol(firSymbol: FirPropertySymbol): KtVariableSymbol {
            return when {
                firSymbol.isLocal -> buildLocalVariableSymbol(firSymbol)
                firSymbol is FirAccessorSymbol -> buildSyntheticJavaPropertySymbol(firSymbol)
                else -> buildPropertySymbol(firSymbol)
            }
        }

        fun buildPropertySymbol(firSymbol: FirPropertySymbol): KtKotlinPropertySymbol {
            checkRequirementForBuildingSymbol<KtKotlinPropertySymbol>(firSymbol, !firSymbol.isLocal)
            checkRequirementForBuildingSymbol<KtKotlinPropertySymbol>(firSymbol, firSymbol !is FirAccessorSymbol)
            checkRequirementForBuildingSymbol<KtKotlinPropertySymbol>(firSymbol, firSymbol !is FirSyntheticPropertySymbol)
            return symbolsCache.cache(firSymbol) {
                KtFirKotlinPropertySymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder)
            }
        }

        fun buildLocalVariableSymbol(firSymbol: FirPropertySymbol): KtFirLocalVariableSymbol {
            checkRequirementForBuildingSymbol<KtFirLocalVariableSymbol>(firSymbol, firSymbol.isLocal)
            return symbolsCache.cache(firSymbol) {
                KtFirLocalVariableSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder)
            }
        }

        fun buildSyntheticJavaPropertySymbol(firSymbol: FirAccessorSymbol): KtFirSyntheticJavaPropertySymbol {
            return symbolsCache.cache(firSymbol) {
                KtFirSyntheticJavaPropertySymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder)
            }
        }

        fun buildValueParameterSymbol(firSymbol: FirValueParameterSymbol): KtValueParameterSymbol {
            return symbolsCache.cache(firSymbol) {
                KtFirValueParameterSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder)
            }
        }


        fun buildFieldSymbol(firSymbol: FirFieldSymbol): KtFirJavaFieldSymbol {
            checkRequirementForBuildingSymbol<KtFirJavaFieldSymbol>(firSymbol, firSymbol.fir.isJavaFieldOrSubstitutionOverrideOfJavaField())
            return symbolsCache.cache(firSymbol) { KtFirJavaFieldSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }

        fun buildBackingFieldSymbol(firSymbol: FirBackingFieldSymbol): KtFirBackingFieldSymbol {
            return backingFieldCache.cache(firSymbol) {
                KtFirBackingFieldSymbol(firSymbol.fir.propertySymbol, token, this@KtSymbolByFirBuilder)
            }
        }

        fun buildBackingFieldSymbolByProperty(firSymbol: FirPropertySymbol): KtFirBackingFieldSymbol {
            val backingFieldSymbol = firSymbol.backingFieldSymbol
                ?: error("FirProperty backingField is null")
            return buildBackingFieldSymbol(backingFieldSymbol)
        }

        private fun FirField.isJavaFieldOrSubstitutionOverrideOfJavaField(): Boolean = when (this) {
            is FirJavaField -> true
            is FirFieldImpl -> (this as FirField).originalForSubstitutionOverride?.isJavaFieldOrSubstitutionOverrideOfJavaField() == true
            else -> throwUnexpectedElementError(this)
        }
    }

    inner class CallableSymbolBuilder {
        fun buildCallableSymbol(firSymbol: FirCallableSymbol<*>): KtCallableSymbol {
            return when (firSymbol) {
                is FirPropertyAccessorSymbol -> buildPropertyAccessorSymbol(firSymbol)
                is FirFunctionSymbol<*> -> functionLikeBuilder.buildFunctionLikeSymbol(firSymbol)
                is FirVariableSymbol<*> -> variableLikeBuilder.buildVariableLikeSymbol(firSymbol)
                else -> throwUnexpectedElementError(firSymbol)
            }
        }


        fun buildPropertyAccessorSymbol(firSymbol: FirPropertyAccessorSymbol): KtPropertyAccessorSymbol {
            return when {
                firSymbol.isGetter -> buildGetterSymbol(firSymbol)
                else -> buildSetterSymbol(firSymbol)
            }
        }

        fun buildGetterSymbol(firSymbol: FirPropertyAccessorSymbol): KtFirPropertyGetterSymbol {
            checkRequirementForBuildingSymbol<KtFirPropertyGetterSymbol>(firSymbol, firSymbol.isGetter)
            return symbolsCache.cache(firSymbol) { KtFirPropertyGetterSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }

        fun buildSetterSymbol(firSymbol: FirPropertyAccessorSymbol): KtFirPropertySetterSymbol {
            checkRequirementForBuildingSymbol<KtFirPropertySetterSymbol>(firSymbol, firSymbol.isSetter)
            return symbolsCache.cache(firSymbol) { KtFirPropertySetterSymbol(firSymbol, resolveState, token, this@KtSymbolByFirBuilder) }
        }
    }

    inner class AnonymousInitializerBuilder {
        fun buildClassInitializer(firSymbol: FirAnonymousInitializerSymbol): KtClassInitializerSymbol {
            return symbolsCache.cache(firSymbol) { KtFirClassInitializerSymbol(firSymbol, resolveState, token) }
        }
    }

    inner class TypeBuilder {
        fun buildKtType(coneType: ConeKotlinType): KtType {
            return typesCache.cache(coneType) {
                when (coneType) {
                    is ConeClassLikeTypeImpl -> {
                        if (hasFunctionalClassId(coneType)) KtFirFunctionalType(coneType, token, this@KtSymbolByFirBuilder)
                        else KtFirUsualClassType(coneType, token, this@KtSymbolByFirBuilder)
                    }
                    is ConeTypeParameterType -> KtFirTypeParameterType(coneType, token, this@KtSymbolByFirBuilder)
                    is ConeClassErrorType -> KtFirClassErrorType(coneType, token, this@KtSymbolByFirBuilder)
                    is ConeFlexibleType -> KtFirFlexibleType(coneType, token, this@KtSymbolByFirBuilder)
                    is ConeIntersectionType -> KtFirIntersectionType(coneType, token, this@KtSymbolByFirBuilder)
                    is ConeDefinitelyNotNullType -> KtFirDefinitelyNotNullType(coneType, token, this@KtSymbolByFirBuilder)
                    is ConeCapturedType -> KtFirCapturedType(coneType, token)
                    is ConeIntegerLiteralType -> KtFirIntegerLiteralType(coneType, token, this@KtSymbolByFirBuilder)
                    else -> throwUnexpectedElementError(coneType)
                }
            }
        }

        private fun hasFunctionalClassId(coneType: ConeClassLikeTypeImpl): Boolean {
            val classId = coneType.classId ?: return false
            return FunctionClassKind.byClassNamePrefix(classId.packageFqName, classId.relativeClassName.asString()) != null
        }

        fun buildKtType(coneType: FirTypeRef): KtType {
            return buildKtType(coneType.coneType)
        }

        fun buildTypeArgument(coneType: ConeTypeProjection): KtTypeArgument = when (coneType) {
            is ConeStarProjection -> KtStarProjectionTypeArgument(token)
            is ConeKotlinTypeProjection -> KtTypeArgumentWithVariance(
                buildKtType(coneType.type),
                coneType.kind.toVariance(),
                token,
            )
        }

        private fun ProjectionKind.toVariance(): Variance = when (this) {
            ProjectionKind.OUT -> Variance.OUT_VARIANCE
            ProjectionKind.IN -> Variance.IN_VARIANCE
            ProjectionKind.INVARIANT -> Variance.INVARIANT
            ProjectionKind.STAR -> error("KtStarProjectionTypeArgument should not be directly created")
        }

        fun buildSubstitutor(substitutor: ConeSubstitutor): KtSubstitutor {
            if (substitutor == ConeSubstitutor.Empty) return KtSubstitutor.Empty(token)
            return KtFirSubstitutor(substitutor, this@KtSymbolByFirBuilder, token)
        }
    }

    companion object {
        private fun throwUnexpectedElementError(element: Any): Nothing {
            error("Unexpected ${element::class.simpleName}")
        }

        @OptIn(ExperimentalContracts::class)
        private inline fun <reified S : KtSymbol> checkRequirementForBuildingSymbol(
            firSymbol: FirBasedSymbol<*>,
            requirement: Boolean,
        ) {
            contract {
                returns() implies requirement
            }
            require(requirement) {
                "Cannot build ${S::class.simpleName} for ${firSymbol.fir.renderWithType(FirRenderer.RenderMode.WithResolvePhases)}"
            }
        }
    }
}


private class BuilderCache<From, To : Any> private constructor(
    private val cache: ConcurrentMap<From, To>,
    private val isReadOnly: Boolean
) {
    constructor() : this(ContainerUtil.createConcurrentSoftMap(), isReadOnly = false)

    fun createReadOnlyCopy(): BuilderCache<From, To> {
        check(!isReadOnly) { "Cannot create readOnly BuilderCache from a readonly one" }
        return BuilderCache(cache, isReadOnly = true)
    }

    inline fun <reified S : To> cache(key: From, calculation: () -> S): S {
        val value = if (isReadOnly) {
            cache[key] ?: calculation()
        } else cache.getOrPut(key, calculation)
        return value as? S
            ?: error("Cannot cast ${value::class} to ${S::class}\n${DebugSymbolRenderer.render(value as KtSymbol)}")
    }
}

internal fun FirElement.buildSymbol(builder: KtSymbolByFirBuilder) =
    (this as? FirDeclaration)?.symbol?.let(builder::buildSymbol)

internal fun FirDeclaration.buildSymbol(builder: KtSymbolByFirBuilder) =
    builder.buildSymbol(symbol)
