package io.github.jvspier.dabplay.driver.dtr100

import io.github.jvspier.dabplay.tuner.Transport
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays a USB transfer log recorded by the Python driver against the real dongle (tools/dtr100.py --log).
 * Every request the Kotlin driver sends must equal the logged request; replies come from the log.
 */
class LogTransport(file: File) : Transport {
    val pairs: List<Pair<String, ByteArray>>
    var pos = 0

    init {
        val lines = file.readLines()
        val out = ArrayList<Pair<String, ByteArray>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("-> ") && i + 1 < lines.size && lines[i + 1].startsWith("<- ")) {
                out += lines[i].substring(3).trim() to parseReply(lines[i + 1].substring(3))
                i += 2
            } else i++
        }
        pairs = out
    }

    private fun parseReply(s: String): ByteArray {
        val body = s.substringBefore("   (expected").trim()
        val m = Regex("^(.*) \\.\\.\\. \\((\\d+) bytes\\)$").find(body)
        val hex = (m?.groupValues?.get(1) ?: body).split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }
        return if (m != null) ByteArray(m.groupValues[2].toInt()) { hex.getOrElse(it) { 0 } } else hex.toByteArray()
    }

    private var pending: ByteArray? = null

    /** Requests are compared exactly, except the rolling sequence byte of cmd 0x42 (skipped reads advance it). */
    override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        check(pos < pairs.size) { "driver sent more requests than the log has: ${data.hex()}" }
        val (req, rep) = pairs[pos]
        val got = data.hex()
        val norm = { r: String -> if (r.startsWith("42 00 00 ")) "42 00 00 xx" else r }
        assertEquals(norm(req), norm(got), "request #${pos + 1}")
        pos++
        pending = rep
        return data.size
    }

    /** Nothing asked for: like the real endpoint, the read times out (drainStale relies on that). */
    override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        val p = pending ?: throw java.io.IOException("timeout"); pending = null
        val n = minOf(p.size, buffer.size)
        p.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun close() {}

    /** Skip logged transfers until [pattern] (a list of consecutive requests) starts at the current position. */
    fun skipTo(vararg pattern: String) {
        while (pos < pairs.size) {
            if (pattern.indices.all { pos + it < pairs.size && pairs[pos + it].first == pattern[it] }) return
            pos++
        }
        error("pattern not found: ${pattern.toList()}")
    }
}

class TransferLogTest {
    private val captures = File(System.getProperty("dabplay.captures") ?: "../captures")

    @Test fun initTuneFicAndStationSwitchMatchTheRealDongleSession() {
        val log = File(captures, "2026-09-25-switch-538-qmusic.log")
        assumeTrue("local transfer log missing: $log", log.exists())
        val t = LogTransport(log)
        val d = Dtr100(t, sleep = {}, clockMs = { 0L })

        // tools/switch_test.py: version, power on, system init, tune 11C (up to 3 tries), FIC enable
        assertEquals(4, d.version())
        d.powerOn()
        d.systemInit()
        var outcome = d.tune(220352)
        var tries = 1
        while (!outcome.locked && tries < 3) { outcome = d.tune(220352); tries++ }
        assertTrue(outcome.locked, "tune: ${outcome.info}")
        d.ficEnable()
        val afterInit = t.pos
        println("  init + tune 11C + FIC enable: $afterInit transfers identical (tries $tries, u1 ${outcome.info.u1}, q ${outcome.info.q})")

        // (FIC and MSC reads are time-driven in the Python session: skip them, compare the control sequences)
        val restart = arrayOf("21 00 00 02 03 06", "21 00 00 02 10 48", "21 00 00 02 10 c9")
        t.skipTo(*restart); d.startAudio(2, 80 * 3)          // 538: subch 2, 80 kbit/s
        t.skipTo("21 00 00 02 03 09", "21 00 00 02 3a 02"); d.stopAudio()
        d.startAudio(7, 88 * 3)                               // Qmusic: subch 7, 88 kbit/s (no re-init)
        t.skipTo("21 00 00 02 03 09", "21 00 00 02 3a 07"); d.stopAudio()
        println("  start 538 / stop / start Qmusic / stop: identical; ${t.pos} of ${t.pairs.size} logged transfers consumed")
    }

    /** The engine's path since the driver interface: C100.open → RawTuner. Same bytes as the direct calls above. */
    @Test fun theDriverInterfacePathSendsTheSameTraffic() {
        val log = File(captures, "2026-09-25-switch-538-qmusic.log")
        assumeTrue("local transfer log missing: $log", log.exists())
        val t = LogTransport(log)
        val tuner = C100.open(t, newDriver = { Dtr100(it, sleep = {}, clockMs = { 0L }) })
        assertTrue(tuner is Dtr100Tuner, "the register-level driver must be chosen for the DTR-100")
        assertTrue(tuner.tune(220352, retries = true).locked)
        val restart = arrayOf("21 00 00 02 03 06", "21 00 00 02 10 48", "21 00 00 02 10 c9")
        t.skipTo(*restart); tuner.startAudio(2, 80)
        t.skipTo("21 00 00 02 03 09", "21 00 00 02 3a 02"); tuner.stopAudio()
        tuner.startAudio(7, 88)
        t.skipTo("21 00 00 02 03 09", "21 00 00 02 3a 07"); tuner.stopAudio()
    }

    /**
     * The playback itself through the engine's interface: every status poll and audio read of the recorded 538 and
     * Qmusic sessions, byte for byte, with the clock running as fast as the real polls did (a 5 ms pause plus the
     * transfer, ~7 ms each). The chip-audio fallback must never step in on this stick, nor add a single transfer.
     */
    @Test fun playbackTrafficIsUnchangedAndTheChipFallbackStaysOut() {
        val log = File(captures, "2026-09-25-switch-538-qmusic.log")
        assumeTrue("local transfer log missing: $log", log.exists())
        val t = LogTransport(log)
        val d = Dtr100(t, sleep = {}, clockMs = { 0L })
        var now = 0L
        val logged = ArrayList<String>()
        assertEquals(4, d.version()); d.powerOn(); d.systemInit()
        val tuner = Dtr100Tuner(d, 4, log = { logged += it }, clockMs = { now })
        assertTrue(tuner.tune(220352, retries = true).locked)
        var firstAudioAt = -1L
        fun play(): Int {
            var bytes = 0
            while (t.pos < t.pairs.size && t.pairs[t.pos].first.startsWith("42 00 00")) {
                val n = tuner.mscRead().size
                if (n > 0 && firstAudioAt < 0) firstAudioAt = now
                bytes += n; now += 7
            }
            return bytes
        }
        t.skipTo("21 00 00 02 03 06", "21 00 00 02 10 48", "21 00 00 02 10 c9")
        now = 0; tuner.startAudio(2, 80)                      // 538
        val bytes538 = play()
        assertEquals("21 00 00 02 03 09", t.pairs[t.pos].first, "the recording stops 538 right after the last read")
        tuner.stopAudio()
        tuner.startAudio(7, 88)                               // Qmusic
        val bytesQ = play()
        tuner.stopAudio()
        assertTrue(bytes538 > 0 && bytesQ > 0, "audio read: 538 $bytes538, Qmusic $bytesQ bytes")
        assertTrue(firstAudioAt in 1 until Dtr100Tuner.MCU_GRACE_MS, "first audio after $firstAudioAt ms")
        assertTrue(!d.chipAudio && logged.none { "DAB chip" in it }, logged.toString())
        println("  playback 538 + Qmusic: ${t.pos} transfers identical, $bytes538 + $bytesQ bytes, first audio after ~$firstAudioAt ms")
    }
}
