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

/**
 * A register-level stick: the MCU commands the driver uses, and chip registers per page that read back what was
 * written. [notReadyChecks] interface checks read back 0x00 (a chip still coming up); page 0x0F reg 0x15 reads [r15].
 */
private class FakeRegisterStick(var notReadyChecks: Int = 0, var r15: Int = 0x01) : Transport {
    val writes = ArrayList<Triple<Int, Int, Int>>()      // page, reg, value
    private val regs = HashMap<Pair<Int, Int>, Int>()
    private var pg = 0
    private var reply: ByteArray? = null

    override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val d = IntArray(data.size) { data[it].toInt() and 0xFF }
        reply = when (d[0]) {
            0x01 -> b(0x81, 0, 0, 4)
            0x10 -> b(0x90, 0, 0, 0)
            0x21 -> { val (reg, v) = d[4] to d[5]; if (reg == 0x03) pg = v else { regs[pg to reg] = v; writes += Triple(pg, reg, v) }; b(0xA1) }
            0x22 -> {
                val reg = d[4]; val n = d[2] or (d[3] shl 8)
                val v = when {
                    pg == 7 && reg == 0x7D && notReadyChecks > 0 -> { notReadyChecks--; 0x00 }
                    pg == 0x0F && reg == 0x15 -> r15
                    else -> regs[pg to reg] ?: 0
                }
                b(0xA2, 0, n, n shr 8) + ByteArray(n) { v.toByte() }
            }
            else -> error("unexpected command ${data.hex()}")
        }
        return data.size
    }

    override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        val r = reply ?: throw java.io.IOException("timeout"); reply = null
        r.copyInto(buffer); return r.size
    }
    override fun close() {}
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}

class Dtr100InitTest {
    @kotlin.test.BeforeTest fun freshProcess() { C100.registerLevelSeen = false }
    private val logged = ArrayList<String>()
    private fun open(stick: Transport) = C100.open(stick, log = { logged += it }, newDriver = { Dtr100(it, sleep = {}, clockMs = { 0L }) },
        highLevel = { _, _ -> null })

    /** Issue #3: the chip answered the interface check only after a while; one try made every init fail. */
    @Test fun aChipThatIsSlowAfterPowerOnIsWaitedFor() {
        val stick = FakeRegisterStick(notReadyChecks = 40)
        kotlin.test.assertIs<Dtr100Tuner>(open(stick))
        assertTrue("chip answered after 41 interface checks" in logged, logged.toString())
    }

    @Test fun aChipThatNeverAnswersFailsTheRegisterCheckWithWhatItReadBack() {
        val stick = FakeRegisterStick(notReadyChecks = Int.MAX_VALUE)
        val e = kotlin.test.assertFailsWith<Dtr100.NotRegisterLevel> { open(stick) }
        assertEquals("check interface failed 100 times (read back 0x00)", e.message)
    }

    /** Issue #3: as in the vendor's FUN_00078b20, a clock plan that doesn't apply fails the tune; the next tune retries it. */
    @Test fun aClockPlanThatDoesNotApplyIsAFailedTuneAndTheNextTuneWritesItAgain() {
        val stick = FakeRegisterStick()
        val d = Dtr100(stick, sleep = {}, clockMs = { 0L })
        d.version(); d.powerOn(); d.systemInit()             // clock plan 0
        stick.r15 = 0x00
        val first = d.tune(174928)                           // 5A: clock plan 3
        assertEquals(-14, first.rc)
        assertEquals("clock plan not applied", first.info.why)
        stick.writes.clear(); stick.r15 = 0x01
        d.tune(174928)
        assertTrue(stick.writes.any { it.first == 0x0F && it.second == 0xE8 }, "clock plan written again")
    }
}
