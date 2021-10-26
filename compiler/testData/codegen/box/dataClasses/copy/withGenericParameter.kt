interface X

class Y : X

fun box() : String {

    val x = X::class
    val y = Y()

    if (x.isInstance(y)) {
        return "OK"
    }

    return "fail"
}
