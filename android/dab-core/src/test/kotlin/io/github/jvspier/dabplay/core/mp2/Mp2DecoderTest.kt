package io.github.jvspier.dabplay.core.mp2

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden tests against ffmpeg's decoder on synthetic vectors (tools/gen_mp2_vectors.sh: tones + noise, no recordings).
 * Float arithmetic in both decoders, so allow ±1 LSB of rounding difference; the SNR shows it's the same decoding.
 */
class Mp2DecoderTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/mp2/$name")!!.readBytes()

    private data class Result(val frames: Int, val maxDiff: Int, val snrDb: Double, val rate: Int, val channels: Int)

    private fun decodeAll(name: String): Result {
        val mp2 = res("$name.mp2")
        val ref = res("$name.s16").let { b -> ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort() } }
        val dec = Mp2Decoder()
        val pcm = ArrayList<Short>()
        var off = 0; var rate = 0; var ch = 0
        while (off < mp2.size) {
            val h = Mp2Header.parse(mp2, off) ?: error("$name: no header at $off")
            val p = dec.decode(mp2.copyOfRange(off, off + h.frameBytes)) ?: error("$name: frame at $off didn't decode")
            p.samples.forEach { pcm += it }; rate = p.sampleRate; ch = p.channels
            off += h.frameBytes
        }
        assertEquals(ref.size, pcm.size, "$name: sample count")
        var maxDiff = 0; var sig = 0.0; var err = 0.0
        for (i in ref.indices) {
            val d = pcm[i] - ref[i]
            maxDiff = maxOf(maxDiff, abs(d)); sig += ref[i].toDouble() * ref[i]; err += d.toDouble() * d
        }
        return Result(dec.frames, maxDiff, if (err == 0.0) 200.0 else 10 * log10(sig / err), rate, ch)
    }

    private fun check(name: String, rate: Int, channels: Int) {
        val r = decodeAll(name)
        println("$name: ${r.frames} frames, ${r.rate} Hz × ${r.channels}, max diff ${r.maxDiff} LSB, SNR ${"%.1f".format(r.snrDb)} dB")
        assertEquals(rate, r.rate); assertEquals(channels, r.channels)
        assertTrue(r.maxDiff <= 1, "$name: max diff ${r.maxDiff}")
    }

    @Test fun stereo48kHzTableB2a() = check("s48_192k", 48000, 2)
    @Test fun mono48kHz() = check("m48_64k", 48000, 1)
    @Test fun lowRate48kHzTableB2c() = check("s48_64k", 48000, 2)
    @Test fun halfRate24kHzStereo() = check("s24_64k", 24000, 2)
    @Test fun halfRate24kHzMono() = check("m24_32k", 24000, 1)
    @Test fun jointStereo() = check("j48_128k", 48000, 2)
    @Test fun crcProtected() = check("c48_160k", 48000, 2)

    @Test fun corruptFrameFailsTheCrc() {
        val mp2 = res("c48_160k.mp2")
        val h = Mp2Header.parse(mp2)!!
        val frame = mp2.copyOfRange(0, h.frameBytes)
        frame[8] = (frame[8].toInt() xor 0x10).toByte()                      // a bit in the bit allocation
        val dec = Mp2Decoder()
        assertEquals(null, dec.decode(frame))
        assertEquals(1, dec.crcErrors)
    }
}
