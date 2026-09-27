package io.github.jvspier.dabplay.engine

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import io.github.jvspier.dabplay.audio.AacPlayer
import io.github.jvspier.dabplay.core.Band3
import io.github.jvspier.dabplay.core.dabplus.SuperframeDecoder
import io.github.jvspier.dabplay.core.fic.FicDecoder
import io.github.jvspier.dabplay.core.pad.DlsParser
import io.github.jvspier.dabplay.core.pad.NowPlaying
import io.github.jvspier.dabplay.core.pad.PadDecoder
import io.github.jvspier.dabplay.data.ServiceKey
import io.github.jvspier.dabplay.data.Station
import io.github.jvspier.dabplay.data.StationStore
import io.github.jvspier.dabplay.data.StoredEnsemble
import io.github.jvspier.dabplay.data.StoredService
import io.github.jvspier.dabplay.driver.dtr100.Dtr100
import io.github.jvspier.dabplay.usb.AndroidUsbTransport
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ScanProgress(val channel: String, val index: Int, val total: Int, val ensembles: Int, val services: Int)

data class EngineState(
    val dongle: Boolean = false,
    val status: String = "Connect the DAB dongle",
    val scan: ScanProgress? = null,
    val current: Station? = null,
    val playing: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val slide: ByteArray? = null,
    val signalBars: Int = 0,
    val error: String? = null,
)

/**
 * The radio engine: one USB thread runs the dtr100 driver; commands arrive through a queue. Scans the band, plays a
 * station (retuning only when it's on another ensemble), decodes audio and PAD, and publishes [state].
 */
class DabEngine(private val usb: UsbManager, private val store: StationStore, private val focus: AudioFocus) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    private sealed interface Cmd {
        data object Scan : Cmd
        data class Play(val key: ServiceKey) : Cmd
        data object Pause : Cmd
    }

    private val commands = LinkedBlockingQueue<Cmd>()
    private var thread: Thread? = null
    @Volatile private var device: UsbDevice? = null

    fun attach(dev: UsbDevice) {
        if (thread?.isAlive == true && device?.deviceName == dev.deviceName) return
        device = dev
        thread = Thread({ run(dev) }, "dab-usb").apply { isDaemon = true; start() }
    }

    fun detached() { device = null; thread?.interrupt() }
    fun scan() = commands.offer(Cmd.Scan)
    fun play(key: ServiceKey) = commands.offer(Cmd.Play(key))
    fun pause() = commands.offer(Cmd.Pause)
    fun resume() { state.value.current?.let { play(it.key) } ?: store.state.value.lastPlayed?.let(::play) }

    /** Next/previous favourite (steering-wheel buttons); falls back to all stations when there are no favourites. */
    fun skip(delta: Int) {
        val s = store.state.value
        val list = s.favouriteStations.ifEmpty { s.stations }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.key == state.value.current?.key }
        play(list[Math.floorMod((if (i < 0) -1 else i) + delta, list.size)].key)
    }

    private fun status(s: String) { Log.i(TAG, s); _state.update { it.copy(status = s) } }

    private fun run(dev: UsbDevice) {
        var conn: UsbDeviceConnection? = null
        val player = AacPlayer()
        try {
            conn = usb.openDevice(dev) ?: error("cannot open the USB device")
            val intf = dev.getInterface(0)
            check(conn.claimInterface(intf, true)) { "cannot claim interface 0" }
            check(dev.manufacturerName == Dtr100.MANUFACTURER) { "unsupported dongle (manufacturer '${dev.manufacturerName}')" }
            val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }.associateBy { it.address }
            val d = Dtr100(AndroidUsbTransport(conn, eps))
            val fw = d.version()
            d.powerOn()
            d.systemInit()
            _state.update { it.copy(dongle = true, error = null) }
            status("Dongle ready (firmware level $fw)")
            val radio = Session(d, player)
            if (store.state.value.ensembles.isEmpty()) commands.offer(Cmd.Scan)
            else store.state.value.lastPlayed?.let { commands.offer(Cmd.Play(it)) }
            while (!Thread.currentThread().isInterrupted) {
                when (val c = commands.take()) {
                    Cmd.Scan -> radio.scan()
                    is Cmd.Play -> radio.play(c.key)
                    Cmd.Pause -> Unit
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "engine stopped")
        } catch (e: Exception) {
            Log.e(TAG, "engine error", e)
            _state.update { it.copy(error = e.message) }
            status("Dongle error: ${e.message}")
        } finally {
            player.release(); focus.abandon()
            runCatching { conn?.close() }
            _state.update { it.copy(dongle = false, playing = false, scan = null) }
        }
    }

    /** Everything that runs on the USB thread. */
    private inner class Session(private val d: Dtr100, private val player: AacPlayer) {
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
                f.feed(data)
                val k = "${f.ensembleLabel}/${f.services.size}/${f.components.size}/${f.subchannels.size}"
                if (k != lastKey) { lastKey = k; lastChange = System.currentTimeMillis() }
                val complete = f.ensembleLabel != null && f.components.isNotEmpty() && f.components.keys.all { it in f.services } &&
                    f.components.values.flatten().all { it.subchannel in f.subchannels }
                if (complete && System.currentTimeMillis() - lastChange >= 300) break
            }
            return f
        }

        fun scan() {
            stopAudio()
            val found = ArrayList<StoredEnsemble>()
            val total = Band3.CHANNELS.size
            for ((i, ch) in Band3.CHANNELS.withIndex()) {
                if (commands.peek() is Cmd.Play) break                  // the user picked a station: stop scanning
                val (name, khz) = ch
                _state.update { it.copy(scan = ScanProgress(name, i + 1, total, found.size, found.sumOf { e -> e.services.size })) }
                if (!tune(khz, retries = true).locked) continue
                var fic = readFic()
                if (fic.fibsOk == 0 && tune(khz, retries = false).locked) fic = readFic()   // lock without valid FIC: retry once
                val ecc = fic.ecc ?: 0xE3
                val services = fic.services.mapNotNull { (sid, label) ->
                    val c = fic.components[sid]?.let { cs -> cs.firstOrNull { it.primary } ?: cs.first() } ?: return@mapNotNull null
                    val sc = fic.subchannels[c.subchannel] ?: return@mapNotNull null
                    StoredService(ServiceKey(ecc, sid), label, c.subchannel, sc.bitrateKbps ?: return@mapNotNull null, c.ascty)
                }
                if (services.isNotEmpty()) found += StoredEnsemble(fic.eid ?: 0, name, khz, fic.ensembleLabel ?: name, 0, 0, services)
            }
            store.applyScan(found, Band3.CHANNELS.map { it.first }.toSet())
            _state.update { it.copy(scan = null) }
            status("Scan done: ${found.size} ensembles, ${found.sumOf { it.services.size }} stations")
        }

        private var playingKey: ServiceKey? = null

        private fun stopAudio() {
            if (playingKey != null) { d.stopAudio(); playingKey = null }
            player.release()
            _state.update { it.copy(playing = false) }
        }

        fun play(key: ServiceKey) {
            val st = store.state.value.station(key) ?: return status("Station not found, please scan")
            stopAudio()
            _state.update { it.copy(current = st, nowPlaying = null, slide = null) }
            if (st.service.ascty != 63) return status("${st.label}: DAB (MP2) is not supported yet")
            if (tunedKhz != st.ensemble.freqKhz) {
                status("Tuning ${st.ensemble.channel}…")
                if (!tune(st.ensemble.freqKhz, retries = true).locked) return status("No signal for ${st.label}")
            }
            if (!focus.request { pause() }) return status("Audio focus denied")
            d.startAudio(st.service.subchannel, st.service.bitrateKbps * 3)
            playingKey = key
            store.played(key)
            _state.update { it.copy(playing = true) }
            status("Playing ${st.label}")
            val dec = SuperframeDecoder(st.service.bitrateKbps)
            val pad = PadDecoder(
                onDls = { t -> if (t.isNotEmpty()) _state.update { it.copy(nowPlaying = DlsParser.parse(t, key.ecc, key.sid, st.label)) } },
                onSlide = { s -> _state.update { it.copy(slide = s.body) } },
            )
            while (true) {
                val next = commands.poll(0, TimeUnit.MILLISECONDS)
                if (next != null) {
                    if (next is Cmd.Pause) { stopAudio(); focus.abandon(); status("Paused"); return }
                    commands.offer(next)          // play/scan: handled by the main loop after stopping here
                    return
                }
                val data = d.mscRead()
                if (data.isEmpty()) { Thread.sleep(5); continue }
                dec.feed(data) { au ->
                    pad.feedAu(au.data)
                    player.feed(au.asc, au.data, au.format.outputRate)
                }
            }
        }
    }

    private companion object { const val TAG = "DabEngine" }
}
