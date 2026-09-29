package io.github.jvspier.dabplay.driver.si4684

import io.github.jvspier.dabplay.core.DabText
import io.github.jvspier.dabplay.tuner.ModuleListener
import io.github.jvspier.dabplay.tuner.ModuleService
import io.github.jvspier.dabplay.tuner.ModuleTuner
import io.github.jvspier.dabplay.tuner.SignalReading
import io.github.jvspier.dabplay.tuner.Transport
import java.io.IOException

/**
 * The Si4684 module (Eonon A503, XFA-DAB220; USB 1a86:5537) as DABdream+ drives it (docs/protocol-si4684.md).
 * **Untested on hardware**: written from static analysis, with unit tests against that description.
 *
 * Commands go out on EP 0x01; replies and events come in on EP 0x82 and are parsed by one reader thread, which also
 * answers them the way DABdream+ does (a status reply asks for the next status, "new text" asks for the text…);
 * finished audio comes in on EP 0x81, read by a second thread. Only the commands DABdream+ sends are ever sent.
 * Deliberate difference: EP 0x81 is read (and discarded) also while nothing plays, so no stale audio queues up.
 */
class Si4684 internal constructor(
    private val t: Transport,
    private val listener: ModuleListener,
    private val log: (String) -> Unit,
    private val sleep: (Long) -> Unit,
    private val clockMs: () -> Long,
    private val threads: Boolean,
) : ModuleTuner {
    override var description = "Si4684 module"; private set

    private val lock = Object()
    private val writeLock = Any()
    private val parser = FrameParser(::onFrame)
    private var replyThread: Thread? = null
    private var audioThread: Thread? = null
    @Volatile private var closed = false

    // all guarded by [lock]
    private var version: String? = null
    private var ackSeen = false
    private var resolving: Pair<Long, Int>? = null
    private var resolvedIdx = -1
    private var current: ModuleService? = null
    private var decoding = false
    private var scanning = false
    private var scanListPending = false
    private var scanResult: List<ModuleService>? = null
    private var scanProgress: ((Int, Int) -> Unit)? = null
    private var level = 0
    private var levelKnown = false
    private var linkInfo = false
    @Volatile private var lastRxAt = 0L
    private var lastHeartbeatAt = 0L
    private val slide = java.io.ByteArrayOutputStream()
    private var slideTag = -1
    private var slideSeq = 0
    private var pcmTail = ByteArray(0)                    // audio thread only
    @Volatile private var dropTail = false

    /** Init as DABdream+ does it: version (≤ 1 s), then only if it answered: 500 ms, 0x32, 500 ms; then status, list. */
    internal fun init() {
        if (threads) startThreads()
        send(0x30)
        if (await(1000) { version != null }) {
            description = "Si4684 module, firmware ${version!!.ifEmpty { "?" }}"
            sleep(500); send(0x32); sleep(500)
        } else log("Si4684: no version reply; not sending 0x32")
        send(0x40)
        send(0x47)
    }

    override fun scan(progress: (percent: Int, found: Int) -> Unit, timeoutMs: Long): List<ModuleService> {
        synchronized(lock) { scanning = true; scanResult = null; scanProgress = progress; current = null; decoding = false }
        send(0x01)
        await(timeoutMs) { scanResult != null }
        synchronized(lock) {
            scanning = false; scanProgress = null
            return scanResult ?: throw IOException("the module's scan did not finish within ${timeoutMs / 1000} s")
        }
    }

    /**
     * DABdream+'s start: service following on/off, audio off (≤ 300 ms for the ack), 150 ms, select with list index 0
     * so the module reports the real index (≤ 2 s), select with that index. Audio on (0x48 01) follows from the reply
     * thread as soon as a status names this service.
     */
    override fun play(service: ModuleService, serviceFollowing: Boolean) {
        send(0x0C, byteArrayOf(if (serviceFollowing) 1 else 0))
        synchronized(lock) { current = service; decoding = false; ackSeen = false }
        dropTail = true
        send(0x48, byteArrayOf(0))
        await(300) { ackSeen }
        synchronized(lock) { resolving = service.sid to service.cid; resolvedIdx = -1 }
        sleep(150)
        send(0x44, select(0, service))
        val idx = if (await(2000) { resolvedIdx >= 0 }) synchronized(lock) { resolvedIdx } else 0
        synchronized(lock) { resolving = null }
        send(0x44, select(idx, service))
    }

    override fun stop() { synchronized(lock) { current = null; decoding = false } }   // DABdream+ sends nothing here

    override val signal: SignalReading?
        get() = synchronized(lock) {
            if (!levelKnown) null
            // the module's 0..5 (DABdream+: no signal, bad, poor, okay, good, best) folded into our 0..4 bars
            else SignalReading(bars = minOf(level, 4), none = level == 0, weak = level <= 1, raw = level)
        }

    override val following: Boolean get() = synchronized(lock) { linkInfo }

    override fun close() {
        closed = true
        replyThread?.interrupt(); audioThread?.interrupt()
        runCatching { replyThread?.join(1000) }; runCatching { audioThread?.join(1000) }
    }

    // --- readers ------------------------------------------------------------------------------------------------

    private fun startThreads() {
        replyThread = Thread({ while (!closed && !Thread.currentThread().isInterrupted) pumpReplies() }, "si4684-replies")
            .apply { isDaemon = true; start() }
        audioThread = Thread({
            runCatching { urgentAudioPriority() }
            while (!closed && !Thread.currentThread().isInterrupted) pumpAudio()
        }, "si4684-audio").apply { isDaemon = true; start() }
    }

    private val replyBuf = ByteArray(4096)
    private val audioBuf = ByteArray(16384)

    /** One read on EP 0x82 (≤ 500 ms), parsed; plus DABdream+'s keep-alive: a status poll after silence, every 2 s. */
    internal fun pumpReplies() {
        val n = try { t.read(EP_REPLIES, replyBuf, 500) } catch (e: IOException) { 0 }
        if (n > 0) { lastRxAt = clockMs(); parser.feed(replyBuf, n) } else if (threads) sleep(10)
        val now = clockMs()
        if (now - lastHeartbeatAt >= 2000) {
            lastHeartbeatAt = now
            if (now - lastRxAt > 501) send(0x40)
        }
    }

    /** One read on EP 0x81 (≤ 500 ms): forwarded while a service plays, else dropped. */
    internal fun pumpAudio() {
        val n = try { t.read(EP_AUDIO, audioBuf, 500) } catch (e: IOException) { 0 }
        if (n <= 0) { if (threads) sleep(5); return }
        val playing = synchronized(lock) { decoding && current != null }
        if (dropTail) { dropTail = false; pcmTail = ByteArray(0) }
        if (!playing) return
        // s16le stereo: forward whole 4-byte frames, keep the rest for the next read
        val all = if (pcmTail.isEmpty()) audioBuf.copyOf(n) else pcmTail + audioBuf.copyOf(n)
        val whole = all.size / 4 * 4
        pcmTail = all.copyOfRange(whole, all.size)
        if (whole > 0) listener.onPcm(all.copyOf(whole), 48000, 2)
    }

    // --- replies and events -------------------------------------------------------------------------------------

    private fun onFrame(type: Int, p: ByteArray) {
        if (p.isEmpty()) return
        when (type) {
            0xB0 -> signalAll { version = text(p, 0, p.size, 15) }
            0xC8 -> signalAll { ackSeen = true }
            0xC4 -> if (p[0].toInt() == 0) listener.onServiceNotFound()
            0x81 -> onScanProgress(p)
            0xC7 -> onList(p)
            0xC0 -> onStatus(p)
            0xA1 -> { if (p.size > 1) listener.onText(text(p, 1, p.size, charset(p[0]))); send(0x21) }
            0xB1 -> onSlide(p)
        }
    }

    private fun onScanProgress(p: ByteArray) {
        val percent = p.u(0)
        val found = if (p.size > 2) p.u(2) else 0
        val cb = synchronized(lock) { scanListPending = true; scanProgress }
        cb?.invoke(minOf(percent, 100), found)
        if (percent >= 100) send(0x47)
    }

    private fun onList(p: ByteArray) {
        if (p.size % 44 != 0) log("Si4684: service list of ${p.size} bytes is not whole 44-byte records")
        val list = (0 until p.size / 44).mapNotNull { i -> record(p, i * 44) }
        signalAll {
            if (scanListPending) { scanResult = list; scanListPending = false }
        }
    }

    /** One 44-byte list record: index, freqIdx, SId (BE), component handle (BE), labels with their charset flags, PTY. */
    private fun record(p: ByteArray, o: Int): ModuleService? {
        val fi = p.u(o + 1)
        val (ch, khz) = Frames.BAND3.getOrNull(fi) ?: return null.also { log("Si4684: record with channel index $fi skipped") }
        return ModuleService(
            freqIdx = fi, freqKhz = khz, channel = ch, sid = p.u32(o + 2), cid = p.u16(o + 6),
            label = text(p, o + 9, o + 25, charset(p[o + 8])), ensembleLabel = text(p, o + 28, o + 44, charset(p[o + 27])),
            pty = p.u(o + 26),
        )
    }

    /**
     * Status (≥ 21 bytes): [0] list index, [2..5] SId, [6..7] component, [10] level 0..5, [19] 1 = service following
     * running, [20] bit 0 new text, bit 1 list changed. DABdream+ answers every status with the next status request.
     */
    private fun onStatus(p: ByteArray) {
        if (p.size < 21) { send(0x40); return }
        val sid = p.u32(2); val cid = p.u16(6)
        var start = false
        signalAll {
            linkInfo = p.u(19) == 1
            resolving?.let { (s, c) -> if (s == sid && c == cid) resolvedIdx = p.u(0) }
            val cur = current
            if (cur != null && !scanning && !decoding && cur.sid == sid && cur.cid == cid) { decoding = true; start = true }
            // DABdream+'s smoothing: from "unknown" (0) straight to the value, then one step per status
            val target = p.u(10).coerceIn(0, 5)
            level = if (level == 0) target else level + (target - level).coerceIn(-1, 1)
            levelKnown = true
        }
        if (start) { send(0x48, byteArrayOf(1)); listener.onStarted() }
        val flags = p.u(20)
        if (flags and 1 != 0) send(0x21)
        if (flags and 2 != 0) send(0x47)
        send(0x40)
    }

    /** Slideshow: 0x73 header ([6] tag), then 0x74 chunks ([2] bit 7 last, [3] sequence, [6] tag, [7..8] 13-bit length, data). */
    private fun onSlide(p: ByteArray) {
        when (p.u(0)) {
            0x73 -> { slide.reset(); if (p.size >= 7) { slideTag = p.u(6); slideSeq = 0 } else slideTag = -1 }
            0x74 -> {
                if (slideTag < 0 || p.size < 9) return
                if (p.u(6) != slideTag || p.u(3) != slideSeq) { slide.reset(); slideTag = -1; return }
                val len = ((p.u(7) and 0x1F) shl 8) or p.u(8)
                if (9 + len > p.size || slide.size() + len > MAX_SLIDE_BYTES) { slide.reset(); slideTag = -1; return }
                slide.write(p, 9, len); slideSeq = (slideSeq + 1) and 0xFF
                if (p.u(2) and 0x80 != 0) {
                    val img = slide.toByteArray(); slide.reset(); slideTag = -1
                    if (synchronized(lock) { current != null }) listener.onSlide(img)
                }
            }
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private fun send(cmd: Int, payload: ByteArray = ByteArray(0)) {
        val f = Frames.command(cmd, payload)
        synchronized(writeLock) { runCatching { t.write(EP_COMMANDS, f, 500) } }     // DABdream+ ignores write errors
    }

    private fun select(idx: Int, s: ModuleService) = byteArrayOf(
        idx.toByte(), s.freqIdx.toByte(),
        (s.sid shr 24).toByte(), (s.sid shr 16).toByte(), (s.sid shr 8).toByte(), s.sid.toByte(),
        (s.cid shr 8).toByte(), s.cid.toByte(),
    )

    private inline fun signalAll(block: () -> Unit) = synchronized(lock) { block(); lock.notifyAll() }

    /** Wait for [cond]; without reader threads (tests) this pumps the replies itself. */
    private fun await(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = clockMs() + timeoutMs
        while (true) {
            synchronized(lock) { if (cond()) return true }
            val left = end - clockMs()
            if (left <= 0) return false
            if (!threads) pumpReplies() else synchronized(lock) { if (!cond()) lock.wait(minOf(left, 50)) }
        }
    }

    /** Module charset flag: 0 = EBU Latin; DABdream+ treats anything else as UTF-8 (we also honour 6 = UCS-2). */
    private fun charset(b: Byte) = when (b.toInt() and 0xFF) { 0 -> 0; 6 -> 6; else -> 15 }

    /** Text up to the first NUL, trailing space/CR/LF/tab trimmed. */
    private fun text(p: ByteArray, from: Int, to: Int, charset: Int): String {
        var end = from
        while (end < to && p[end].toInt() != 0) end++
        return DabText.decode(p, from, end, charset).trimEnd(' ', '\n', '\r', '\t')
    }

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int) = (u(i) shl 8) or u(i + 1)
    private fun ByteArray.u32(i: Int) = (u(i).toLong() shl 24) or (u(i + 1).toLong() shl 16) or (u(i + 2).toLong() shl 8) or u(i + 3).toLong()

    companion object {
        const val VID = 0x1A86
        const val PID = 0x5537
        const val EP_COMMANDS = 0x01
        const val EP_AUDIO = 0x81
        const val EP_REPLIES = 0x82
        private const val MAX_SLIDE_BYTES = 512 * 1024

        /** Starts the reader threads and runs the init. */
        fun open(t: Transport, listener: ModuleListener, log: (String) -> Unit = {}): Si4684 =
            Si4684(t, listener, log, { Thread.sleep(it) }, { System.nanoTime() / 1_000_000 }, threads = true).also { it.init() }

        /** For tests: no threads; waits pump the replies themselves. */
        internal fun forTest(t: Transport, listener: ModuleListener, clockMs: () -> Long, sleep: (Long) -> Unit): Si4684 =
            Si4684(t, listener, {}, sleep, clockMs, threads = false)
    }
}

/** Audio priority for the PCM reader (android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); this module is plain JVM. */
private fun urgentAudioPriority() {
    Class.forName("android.os.Process").getMethod("setThreadPriority", Int::class.javaPrimitiveType).invoke(null, -19)
}
