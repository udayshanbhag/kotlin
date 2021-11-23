// IGNORE_BACKEND_FIR: JVM_IR
// TARGET_BACKEND: JVM_IR
// IGNORE_DEXING
// WITH_RUNTIME
// !LANGUAGE: +InstantiationOfAnnotationClasses

// MODULE: lib
// FILE: lib.kt

package a

import kotlin.reflect.KClass

annotation class A(val kClass: KClass<*> = Int::class)

annotation class OtherArrays(
    val doublesArray: DoubleArray = [],
    val enumArray: Array<kotlin.text.RegexOption> = [],
    val annotationsArray: Array<JvmStatic> = [],
    val namesArray: Array<JvmName> = [JvmName("foo")]
)

// MODULE: app(lib)
// FILE: app.kt

// kotlin.Metadata: IntArray, Array<String>
// kotlin.Deprecated: Nested annotation, enum instance
// a.A: KClass
// a.OtherArrays: Arrays of enums and other annotations

package test

import a.*
import kotlin.test.*

class C {
    fun one(): A = A()
    fun two(): Metadata = Metadata()
    fun three(): Deprecated = Deprecated("foo")
    fun four(): OtherArrays = OtherArrays()
}

fun box(): String {
    val a = C().one()
    assertEquals(Int::class, a.kClass)
    assertEquals(
        """@kotlin.Metadata(bytecodeVersion=[1, 0, 3], data1=[], data2=[], extraInt=0, extraString=, kind=1, metadataVersion=[], packageName=)""",
        C().two().toString()
    )
    assertEquals(
        """@kotlin.Deprecated(level=WARNING, message=foo, replaceWith=@kotlin.ReplaceWith(expression=, imports=[]))""",
        C().three().toString()
    )
    assertEquals(
        """@a.OtherArrays(annotationsArray=[], doublesArray=[], enumArray=[], namesArray=[@kotlin.jvm.JvmName(name=foo)])""",
        C().four().toString()
    )
    return "OK"
}
