// FIR_IDENTICAL
fun <E> SmartList(x: E) {}
fun <E> SmartList(x: Collection<E>) {}

fun main() {
    SmartList(1..2)
}