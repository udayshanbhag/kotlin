/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package kotlin.reflect.wasm.internal

import kotlin.reflect.*
import kotlin.wasm.internal.TypeInfoData
import kotlin.wasm.internal.getInterfaceImplId

internal object NothingKClassImpl : KClass<Nothing> {
    override val simpleName: String = "Nothing"
    override val qualifiedName: String get() = "kotlin.Nothing"

    override fun isInstance(value: Any?): Boolean = false

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -1
}

internal object ErrorKClass : KClass<Nothing> {
    override val simpleName: String get() = error("Unknown simpleName for ErrorKClass")
    override val qualifiedName: String get() = error("Unknown qualifiedName for ErrorKClass")

    override fun isInstance(value: Any?): Boolean = error("Can's check isInstance on ErrorKClass")

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0
}

internal class KClassImpl<T : Any>(private val typeData: TypeInfoData) : KClass<T> {
    override val simpleName: String get() = typeData.typeName
    override val qualifiedName: String =
        if (typeData.packageName.isEmpty()) typeData.typeName else "${typeData.packageName}.${typeData.typeName}"

    override fun isInstance(value: Any?): Boolean =
        value != null && (value.typeInfo == typeData.typeId || getInterfaceImplId(value, typeData.typeId) != -1)

    override fun equals(other: Any?): Boolean =
        (this === other) || (other is KClassImpl<*> && other.typeInfo == typeData.typeId)

    override fun hashCode(): Int = typeData.typeId
}