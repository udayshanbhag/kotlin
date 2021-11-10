
@file:DependsOn("junit:junit:(4.13.2,5.0)")

org.junit.Assert.assertThrows(NullPointerException::class.java) {
    throw null!!
}

println("Hello, World!")

