package io.github.jvspier.dabplay.engine

import io.github.jvspier.dabplay.core.Crc
import io.github.jvspier.dabplay.core.dabplus.ReedSolomon

/** A DAB+ service on an ensemble, for building test broadcasts: 80 kbit/s, EEP 3-A. */
data class TestService(val sid: Long, val label: String, val subchannel: Int, val kbps: Int = 80)

/**
 * Builds broadcast data as a DAB dongle would deliver it, so the engine runs end to end on the JVM without captures:
 * the FIC of an ensemble (FIBs with FIG 0/0, 0/1, 0/2, 1/0, 1/1, plus optional announcement FIGs) and valid DAB+
 * superframes (firecode, AU CRCs, RS(120,110) parity) with silent AUs.
 */
object Broadcast {
    private val rs = ReedSolomon()

    /** The FIC of one ensemble: whole 32-byte FIBs with valid CRCs. [extraFig0] go into a FIB of their own. */
    fun fic(eid: Int, label: String, services: List<TestService>, extraFig0: List<ByteArray> = emptyList()): ByteArray {
        val fibs = ArrayList<ByteArray>()
        // FIG 0/0 (EId, no change, CIF count 0) and FIG 0/1 (long form, EEP 3-A: 6 CU per 8 kbit/s)
        val subch = services.flatMap { s ->
            val size = s.kbps / 8 * 6
            listOf(s.subchannel shl 2, 0, 0x80 or (0 shl 4) or (2 shl 2) or (size shr 8), size and 0xFF)
        }
        fibs += fib(fig(0, bytes(0x00, eid shr 8, eid and 0xFF, 0, 0)), fig(0, bytes(0x01) + bytes(*subch.toIntArray())))
        fibs += fib(fig(1, bytes(0x00, eid shr 8, eid and 0xFF) + label16(label) + bytes(0xFF, 0x00)))
        for (s in services) {
            // FIG 0/2: SId (16 bit), one component: TMId 00 + ASCTy 63 (DAB+), sub-channel, primary
            fibs += fib(fig(0, bytes(0x02, (s.sid shr 8).toInt() and 0xFF, s.sid.toInt() and 0xFF, 0x01, 0x3F, (s.subchannel shl 2) or 0x02)))
            fibs += fib(fig(1, bytes(0x01, (s.sid shr 8).toInt() and 0xFF, s.sid.toInt() and 0xFF) + label16(s.label) + bytes(0xFF, 0x00)))
        }
        if (extraFig0.isNotEmpty()) fibs += fib(*extraFig0.map { fig(0, it) }.toTypedArray())
        return fibs.reduce { a, b -> a + b }
    }

    /** FIG 0/18: [sid] takes part in traffic announcements (ASu 0x0002) in [cluster]. */
    fun trafficSupport(sid: Long, cluster: Int) =
        bytes(0x12, (sid shr 8).toInt() and 0xFF, sid.toInt() and 0xFF, 0x00, 0x02, 0x01, cluster)

    /** FIG 0/19: [cluster] switches to [subchannel] for traffic (ASw 0x0002), or is idle (asw 0). */
    fun announcementSwitch(cluster: Int, subchannel: Int, asw: Int = 0x0002) =
        bytes(0x13, cluster, asw shr 8, asw and 0xFF, subchannel and 0x3F)

    /** A valid DAB+ superframe: no DAC, SBR (2 AUs per superframe, 32 kHz out), mono, silent AU bodies. */
    fun superframe(kbps: Int): ByteArray {
        val s = kbps / 8
        val n = 110 * s
        val data = ByteArray(n)
        data[2] = 0x20                                          // dac 0, sbr 1: 2 AUs
        val mid = 5 + (n - 5) / 2                               // AU 0 at 5 (2 AUs), AU 1 from mid
        data[3] = (mid shr 4).toByte(); data[4] = ((mid and 0x0F) shl 4).toByte()
        for ((a, b) in listOf(5 to mid, mid to n)) {
            val crc = Crc.crc16Ccitt(data, a, b - 2) xor 0xFFFF
            data[b - 2] = (crc shr 8).toByte(); data[b - 1] = crc.toByte()
        }
        val fc = Crc.firecode(data, 2)
        data[0] = (fc shr 8).toByte(); data[1] = fc.toByte()
        // RS(120,110) per column; the decoder reads column c as bytes c, c+s, c+2s, …
        val sf = data.copyOf(120 * s)
        for (col in 0 until s) {
            val cw = rs.encode(ByteArray(110) { data[col + it * s] })
            for (r in 110 until 120) sf[col + r * s] = cw[r]
        }
        return sf
    }

    private fun fig(type: Int, body: ByteArray) = byteArrayOf(((type shl 5) or body.size).toByte()) + body
    private fun fib(vararg figs: ByteArray): ByteArray {
        val b = ByteArray(32) { 0xFF.toByte() }
        var i = 0
        for (f in figs) { require(i + f.size <= 30) { "FIB overflow" }; f.copyInto(b, i); i += f.size }
        val crc = Crc.crc16Ccitt(b, 0, 30) xor 0xFFFF
        b[30] = (crc shr 8).toByte(); b[31] = crc.toByte()
        return b
    }
    private fun label16(s: String) = s.padEnd(16).take(16).toByteArray(Charsets.ISO_8859_1)
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
