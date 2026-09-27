package io.github.jvspier.dabplay.core

import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.assertEquals

/** Local test material (captures/raw, captures/golden from tools/golden.py). Tests skip when it's absent. */
object Golden {
    val root = File(System.getProperty("dabplay.captures") ?: "../captures")

    fun raw(glob: String): List<File> =
        (File(root, "raw").listFiles() ?: emptyArray()).filter { it.name.matches(Regex(glob)) }.sortedBy { it.name }

    fun expected(capture: File): List<String> {
        val f = File(root, "golden/${capture.name}.txt")
        assumeTrue("golden file missing: $f (run tools/golden.py)", f.exists())
        return f.readLines()
    }

    /** Same quoting as Python's json.dumps(ensure_ascii=False). */
    fun q(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == '\u000C' -> append("\\f")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    fun compare(name: String, expected: List<String>, actual: List<String>) {
        val firstDiff = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] }
        if (firstDiff != null || expected.size != actual.size) {
            val i = firstDiff ?: minOf(expected.size, actual.size)
            assertEquals(expected.getOrNull(i), actual.getOrNull(i), "$name: first difference at line ${i + 1} " +
                "(expected ${expected.size} lines, got ${actual.size})")
        }
    }
}
