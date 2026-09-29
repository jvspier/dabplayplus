package io.github.jvspier.dabplay.driver.si4684

import io.github.jvspier.dabplay.tuner.ModuleListener
import io.github.jvspier.dabplay.tuner.ModuleService
import io.github.jvspier.dabplay.tuner.Transport
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
private fun bytes(h: String) = h.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

/** A module → host frame: AA type len16 BE payload BB. */
private fun reply(type: Int, vararg p: Int) = reply(type, ByteArray(p.size) { p[it].toByte() })
private fun reply(type: Int, p: ByteArray) =
    byteArrayOf(0xAA.toByte(), type.toByte(), (p.size shr 8).toByte(), p.size.toByte()) + p + byteArrayOf(0xBB.toByte())

/**
 * A simulated module: answers each command through [answer] (bytes queued for EP 0x82), serves [pcm] on EP 0x81 and
 * records the commands it received ("cmd payload" hex, from the inner frame). An empty read times out: +500 ms.
 */
private class FakeModule : Transport {
    var clock = 0L
    val commands = ArrayList<String>()
    val raw = ArrayList<ByteArray>()
    val replies = java.util.ArrayDeque<ByteArray>()
    val pcm = java.util.ArrayDeque<ByteArray>()
    var answer: (cmd: Int, payload: ByteArray) -> List<ByteArray> = { _, _ -> emptyList() }

    override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        assertEquals(0x01, endpoint)
        raw += data
        val n = ((data[7].toInt() and 0xFF) shl 8) or (data[8].toInt() and 0xFF)
        val cmd = data[6].toInt() and 0xFF
        val payload = data.copyOfRange(9, 9 + n)
        commands += if (n == 0) "%02x".format(cmd) else "%02x %s".format(cmd, hex(payload))
        answer(cmd, payload).forEach { replies += it }
        return data.size
    }

    override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        val q = if (endpoint == 0x82) replies else pcm
        val b = q.pollFirst() ?: run { clock += timeoutMs; throw IOException("timeout") }
        b.copyInto(buffer); return b.size
    }

    override fun close() {}
}

private class Recorder : ModuleListener {
    val pcm = ArrayList<Int>(); val texts = ArrayList<String>(); val slides = ArrayList<ByteArray>(); var started = 0
    override fun onPcm(data: ByteArray, sampleRate: Int, channels: Int) { assertEquals(48000, sampleRate); assertEquals(2, channels); pcm += data.size }
    override fun onStarted() { started++ }
    override fun onText(text: String) { texts += text }
    override fun onSlide(bytes: ByteArray) { slides += bytes }
}

class Si4684Test {
    private val m = FakeModule()
    private val rec = Recorder()
    private val d = Si4684.forTest(m, rec, clockMs = { m.clock }, sleep = { m.clock += it })

    /** 538 on 11C in the spec's worked example: freqIdx 0x1C, SId 0x00008301, component 0x0005. */
    private val station = ModuleService(0x1C, 220352, "11C", 0x8301, 5, "538", "Radio 538", 10)

    /** A status: index, freqIdx, SId, cid, counts, level, snr, valid, bitrate, channel, rate, pty, link, flags. */
    private fun status(idx: Int, sid: Long, cid: Int, level: Int, link: Int = 0, flags: Int = 0) = reply(0xC0,
        idx, 0x1C, (sid shr 24).toInt() and 0xFF, (sid shr 16).toInt() and 0xFF, (sid shr 8).toInt() and 0xFF, sid.toInt() and 0xFF,
        cid shr 8, cid and 0xFF, 20, 3, level, 12, 1, 0, 96, 0x1C, 0xBB, 0x80, 10, link, flags)

    @Test fun commandsAreFramedExactlyAsTheSpecsWorkedExamples() {
        assertEquals("55 10 00 00 05 aa 30 00 00 bb 95 11", hex(Frames.command(0x30)))
        assertEquals("55 10 00 00 05 aa 40 00 00 bb a5 11", hex(Frames.command(0x40)))
        assertEquals("55 10 00 00 05 aa 47 00 00 bb ac 11", hex(Frames.command(0x47)))
        assertEquals("55 10 00 00 06 aa 48 00 01 01 bb af 11", hex(Frames.command(0x48, byteArrayOf(1))))
        assertEquals("55 10 00 00 06 aa 0c 00 01 00 bb 72 11", hex(Frames.command(0x0C, byteArrayOf(0))))
    }

    @Test fun initSendsVersionThen0x32OnlyAfterAVersionReply() {
        m.answer = { c, _ -> if (c == 0x30) listOf(reply(0xB0, *"2.1.5 ".map { it.code }.toIntArray())) else emptyList() }
        d.init()
        assertEquals(listOf("30", "32", "40", "47"), m.commands)
        assertEquals("Si4684 module, firmware 2.1.5", d.description)
        assertEquals(1000L, m.clock)                                       // the two 500 ms pauses around 0x32
    }

    @Test fun noVersionReplyMeansNo0x32() {
        d.init()
        assertEquals(listOf("30", "40", "47"), m.commands)
    }

    @Test fun scanReportsProgressAndReturnsTheModulesList() {
        fun rec(idx: Int, fi: Int, sid: Long, cid: Int, flag: Int, label: ByteArray, pty: Int, ens: String) =
            byteArrayOf(idx.toByte(), fi.toByte(), (sid shr 24).toByte(), (sid shr 16).toByte(), (sid shr 8).toByte(), sid.toByte(),
                (cid shr 8).toByte(), cid.toByte(), flag.toByte()) + label.copyOf(16) + byteArrayOf(0, pty.toByte(), 0) + ens.toByteArray().copyOf(16)
        val list = rec(0, 0x1C, 0x8301, 5, 0, "Caf\u0082 FM  ".toByteArray(Charsets.ISO_8859_1), 10, "NPO") +      // EBU Latin é
            rec(1, 0x08, 0x83C7, 7, 15, "Qmusic ♥".toByteArray(), 11, "DAB-ensemble")
        m.answer = { c, _ -> when (c) {
            0x01 -> listOf(reply(0x81, 50, 0, 3), reply(0x81, 100, 0, 2))
            0x47 -> listOf(reply(0xC7, list))
            else -> emptyList()
        } }
        val progress = ArrayList<Pair<Int, Int>>()
        val found = d.scan({ p, n -> progress += p to n }, timeoutMs = 10_000)
        assertEquals(listOf(50 to 3, 100 to 2), progress)
        assertEquals(listOf("01", "47"), m.commands)
        assertEquals(listOf("Café FM", "Qmusic ♥"), found.map { it.label })
        assertEquals(ModuleService(0x1C, 220352, "11C", 0x8301, 5, "Café FM", "NPO", 10), found[0])
        assertEquals("7A", found[1].channel)
    }

    @Test fun playFollowsDabdreamsHandshake() {
        m.answer = { c, p -> when {
            c == 0x48 && p[0].toInt() == 0 -> listOf(reply(0xC8, 1))
            c == 0x44 && p[0].toInt() == 0 -> listOf(status(7, 0x8301, 5, level = 4))   // the module names the real index 7
            else -> emptyList()
        } }
        d.play(station, serviceFollowing = true)
        // the status naming the service starts the audio (48 01) and asks for the next status, before the second select
        assertEquals(listOf("0c 01", "48 00", "44 00 1c 00 00 83 01 00 05", "48 01", "40", "44 07 1c 00 00 83 01 00 05"), m.commands)
        assertEquals("55 10 00 00 0d aa 44 00 08 00 1c 00 00 83 01 00 05 bb 56 11", hex(m.raw[2]))   // the spec's example
        assertEquals("55 10 00 00 0d aa 44 00 08 07 1c 00 00 83 01 00 05 bb 5d 11", hex(m.raw[5]))
        assertEquals(1, rec.started)
        assertEquals(4, d.signal!!.bars)
    }

    @Test fun audioIsForwardedInWholeFramesOnlyWhilePlaying() {
        m.pcm += ByteArray(8)
        d.pumpAudio()                                                        // nothing plays yet: dropped
        assertTrue(rec.pcm.isEmpty())
        m.answer = { c, p -> if (c == 0x44 && p[0].toInt() == 0) listOf(status(0, 0x8301, 5, 3)) else emptyList() }
        d.play(station, false)
        m.pcm += ByteArray(6); m.pcm += ByteArray(6)
        d.pumpAudio(); d.pumpAudio()
        assertEquals(listOf(4, 8), rec.pcm)                                  // 2 bytes carried over to the next read
        d.stop()
        m.pcm += ByteArray(8); d.pumpAudio()
        assertEquals(2, rec.pcm.size)
    }

    @Test fun newTextIsFetchedAndDecoded() {
        m.answer = { c, _ -> if (c == 0x21) listOf(reply(0xA1, 0, 'H'.code, 0x82, ' '.code, 0)) else emptyList() }
        m.replies += status(0, 1, 1, 3, flags = 1)
        d.pumpReplies()                                                      // flag bit 0: ask for the text
        d.pumpReplies()                                                      // the text arrives; DABdream+ asks again
        assertEquals(listOf("Hé"), rec.texts)
        assertEquals(listOf("21", "40", "21"), m.commands)
    }

    @Test fun slidesAreReassembledAndBrokenOnesDropped() {
        m.answer = { c, p -> if (c == 0x44 && p[0].toInt() == 0) listOf(status(0, 0x8301, 5, 3)) else emptyList() }
        d.play(station, false)
        fun chunk(last: Boolean, seq: Int, tag: Int, data: ByteArray) =
            reply(0xB1, byteArrayOf(0x74, 0, if (last) 0x80.toByte() else 0, seq.toByte(), 0, 0, tag.toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data)
        m.replies += reply(0xB1, 0x73, 0, 0, 0, 0, 0, 5)
        m.replies += chunk(false, 0, 5, bytes("ff d8 01"))
        m.replies += chunk(true, 1, 5, bytes("02 ff d9"))
        while (m.replies.isNotEmpty()) d.pumpReplies()
        assertEquals(1, rec.slides.size)
        assertContentEquals(bytes("ff d8 01 02 ff d9"), rec.slides[0])
        m.replies += reply(0xB1, 0x73, 0, 0, 0, 0, 0, 6)
        m.replies += chunk(false, 0, 6, bytes("aa"))
        m.replies += chunk(true, 2, 6, bytes("bb"))                        // sequence 1 missing
        while (m.replies.isNotEmpty()) d.pumpReplies()
        assertEquals(1, rec.slides.size)
    }

    @Test fun theLevelStepsOneAtATimeAfterTheFirstStatus() {
        assertNull(d.signal)
        val seen = listOf(5, 1, 1, 1, 1, 0).map { m.replies += status(0, 1, 1, it); d.pumpReplies(); d.signal!!.raw }
        assertEquals(listOf(5, 4, 3, 2, 1, 0), seen)
        assertTrue(d.signal!!.none)
    }

    @Test fun theParserResynchronisesAndHandlesSplitFrames() {
        val got = ArrayList<String>()
        val p = FrameParser { t, b -> got += "%02x %s".format(t, hex(b)) }
        val two = reply(0xC8, 1) + reply(0xC4, 0)
        p.feed(two.copyOfRange(0, 3)); p.feed(two.copyOfRange(3, two.size))            // split, and two in one read
        p.feed(bytes("55 10 00 00 06") + reply(0xC8, 2) + bytes("aa 11"))            // host-style outer bytes around it
        p.feed(bytes("aa c8 00 01 05 cc") + reply(0xC8, 3))                            // bad footer, then a good frame
        assertEquals(listOf("c8 01", "c4 00", "c8 02", "c8 03"), got)
    }
}
