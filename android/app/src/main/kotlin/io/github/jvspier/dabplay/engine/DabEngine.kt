package io.github.jvspier.dabplay.engine

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import io.github.jvspier.dabplay.audio.AudioPlayer
import io.github.jvspier.dabplay.core.Band3
import io.github.jvspier.dabplay.core.dabplus.SuperframeDecoder
import io.github.jvspier.dabplay.core.mp2.DabMp2Pad
import io.github.jvspier.dabplay.core.mp2.Mp2Decoder
import io.github.jvspier.dabplay.core.mp2.Mp2Framer
import io.github.jvspier.dabplay.core.fic.FicDecoder
import io.github.jvspier.dabplay.core.fic.dabAlternatives
import io.github.jvspier.dabplay.core.Announcement
import io.github.jvspier.dabplay.core.AnnouncementWatcher
import io.github.jvspier.dabplay.core.Reception
import io.github.jvspier.dabplay.core.ReceptionMonitor
import io.github.jvspier.dabplay.core.pad.DlsParser
import io.github.jvspier.dabplay.core.pad.NowPlaying
import io.github.jvspier.dabplay.core.pad.PadDecoder
import io.github.jvspier.dabplay.logos.ImageSniff
import io.github.jvspier.dabplay.data.AltFreq
import io.github.jvspier.dabplay.data.ServiceKey
import io.github.jvspier.dabplay.data.Station
import io.github.jvspier.dabplay.data.StationStore
import io.github.jvspier.dabplay.data.StoredEnsemble
import io.github.jvspier.dabplay.data.StoredService
import io.github.jvspier.dabplay.driver.dtr100.Dtr100
import io.github.jvspier.dabplay.usb.AndroidUsbTransport
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ScanProgress(val channel: String, val index: Int, val total: Int, val ensembles: Int, val services: Int)

/** An announcement on air: its type ([Announcement] flags), who's announcing, and the station to return to. */
data class AnnouncementInfo(val flags: Int, val station: String, val returnTo: ServiceKey)

data class EngineState(
    val dongle: Boolean = false,
    val status: String = "Connect the DAB dongle",
    val scan: ScanProgress? = null,
    val current: Station? = null,
    val playing: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val slide: ByteArray? = null,
    val signalBars: Int = 0,
    val tuning: Boolean = false,
    /** While playing: GOOD, WEAK (marginal) or LOST (no signal; audio silent, resumes by itself). */
    val reception: Reception = Reception.GOOD,
    /** A traffic (or alarm) announcement being played instead of the listener's station, else null. */
    val announcement: AnnouncementInfo? = null,
    /** The listener's station takes part in traffic announcements (FIG 0/18): the TA badge is lit. */
    val taSupported: Boolean = false,
    /** Service following: the channel being tried while the signal is gone (e.g. "7C"), else null. */
    val following: String? = null,
    val problem: String? = null,
    val error: String? = null,
)

/**
 * The radio engine: one USB thread runs the dtr100 driver; commands arrive through a queue. Scans the band, plays a
 * station (retuning only when it's on another ensemble), decodes audio and PAD, and publishes [state].
 */
class DabEngine(
    private val usb: UsbManager,
    private val store: StationStore,
    private val focus: AudioFocus,
    private val resumeOnStart: () -> Boolean = { true },
    private val followStation: () -> Boolean = { true },
    private val trafficAnnouncements: () -> Boolean = { false },
) {
    // show the last-played station straight away (car start: the dongle takes a moment; or it isn't plugged in)
    private val _state = MutableStateFlow(EngineState(current = store.state.value.let { s -> s.lastPlayed?.let(s::station) }))
    val state: StateFlow<EngineState> = _state

    private sealed interface Cmd {
        data object Scan : Cmd
        data class Play(val key: ServiceKey) : Cmd
        data object Pause : Cmd
        data object SkipAnnouncement : Cmd
    }

    private val commands = LinkedBlockingDeque<Cmd>()
    private var thread: Thread? = null
    @Volatile private var device: UsbDevice? = null

    fun attach(dev: UsbDevice) {
        if (thread?.isAlive == true && device?.deviceName == dev.deviceName) return
        val old = thread
        device = dev
        old?.interrupt()
        // a quick re-plug: let the old session finish its cleanup first, so it can't undo the new one's state
        thread = Thread({ runCatching { old?.join(3000) }; run(dev) }, "dab-usb").apply { isDaemon = true; start() }
    }

    /** A USB device went away ([deviceName]); only our dongle leaving stops the engine (a USB stick or phone doesn't). */
    fun detached(deviceName: String?) {
        if (deviceName != null && deviceName != device?.deviceName) return
        device = null; thread?.interrupt()
    }
    fun scan() = commands.offer(Cmd.Scan)
    fun play(key: ServiceKey) = commands.offer(Cmd.Play(key))
    fun pause() = commands.offer(Cmd.Pause)
    fun resume() { listenerKey()?.let(::play) ?: store.state.value.lastPlayed?.let(::play) }

    /** The listener's station: during an announcement the one to return to, not the announcing one. */
    private fun listenerKey() = state.value.announcement?.returnTo ?: state.value.current?.key

    /** Back to the listener's station now; this announcement is ignored until its cluster goes quiet. */
    fun skipAnnouncement() = commands.offer(Cmd.SkipAnnouncement)

    /** Next/previous favourite (steering-wheel buttons); falls back to all stations when there are no favourites. */
    fun skip(delta: Int) {
        val s = store.state.value
        val list = s.favouriteStations.ifEmpty { s.stations }
        if (list.isEmpty()) return
        val cur = listenerKey()
        val i = list.indexOfFirst { it.key == cur }
        play(list[Math.floorMod((if (i < 0) -1 else i) + delta, list.size)].key)
    }

    private fun status(s: String) { Log.i(TAG, s); _state.update { it.copy(status = s) } }

    /**
     * The USB thread. A USB or driver error (a timeout on a voltage dip while cranking, a stale reply) doesn't end it:
     * it reconnects with the same init sequence as at startup, backing off 0.5 s → 8 s, and carries on with the
     * station that was playing. It ends only when the dongle is detached (or another attach replaces it).
     */
    private fun run(dev: UsbDevice) {
        // the MSC reader must never fall behind the dongle (its buffer overflows: audible skips)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        commands.clear()                                     // buttons pressed while there was no dongle: don't replay
        var failures = 0
        var resume: ServiceKey? = null
        while (device?.deviceName == dev.deviceName && !Thread.currentThread().isInterrupted) {
            val failed = session(dev, resume) ?: break          // null: stopped on purpose
            resume = failed.resume
            failures++
            val wait = minOf(8000L, 500L shl minOf(failures - 1, 4))
            status("Dongle error, reconnecting in ${"%.1f".format(wait / 1000.0)} s")
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
        }
        runCatching { focus.abandon() }
    }

    private class Failure(val resume: ServiceKey?)

    /** One connection: open, init, serve commands. Null when stopped on purpose, else what to resume after reconnecting. */
    private fun session(dev: UsbDevice, resume: ServiceKey?): Failure? {
        var conn: UsbDeviceConnection? = null
        val player = AudioPlayer()
        try {
            // check before claiming: 16c0:05dc is a shared V-USB id, don't grab someone else's device
            check(dev.manufacturerName == Dtr100.MANUFACTURER) { "unsupported dongle (manufacturer '${dev.manufacturerName}')" }
            conn = usb.openDevice(dev) ?: error("cannot open the USB device")
            val intf = dev.getInterface(0)
            check(conn.claimInterface(intf, true)) { "cannot claim interface 0" }
            val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }.associateBy { it.address }
            val d = Dtr100(AndroidUsbTransport(conn, eps))
            d.drainStale().takeIf { it > 0 }?.let { Log.i(TAG, "dropped $it stale bytes from a previous session") }
            val fw = d.version()
            d.powerOn()
            d.systemInit()
            _state.update { it.copy(dongle = true, error = null) }
            status("Dongle ready (firmware level $fw)")
            val radio = Session(d, player)
            when {
                resume != null -> commands.offerFirst(Cmd.Play(resume))
                store.state.value.ensembles.isEmpty() -> commands.offer(Cmd.Scan)
                resumeOnStart() -> store.state.value.lastPlayed?.let { commands.offer(Cmd.Play(it)) }
            }
            while (!Thread.currentThread().isInterrupted) {
                when (val c = commands.take()) {
                    Cmd.Scan -> radio.scan()
                    is Cmd.Play -> radio.play(c.key)
                    Cmd.Pause, Cmd.SkipAnnouncement -> Unit
                }
            }
            return null
        } catch (e: InterruptedException) {
            Log.i(TAG, "engine stopped")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "engine error", e)
            val s = _state.value
            _state.update { it.copy(error = e.message) }
            return Failure(if (s.playing || s.tuning) (s.announcement?.returnTo ?: s.current?.key) else null)
        } finally {
            // a detach interrupts this thread; the flag would make the cleanup's join throw, so park it meanwhile
            val interrupted = Thread.interrupted()
            runCatching { player.release() }
            runCatching { conn?.close() }
            _state.update {
                it.copy(dongle = false, playing = false, scan = null, tuning = false, following = null, reception = Reception.GOOD,
                    announcement = null, taSupported = false, signalBars = 0,
                    current = it.announcement?.returnTo?.let(store.state.value::station) ?: it.current)
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Everything that runs on the USB thread. */
    private inner class Session(private val d: Dtr100, private val player: AudioPlayer) {
        private var tunedKhz: Int? = null
        private var ficEnabled = false

        private fun tune(khz: Int, retries: Boolean): Dtr100.TuneOutcome {
            var t = d.tune(khz); var tries = 1
            // same retry rules as tools/dtr100.py scan: sync 3 -> 2 tries, promising (u1 >= 1000) -> 3 tries
            while (!t.locked && retries && tries < (if ((t.info.u1 ?: 0) >= 1000) 3 else if (t.info.sync == 3) 2 else 1)) { t = d.tune(khz); tries++ }
            tunedKhz = if (t.locked) khz else null
            if (t.locked && !ficEnabled) { d.ficEnable(); ficEnabled = true }
            if (t.locked) _state.update { it.copy(signalBars = bars(t.info.u1 ?: 0)) }
            return t
        }

        private fun bars(u1: Int) = when { u1 < 4000 -> 1; u1 < 9000 -> 2; u1 < 15000 -> 3; else -> 4 }

        /** Read FIC until the service list is complete and quiet for 300 ms (max 3 s). */
        private fun readFic(): FicDecoder {
            val f = FicDecoder()
            val t0 = System.currentTimeMillis()
            var lastChange = t0; var lastKey = ""
            while (System.currentTimeMillis() - t0 < 3000) {
                val data = d.ficRead()
                if (data == null) { Thread.sleep(5); continue }
                guarded("FIC") { f.feed(data) }
                val k = "${f.ensembleLabel}/${f.services.size}/${f.components.size}/${f.subchannels.size}"
                if (k != lastKey) { lastKey = k; lastChange = System.currentTimeMillis() }
                val complete = f.ensembleLabel != null && f.components.isNotEmpty() && f.components.keys.all { it in f.services } &&
                    f.components.values.flatten().all { it.subchannel in f.subchannels }
                if (complete && System.currentTimeMillis() - lastChange >= 300) break
            }
            return f
        }

        fun scan() {
            stopAudio(); focus.abandon(); dropAnnouncement()
            val found = ArrayList<StoredEnsemble>()
            val tried = HashSet<String>()                            // an aborted scan only counts channels it reached
            val total = Band3.CHANNELS.size
            for ((i, ch) in Band3.CHANNELS.withIndex()) {
                if (commands.peek() is Cmd.Play) break                  // the user picked a station: stop scanning
                val (name, khz) = ch
                tried += name
                _state.update { it.copy(scan = ScanProgress(name, i + 1, total, found.size, found.sumOf { e -> e.services.size })) }
                if (!tune(khz, retries = true).locked) continue
                var fic = readFic()
                if (fic.fibsOk == 0 && tune(khz, retries = false).locked) fic = readFic()   // lock without valid FIC: retry once
                ensembleFrom(fic, name, khz)?.let { found += it }
            }
            store.applyScan(found, tried)
            _state.update { it.copy(scan = null) }
            status("Scan done: ${found.size} ensembles, ${found.sumOf { it.services.size }} stations")
        }

        /** The ensemble a FIC read describes, with its audio services (as the scan stores it); null if none. */
        private fun ensembleFrom(fic: FicDecoder, name: String, khz: Int): StoredEnsemble? {
            val ecc = fic.ecc ?: 0xE3
            val services = fic.services.mapNotNull { (sid, label) ->
                val c = fic.components[sid]?.let { cs -> cs.firstOrNull { it.primary } ?: cs.first() } ?: return@mapNotNull null
                val sc = fic.subchannels[c.subchannel] ?: return@mapNotNull null
                val br = sc.bitrateKbps?.takeIf { it in SuperframeDecoder.MIN_KBPS..SuperframeDecoder.MAX_KBPS } ?: return@mapNotNull null
                StoredService(ServiceKey(ecc, sid), label, c.subchannel, br, c.ascty)
            }
            return if (services.isEmpty()) null else StoredEnsemble(fic.eid ?: 0, name, khz, fic.ensembleLabel ?: name, 0, 0, services)
        }

        private var playingKey: ServiceKey? = null

        // FIC read while playing, kept across sub-channel switches on the same ensemble (announcements)
        private var liveFic = FicDecoder()
        private var liveFicKhz = 0
        private val watcher = AnnouncementWatcher()
        private var taReturn: Station? = null       // playing an announcement: the listener's station
        private var taCluster = -1
        private var nextFollowAt = 0L               // across stream() restarts, so rounds stay 30 s apart
        @Volatile private var focusLevel = 1f        // what audio focus allows now (a call: 0, navigation: ducked)

        /** Leaving an announcement without switching back (pause, scan, a new station): the listener's station shows again. */
        private fun dropAnnouncement() {
            val back = taReturn ?: return
            taReturn = null; taCluster = -1
            _state.update { it.copy(announcement = null, current = back) }
        }

        private fun stopAudio() {
            if (playingKey != null) { d.stopAudio(); playingKey = null }
            player.release()
            _state.update { it.copy(playing = false) }
        }

        fun play(key: ServiceKey) {
            val st = store.state.value.station(key) ?: return status("Station not found, please scan")
            stopAudio()
            _state.update { it.copy(current = st, nowPlaying = null, slide = null, problem = null, tuning = true, reception = Reception.GOOD, following = null) }
            fun problem(msg: String) { status(msg); _state.update { it.copy(tuning = false, problem = msg) } }
            // ASCTy (FIG 0/2): 0 = DAB, MPEG Layer II; 63 = DAB+, HE-AAC. Anything else isn't audio we know.
            if (st.service.ascty != 0 && st.service.ascty != 63) return problem("This station's audio format is not supported")
            if (st.service.bitrateKbps !in SuperframeDecoder.MIN_KBPS..SuperframeDecoder.MAX_KBPS) return problem("This station can't be played")
            if (tunedKhz != st.ensemble.freqKhz) {
                status("Tuning ${st.ensemble.channel}…")
                // no lock (an underground car park at start-up): don't give up, stream() keeps retrying and following
                tune(st.ensemble.freqKhz, retries = true)
            }
            if (!focus.request { c ->
                    focusLevel = when (c) {
                        FocusChange.LOST -> { pause(); 1f }                  // another media app took over
                        FocusChange.SILENCE -> 0f                            // phone call, voice assistant
                        FocusChange.DUCK -> DUCK_LEVEL                       // navigation prompt
                        FocusChange.RESTORE -> 1f
                    }
                    player.setLevel(focusLevel)
                    Log.i(TAG, "audio focus: $c")
                }) return problem("Audio focus denied")
            player.setLevel(focusLevel)                                      // e.g. a station change during a call stays silent
            store.played(key)
            taReturn = null; taCluster = -1; _state.update { it.copy(announcement = null) }
            var cur: Station = st
            while (true) cur = stream(cur) ?: return             // following hands over to the same station elsewhere
        }

        /** Stops the dongle's audio stream and the player, but not the "playing" state (following, re-locking). */
        private fun pauseStream() { if (playingKey != null) { d.stopAudio(); playingKey = null }; player.release() }

        /**
         * Plays [st] until paused or replaced (returns null), or until service following found it elsewhere
         * (returns that station) or gave up and came back ([st] again, to restart cleanly).
         */
        private fun stream(st: Station): Station? {
            val key = st.key
            // back from a failed follow attempt, the original may not lock yet: keep trying, showing "No signal"
            if (nextFollowAt == 0L) nextFollowAt = System.currentTimeMillis() + FOLLOW_AFTER_MS
            while (tunedKhz != st.ensemble.freqKhz) {
                if (tune(st.ensemble.freqKhz, retries = false).locked) break
                _state.update { it.copy(current = if (taReturn == null) st else it.current, reception = Reception.LOST,
                    signalBars = 0, playing = true, tuning = false) }
                when (val c = commands.poll(RELOCK_EVERY_MS, TimeUnit.MILLISECONDS)) {
                    null, Cmd.SkipAnnouncement -> Unit
                    Cmd.Pause -> { pauseStream(); focus.abandon(); dropAnnouncement(); _state.update { it.copy(playing = false) }; status("Paused"); return null }
                    else -> { commands.offerFirst(c); return null }            // keep the order: it's next
                }
                if (followStation() && System.currentTimeMillis() >= nextFollowAt) {
                    nextFollowAt = System.currentTimeMillis() + FOLLOW_RETRY_MS
                    follow(st)?.let { if (it !== st) return it }
                }
            }
            d.startAudio(st.service.subchannel, st.service.bitrateKbps * 3)
            playingKey = key
            // a different station than shown (an announcement, following): its predecessor's text and slide must go
            _state.update { it.copy(current = st, playing = true, tuning = false, following = null,
                nowPlaying = if (it.current?.key == st.key) it.nowPlaying else null, slide = if (it.current?.key == st.key) it.slide else null) }
            status("Playing ${st.label} (${st.ensemble.channel})")
            val dec = SuperframeDecoder(st.service.bitrateKbps)
            // DAB (MP2): Layer II frames straight in the sub-channel, PAD at each frame's end (untested on air: none in NL)
            val framer = if (st.service.ascty == 0) Mp2Framer() else null
            var mp2Frames = 0; var mp2Bad = 0
            fun good() = if (framer != null) mp2Frames else dec.ausOk
            fun bad() = if (framer != null) framer.resyncs + mp2Bad else dec.ausBad
            val pad = PadDecoder(
                onDls = { t ->
                    // DLS may carry control codes (line breaks, end-of-headline); blank after that = nothing to show
                    val text = t.map { if (it < ' ') ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim()
                    if (text.isNotEmpty()) Log.i(TAG, "DLS: $text")
                    _state.update { it.copy(nowPlaying = if (text.isEmpty()) null else DlsParser.parse(text, key.ecc, key.sid, st.label)) }
                },
                onSlide = { s ->
                    // checked from the header before anything decodes it: a small file can declare 16000x16000 (1 GB)
                    val info = ImageSniff.info(s.body)
                    if (info != null && info.width <= MAX_SLIDE_PX && info.height <= MAX_SLIDE_PX) _state.update { it.copy(slide = s.body) }
                    else Log.w(TAG, "slide dropped: ${info?.let { "${it.width}x${it.height} ${it.mime}" } ?: "not a JPEG/PNG"}")
                },
            )
            val monitor = ReceptionMonitor().apply { start(System.currentTimeMillis()) }
            var checkAt = 0L; var diagAt = 0L; var diag = IntArray(4); var diagBytes = 0
            // FIC while playing (owner-approved test 2026-09-27: audio stays clean, FIBs valid, ~15 % of frames caught;
            // the vendor lib serialises its FIC and MSC reads with one mutex too): learns FIG 0/24 + 0/21 alternatives
            if (st.ensemble.freqKhz != liveFicKhz) { liveFic = FicDecoder(); liveFicKhz = st.ensemble.freqKhz }
            val ficLive = liveFic; var ficAt = 0L; var learnedKey = ""; var learnedAt = 0L
            val base = taReturn ?: st                        // the station the listener chose
            while (true) {
                val next = commands.poll(0, TimeUnit.MILLISECONDS)
                if (next != null) {
                    if (next is Cmd.Pause) { stopAudio(); focus.abandon(); dropAnnouncement(); status("Paused"); return null }
                    if (next is Cmd.SkipAnnouncement) {
                        if (taReturn != null) { watcher.skip(taCluster); return endAnnouncement("skipped") }
                        continue
                    }
                    commands.offerFirst(next)     // play/scan: handled by the main loop after stopping here, still first
                    return null
                }
                val now = System.currentTimeMillis()
                if (now - checkAt >= 500) {                                // reception, twice a second
                    checkAt = now
                    val sig = d.mcuSignal ?: 0
                    val r = monitor.update(now, good(), bad(), noSignal = sig >= Dtr100.SIGNAL_NONE, weakSignal = sig >= 700)
                    // one bar per update, like RAON's own level meter: CER jitters, the bars shouldn't (lost is at once)
                    val want = if (r == Reception.LOST) 0 else Dtr100.signalBars(sig)
                    val have = _state.value.signalBars
                    val bars = if (want == 0) 0 else have + (want - have).coerceIn(-1, 1)
                    val prev = _state.value
                    if (r != prev.reception || bars != prev.signalBars) {
                        if (r != prev.reception) Log.i(TAG, "reception ${prev.reception} -> $r (sig $sig)")
                        if (r == Reception.LOST && prev.reception != Reception.LOST) pad.resetPartial()
                        _state.update { it.copy(reception = r, signalBars = bars) }
                    }
                    if (r != Reception.GOOD && now - diagAt >= 1000) {     // field diagnostics, only when it matters
                        diagAt = now
                        Log.i(TAG, "rx sig=$sig bytes=$diagBytes sf=${dec.superframes - diag[0]} rsFail=${dec.rsFail - diag[1]} " +
                            "${if (framer != null) "frames" else "au"}=${good() - diag[2]}/${bad() - diag[3]}")
                        diag = intArrayOf(dec.superframes, dec.rsFail, good(), bad()); diagBytes = 0
                    }
                    // service following: the signal has been gone for a while, try the station on another ensemble
                    // announcements (EN 300 401 §8.1.6): alarm always, traffic when the setting is on
                    val wanted = Announcement.ALARM or (if (trafficAnnouncements()) Announcement.TRAFFIC else 0)
                    val ta = watcher.supports(ficLive, base.key.sid, Announcement.TRAFFIC)
                    if (ta != _state.value.taSupported) _state.update { it.copy(taSupported = ta) }
                    val act = watcher.update(now, ficLive, base.key.sid, base.service.subchannel, wanted)
                    if (taReturn == null && act != null) announcementStation(base, act.subchannel)?.let { src ->
                        Log.i(TAG, "announcement (flags ${act.flags}) in cluster ${act.cluster}: ${base.label} -> ${src.label}")
                        pauseStream()
                        taReturn = base; taCluster = act.cluster
                        _state.update { it.copy(announcement = AnnouncementInfo(act.flags, src.label, base.key)) }
                        return src
                    }
                    if (taReturn != null && act?.cluster != taCluster) return endAnnouncement("over")
                    if (r == Reception.LOST && followStation() && monitor.lostSince >= 0 &&
                        now - monitor.lostSince >= FOLLOW_AFTER_MS && now >= nextFollowAt) {
                        nextFollowAt = now + FOLLOW_RETRY_MS
                        follow(st)?.let { return it }
                    }
                }
                if (now - ficAt >= 50 && monitor.state != Reception.LOST) {
                    ficAt = now
                    d.ficRead()?.let { f ->
                        guarded("FIC") { ficLive.feed(f) }
                        val k = "${ficLive.otherEnsembleServices.values.sumOf { it.size }}/${ficLive.frequencyInfo.size}"
                        if (k != learnedKey && now - learnedAt >= LEARN_EVERY_MS) { learnedKey = k; learnedAt = now; learnAlternatives(ficLive, st) }
                    }
                }
                val t0 = System.nanoTime()
                val data = d.mscRead()
                diagBytes += data.size
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (ms > 150) Log.w(TAG, "slow MSC read: $ms ms")
                if (data.isEmpty()) { Thread.sleep(5); continue }
                if (framer != null) {
                    guarded("MP2") {
                        framer.feed(data) { frame, h ->
                            if (Mp2Decoder.crcOk(frame)) mp2Frames++ else mp2Bad++      // a CRC-failed frame plays as silence
                            guarded("PAD") { DabMp2Pad.split(frame, h)?.let { (x, f) -> pad.feedPad(x, f, exact = false) } }
                            player.feedMp2(frame)
                        }
                    }
                    continue
                }
                guarded("DAB+") { dec.feed(data) { au ->
                    pad.feedAu(au.data)
                    player.feedAac(au.asc, au.data, au.format.outputRate)
                } }
            }
        }

        /** The service carrying [subchannel] on [base]'s ensemble: from the scan, else from the live FIC. */
        private fun announcementStation(base: Station, subchannel: Int): Station? {
            fun playable(ascty: Int, br: Int) = (ascty == 0 || ascty == 63) && br in SuperframeDecoder.MIN_KBPS..SuperframeDecoder.MAX_KBPS
            base.ensemble.services.firstOrNull { it.subchannel == subchannel }?.let {
                return if (playable(it.ascty, it.bitrateKbps)) Station(it, base.ensemble) else null }
            val (sid, comp) = liveFic.components.entries.firstNotNullOfOrNull { (sid, cs) ->
                cs.firstOrNull { it.subchannel == subchannel }?.let { sid to it } } ?: return null
            val br = liveFic.subchannels[subchannel]?.bitrateKbps ?: return null
            if (!playable(comp.ascty, br)) return null
            val label = liveFic.services[sid] ?: "Announcement"
            return Station(StoredService(ServiceKey(base.key.ecc, sid), label, subchannel, br, comp.ascty), base.ensemble)
        }

        /** Back from an announcement to the listener's station. */
        private fun endAnnouncement(why: String): Station? {
            val back = taReturn ?: return null
            Log.i(TAG, "announcement $why: back to ${back.label}")
            pauseStream()
            taReturn = null; taCluster = -1
            _state.update { it.copy(announcement = null) }
            return back
        }

        private var decodeErrors = 0

        /**
         * Broadcast bytes are untrusted (a corrupt FIB passes its CRC about once in 65536): a parser exception must cost
         * one packet, not the radio. Logged sparingly.
         */
        private inline fun guarded(what: String, block: () -> Unit) {
            try { block() } catch (e: RuntimeException) {
                decodeErrors++
                if (decodeErrors <= 5 || decodeErrors % 100 == 0) Log.w(TAG, "$what decode error #$decodeErrors", e)
            }
        }

        /** Store what this ensemble announces about its services elsewhere (for all its services, not just [st]). */
        private fun learnAlternatives(f: FicDecoder, st: Station) {
            val found = f.otherEnsembleServices.keys.associate { sid ->
                ServiceKey(st.key.ecc, sid) to dabAlternatives(f.otherEnsembleServices, f.frequencyInfo, sid, st.ensemble.eid)
                    .filter { a -> Band3.CHANNELS.any { it.second == a.khz } }     // L-band etc.: the tuner can't go there
                    .map { AltFreq(it.eid, it.khz) }
            }.filterValues { it.isNotEmpty() }
            if (found.isEmpty()) return
            Log.i(TAG, "learned alternatives: " + found.entries.joinToString { (k, v) -> "$k on ${v.joinToString("/") { channelName(it.khz) }}" })
            store.learnAlternatives(found)
        }

        /**
         * Service following. Candidates: the ensembles this one announced (FIG 0/24 + 0/21, learned while listening)
         * and ensembles from scans that carry the same service. For each: tune, confirm the service is there
         * (brief FIC read), and return it on that ensemble. Nothing found: back to [st]'s frequency and return [st]
         * (the caller restarts it; the chip re-locks by itself when the signal returns). No candidates: null, untouched.
         */
        private fun follow(st: Station): Station? {
            val snap = store.state.value
            val own = st.ensemble.freqKhz
            val cands = (snap.alternatives[st.key].orEmpty().map { it.khz } +
                snap.ensembles.filter { e -> e.services.any { it.key == st.key } }.sortedByDescending { it.lastSeen }.map { it.freqKhz })
                .distinct().filter { it != own && Band3.CHANNELS.any { c -> c.second == it } }   // band III only: L-band etc. can't be tuned
            if (cands.isEmpty()) return null
            pauseStream()
            for (khz in cands) {
                if (commands.peek() != null) break
                val ch = channelName(khz)
                _state.update { it.copy(following = ch) }
                Log.i(TAG, "following ${st.label}: trying $ch")
                val ens = try {
                    if (!tune(khz, retries = false).locked) continue
                    ensembleFrom(readFic(), ch, khz) ?: continue
                } catch (e: Dtr100.DriverException) { Log.w(TAG, "following: $ch failed: ${e.message}"); continue }
                val svc = ens.services.firstOrNull { it.key == st.key } ?: continue
                Log.i(TAG, "following ${st.label}: continuing on $ch (${ens.label})")
                _state.update { it.copy(following = null) }
                return Station(svc, ens.copy(lastSeen = System.currentTimeMillis()))
            }
            _state.update { it.copy(following = null) }
            Log.i(TAG, "following ${st.label}: no alternative receivable, back to ${st.ensemble.channel}")
            runCatching { tune(own, retries = false) }
            return st
        }

        private fun channelName(khz: Int) = Band3.CHANNELS.firstOrNull { it.second == khz }?.first ?: "%.3f MHz".format(khz / 1000.0)
    }

    private companion object {
        const val TAG = "DabEngine"
        const val LEARN_EVERY_MS = 10_000L       // store learned alternatives at most this often
        const val MAX_SLIDE_PX = 1280            // DAB slides are 320x240 (up to 640x480); anything bigger is dropped
        const val DUCK_LEVEL = 0.25f            // under a navigation prompt
        const val FOLLOW_AFTER_MS = 6_000L      // signal gone this long before trying another ensemble
        const val FOLLOW_RETRY_MS = 30_000L     // then again every 30 s while it stays gone
        const val RELOCK_EVERY_MS = 5_000L      // retune attempts on the station's own frequency while nothing locks
    }
}
