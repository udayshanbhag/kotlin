// TARGET_BACKEND: JVM_IR
// MODULE: m1
// FILE: m1.kt

abstract class Base {
    protected lateinit var some: String
}

// MODULE: m2(m1)
// FILE: m2.kt

class Derived : Base() {
    init {
        some = "OK"
    }

    fun getAnswer() = some
}

fun box() = Derived().getAnswer()

