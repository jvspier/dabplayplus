package io.github.jvspier.dabplay.core.dabplus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReedSolomonTest {
    private val rs = ReedSolomon()

    @Test fun matchesReedsoloParity() {
        // parity of bytes 0..109 computed with Python reedsolo (RSCodec(10, fcr=0, prim=0x11d))
        val msg = ByteArray(110) { it.toByte() }
        assertEquals("a28a690cea30bdd4a35c", rs.encode(msg).copyOfRange(110, 120).joinToString("") { "%02x".format(it) })
    }

    @Test fun correctsUpToFiveErrorsAnywhere() {
        val rnd = Random(42)
        repeat(2000) { round ->
            val msg = ByteArray(110).also { rnd.nextBytes(it) }
            val cw = rs.encode(msg)
            assertTrue(rs.check(cw))
            val n = round % 6                                  // 0..5 errors
            val pos = (0 until 120).shuffled(rnd).take(n)
            for (p in pos) cw[p] = (cw[p].toInt() xor (1 + rnd.nextInt(255))).toByte()
            assertEquals(n, rs.decode(cw), "round $round")
            assertContentEquals(msg, cw.copyOfRange(0, 110))
        }
    }

    @Test fun reportsSixErrorsAsUncorrectableOrCorrectsToAValidCodeword() {
        val rnd = Random(7)
        var failed = 0
        repeat(500) {
            val cw = rs.encode(ByteArray(110).also { rnd.nextBytes(it) })
            for (p in (0 until 120).shuffled(rnd).take(6)) cw[p] = (cw[p].toInt() xor (1 + rnd.nextInt(255))).toByte()
            val r = rs.decode(cw)
            if (r < 0) failed++ else assertTrue(rs.check(cw))   // miscorrection to another codeword is possible
        }
        assertTrue(failed > 450, "6 errors should almost always be detected as uncorrectable ($failed/500)")
    }
}
