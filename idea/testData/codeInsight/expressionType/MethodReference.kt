fun foo() {
}

fun bar() {
    run(::<caret>foo)
}

// TYPE: ::foo -> <html>KFunction0&lt;Unit&gt;</html>
// TYPE: run(::foo) -> <html>Unit</html>
