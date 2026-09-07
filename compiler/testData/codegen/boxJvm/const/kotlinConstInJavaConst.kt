// LANGUAGE: +IntrinsicConstEvaluation
// TARGET_BACKEND: JVM
// WITH_STDLIB

// The other direction: a Kotlin `const val` computed by the newly allowed
// functions is emitted with a ConstantValue attribute, so javac accepts it as a
// JLS constant variable -- in an initializer, in constant arithmetic, in string
// concatenation and as a `switch` case label, which is the strictest constant
// context javac has. The results then come back into Kotlin `const val`s.

// FILE: KConst.kt
import kotlin.experimental.and

object KConst {
    const val PADDED = "  OK  "
    const val TRIMMED = PADDED.trim()
    const val N = 41
    const val BUMPED = N.inc()
    const val MASK: Byte = 0x0F
    const val MASKED: Byte = MASK and 0x3C.toByte()
}

// FILE: JUses.java
public class JUses {
    public static final String FROM_KOTLIN = KConst.TRIMMED;
    public static final int SUM = KConst.BUMPED + 1;
    public static final byte MASKED = KConst.MASKED;
    public static final String CONCAT = KConst.TRIMMED + "!";

    public static int classify(int v) {
        switch (v) {
            case KConst.BUMPED: return 1;
            default: return 0;
        }
    }
}

// FILE: box.kt
fun <T> T.id() = this

const val roundTrip: String = JUses.FROM_KOTLIN.trim()
const val roundTripUpper: String = JUses.CONCAT.uppercase()
const val roundTripInt: Int = JUses.SUM.inc()

fun box(): String {
    if (JUses.FROM_KOTLIN.id() != "OK") return "Fail FROM_KOTLIN: ${JUses.FROM_KOTLIN}"
    if (JUses.SUM.id() != 43) return "Fail SUM: ${JUses.SUM}"
    if (JUses.MASKED.id() != 0x0C.toByte()) return "Fail MASKED: ${JUses.MASKED}"
    if (JUses.CONCAT.id() != "OK!") return "Fail CONCAT: ${JUses.CONCAT}"
    if (JUses.classify(42).id() != 1) return "Fail classify: ${JUses.classify(42)}"
    if (roundTrip.id() != "OK") return "Fail roundTrip: $roundTrip"
    if (roundTripUpper.id() != "OK!") return "Fail roundTripUpper: $roundTripUpper"
    if (roundTripInt.id() != 44) return "Fail roundTripInt: $roundTripInt"
    return "OK"
}
