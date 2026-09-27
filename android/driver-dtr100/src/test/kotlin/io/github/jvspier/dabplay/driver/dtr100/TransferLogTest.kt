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

    override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        val p = pending!!; pending = null
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
}
