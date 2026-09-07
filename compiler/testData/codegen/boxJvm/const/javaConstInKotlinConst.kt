// LANGUAGE: +IntrinsicConstEvaluation
// TARGET_BACKEND: JVM
// WITH_STDLIB

// A Java `static final` is a JLS constant variable, so it can seed a Kotlin
// `const val`. Every initializer below goes through a function that is only
// const-evaluable under +IntrinsicConstEvaluation.

// FILE: J.java
public class J {
    public static final String PADDED = "  OK  ";
    public static final byte MASK = 0x0F;
    public static final int N = 41;
    public static final char C = 'a';
}

// FILE: box.kt
import kotlin.experimental.and
import kotlin.experimental.inv

fun <T> T.id() = this

const val trimmed: String = J.PADDED.trim()
const val upper: String = J.PADDED.trim().uppercase()
const val masked: Byte = J.MASK and 0x3C.toByte()
const val inverted: Byte = J.MASK.inv()
const val bumped: Int = J.N.inc()
const val fromCode: Char = Char(J.N + 24)
const val fromJavaChar: String = J.C.toString().uppercase()

fun box(): String {
    if (trimmed.id() != "OK") return "Fail trimmed: $trimmed"
    if (upper.id() != "OK") return "Fail upper: $upper"
    if (masked.id() != 0x0C.toByte()) return "Fail masked: $masked"
    if (inverted.id() != (-16).toByte()) return "Fail inverted: $inverted"
    if (bumped.id() != 42) return "Fail bumped: $bumped"
    if (fromCode.id() != 'A') return "Fail fromCode: $fromCode"
    if (fromJavaChar.id() != "A") return "Fail fromJavaChar: $fromJavaChar"
    return "OK"
}
