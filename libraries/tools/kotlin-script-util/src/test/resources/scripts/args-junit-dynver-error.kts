
@file:DependsOn("junit:junit:(4.12,4.13.2]")

org.junit.Assert.assertThrows(NullPointerException::class.java) {
    throw null!!
}

println("Hello, world!")

