package io.github.jvspier.dabplay.driver.siano

import io.github.jvspier.dabplay.core.Crc
import io.github.jvspier.dabplay.tuner.Transport
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
private fun le32(v: Long) = ByteArray(4) { (v ushr (8 * it)).toByte() }

/** A stick → host message: header (source 0x0B) + payload. */
private fun reply(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
    val len = 8 + payload.size
    return byteArrayOf(type.toByte(), (type shr 8).toByte(), 0x0B, 0, len.toByte(), (len shr 8).toByte(), 0, 0) + payload
}

/** A FIB carrying FIG 0/1 for one EEP sub-channel (long form), with a valid CRC. */
private fun fig01Fib(id: Int, start: Int, option: Int, level: Int, sizeCu: Int): ByteArray {
    val sub = byteArrayOf(((id shl 2) or (start shr 8)).toByte(), start.toByte(),
        (0x80 or (option shl 4) or (level shl 2) or (sizeCu shr 8)).toByte(), sizeCu.toByte())
    val fig = byteArrayOf((0 shl 5 or (1 + sub.size)).toByte(), 0x01) + sub            // FIG type 0, extension 1
    val fib = (fig + ByteArray(30 - fig.size) { 0xFF.toByte() })
    val crc = Crc.crc16Ccitt(fib, 0, 30) xor 0xFFFF
    return fib + byteArrayOf((crc shr 8).toByte(), crc.toByte())
}

/** A simulated stick: answers through [answer], records what was written; an empty read times out (+timeout ms). */
private class FakeStick : Transport {
    var clock = 0L
    val sent = ArrayList<String>()
    val inbox = java.util.ArrayDeque<ByteArray>()
    var answer: (type: Int, msg: ByteArray) -> List<ByteArray> = { _, _ -> emptyList() }

    override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        assertEquals(0x02, endpoint)
        sent += hex(data)
        answer((data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8), data).forEach { inbox += it }
        return data.size
    }

    override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        assertEquals(0x81, endpoint)
        val b = inbox.pollFirst() ?: run { clock += timeoutMs; throw IOException("timeout") }
        b.copyInto(buffer); return b.size
    }

    override fun close() {}
}

class SianoTest {
    private val stick = FakeStick()
    private val d = Siano.forTest(stick, clockMs = { stick.clock })

    private fun version(label: String) = reply(0x029D, ByteArray(14) + label.toByteArray() + ByteArray(34 - label.length))
    /** GET_STATISTICS reply: is_demod_locked at offset 20, pre-BER at 156 (message offsets). */
    private fun stats(locked: Boolean, preBer: Long) =
        reply(0x0268, ByteArray(12) + byteArrayOf(if (locked) 1 else 0, 0, 0, 0) + ByteArray(156 - 24) + le32(preBer) + ByteArray(8))

    private fun tdmbStick() {
        stick.answer = { type, _ -> when (type) {
            0x029C -> listOf(version("TDMB 2.1"))
            0x0231 -> listOf(reply(0x0232))
            0x0267 -> listOf(stats(locked = true, preBer = 3000))
            0x024D -> listOf(reply(0x024E))
            0x024F -> listOf(reply(0x0250))
            else -> emptyList()
        } }
        d.init()
    }

    @Test fun messagesMatchTheSpecsGoldenBytes() {
        assertEquals("9c 02 00 0b 08 00 00 00", hex(Siano.msg(0x029C)))
        assertEquals("31 02 00 0b 18 00 00 00 00 3d 8d 0d 0f 00 00 00 00 1b b7 00 00 00 00 00",
            hex(Siano.msg(0x0231, 227_360_000, 15, 12_000_000, 0)))                         // tune 12D
        assertEquals("4f 02 00 0b 0c 00 00 00 05 00 00 00", hex(Siano.msg(0x024F, 5)))
        assertEquals("44 03 00 0b 0c 00 00 00 01 00 00 00", hex(Siano.msg(0x0344, 1)))
    }

    @Test fun tdmbFirmwareInitsWithoutAnythingElse() {
        tdmbStick()
        assertEquals(listOf("9c 02 00 0b 08 00 00 00"), stick.sent)
        assertEquals("Siano, firmware TDMB 2.1", d.description)
    }

    @Test fun dab8FirmwareSwitchesOnPeriodicStatisticsAndWaitsForThem() {
        stick.answer = { type, _ -> when (type) {
            0x029C -> listOf(version("DAB8.0.62dDNV"))
            0x0344 -> listOf(reply(0x0345), reply(0x031E, byteArrayOf(1, 0, 0, 0) + ByteArray(8) + le32(4100) + ByteArray(24)))
            else -> emptyList()
        } }
        d.init()
        assertEquals(listOf("9c 02 00 0b 08 00 00 00", "44 03 00 0b 0c 00 00 00 01 00 00 00"), stick.sent)
        assertEquals(4100, d.signal!!.raw)
    }

    @Test fun theRioAndUnknownFirmwareAreRefused() {
        stick.answer = { type, _ -> if (type == 0x029C) listOf(version("Rio-A0 1.0")) else emptyList() }
        assertFailsWith<IOException> { d.init() }
        val d2 = Siano.forTest(stick, clockMs = { stick.clock })
        stick.answer = { type, _ -> if (type == 0x029C) listOf(version("ISDBT 5.0")) else emptyList() }
        assertFailsWith<IOException> { d2.init() }
    }

    @Test fun tuneLocksFromTheStatisticsAndFicIsFilteredByFrequency() {
        tdmbStick()
        stick.sent.clear()
        val t = d.tune(227360)
        assertTrue(t.locked)
        assertEquals(4, t.bars)                                                     // pre-BER 3000: best
        assertEquals("31 02 00 0b 18 00 00 00 00 3d 8d 0d 0f 00 00 00 00 1b b7 00 00 00 00 00", stick.sent[0])
        assertEquals("67 02 00 0b 08 00 00 00", stick.sent[1])                      // statistics request
        val fib = fig01Fib(5, 26, 0, 2, 72)
        stick.inbox += reply(0x025E, ByteArray(8) + le32(227_360_000) + fib)       // this frequency: kept
        stick.inbox += reply(0x025E, ByteArray(8) + le32(220_352_000) + fib)       // another one: ignored
        assertContentEquals(fib, d.ficRead())
        assertNull(d.ficRead())
    }

    @Test fun startUsesTheFicLayoutExactlyAsTheSpecsExample() {
        tdmbStick()
        d.tune(227360)
        stick.inbox += reply(0x025E, ByteArray(8) + le32(227_360_000) + fig01Fib(5, 26, 0, 2, 72))   // EEP 3-A, 72 CU = 96 kbit/s
        d.ficRead()
        stick.sent.clear()
        d.startAudio(5, 96)
        assertEquals("4d 02 00 0b 40 00 00 00 00 00 00 00 05 00 00 00 1a 00 00 00 00 12 00 00 00 09 00 00 01 00 00 00 " +
            "03 00 00 00 ff ff ff ff 00 00 00 00 ff ff ff ff ff ff ff ff 00 00 00 00 01 00 00 00 ff ff ff ff", stick.sent[0])
        stick.inbox += reply(0x025F, byteArrayOf(1, 2, 3, 4, 5)) + reply(0x025F, byteArrayOf(6))   // two in one read
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), d.mscRead())
        stick.sent.clear()
        d.stopAudio()
        assertEquals(listOf("4f 02 00 0b 0c 00 00 00 05 00 00 00"), stick.sent)
        stick.inbox += reply(0x025F, byteArrayOf(9))
        assertEquals(0, d.mscRead().size)                                            // stopped: nothing kept
    }

    @Test fun preBerMapsToDabdreamsLevels() {
        tdmbStick()
        for ((ber, bars) in listOf(4000L to 4, 4200L to 4, 4201L to 3, 4500L to 3, 6000L to 2, 7500L to 1, 7501L to 0)) {
            stick.inbox += stats(locked = true, preBer = ber)
            d.mscRead()
            assertEquals(bars, d.signal!!.bars, "pre-BER $ber")
        }
        stick.inbox += stats(locked = false, preBer = 1000)
        d.mscRead()
        assertTrue(d.signal!!.none)
    }
}
