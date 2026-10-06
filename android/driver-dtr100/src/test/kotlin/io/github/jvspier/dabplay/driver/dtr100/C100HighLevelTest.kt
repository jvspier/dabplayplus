package io.github.jvspier.dabplay.driver.dtr100

import io.github.jvspier.dabplay.tuner.Transport
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A simulated C100 stick that speaks the YHX high-level command set (docs/protocol-c100-highlevel.md) and does not
 * answer the register-level SPI passthrough. Records every transfer as "> hex (timeout)" / "< len (timeout)".
 */
private class FakeHighLevelStick(val fw: Int, val dual: Boolean, var sig: Int = 30) : Transport {
    val log = ArrayList<String>()
    var mscAvail = 0
    var ficAvail = 0
    private var reply: ByteArray? = null

    override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        assertEquals(0x02, endpoint)
        log += "> ${data.hex()} ($timeoutMs)"
        val c = data[0].toInt() and 0xFF
        reply = when (c) {
            0x00 -> b(0x80, 0, 0, 0)
            0x01 -> b(0x81, 0, 0, fw)
            0x02 -> if (dual) b(0x82, 'C'.code, '2'.code, 'D'.code, 0) else b(0x82, 0, 0, 0, 0)
            0x10 -> b(0x90, 0, 0, 0)
            0x59 -> b(0xD9, 0)
            0x48 -> b(0xC8, 1, 0, 0, 0)
            0x41, 0x43, 0x49, 0x58 -> b(c or 0x80, 0, 0, 0, 0)
            0x46 -> b(0xC6, 0, mscAvail, mscAvail shr 8, ficAvail, ficAvail shr 8, 0, 0, sig, sig shr 8, 0, 0, 7, 0)
            0x44, 0x40 -> {
                val n = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                b(c or 0x80, 0, n, n shr 8) + ByteArray(n) { it.toByte() }
            }
            else -> b(0xEE, 0, 0, 0)                  // register-level commands (0x21, 0x22): not understood
        }
        return data.size
    }

    override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        assertEquals(0x82, endpoint)
        val r = reply ?: throw IOException("timeout")       // nothing asked: like the real endpoint, a timeout
        reply = null
        log += "< ${buffer.size} ($timeoutMs)"
        val n = minOf(r.size, buffer.size)
        r.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun close() {}
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}

class C100HighLevelTest {
    @kotlin.test.BeforeTest fun freshProcess() { C100.registerLevelSeen = false }
    private var clock = 0L
    private fun open(stick: Transport) = C100.open(stick, newDriver = { Dtr100(it, sleep = {}, clockMs = { 0L }) },
        highLevel = { tr, l -> C100HighLevel.open(tr, l, sleep = {}, clockMs = { clock }) })

    /** The transfers from the first high-level command (the echo) on. */
    private fun FakeHighLevelStick.highLevel() = log.drop(log.indexOfFirst { it.startsWith("> 00 00 00 00") })

    @Test fun aDualAntennaStickGetsYhxsInitAfterTheRegisterLevelInitFails() {
        val stick = FakeHighLevelStick(fw = 5, dual = true)
        assertIs<C100HighLevel>(open(stick))
        assertEquals(listOf(
            "> 00 00 00 00 (2000)", "< 4 (8000)",
            "> 01 00 00 00 (1000)", "< 4 (10000)",
            "> 02 00 00 00 (2000)", "< 5 (10000)",
            "> 01 00 00 00 (1000)", "< 4 (10000)",
            "> 10 00 00 01 (2000)", "< 4 (8000)",
            "> 59 00 00 00 (2000)", "< 2 (8000)",
        ), stick.highLevel())
    }

    @Test fun tuneStartReadAndStopMatchTheReferenceTraces() {
        val stick = FakeHighLevelStick(fw = 5, dual = true)
        val tuner = open(stick)
        stick.log.clear()
        val tuned = tuner.tune(220352)                                    // 11C
        assertTrue(tuned.locked)
        assertEquals(4, tuned.bars)                                       // sig 30: DABdream's 100 %
        assertEquals(listOf("> 43 00 00 00 00 (2000)", "< 4 (2000)", "> 48 00 c0 5c 03 00 (2000)", "< 5 (2000)",
            "> 46 00 00 00 (2000)", "< 14 (2000)"), stick.log)

        stick.log.clear()
        tuner.startAudio(5, 96)                                           // sub-channel 5, 96 kbit/s: 288 bytes per CIF
        assertEquals(listOf("> 43 00 00 00 00 (2000)", "< 4 (2000)", "> 49 00 c0 5c 03 00 60 00 05 01 (2000)", "< 5 (2000)",
            "> 41 00 20 01 (2000)", "< 4 (2000)", "> 43 00 00 01 00 (2000)", "< 4 (2000)"), stick.log)

        stick.log.clear()
        stick.mscAvail = 1000; stick.ficAvail = 384
        clock += 20                                                       // past the minimum poll interval
        assertEquals(576, tuner.mscRead().size)                           // two CIFs, as DABdream reads them
        assertEquals(384, tuner.ficRead()!!.size)                         // status already says 384 FIC bytes
        assertNull(tuner.ficRead())                                       // used up; the next poll is too soon
        assertEquals(listOf("> 46 00 00 00 (2000)", "< 14 (2000)", "> 40 00 20 01 (2000)", "< 292 (2000)",
            "> 40 00 20 01 (2000)", "< 292 (2000)", "> 44 00 80 01 (2000)", "< 388 (2000)"), stick.log)

        stick.log.clear()
        tuner.stopAudio()
        assertEquals(listOf("> 43 00 00 00 00 (2000)", "< 4 (2000)", "> 58 00 00 00 (2000)", "< 2 (8000)"), stick.log)
    }

    @Test fun firmwareLevel4UsesTheShortStreamOff() {
        val stick = FakeHighLevelStick(fw = 4, dual = true)
        val tuner = open(stick)
        stick.log.clear()
        tuner.tune(174928)                                                // 5A
        assertEquals("> 41 00 00 00 (2000)", stick.log[0])
        assertEquals("> 48 00 50 ab 02 00 (2000)", stick.log[2])
    }

    @Test fun aStickThatIsNeitherDualNorLevel10IsLeftAlone() {
        val stick = FakeHighLevelStick(fw = 4, dual = false)
        assertFailsWith<Dtr100.DriverException> { open(stick) }         // the register-level error, as before
        assertEquals(listOf("> 00 00 00 00 (2000)", "< 4 (8000)", "> 01 00 00 00 (1000)", "< 4 (10000)",
            "> 02 00 00 00 (2000)", "< 5 (10000)", "> 01 00 00 00 (1000)", "< 4 (10000)"), stick.highLevel())
    }

    @Test fun signalFollowsDabdreamsLevels() {
        val stick = FakeHighLevelStick(fw = 5, dual = true)
        val tuner = open(stick)
        for ((sig, bars) in listOf(10 to 4, 99 to 4, 150 to 3, 300 to 2, 500 to 1, 600 to 0, 2000 to 0)) {
            stick.sig = sig
            tuner.tune(220352)
            assertEquals(bars, tuner.signal!!.bars, "sig $sig")
            assertEquals(sig >= 600, tuner.signal!!.none)
        }
    }

    /** Our DTR-100 with a voltage dip: power-on times out. That is not "a different kind of stick": no 0x02. */
    @Test fun aTimeoutOnARegisterLevelStickNeverTriesTheHighLevelSet() {
        val sent = ArrayList<Int>()
        var reply: ByteArray? = null
        val dtr = object : Transport {
            override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
                val c = data[0].toInt() and 0xFF; sent += c
                reply = when (c) { 0x01 -> byteArrayOf(0x81.toByte(), 0, 0, 4); else -> null }    // 0x10: no answer
                return data.size
            }
            override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
                val r = reply ?: throw IOException("timeout"); reply = null
                r.copyInto(buffer); return r.size
            }
            override fun close() {}
        }
        assertFailsWith<IOException> { open(dtr) }
        assertEquals(listOf(0x01, 0x10), sent)
    }

    /** Once a register-level init worked in this process, a later failed register check is a glitch, not a new stick. */
    @Test fun afterARegisterLevelSuccessTheFallbackIsOff() {
        C100.registerLevelSeen = true
        val stick = FakeHighLevelStick(fw = 5, dual = true)
        assertFailsWith<Dtr100.NotRegisterLevel> { open(stick) }
        assertTrue(stick.log.none { it.startsWith("> 02") })
    }
}
