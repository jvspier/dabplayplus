package io.github.jvspier.dabplay.driver.dtr100

import io.github.jvspier.dabplay.tuner.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Replays request/reply pairs recorded from the real dongle (captures, 2026-09-23). */
class ScriptedTransport(private val script: List<Pair<String, String>>) : Transport {
    private var i = 0
    private var pending: ByteArray? = null
    override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val (req, rep) = script[i++]
        assertEquals(req, data.hex(), "request #$i")
        pending = rep.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        return data.size
    }
    override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        val p = pending!!; p.copyInto(buffer); pending = null; return p.size
    }
    override fun close() {}
}

class Dtr100Test {
    @Test fun echoAndVersionAsRecordedOnHardware() {
        val d = Dtr100(ScriptedTransport(listOf(
            "00 00 00 00" to "80 44 41 42",
            "01 00 00 00" to "81 00 00 04",
        )))
        assertTrue(d.echo())
        assertEquals(4, d.version())
        assertEquals(4, d.firmwareLevel)
    }
}
