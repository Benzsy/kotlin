// LANGUAGE: +IntrinsicConstEvaluation
// TARGET_BACKEND: JVM
// WITH_STDLIB

// Kotlin constants computed by the newly allowed functions used as arguments of
// a *Java* annotation, applied both from Java and from Kotlin. KHolderInline
// additionally calls the functions directly in the annotation argument instead
// of going through a named `const val`.
//
// `bigDecoded` covers the unsigned case: a UInt const larger than Int.MAX_VALUE
// flattens to a negative JVM int, and each side has to reach the true value its
// own way -- Kotlin by `toLong()`, Java by masking off the sign extension.

// FILE: KConst.kt
import kotlin.experimental.and

object KConst {
    const val TRIMMED: String = "  OK  ".trim()
    const val BUMPED: Int = 41.inc()
    const val MASKED: Byte = (0x0F.toByte()) and (0x3C.toByte())
    const val CODE: Char = Char(41 + 24)

    // Does not fit in a signed Int. Kotlin's view is 4200000000; the JVM field is
    // an `int` holding -94967296, which is what Java reads without decoding.
    const val BIG_U: UInt = 4000000000u + 200000000u
}

// FILE: JAnno.java
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface JAnno {
    String name();
    int count();
    byte mask();
    char code();

    // Declared `long` so the unsigned value fits. See the call sites for the two
    // ways of getting the correct number into it.
    long bigDecoded();
}

// FILE: JHolder.java
// `KConst.BIG_U` on its own would widen the raw int and store -94967296.
// `& 0xFFFFFFFFL` clears the sign extension and recovers 4200000000; it is what
// Integer.toUnsignedLong does internally, but unlike a method call it is a
// constant expression (JLS 15.28) and so is legal in an annotation argument.
@JAnno(
    name = KConst.TRIMMED,
    count = KConst.BUMPED,
    mask = KConst.MASKED,
    code = KConst.CODE,
    bigDecoded = KConst.BIG_U & 0xFFFFFFFFL
)
public class JHolder {}

// FILE: box.kt
fun <T> T.id() = this

// From Kotlin the unsigned type survives, so a plain `toLong()` is enough -- and
// it const-evaluates to the same 4200000000 that Java has to unmask for.
@JAnno(
    name = KConst.TRIMMED,
    count = KConst.BUMPED,
    mask = KConst.MASKED,
    code = KConst.CODE,
    bigDecoded = KConst.BIG_U.toLong(),
)
class KHolderNamed

@JAnno(
    name = "  OK  ".trim(),
    count = 41.inc(),
    mask = 0x0C,
    code = Char(41 + 24),
    bigDecoded = (4000000000u + 200000000u).toLong(),
)
class KHolderInline

fun box(): String {
    val holders = listOf(
        "JHolder" to JHolder::class.java,
        "KHolderNamed" to KHolderNamed::class.java,
        "KHolderInline" to KHolderInline::class.java,
    )
    for ((tag, klass) in holders) {
        val a = klass.getAnnotation(JAnno::class.java) ?: return "Fail $tag: no annotation"
        if (a.name.id() != "OK") return "Fail $tag name: ${a.name}"
        if (a.count.id() != 42) return "Fail $tag count: ${a.count}"
        if (a.mask.id() != 0x0C.toByte()) return "Fail $tag mask: ${a.mask}"
        if (a.code.id() != 'A') return "Fail $tag code: ${a.code}"
        // All three routes -- Java's mask, Kotlin's toLong() on a named const and
        // Kotlin's toLong() inline -- must agree on the unsigned value.
        if (a.bigDecoded.id() != 4200000000L) return "Fail $tag bigDecoded: ${a.bigDecoded}"
    }
    if (KConst.BIG_U.id() != 4200000000u) return "Fail BIG_U: ${KConst.BIG_U}"
    return "OK"
}
