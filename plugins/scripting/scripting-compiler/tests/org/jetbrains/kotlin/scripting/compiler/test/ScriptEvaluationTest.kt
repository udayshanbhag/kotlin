import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptJvmCompilerIsolated
import org.jetbrains.kotlin.scripting.compiler.test.assertEqualsTrimmed
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.util.renderError

/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */


class ScriptEvaluationTest : TestCase() {

    fun testExceptionWithCause() {
        checkEvaluateAsError(
            """
                try {
                    throw Exception("Error!")
                } catch (e: Exception) {
                    throw Exception("Oh no", e)
                }
            """.trimIndent().toScriptSource("exceptionWithCause.kts"),
            """
                java.lang.Exception: Oh no
	                    at ExceptionWithCause.<init>(exceptionWithCause.kts:4)
                Caused by: java.lang.Exception: Error!
	                    at ExceptionWithCause.<init>(exceptionWithCause.kts:2)
            """.trimIndent()
        )
    }

    fun testReceivers1() {
        checkEvaluate(
            """
                fun foo() {
                    B()
                }
                //val b = B()

                class A
                fun A.ext() = Unit

                class B {
                    fun bar() {
                        A().ext()
                    }
                }            
            """.trimIndent().toScriptSource()
        )
    }

    fun testReceivers2() {
        checkEvaluate(
            File("plugins/scripting/scripting-compiler/testData/compiler/kt-49443.main.kts").toScriptSource()
        )
    }

    fun testReceivers3() {
        checkEvaluate(
            """
                class A
                fun A.ext() = Unit

                class B {
                    fun bar() {
                        A().ext()
                    }
                } 
                           
                object C {
                    fun foo() {
                        B()
                    }
                }
                //val b = B()
            """.trimIndent().toScriptSource()
        )
    }

    fun testReceivers4() {
        checkEvaluate(
            """
                val x = 1

                class B {
                    fun bar() {
                        val y = x
                    }
                } 
                           
                class C {
                    fun foo() {
                        B()
                    }
                }
                //val b = B()
            """.trimIndent().toScriptSource()
        )
    }

    private fun checkEvaluateAsError(script: SourceCode, expectedOutput: String): EvaluationResult {
        val res = checkEvaluate(script)
        assert(res.returnValue is ResultValue.Error)
        ByteArrayOutputStream().use { os ->
            val ps = PrintStream(os)
            (res.returnValue as ResultValue.Error).renderError(ps)
            ps.flush()
            assertEqualsTrimmed(expectedOutput, os.toString())
        }
        return res
    }

    private fun checkEvaluate(script: SourceCode): EvaluationResult {
        val compilationConfiguration = ScriptCompilationConfiguration()
        val compiler = ScriptJvmCompilerIsolated(defaultJvmScriptingHostConfiguration)
        val compiled = compiler.compile(script, compilationConfiguration).valueOrThrow()
        val evaluationConfiguration = ScriptEvaluationConfiguration()
        val evaluator = BasicJvmScriptEvaluator()
        val res = runBlocking {
            evaluator.invoke(compiled, evaluationConfiguration).valueOrThrow()
        }
        return res
    }
}
