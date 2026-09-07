// LANGUAGE: +IntrinsicConstEvaluation
// TARGET_BACKEND: JVM
// WITH_STDLIB

// Java constants transformed by the newly allowed functions used as arguments
// of a *Kotlin* annotation. KTarget applies it from Kotlin, running the values
// through trim/inc/and/Char; JTarget applies the same Kotlin annotation from
// Java, where the same values have to be spelled as Java constant expressions.

// FILE: JSource.java
public class JSource {
    public static final String PADDED = "  OK  ";
    public static final int N = 41;
    public static final byte MASK = 0x0F;
}

// FILE: KAnno.kt
@Retention(AnnotationRetention.RUNTIME)
annotation class KAnno(val name: String, val count: Int, val mask: Byte, val code: Char)

// FILE: JTarget.java
@KAnno(
    name = "OK",
    count = JSource.N + 1,
    mask = (byte) (JSource.MASK & 0x3C),
    code = (char) (JSource.N + 24)
)
public class JTarget {}

// FILE: box.kt
import kotlin.experimental.and

fun <T> T.id() = this

@KAnno(
    name = JSource.PADDED.trim(),
    count = JSource.N.inc(),
    mask = JSource.MASK and 0x3C.toByte(),
    code = Char(JSource.N + 24),
)
class KTarget

fun box(): String {
    val targets = listOf(
        "KTarget" to KTarget::class.java,
        "JTarget" to JTarget::class.java,
    )
    for ((tag, klass) in targets) {
        val a = klass.getAnnotation(KAnno::class.java) ?: return "Fail $tag: no annotation"
        if (a.name.id() != "OK") return "Fail $tag name: ${a.name}"
        if (a.count.id() != 42) return "Fail $tag count: ${a.count}"
        if (a.mask.id() != 0x0C.toByte()) return "Fail $tag mask: ${a.mask}"
        if (a.code.id() != 'A') return "Fail $tag code: ${a.code}"
    }
    return "OK"
}
