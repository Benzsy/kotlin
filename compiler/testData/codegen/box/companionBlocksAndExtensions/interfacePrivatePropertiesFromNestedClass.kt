// LANGUAGE: +CompanionBlocks

interface I {
    companion {
        private val privateVal = "OK"
    }

    class NestedClass {
        fun read() = privateVal
    }
}

fun box(): String {
    return I.NestedClass().read()
}
