package io.github.jvspier.dabplay.core.mp2

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/** An MPEG-1/2 Layer II frame header (ISO/IEC 11172-3 §2.4.2.3; 13818-3 for the half-rate "LSF" variant DAB uses at 24 kHz). */
data class Mp2Header(
    val lsf: Boolean, val crc: Boolean, val bitrateKbps: Int, val sampleRate: Int,
    val padding: Boolean, val mode: Int, val modeExt: Int,
) {
    val channels get() = if (mode == MONO) 1 else 2
    /** Frame length in bytes: 1152 samples, 144 · bitrate / fs (+ 1 padding byte). DAB frames never pad. */
    val frameBytes get() = 144 * bitrateKbps * 1000 / sampleRate + if (padding) 1 else 0

    companion object {
        const val STEREO = 0; const val JOINT = 1; const val DUAL = 2; const val MONO = 3
        private val BR_MPEG1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        private val BR_LSF = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

        /** Header at [off], or null if it isn't a valid Layer II header (sync, layer, bitrate, sample rate). */
        fun parse(b: ByteArray, off: Int = 0): Mp2Header? {
            if (off + 4 > b.size) return null
            val h = ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
            if (h ushr 21 != 0x7FF) return null
            val ver = (h ushr 19) and 3                       // 3 = MPEG-1, 2 = MPEG-2 (LSF)
            if (ver != 3 && ver != 2) return null
            if ((h ushr 17) and 3 != 2) return null           // layer bits '10' = Layer II
            val bri = (h ushr 12) and 15; val sfi = (h ushr 10) and 3
            if (bri == 0 || bri == 15 || sfi == 3) return null // free format / invalid
            val lsf = ver == 2
            val fs = (if (lsf) intArrayOf(22050, 24000, 16000) else intArrayOf(44100, 48000, 32000))[sfi]
            return Mp2Header(lsf, (h ushr 16) and 1 == 0, (if (lsf) BR_LSF else BR_MPEG1)[bri], fs,
                (h ushr 9) and 1 == 1, (h ushr 6) and 3, (h ushr 4) and 3)
        }
    }
}

/** Decoded PCM of one frame: 1152 samples per channel, interleaved when stereo. */
class Mp2Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

/**
 * MPEG-1/2 Layer II decoder, as DAB (not DAB+) uses it: 48 kHz (tables B.2a/B.2c) and the 24 kHz half rate (LSF table),
 * mono / stereo / dual / joint stereo, header CRC checked when present. The reference ISO/IEC 11172-3 algorithm with the
 * polyphase synthesis filter of §2.4.3.2.2; verified against ffmpeg on synthetic test vectors (tools/gen_mp2_vectors.sh).
 * Not thread-safe: one instance per stream (the synthesis filter keeps state between frames).
 */
class Mp2Decoder {
    private val synth = arrayOf(Synthesis(), Synthesis())
    var frames = 0; private set
    var crcErrors = 0; private set

    /** Decodes one whole frame; null if the header is invalid, the frame is short, or its CRC fails. */
    fun decode(frame: ByteArray): Mp2Pcm? {
        val h = Mp2Header.parse(frame) ?: return null
        if (frame.size < h.frameBytes) return null
        val nch = h.channels
        val t = Tables.select(h)
        val bound = if (h.mode == Mp2Header.JOINT) minOf((h.modeExt + 1) * 4, t.sblimit) else t.sblimit
        val r = Bits(frame, 32)
        val crcRead = if (h.crc) r.read(16) else -1

        val alloc = Array(2) { IntArray(32) }
        for (sb in 0 until t.sblimit) {
            val nb = t.nbal[sb]
            if (sb < bound) for (ch in 0 until nch) alloc[ch][sb] = r.read(nb)
            else { val a = r.read(nb); alloc[0][sb] = a; alloc[1][sb] = a }
        }
        val scfsi = Array(2) { IntArray(32) }
        for (sb in 0 until t.sblimit) for (ch in 0 until nch) if (alloc[ch][sb] != 0) scfsi[ch][sb] = r.read(2)
        if (h.crc) {
            // CRC-16 (0x8005, init 0xFFFF) over header bits 16..31 plus allocation and scfsi (§2.4.3.1)
            val c = Crc16().apply { update(frame, 16, 16); update(frame, 48, r.pos - 48) }.value
            if (c != crcRead) { crcErrors++; return null }
        }
        val scf = Array(2) { Array(32) { IntArray(3) } }
        for (sb in 0 until t.sblimit) for (ch in 0 until nch) if (alloc[ch][sb] != 0) {
            val s = scf[ch][sb]
            when (scfsi[ch][sb]) {
                0 -> { s[0] = r.read(6); s[1] = r.read(6); s[2] = r.read(6) }
                1 -> { s[0] = r.read(6); s[1] = s[0]; s[2] = r.read(6) }
                2 -> { s[0] = r.read(6); s[1] = s[0]; s[2] = s[0] }
                else -> { s[0] = r.read(6); s[1] = r.read(6); s[2] = s[1] }
            }
        }

        val out = ShortArray(1152 * nch)
        val sub = Array(2) { FloatArray(32) }
        val frac = FloatArray(3)
        val q = Array(2) { Array(3) { FloatArray(32) } }             // [ch][sample of triplet][sb]
        for (gr in 0 until 12) {
            val part = gr / 4
            for (sb in 0 until 32) {
                if (sb >= t.sblimit) { for (ch in 0 until 2) for (k in 0 until 3) q[ch][k][sb] = 0f; continue }
                val chans = if (sb < bound) nch else 1
                for (ch in 0 until chans) {
                    val a = alloc[ch][sb]
                    if (a == 0) { for (k in 0 until 3) { q[ch][k][sb] = 0f; if (sb >= bound) q[1][k][sb] = 0f }; continue }
                    readTriplet(r, t.levels[sb][a], frac)
                    if (sb < bound) for (k in 0 until 3) q[ch][k][sb] = frac[k] * SCALE[scf[ch][sb][part]]
                    else for (k in 0 until 3) {                         // joint stereo: shared samples, own scalefactors
                        q[0][k][sb] = frac[k] * SCALE[scf[0][sb][part]]
                        q[1][k][sb] = frac[k] * SCALE[scf[1][sb][part]]
                    }
                }
            }
            for (k in 0 until 3) for (ch in 0 until nch) {
                q[ch][k].copyInto(sub[ch])
                synth[ch].run(sub[ch], out, (gr * 3 + k) * 32 * nch + ch, nch)
            }
        }
        frames++
        return Mp2Pcm(out, h.sampleRate, nch)
    }

    /** Three dequantized fractions (§2.4.3.3.4): grouped codes for 3/5/9 levels, else 3 separate codes. */
    private fun readTriplet(r: Bits, levels: Int, f: FloatArray) {
        when (levels) {
            3, 5, 9 -> {
                var c = r.read(when (levels) { 3 -> 5; 5 -> 7; else -> 10 })
                for (k in 0 until 3) { f[k] = (2 * (c % levels) - levels + 1).toFloat() / levels; c /= levels }
            }
            else -> {
                val nb = 32 - Integer.numberOfLeadingZeros(levels)     // levels = 2^nb − 1
                for (k in 0 until 3) f[k] = (2 * r.read(nb) - levels + 1).toFloat() / levels
            }
        }
    }

    private class Bits(val b: ByteArray, var pos: Int) {
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = pos ushr 3
                val bit = if (byte < b.size) (b[byte].toInt() ushr (7 - (pos and 7))) and 1 else 0
                v = (v shl 1) or bit; pos++
            }
            return v
        }
    }

    private class Crc16 {
        var value = 0xFFFF; private set
        fun update(b: ByteArray, fromBit: Int, bits: Int) {
            for (i in fromBit until fromBit + bits) {
                val bit = (b[i ushr 3].toInt() ushr (7 - (i and 7))) and 1
                val top = (value ushr 15) and 1
                value = (value shl 1) and 0xFFFF
                if (top xor bit == 1) value = value xor 0x8005
            }
        }
    }

    /** ISO/IEC 11172-3 polyphase synthesis: matrixing into V, then the 512-tap window D. */
    private class Synthesis {
        private val v = FloatArray(1024)
        private var off = 0

        fun run(s: FloatArray, out: ShortArray, start: Int, stride: Int) {
            off = (off - 64) and 1023
            for (i in 0 until 64) {
                var acc = 0f
                val n = i * 32
                for (k in 0 until 32) acc += N[n + k] * s[k]
                v[off + i] = acc
            }
            for (j in 0 until 32) {
                var acc = 0f
                for (i in 0 until 8) {
                    // U[i·64 + j] = V[i·128 + j], U[i·64 + 32 + j] = V[i·128 + 96 + j]
                    acc += v[(off + i * 128 + j) and 1023] * D[i * 64 + j]
                    acc += v[(off + i * 128 + 96 + j) and 1023] * D[i * 64 + 32 + j]
                }
                out[start + j * stride] = (acc * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }

    private companion object {
        /** Scalefactors 2^(1 − i/3), i = 0..62 (Table B.1); index 63 is invalid, treated as silence. */
        val SCALE = FloatArray(64) { if (it == 63) 0f else 2.0.pow(1.0 - it / 3.0).toFloat() }

        /** Matrixing coefficients N[i][k] = cos((16 + i)(2k + 1)π / 64). */
        val N = FloatArray(64 * 32) { val i = it / 32; val k = it % 32; cos((16 + i) * (2 * k + 1) * PI / 64).toFloat() }

        /** The synthesis window D[0..511] (Table B.3), built from its first 257 values and the window's symmetry. */
        val D = FloatArray(512).also { d ->
            for (i in 0..256) {
                val w = Mp2Tables.WINDOW[i] / 65536f
                d[i] = w
                if (i != 0 && i != 256) d[512 - i] = if (i % 64 != 0) -w else w
            }
        }
    }
}

/** Layer II bit allocation tables: per subband the allocation width and, per allocation value, the quantizer levels. */
internal class Tables(val sblimit: Int, val nbal: IntArray, val levels: Array<IntArray>) {
    companion object {
        private val A = intArrayOf(0, 3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535)
        private val B = intArrayOf(0, 3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 65535)
        private val C = intArrayOf(0, 3, 5, 7, 9, 15, 31, 65535)
        private val DD = intArrayOf(0, 3, 5, 65535)
        private val E = intArrayOf(0, 3, 5, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767)
        private val F = intArrayOf(0, 3, 5, 9, 15, 31, 63, 127)
        private val G = intArrayOf(0, 3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383)
        private val I = intArrayOf(0, 3, 5, 9)

        private fun table(vararg spans: Pair<Int, IntArray>): Tables {
            val lv = spans.flatMap { (n, l) -> List(n) { l } }
            return Tables(lv.size, IntArray(lv.size) { Integer.numberOfTrailingZeros(lv[it].size) }, lv.toTypedArray())
        }

        /** 11172-3 Table B.2a: 27 subbands (48 kHz ≥ 56 kbit/s per channel, or 56..80 at any rate). */
        val B2A = table(3 to A, 8 to B, 12 to C, 4 to DD)
        /** Table B.2b: 30 subbands (44.1/32 kHz ≥ 96 kbit/s per channel; not used in DAB, kept for completeness). */
        val B2B = table(3 to A, 8 to B, 12 to C, 7 to DD)
        /** Table B.2c: 8 subbands (≤ 48 kbit/s per channel, not 32 kHz). */
        val B2C = table(2 to E, 6 to F)
        /** Table B.2d: 12 subbands (32 kHz ≤ 48 kbit/s per channel). */
        val B2D = table(2 to E, 10 to F)
        /** 13818-3 Table B.1: MPEG-2 half rate (LSF), 30 subbands. */
        val LSF = table(4 to G, 7 to F, 19 to I)

        /** Table choice by sample rate and bitrate per channel (11172-3 Annex B, the usual decoder rule). */
        fun select(h: Mp2Header): Tables {
            if (h.lsf) return LSF
            val perCh = h.bitrateKbps / h.channels
            return when {
                (h.sampleRate == 48000 && perCh >= 56) || perCh in 56..80 -> B2A
                h.sampleRate != 48000 && perCh >= 96 -> B2B
                h.sampleRate != 32000 && perCh <= 48 -> B2C
                else -> B2D
            }
        }
    }
}
