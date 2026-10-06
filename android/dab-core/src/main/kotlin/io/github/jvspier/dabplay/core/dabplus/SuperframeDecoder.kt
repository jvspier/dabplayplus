package io.github.jvspier.dabplay.core.dabplus

import io.github.jvspier.dabplay.core.Crc
import io.github.jvspier.dabplay.core.u8

/** Audio format of a DAB+ sub-channel, from the superframe header. */
data class AudioFormat(val outputRate: Int, val coreRate: Int, val sbr: Boolean, val ps: Boolean, val stereo: Boolean, val ausPerSuperframe: Int)

/** One AAC access unit (960-sample frame) plus the AudioSpecificConfig to decode it with (MediaCodec csd-0). */
class AccessUnit(val asc: ByteArray, val data: ByteArray, val format: AudioFormat)

/**
 * DAB+ superframe decoding (ETSI TS 102 563), port of tools/dabplus.py: firecode sync, RS(120,110) per column,
 * AU split with CRC check. Input is the raw sub-channel stream; output is AUs with their ASC.
 */
class SuperframeDecoder(bitrateKbps: Int) {
    init { require(bitrateKbps in MIN_KBPS..MAX_KBPS) { "bitrate $bitrateKbps kbit/s out of range" } }

    companion object {
        /** Sub-channel bitrates the dongle can stream (bytes per CIF 24..3384). */
        const val MIN_KBPS = 8
        const val MAX_KBPS = 1128
    }
    private val s = bitrateKbps / 8                  // RS columns
    private val sfLen = 120 * s                      // superframe = 5 logical frames of 24*s bytes
    private val rs = ReedSolomon()
    private var buf = ByteArray(0)
    private var synced = false

    var superframes = 0; private set
    var rsFixed = 0; private set
    var rsFail = 0; private set
    var ausOk = 0; private set
    var ausBad = 0; private set
    var resyncs = 0; private set
    var format: AudioFormat? = null; private set

    private fun fireOk(b: ByteArray, o: Int) = Crc.firecode(b, o + 2) == ((b.u8(o) shl 8) or b.u8(o + 1))

    private var unsyncedBytes = 0                    // skipped while hunting for the next superframe

    /**
     * Feed raw sub-channel bytes; [out] receives every CRC-valid access unit, [lost] the number of AUs that didn't
     * make it (a failed AU CRC, or whole superframes skipped while resynchronising), so the player can keep time.
     */
    fun feed(data: ByteArray, lost: (Int) -> Unit = {}, out: (AccessUnit) -> Unit) {
        buf += data
        var pos = 0
        while (true) {
            if (!synced) {
                val limit = maxOf(0, buf.size - pos - 11)
                var found = -1
                for (off in 0 until limit) if (fireOk(buf, pos + off)) { found = off; break }
                if (found < 0) { pos += limit; skipped(limit, lost); break }
                if (found > 0) resyncs++
                pos += found
                skipped(found, lost)
                synced = true
                // the rest of a superframe's worth, rounded: the time on air between the last report and the new lock
                if (unsyncedBytes * 2 >= sfLen) reportLost(lost)
                unsyncedBytes = 0
            }
            if (buf.size - pos < sfLen) break
            val sf = buf.copyOfRange(pos, pos + sfLen)
            if (!fireOk(sf, 0)) {
                correct(sf)                            // the firecode itself may have been hit by errors
                if (!fireOk(sf, 0)) { synced = false; pos += 1; skipped(1, lost); continue }
            } else correct(sf)
            pos += sfLen
            superframes++
            accessUnits(sf, lost, out)
        }
        buf = buf.copyOfRange(pos, buf.size)
    }

    /**
     * Bytes that went by while hunting for the next superframe: every superframe's worth is reported as lost AUs right
     * away, so the player fills the gap as it happens (not one long silence after the signal is back). Nothing before
     * the first superframe: there's no format, and no audio yet to keep in time with.
     */
    private fun skipped(n: Int, lost: (Int) -> Unit) {
        unsyncedBytes += n
        while (unsyncedBytes >= sfLen) { unsyncedBytes -= sfLen; reportLost(lost) }
    }

    private fun reportLost(lost: (Int) -> Unit, count: Boolean = true) {
        val per = format?.ausPerSuperframe ?: return
        if (count) ausBad += per
        lost(per)
    }

    private fun correct(sf: ByteArray) {
        val cw = ByteArray(120)
        for (col in 0 until s) {
            for (r in 0 until 120) cw[r] = sf[col + r * s]
            if (rs.check(cw)) continue
            if (rs.decode(cw) >= 0) {
                for (r in 0 until 120) sf[col + r * s] = cw[r]
                rsFixed++
            } else rsFail++
        }
    }

    private fun accessUnits(sf: ByteArray, lost: (Int) -> Unit, out: (AccessUnit) -> Unit) {
        val f = sf.u8(2)
        val dac = (f shr 6) and 1
        val sbr = (f shr 5) and 1
        val stereo = (f shr 4) and 1
        val ps = (f shr 3) and 1
        val nAu = when (dac * 2 + sbr) { 0 -> 4; 1 -> 2; 2 -> 6; else -> 3 }
        var bits = 0L
        for (k in 3 until 11) bits = (bits shl 8) or sf.u8(k).toLong()
        val starts = IntArray(nAu + 1)
        starts[0] = when (nAu) { 2 -> 5; 3 -> 6; 4 -> 8; else -> 11 }
        for (k in 0 until nAu - 1) starts[k + 1] = ((bits ushr (64 - 12 * (k + 1))) and 0xFFF).toInt()
        starts[nAu] = 110 * s
        val outRate = if (dac == 1) 48000 else 32000
        val core = if (sbr == 1) outRate / 2 else outRate
        val fmt = AudioFormat(outRate, core, sbr == 1, ps == 1, stereo == 1, nAu)
        val asc = Asc.build(core, outRate, sbr == 1, ps == 1, stereo == 1)
        val aus = (0 until nAu).map { k ->
            val a = starts[k]; val b = starts[k + 1]
            if (!(a < b && b <= 110 * s) || b - a < 3) null
            else if (Crc.crc16Ccitt(sf, a, b - 2) xor 0xFFFF != ((sf.u8(b - 2) shl 8) or sf.u8(b - 1))) null
            else sf.copyOfRange(a, b - 2)
        }
        val good = aus.count { it != null }
        ausOk += good; ausBad += nAu - good
        // a superframe with no valid AU may be noise that passed the firecode by chance: its header can't be trusted,
        // so it neither sets the format nor decides how much is lost (one superframe's worth, in the known format)
        if (good == 0) { reportLost(lost, count = false); return }
        format = fmt
        for (au in aus) if (au != null) out(AccessUnit(asc, au, fmt)) else lost(1)
    }
}

/** AudioSpecificConfig for DAB+ AUs, built as welle.io/dablin do: AAC-LC core, 960-sample frames, SBR/PS sync extensions. */
object Asc {
    private val SR_INDEX = mapOf(48000 to 3, 32000 to 5, 24000 to 6, 16000 to 8)

    fun build(coreRate: Int, outputRate: Int, sbr: Boolean, ps: Boolean, stereo: Boolean): ByteArray {
        val w = BitWriter()
        w.put(2, 5)                                   // AOT AAC LC
        w.put(SR_INDEX.getValue(coreRate), 4)
        w.put(if (stereo && !ps) 2 else 1, 4)
        w.put(1, 1)                                   // frameLengthFlag: 960 samples
        w.put(0, 1)                                   // dependsOnCoreCoder
        w.put(0, 1)                                   // extensionFlag
        if (sbr) {
            w.put(0x2B7, 11); w.put(5, 5); w.put(1, 1) // sync extension, AOT SBR, sbrPresentFlag
            w.put(SR_INDEX.getValue(outputRate), 4)
            if (ps) { w.put(0x548, 11); w.put(1, 1) } // psPresentFlag
        }
        return w.bytes()
    }

    private class BitWriter {
        private val bits = ArrayList<Int>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits += (v shr i) and 1 }
        fun bytes() = ByteArray((bits.size + 7) / 8) { i -> var b = 0; for (k in 0 until 8) b = (b shl 1) or (bits.getOrNull(i * 8 + k) ?: 0); b.toByte() }
    }

}
