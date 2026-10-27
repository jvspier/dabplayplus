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
 * The MCU's audio buffer holds [mcuAvail] bytes (a tester's stick: never any, and "no signal"); the chip's page 9
 * reg 0x33 reads [chipStatus] and its audio buffer (page 0x0C reg 0x10) reads 0xAB bytes.
 */
private class FakeRegisterStick(var notReadyChecks: Int = 0, var r15: Int = 0x01) : Transport {
    val writes = ArrayList<Triple<Int, Int, Int>>()      // page, reg, value
    /** Every request, as hex. */
    val sent = ArrayList<String>()
    var mcuAvail = 0
    var mcuSignal = 2000
    var chipStatus = 0
    private val regs = HashMap<Pair<Int, Int>, Int>()
    private var pg = 0
    private var reply: ByteArray? = null

    override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val d = IntArray(data.size) { data[it].toInt() and 0xFF }
        sent += data.hex()
        reply = when (d[0]) {
            0x01 -> b(0x81, 0, 0, 4)
            0x10 -> b(0x90, 0, 0, 0)
            0x40 -> { val n = minOf(d[2] or (d[3] shl 8), mcuAvail); mcuAvail -= n; b(0xC0, 0, n and 0xFF, n shr 8) + ByteArray(n) { 0x55 } }
            0x41 -> b(0xC1, 0, 0, 0)
            0x42 -> b(0xC2, 0, mcuAvail and 0xFF, mcuAvail shr 8, mcuSignal and 0xFF, mcuSignal shr 8)
            0x43 -> b(0xC3, 0, 0, 0)
            0x21 -> { val (reg, v) = d[4] to d[5]; if (reg == 0x03) pg = v else { regs[pg to reg] = v; writes += Triple(pg, reg, v) }; b(0xA1) }
            0x22 -> {
                val reg = d[4]; val n = d[2] or (d[3] shl 8)
                val v = when {
                    pg == 7 && reg == 0x7D && notReadyChecks > 0 -> { notReadyChecks--; 0x00 }
                    pg == 0x0F && reg == 0x15 -> r15
                    pg == 9 && reg == 0x33 -> chipStatus
                    pg == 0x0C && reg == 0x10 -> 0xAB
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

/** Audio from the DAB chip when the stick's MCU passes none on (a Play tester's DTR-100, 2026-10-26). */
class ChipAudioTest {
    @kotlin.test.BeforeTest fun freshProcess() { C100.registerLevelSeen = false; C100.chipAudioNeeded = false }
    private val logged = ArrayList<String>()
    private var now = 0L
    private val stick = FakeRegisterStick()
    private val d = Dtr100(stick, sleep = {}, clockMs = { 0L }).apply { version(); powerOn(); systemInit() }
    private val tuner = Dtr100Tuner(d, 4, log = { logged += it }, clockMs = { now })

    /** Reads for [ms] of silence from the MCU, 10 ms apart. */
    private fun silence(ms: Long) { val end = now + ms; while (now < end) { assertEquals(0, tuner.mscRead().size); now += 10 } }

    @Test fun aSilentControllerHandsTheAudioToTheChipAfterTwoSeconds() {
        tuner.startAudio(5, 96)
        assertTrue("41 00 20 01" in stick.sent, "the MCU is asked first, as always")
        silence(1990)
        assertTrue(!d.chipAudio, "not before 2 s")
        stick.sent.clear()
        now += 10; tuner.mscRead()
        assertTrue(d.chipAudio)
        // the MCU's own stop, then DABdream+'s chip buffer setup (2048-byte threshold, clear) and nothing else
        assertEquals(listOf("42 00 00", "41 00 00 00", "21 00 00 02 03 09", "21 00 00 02 56 08", "21 00 00 02 57 00",
            "21 00 00 02 48 00", "21 00 00 02 48 05", "21 00 00 02 35 04"), stick.sent.map { if (it.startsWith("42 00 00")) "42 00 00" else it })
        assertTrue(logged.any { "reading it from the DAB chip" in it && "status c2 00 00 00 d0 07" in it }, logged.toString())
        assertTrue(C100.chipAudioNeeded)

        stick.sent.clear(); stick.chipStatus = 0x10           // the chip has audio ready
        val data = tuner.mscRead()
        assertEquals(2048, data.size)
        assertTrue(data.all { it == 0xAB.toByte() })
        assertTrue("22 00 00 08 10" in stick.sent, "DABdream+'s read: 2048 bytes of page 0x0C reg 0x10")
        assertTrue(stick.sent.none { it.startsWith("42") || it.startsWith("40") }, "the MCU's buffer is left alone: ${stick.sent}")
        assertEquals(Dtr100.SIGNAL_NONE, tuner.signal?.raw, "no lock bit in this fake: the chip's own reading says none")
    }

    @Test fun aControllerThatDeliveredOnceIsNeverReplaced() {
        tuner.startAudio(5, 96)
        stick.mcuAvail = 288; stick.mcuSignal = 150
        assertEquals(288, tuner.mscRead().size)
        stick.mcuSignal = 2000; stick.chipStatus = 0x10      // a tunnel: nothing from the MCU for a long time
        silence(30_000)
        tuner.stopAudio(); tuner.startAudio(7, 88)           // and a station change in it
        silence(30_000)
        assertTrue(!d.chipAudio && logged.none { "DAB chip" in it }, logged.toString())
    }

    @Test fun afterTheSwitchAStationChangeLeavesTheMcuOut() {
        tuner.startAudio(5, 96); silence(2010)
        assertTrue(d.chipAudio)
        tuner.stopAudio(); stick.sent.clear()
        tuner.startAudio(7, 88)
        assertTrue(stick.sent.none { it.startsWith("41") || it.startsWith("43") }, stick.sent.toString())
        assertTrue("21 00 00 02 56 08" in stick.sent && "21 00 00 02 57 00" in stick.sent, "DABdream+'s threshold: ${stick.sent}")
    }

    @Test fun aReconnectInTheSameProcessReadsFromTheChipStraightAway() {
        C100.chipAudioNeeded = true
        val s2 = FakeRegisterStick()
        val t2 = C100.open(s2, log = { logged += it }, newDriver = { Dtr100(it, sleep = {}, clockMs = { 0L }) }, highLevel = { _, _ -> null })
        s2.sent.clear()
        t2.startAudio(5, 96)
        assertTrue(s2.sent.none { it.startsWith("41") }, s2.sent.toString())
        assertTrue(logged.any { "version reply 81 00 00 04" in it }, logged.toString())
    }

    @Test fun theChipSignalFollowsDabdreamsCer() {
        val s = FakeRegisterStick()
        val dd = Dtr100(s, sleep = {}, clockMs = { 0L }).apply { version() }
        // page 9: lock bit, total 100000 bits, 500 errors (under 4000: perfect), 0xD7 bit 4 set (keep counting)
        fun set(reg: Int, v: Int) { s.write(2, byteArrayOf(0x21, 0, 0, 2, 0x03, 9), 0); s.read(0x82, ByteArray(64), 0)
            s.write(2, byteArrayOf(0x21, 0, 0, 2, reg.toByte(), v.toByte()), 0); s.read(0x82, ByteArray(64), 0) }
        fun counters(total: Long, err: Long) { for (i in 0..3) { set(0x88 + i, (total shr (24 - 8 * i) and 0xFF).toInt()); set(0x8C + i, (err shr (24 - 8 * i) and 0xFF).toInt()) } }
        set(0x37, 1); set(0xD7, 0x10)
        counters(100_000, 500); assertEquals(0, dd.chipSignal())
        counters(100_000, 5_000); assertEquals(500, dd.chipSignal())     // 5000 * 1000 / 100000 * 10
        counters(100_000, 20_000); assertEquals(Dtr100.SIGNAL_NONE, dd.chipSignal())   // 2000 is over DABdream+'s 1200 cap
        set(0x37, 0); assertEquals(Dtr100.SIGNAL_NONE, dd.chipSignal())  // no lock
    }
}
