package io.github.jvspier.dabplay.engine

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import io.github.jvspier.dabplay.audio.AacPlayer
import io.github.jvspier.dabplay.core.dabplus.SuperframeDecoder
import io.github.jvspier.dabplay.core.fic.FicDecoder
import io.github.jvspier.dabplay.core.pad.DlsParser
import io.github.jvspier.dabplay.core.pad.PadDecoder
import io.github.jvspier.dabplay.driver.dtr100.Dtr100
import io.github.jvspier.dabplay.usb.AndroidUsbTransport
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class Station(val sid: Long, val label: String, val subchannel: Int, val bitrate: Int, val ascty: Int)

data class EngineState(
    val status: String = "Waiting for the DAB dongle…",
    val ensemble: String? = null,
    val stations: List<Station> = emptyList(),
    val playing: Station? = null,
    val nowPlaying: String? = null,
    val slide: ByteArray? = null,
    val info: String? = null,
)

/**
 * First-light engine for the hardware test: one USB thread runs the dtr100 driver; commands arrive through a queue.
 * The real playback service (Media3, service following, favourites) replaces this later (docs/architecture.md).
 */
class RadioEngine(private val usb: UsbManager) {
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state
    private val commands = LinkedBlockingQueue<Station>()
    private var thread: Thread? = null

    fun start(device: UsbDevice, channelKhz: Int = 220352, channelName: String = "11C") {
        if (thread != null) return
        thread = Thread({ run(device, channelKhz, channelName) }, "dab-usb").apply { isDaemon = true; start() }
    }

    fun play(station: Station) { commands.offer(station) }

    private fun status(s: String) { Log.i(TAG, s); _state.update { it.copy(status = s) } }

    private fun run(device: UsbDevice, freq: Int, chName: String) {
        try {
            val conn = usb.openDevice(device) ?: error("cannot open the USB device")
            val intf = device.getInterface(0)
            check(conn.claimInterface(intf, true)) { "cannot claim interface 0" }
            val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }.associateBy { it.address }
            val mfr = device.manufacturerName
            check(mfr == Dtr100.MANUFACTURER) { "not a supported dongle (manufacturer '$mfr')" }
            val d = Dtr100(AndroidUsbTransport(conn, eps))

            status("Dongle found · firmware level ${d.version()} · initialising")
            d.powerOn()
            d.systemInit()
            status("Tuning $chName…")
            var t = d.tune(freq); var tries = 1
            while (!t.locked && tries < 3) { t = d.tune(freq); tries++ }
            if (!t.locked) { status("No DAB signal on $chName (${t.info.why})"); return }
            d.ficEnable()
            status("Locked on $chName (signal ${t.info.u1}, quality ${t.info.q}) · reading stations…")

            val fic = FicDecoder()
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 3000) {
                val data = d.ficRead()
                if (data == null) { Thread.sleep(5); continue }
                fic.feed(data)
                if (complete(fic) && System.currentTimeMillis() - t0 > 800) break
            }
            val stations = fic.services.mapNotNull { (sid, label) ->
                val c = fic.components[sid]?.let { cs -> cs.firstOrNull { it.primary } ?: cs.firstOrNull() } ?: return@mapNotNull null
                val sc = fic.subchannels[c.subchannel] ?: return@mapNotNull null
                Station(sid, label, c.subchannel, sc.bitrateKbps ?: return@mapNotNull null, c.ascty)
            }.sortedBy { it.label.lowercase() }
            _state.update { it.copy(ensemble = fic.ensembleLabel, stations = stations) }
            status("${fic.ensembleLabel} · ${stations.size} stations · tap one to play")

            val ecc = fic.ecc ?: 0xE3
            var current: Station? = commands.take()
            val player = AacPlayer()
            while (current != null) {
                val st = current
                current = null
                if (st.ascty != 63) { status("${st.label}: DAB (MP2) is not supported yet"); current = commands.take(); continue }
                d.startAudio(st.subchannel, st.bitrate * 3)
                _state.update { it.copy(playing = st, nowPlaying = null, slide = null, info = "${st.bitrate} kbit/s") }
                status("Playing ${st.label}")
                val dec = SuperframeDecoder(st.bitrate)
                val pad = PadDecoder(
                    onDls = { text -> if (text.isNotEmpty()) _state.update { it.copy(nowPlaying = DlsParser.parse(text, ecc, st.sid, st.label).display) } },
                    onSlide = { s -> _state.update { it.copy(slide = s.body) } },
                )
                while (true) {
                    commands.poll(0, TimeUnit.MILLISECONDS)?.let { current = it }
                    if (current != null) break
                    val data = d.mscRead()
                    if (data.isEmpty()) { Thread.sleep(5); continue }
                    dec.feed(data) { au ->
                        pad.feedAu(au.data)
                        player.feed(au.asc, au.data, au.format.outputRate)
                    }
                    _state.update { it.copy(info = "${st.bitrate} kbit/s · ${dec.format?.let { f -> "${f.outputRate / 1000} kHz${if (f.sbr) " HE-AAC" else ""}${if (f.ps) " v2" else ""}" } ?: ""} · AUs ${dec.ausOk} ok / ${dec.ausBad} bad · ${player.decoderName ?: ""}") }
                }
                d.stopAudio()
            }
        } catch (e: Exception) {
            Log.e(TAG, "engine stopped", e)
            status("Error: ${e.message}")
        }
    }

    private fun complete(f: FicDecoder) = f.ensembleLabel != null && f.components.isNotEmpty() &&
        f.components.keys.all { it in f.services } && f.components.values.flatten().all { it.subchannel in f.subchannels }

    private companion object { const val TAG = "RadioEngine" }
}
