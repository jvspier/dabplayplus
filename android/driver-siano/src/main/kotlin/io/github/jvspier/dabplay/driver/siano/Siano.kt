package io.github.jvspier.dabplay.driver.siano

import io.github.jvspier.dabplay.core.fic.FicDecoder
import io.github.jvspier.dabplay.tuner.RawTuner
import io.github.jvspier.dabplay.tuner.SignalReading
import io.github.jvspier.dabplay.tuner.Transport
import io.github.jvspier.dabplay.tuner.Tuned
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Siano DAB sticks whose firmware is already on board (SMS2160 "Rhine800" 187f:0800, Denver TDMB 187f:0700 / 0802),
 * driven the way DABdream+ does it (docs/protocol-siano.md). **Untested on hardware**: written from static analysis,
 * with unit tests against that description. The host decodes, as with our DTR-100: the stick sends raw FIBs and the
 * raw sub-channel stream. The Rio (187f:0600) is not supported: its ROM needs a Siano firmware file we can't ship.
 *
 * Messages: an 8-byte header (type, source 0, destination 0x0B, length, flags; little-endian) plus u32 words, OUT on
 * EP 0x02, IN on EP 0x81. Only the messages DABdream+ sends to these sticks are ever sent. Deliberate differences
 * from DABdream+ (its spec, section 13): several messages in one read are split by their length, messages that
 * arrive while waiting for a reply are handled instead of dropped, and a failed sub-channel stop doesn't leave the
 * stick unusable.
 */
class Siano internal constructor(
    private val t: Transport,
    private val log: (String) -> Unit,
    private val sleep: (Long) -> Unit,
    private val clockMs: () -> Long,
) : RawTuner {
    override var description = "Siano"; private set

    /** "DAB8…" firmware (Rhine800): statistics arrive by themselves; "TDMB" firmware: the host polls them. */
    private var periodicStats = false
    private var tunedHz = 0
    private var locked = false
    private var preBer: Long? = null
    private var notLocked = 0                       // statistics in a row that said "not locked"
    private var statsSentAt = Long.MIN_VALUE / 2
    private var statsOutstanding = false
    private var ficSeen = false
    private var fic = FicDecoder()                   // the stick's own FIC: sub-channel layouts for SUB_CHANNEL_START
    private val ficOut = ByteArrayOutputStream()
    private val mscOut = ByteArrayOutputStream()
    private var subch: Int? = null
    private val replies = HashMap<Int, ByteArray>()
    private val buf = ByteArray(4096)
    private var warnedSplit = false
    private var warnedLength = false

    /** GET_VERSION_EX, then by its label: TDMB firmware as is, DAB8 firmware with periodic statistics switched on. */
    internal fun init() {
        val v = request(msg(GET_VERSION_EX), GET_VERSION_EX + 1, 3000) ?: throw IOException("Siano: no version reply")
        val label = String(v, 22, maxOf(0, minOf(v.size, 56) - 22), Charsets.ISO_8859_1).substringBefore('\u0000').trim()
        log("Siano: firmware \"$label\"")
        when {
            "Rio-A0" in label -> throw IOException("Siano Rio: needs a Siano firmware file the app can't ship")
            "TDMB" in label || "T-DMB" in label -> periodicStats = false
            label.startsWith("DAB8") -> periodicStats = true     // DABdream+ wants exactly "DAB8.0.62dDNV"; looser here
            else -> throw IOException("Siano: unsupported firmware \"$label\"")
        }
        description = "Siano, firmware $label"
        if (periodicStats) {
            send(msg(SET_PERIODIC_STATISTICS, 1))
            // DABdream+ waits for the first statistics indication, not for the reply
            val end = clockMs() + 5000
            while (preBer == null && clockMs() < end) pump(100)
            if (preBer == null) throw IOException("Siano: periodic statistics didn't start")
        }
    }

    override fun tune(khz: Int, retries: Boolean): Tuned {
        if (periodicStats) send(msg(SET_PERIODIC_STATISTICS, 1))      // DABdream+ re-arms them before every retune
        val hz = khz * 1000
        fic = FicDecoder(); ficOut.reset(); ficSeen = false; locked = false; notLocked = 0
        tunedHz = 0
        request(msg(RF_TUNE, hz, BW_1_5_MHZ, CLOCK_HZ, 0), RF_TUNE + 1, 2000) ?: return Tuned(false, detail = "no tune confirmation")
        tunedHz = hz
        // no lock answer from the tune itself: the statistics say it (or the FIC arriving for this frequency)
        val end = clockMs() + if (retries) 3000 else 1500
        while (clockMs() < end && !locked && !ficSeen && notLocked < 3) { pollStats(); pump(50) }
        val ok = locked || ficSeen
        return Tuned(ok, if (ok) signal?.bars ?: 1 else 0, "locked $locked, FIC $ficSeen, pre-BER $preBer")
    }

    override fun ficRead(): ByteArray? {
        pollStats(); pump(20)
        if (ficOut.size() == 0) return null
        return ficOut.toByteArray().also { ficOut.reset() }
    }

    /**
     * SUB_CHANNEL_START wants the sub-channel's layout, so it waits (≤ 3 s) for FIG 0/1 in the stick's FIC: words
     * [0, id, start CU, size CU × 64, bitrate × 24, protection 0 UEP / 1 EEP-A / 2 EEP-B, level + 1, UEP index or -1,
     * 0, -1, -1, 0, 1, -1] as DABdream+ sends them.
     */
    override fun startAudio(subchannel: Int, bitrateKbps: Int) {
        val end = clockMs() + 3000
        while (fic.subchannels[subchannel] == null && clockMs() < end) pump(50)
        // not in the FIC (yet): a reception or station problem, not a broken stick. No exception (that would reconnect
        // the whole session); no audio comes, so the engine's reception handling and following take it from here
        val sc = fic.subchannels[subchannel] ?: return log("Siano: sub-channel $subchannel not in the FIC, not started")
        val br = sc.bitrateKbps ?: bitrateKbps
        val prot = if (sc.uepIndex != null) 0 else if (sc.eepOption == 1) 2 else 1
        val m = msg(SUB_CHANNEL_START, 0, sc.id, sc.startCu, (sc.sizeCu ?: 0) * 64, (br * 24) and 0x1FFFC0, prot, sc.level + 1,
            sc.uepIndex ?: -1, 0, -1, -1, 0, 1, -1)
        request(m, SUB_CHANNEL_START + 1, 2000) ?: throw IOException("Siano: sub-channel start not confirmed")
        subch = sc.id
        mscOut.reset()
    }

    override fun stopAudio() {
        val id = subch ?: return
        subch = null                                  // even if the reply never comes (DABdream+ would get stuck here)
        request(msg(SUB_CHANNEL_STOP, id), SUB_CHANNEL_STOP + 1, 2000)
    }

    override fun mscRead(): ByteArray {
        pollStats(); pump(20)
        if (mscOut.size() == 0) return EMPTY
        return mscOut.toByteArray().also { mscOut.reset() }
    }

    /** Pre-BER (1e-5 units) as DABdream+ maps it: ≤ 4000 best … ≤ 7500 bad, more or no lock: nothing. */
    override val signal: SignalReading?
        get() {
            val b = preBer ?: return null
            val level = if (!locked || b < 0) 0 else when { b <= 4000 -> 5; b <= 4200 -> 4; b <= 4500 -> 3; b <= 6000 -> 2; b <= 7500 -> 1; else -> 0 }
            return SignalReading(bars = minOf(level, 4), none = !locked, weak = level <= 1, raw = b.toInt())
        }

    /** DABdream+'s teardown: stop the sub-channel, untune. */
    override fun close() {
        runCatching { stopAudio() }
        runCatching { request(msg(RF_TUNE, 0, BW_1_5_MHZ, CLOCK_HZ, 0), RF_TUNE + 1, 1000) }
        tunedHz = 0
    }

    // --- messages -----------------------------------------------------------------------------------------------

    /** TDMB firmware: statistics on request, every 100 ms until locked, then every second; one request at a time. */
    private fun pollStats() {
        if (periodicStats || tunedHz == 0 || statsOutstanding) return
        val now = clockMs()
        if (now - statsSentAt < if (locked) 1000 else 100) return
        send(msg(GET_STATISTICS)); statsSentAt = now; statsOutstanding = true
    }

    /** One read on EP 0x81 (≤ [timeoutMs]); every message in it is handled. */
    internal fun pump(timeoutMs: Int) {
        val n = try { t.read(EP_IN, buf, timeoutMs) } catch (e: IOException) { 0 }
        statsOutstanding = false                      // as DABdream+: any completed read allows the next request
        var off = 0
        while (off + 8 <= n) {
            val len = u16(buf, off + 4)
            if (len < 8 || off + len > n) {
                // longer than a read, or split over reads: an open question for these sticks (protocol-siano.md)
                if (!warnedLength) { warnedLength = true; log("Siano: message 0x%04x of %d bytes in a %d-byte read dropped".format(u16(buf, off), len, n - off)) }
                break
            }
            if (u16(buf, off + 6) and FLAG_SPLIT != 0) {
                if (!warnedSplit) { warnedSplit = true; log("Siano: split message (flags 0x%04x) not supported, dropped".format(u16(buf, off + 6))) }
            } else handle(u16(buf, off), buf.copyOfRange(off, off + len))
            off += len
        }
    }

    private fun handle(type: Int, m: ByteArray) {
        when (type) {
            FIC_DATA -> if (m.size > 20 && u32(m, 16) == tunedHz.toLong()) {
                val fibs = (m.size - 20) / 32 * 32
                ficOut.write(m, 20, fibs); fic.feed(m, 20, 20 + fibs); ficSeen = true
            }
            DAB_CHANNEL -> if (subch != null) mscOut.write(m, 8, m.size - 8)
            STATISTICS -> if (m.size >= 160) stats(m[20].toInt() != 0, u32(m, 156))
            SHORT_STATISTICS -> if (m.size >= 48) stats(m[8].toInt() != 0, u32(m, 20))
            INTERFACE_LOCK -> log("Siano: interface locked")
            else -> replies[type] = m
        }
    }

    private fun stats(isLocked: Boolean, ber: Long) {
        locked = isLocked; preBer = ber
        notLocked = if (isLocked) 0 else notLocked + 1
    }

    /** Send, then read until the reply of [replyType] arrives (other messages are handled meanwhile); null on timeout. */
    private fun request(m: ByteArray, replyType: Int, timeoutMs: Long): ByteArray? {
        replies.remove(replyType)
        send(m)
        val end = clockMs() + timeoutMs
        while (clockMs() < end) {
            pump(100)
            replies.remove(replyType)?.let { return it }
        }
        return null
    }

    /** DABdream+ retries a failed write (up to 100 × 10 ms); a handful is plenty to ride out a busy endpoint. */
    private fun send(m: ByteArray) {
        repeat(10) { try { t.write(EP_OUT, m, 500); return } catch (e: IOException) { sleep(10) } }
        throw IOException("Siano: write of message 0x%04x failed".format(u16(m, 0)))
    }

    companion object {
        const val VID = 0x187F
        /** Sticks with firmware on board. 0x0600 (Rio) is left out: it needs host firmware. */
        val PIDS = setOf(0x0700, 0x0800, 0x0802)
        const val EP_OUT = 0x02
        const val EP_IN = 0x81

        internal const val GET_VERSION_EX = 0x029C
        internal const val SET_PERIODIC_STATISTICS = 0x0344
        internal const val RF_TUNE = 0x0231
        internal const val SUB_CHANNEL_START = 0x024D
        internal const val SUB_CHANNEL_STOP = 0x024F
        internal const val GET_STATISTICS = 0x0267
        internal const val STATISTICS = 0x0268
        internal const val FIC_DATA = 0x025E
        internal const val DAB_CHANNEL = 0x025F
        internal const val SHORT_STATISTICS = 0x031E
        internal const val INTERFACE_LOCK = 0x0325
        private const val BW_1_5_MHZ = 15
        private const val CLOCK_HZ = 12_000_000
        private const val FLAG_SPLIT = 0x0004
        private val EMPTY = ByteArray(0)

        /** A host → stick message: header (destination 0x0B, the main module) + little-endian u32 words. */
        internal fun msg(type: Int, vararg words: Int): ByteArray {
            val len = 8 + 4 * words.size
            val b = ByteArray(len)
            b[0] = type.toByte(); b[1] = (type shr 8).toByte(); b[2] = 0; b[3] = 0x0B
            b[4] = len.toByte(); b[5] = (len shr 8).toByte()
            words.forEachIndexed { i, w -> for (k in 0 until 4) b[8 + 4 * i + k] = (w ushr (8 * k)).toByte() }
            return b
        }

        fun open(t: Transport, log: (String) -> Unit = {}): Siano =
            Siano(t, log, { Thread.sleep(it) }, { System.nanoTime() / 1_000_000 }).also { it.init() }

        internal fun forTest(t: Transport, clockMs: () -> Long, sleep: (Long) -> Unit = {}): Siano = Siano(t, {}, sleep, clockMs)

        private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)
    }
}
