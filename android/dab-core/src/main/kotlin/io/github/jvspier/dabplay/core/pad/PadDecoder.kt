package io.github.jvspier.dabplay.core.pad

import io.github.jvspier.dabplay.core.Crc
import io.github.jvspier.dabplay.core.DabText
import io.github.jvspier.dabplay.core.u8

/** A complete MOT slideshow object (usually a 320×240 JPEG or PNG). */
class Slide(val transportId: Int, val contentType: Int, val contentSubtype: Int, val params: Map<String, String>, val body: ByteArray) {
    val contentName: String? get() = params["ContentName"]
    val mimeType: String? get() = when (contentType to contentSubtype) { 2 to 1 -> "image/jpeg"; 2 to 3 -> "image/png"; 2 to 0 -> "image/gif"; else -> null }
}

/**
 * PAD decoding: X-PAD data subfields → DLS (dynamic label), DL Plus tags, MOT slideshow.
 * Port of tools/pad.py (ETSI EN 300 401, TS 102 980, EN 301 234, TS 101 499). For DAB+ the PAD sits in a
 * data_stream_element at the start of each AU; X-PAD bytes are stored reversed, followed by 2 F-PAD bytes.
 */
class PadDecoder(
    private val onDls: (String) -> Unit = {},
    private val onDlPlus: (tags: Map<String, String>, itemRunning: Boolean, itemToggle: Int) -> Unit = { _, _, _ -> },
    private val onSlide: (Slide) -> Unit = {},
) {
    private var lastApp: Int? = null
    private val dlAsm = Assembler()
    private val motAsm = Assembler()
    private var motLen = 0
    private var dlToggle: Int? = null
    private val dlSegs = LinkedHashMap<Int, Pair<ByteArray, Boolean>>()
    private var dlCharset = 0
    var label: String? = null; private set
    private var labelToggle: Int? = null
    /** Slides being received, by TransportId: at most [MOT_IN_FLIGHT], the least recently touched dropped first. */
    private var lastXpadLen = 0
    private val mot = object : LinkedHashMap<Int, MotObject>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, MotObject>) = size > MOT_IN_FLIGHT
    }

    var pads = 0; private set
    var dlGroups = 0; private set
    var dlCrcBad = 0; private set
    var motGroups = 0; private set
    var motCrcBad = 0; private set
    var slides = 0; private set

    private class Assembler {
        private var buf: ByteArray? = null
        private var need = 0
        fun start(d: ByteArray, need: Int): ByteArray? { buf = d; this.need = need; return done() }
        fun cont(d: ByteArray): ByteArray? { buf = (buf ?: return null) + d; return done() }
        private fun done(): ByteArray? { val b = buf ?: return null; if (b.size < need) return null; buf = null; return b.copyOf(need) }
        fun reset() { buf = null }
    }

    /**
     * After a reception gap: drop half-received data groups and DLS segments. The DLS toggle has only two values,
     * so without this, segments of a message from before the gap can be stitched into a new one. The current
     * [label] stays (it's still what the station last said).
     */
    fun resetPartial() {
        dlAsm.reset(); motAsm.reset(); dlSegs.clear(); dlToggle = null; mot.clear(); lastXpadLen = 0
    }

    private class MotObject {
        val parts = mapOf(3 to HashMap<Int, ByteArray>(), 4 to HashMap<Int, ByteArray>())
        val last = HashMap<Int, Int>()
        var bytes = 0
    }

    /** Extract the PAD from a DAB+ AU (data_stream_element first) and process it. */
    fun feedAu(au: ByteArray) {
        if (au.size < 2 || au.u8(0) shr 5 != 4) return
        var n = au.u8(1)
        var st = 2
        if (n == 255) { if (au.size < 3) return; n = 255 + au.u8(2); st = 3 }
        val end = minOf(st + n, au.size)
        if (end - st >= 2) feedPad(au.copyOfRange(st, end - 2), au.copyOfRange(end - 2, end))
    }

    /**
     * One frame's PAD. [exact]: [xpadReversed] is exactly the X-PAD (DAB+: the DSE); for DAB (MP2) it's the end of the
     * frame, and a frame without CI list continues the previous subfield with a length implied by the previous frame
     * (4 bytes for short X-PAD, else the previous X-PAD's data length; EN 300 401 §7.4.2).
     */
    fun feedPad(xpadReversed: ByteArray, fpad: ByteArray, exact: Boolean = true) {
        pads++
        val ind = (fpad.u8(0) shr 4) and 3
        val ci = (fpad.u8(1) shr 1) and 1 == 1
        val x = xpadReversed.reversedArray()
        if (ind == 0 || x.isEmpty()) return
        val subfields = ArrayList<Pair<Int, ByteArray>>()
        if (ci) {
            var i = 0
            val cis = ArrayList<Pair<Int, Int>>()
            if (ind == 1) { cis += (x.u8(0) and 0x1F) to 3; i = 1 }       // short X-PAD
            else while (i < x.size && cis.size < 4) {                       // variable size X-PAD
                val app = x.u8(i) and 0x1F
                val len = XPAD_LEN[x.u8(i) shr 5]
                i++
                if (app == 0) break
                cis += app to len
            }
            var total = 0
            for ((app, len) in cis) {
                subfields += app to x.copyOfRange(minOf(i, x.size), minOf(i + len, x.size))
                i += len; total += len
            }
            lastXpadLen = if (ind == 1) 3 else total
        } else lastApp?.let {
            val n = when { exact -> x.size; ind == 1 -> 4; else -> lastXpadLen }
            if (n > 0) subfields += contType(it) to x.copyOfRange(0, minOf(n, x.size))
        }
        for ((app, data) in subfields) { subfield(app, data); lastApp = app }
    }

    private fun contType(app: Int) = when (app) { 2 -> 3; 12 -> 13; 14 -> 15; else -> app }

    private fun subfield(app: Int, d: ByteArray) {
        when {
            app == 1 && d.size >= 2 -> motLen = ((d.u8(0) and 0x3F) shl 8) or d.u8(1)
            app == 2 && d.size >= 2 -> {
                val b0 = d.u8(0)
                val need = if (b0 and 0x10 != 0) { if (b0 and 0x0F == 1) 4 else 2 + (d.u8(1) and 0x0F) + 1 + 2 } else 2 + (b0 and 0x0F) + 1 + 2
                dlAsm.start(d, need)?.let(::dlGroup)
            }
            app == 3 -> dlAsm.cont(d)?.let(::dlGroup)
            app == 12 && motLen != 0 -> motAsm.start(d, motLen)?.let(::motGroup)
            app == 13 -> motAsm.cont(d)?.let(::motGroup)
        }
    }

    private fun crcOk(dg: ByteArray) = dg.size >= 2 &&
        (Crc.crc16Ccitt(dg, 0, dg.size - 2) xor 0xFFFF) == ((dg.u8(dg.size - 2) shl 8) or dg.u8(dg.size - 1))

    private fun dlGroup(dg: ByteArray) {
        dlGroups++
        if (!crcOk(dg)) { dlCrcBad++; return }
        val b0 = dg.u8(0); val b1 = dg.u8(1)
        val toggle = b0 shr 7
        val first = (b0 shr 6) and 1 == 1
        val last = (b0 shr 5) and 1 == 1
        if ((b0 shr 4) and 1 == 1) {                                  // command
            when (b0 and 0x0F) {
                1 -> { label = null; onDls("") }                        // remove label
                2 -> dlPlus(toggle, dg.copyOfRange(2, dg.size - 2))
            }
            return
        }
        if (toggle != dlToggle) { dlToggle = toggle; dlSegs.clear() }
        val seg = if (first) 0 else (b1 shr 4) and 7
        if (first) dlCharset = b1 shr 4
        dlSegs[seg] = dg.copyOfRange(2, dg.size - 2) to last
        val lastSeg = dlSegs.entries.firstOrNull { it.value.second }?.key ?: return
        if ((0..lastSeg).all { it in dlSegs }) {
            val raw = (0..lastSeg).map { dlSegs.getValue(it).first }.reduce { a, b -> a + b }
            val text = DabText.decode(raw, charset = dlCharset)
            if (text != label) { label = text; labelToggle = toggle; onDls(text) }
        }
    }

    private fun dlPlus(toggle: Int, cmd: ByteArray) {
        if (cmd.isEmpty() || cmd.u8(0) shr 4 != 0) return             // CId 0 = DL Plus tags
        val itemToggle = (cmd.u8(0) shr 3) and 1
        val itemRunning = (cmd.u8(0) shr 2) and 1 == 1
        val nt = (cmd.u8(0) and 3) + 1
        val text = label ?: return
        if (toggle != labelToggle) return                             // tags refer to a label we don't have
        val tags = LinkedHashMap<String, String>()
        for (k in 0 until nt) {
            if (4 + 3 * k > cmd.size) break
            val ctype = cmd.u8(1 + 3 * k) and 0x7F
            val start = cmd.u8(2 + 3 * k) and 0x7F
            val len = (cmd.u8(3 + 3 * k) and 0x7F) + 1
            if (ctype == 0) continue
            tags[DLP_TYPES[ctype] ?: "TYPE$ctype"] = text.substring(minOf(start, text.length), minOf(start + len, text.length))
        }
        onDlPlus(tags, itemRunning, itemToggle)
    }

    private fun motGroup(dg: ByteArray) {
        motGroups++
        val b0 = dg.u8(0)
        val ext = b0 shr 7 == 1
        val crcf = (b0 shr 6) and 1 == 1
        val segf = (b0 shr 5) and 1 == 1
        val uaf = (b0 shr 4) and 1 == 1
        val type = b0 and 0x0F
        if (crcf && !crcOk(dg)) { motCrcBad++; return }
        var i = 2 + if (ext) 2 else 0
        var last = false
        var segNo = 0
        if (segf) {
            if (i + 2 > dg.size) return
            last = dg.u8(i) shr 7 == 1
            segNo = ((dg.u8(i) and 0x7F) shl 8) or dg.u8(i + 1)
            i += 2
        }
        var tid: Int? = null
        if (uaf) {
            if (i >= dg.size) return
            val li = dg.u8(i) and 0x0F
            if (dg.u8(i) and 0x10 != 0 && li >= 2 && i + 2 < dg.size) tid = (dg.u8(i + 1) shl 8) or dg.u8(i + 2)
            i += 1 + li
        }
        val end = dg.size - if (crcf) 2 else 0
        if (tid == null || (type != 3 && type != 4) || i + 2 > end || segNo >= MOT_MAX_SEGMENTS) return
        val o = mot.getOrPut(tid) { MotObject() }
        val seg = dg.copyOfRange(i + 2, end)                            // skip the segmentation header
        o.bytes += seg.size - (o.parts.getValue(type)[segNo]?.size ?: 0)
        if (o.bytes > MOT_MAX_BYTES) { mot.remove(tid); return }         // no slide is that big: drop it
        o.parts.getValue(type)[segNo] = seg
        if (last) o.last[type] = segNo
        motTry(tid, o)
    }

    private fun motTry(tid: Int, o: MotObject) {
        for (t in intArrayOf(3, 4)) {
            val l = o.last[t] ?: return
            if ((0..l).any { it !in o.parts.getValue(t) }) return
        }
        fun join(t: Int) = java.io.ByteArrayOutputStream(o.bytes).also { out ->
            for (k in 0..o.last.getValue(t)) out.write(o.parts.getValue(t).getValue(k)) }.toByteArray()
        val hdr = join(3)
        val body = join(4)
        if (hdr.size < 7) return
        val bodySize = (hdr.u8(0) shl 20) or (hdr.u8(1) shl 12) or (hdr.u8(2) shl 4) or (hdr.u8(3) shr 4)
        val ctype = (hdr.u8(5) shr 1) and 0x3F
        val csub = ((hdr.u8(5) and 1) shl 8) or hdr.u8(6)
        val params = motParams(hdr, 7)
        mot.remove(tid)
        if (body.size != bodySize) return
        slides++
        onSlide(Slide(tid, ctype, csub, params, body))
    }

    private fun motParams(b: ByteArray, from: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = from
        while (i < b.size) {
            val pli = b.u8(i) shr 6
            val pid = b.u8(i) and 0x3F
            i++
            val len = when (pli) {
                0 -> 0; 1 -> 1; 2 -> 4
                else -> {
                    if (i >= b.size) break
                    if (b.u8(i) and 0x80 != 0) { if (i + 1 >= b.size) break; val l = ((b.u8(i) and 0x7F) shl 8) or b.u8(i + 1); i += 2; l }
                    else { val l = b.u8(i) and 0x7F; i += 1; l }
                }
            }
            val v = b.copyOfRange(minOf(i, b.size), minOf(i + len, b.size))
            i += len
            when {
                pid == 0x0C && v.isNotEmpty() -> out["ContentName"] = DabText.decode(v, 1, v.size, v.u8(0) shr 4)
                pid == 0x05 -> out["TriggerTime"] = v.joinToString("") { "%02x".format(it) }
                pid == 0x25 && v.size >= 2 -> out["CategoryID/SlideID"] = "${v.u8(0)},${v.u8(1)}"
                pid == 0x27 -> out["ClickThroughURL"] = String(v, Charsets.ISO_8859_1)
                pid == 0x28 -> out["AlternativeLocationURL"] = String(v, Charsets.ISO_8859_1)
                else -> out["param_0x%02x".format(pid)] = v.joinToString("") { "%02x".format(it) }
            }
        }
        return out
    }

    companion object {
        /** Bounds on slideshow reassembly (a corrupt or hostile stream must not fill the memory). */
        const val MOT_IN_FLIGHT = 4
        const val MOT_MAX_SEGMENTS = 1024
        const val MOT_MAX_BYTES = 512 * 1024
        private val XPAD_LEN = intArrayOf(4, 6, 8, 12, 16, 24, 32, 48)
        val DLP_TYPES = mapOf(
            1 to "ITEM.TITLE", 2 to "ITEM.ALBUM", 3 to "ITEM.TRACKNUMBER", 4 to "ITEM.ARTIST", 5 to "ITEM.COMPOSITION",
            6 to "ITEM.MOVEMENT", 7 to "ITEM.CONDUCTOR", 8 to "ITEM.COMPOSER", 9 to "ITEM.BAND", 10 to "ITEM.COMMENT",
            11 to "ITEM.GENRE", 12 to "INFO.NEWS", 13 to "INFO.NEWS.LOCAL", 14 to "INFO.STOCKMARKET", 15 to "INFO.SPORT",
            16 to "INFO.LOTTERY", 17 to "INFO.HOROSCOPE", 18 to "INFO.DAILY_DIVERSION", 19 to "INFO.HEALTH",
            20 to "INFO.EVENT", 21 to "INFO.SCENE", 22 to "INFO.CINEMA", 23 to "INFO.STUPIDITY.MACHINE",
            24 to "INFO.DATE_TIME", 25 to "INFO.WEATHER", 26 to "INFO.TRAFFIC", 27 to "INFO.ALARM",
            28 to "INFO.ADVERTISEMENT", 29 to "INFO.URL", 30 to "INFO.OTHER", 31 to "STATIONNAME.SHORT",
            32 to "STATIONNAME.LONG", 33 to "PROGRAMME.NOW", 34 to "PROGRAMME.NEXT", 35 to "PROGRAMME.PART",
            36 to "PROGRAMME.HOST", 37 to "PROGRAMME.EDITORIAL_STAFF", 38 to "PROGRAMME.FREQUENCY",
            39 to "PROGRAMME.HOMEPAGE", 40 to "PROGRAMME.SUBCHANNEL", 41 to "PHONE.HOTLINE", 42 to "PHONE.STUDIO",
            43 to "PHONE.OTHER", 44 to "SMS.STUDIO", 45 to "SMS.OTHER", 46 to "EMAIL.HOTLINE", 47 to "EMAIL.STUDIO",
            48 to "EMAIL.OTHER", 49 to "MMS.OTHER", 50 to "CHAT", 51 to "CHAT.CENTER", 52 to "VOTE.QUESTION",
            53 to "VOTE.CENTRE", 59 to "PRIVATE.1", 60 to "PRIVATE.2", 61 to "PRIVATE.3", 62 to "DESCRIPTOR.PLACE",
            63 to "DESCRIPTOR.APPOINTMENT", 64 to "DESCRIPTOR.IDENTIFIER", 65 to "DESCRIPTOR.PURCHASE",
            66 to "DESCRIPTOR.GET_DATA",
        )
    }
}
