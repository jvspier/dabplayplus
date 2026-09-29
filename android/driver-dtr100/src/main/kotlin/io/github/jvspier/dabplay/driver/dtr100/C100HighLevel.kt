package io.github.jvspier.dabplay.driver.dtr100

import io.github.jvspier.dabplay.tuner.RawTuner
import io.github.jvspier.dabplay.tuner.SignalReading
import io.github.jvspier.dabplay.tuner.Transport
import io.github.jvspier.dabplay.tuner.Tuned
import java.io.IOException

/**
 * The YHX high-level command set of the "C100 Dual Antenna" (docs/protocol-c100-highlevel.md). The MCU drives the chip
 * itself; the host tunes, starts a sub-channel and reads raw FIC and MSC, which dab-core decodes as usual.
 *
 * **Untested on hardware**: written from the traffic of YHX's library as DABdream+ drives it (static analysis), with
 * unit tests against that description. Each command is one OUT on EP 0x02 and one fixed-length IN on EP 0x82 whose
 * byte 0 is the command | 0x80; multi-byte fields are little-endian.
 */
class C100HighLevel private constructor(
    private val t: Transport,
    private val fw: Int,
    private val dual: Boolean,
    private val sleep: (Long) -> Unit,
    private val clockMs: () -> Long,
) : RawTuner {
    override val description = "C100 high level${if (dual) " (dual antenna)" else ""}, firmware level $fw"

    // from the last 0x46 status reply
    private var mscAvail = 0
    private var ficAvail = 0
    private var sig1: Int? = null
    private var statusAt = Long.MIN_VALUE / 2
    private var cif = 0                                    // bytes per 24 ms CIF of the started sub-channel (bitrate × 3)
    private var tunedKhz = 0

    override fun tune(khz: Int, retries: Boolean): Tuned {
        // DABdream: one tune outside a scan; in a scan up to 10, 20 ms apart
        val attempts = if (retries) 10 else 1
        var reply = 0
        for (i in 1..attempts) {
            streamOff()
            // lock = reply byte 1 == 1; a failed or garbled reply is "no lock" (YHX's library returns -1 → 0xFF)
            reply = try { cmd(le(0x48, 0, khz, 4), 5, readTimeout = 2000).u(1) } catch (e: IOException) { 0xFF }
            if (reply == 1) break
            if (i < attempts) sleep(20)
        }
        val locked = reply == 1
        tunedKhz = if (locked) khz else 0
        if (locked) runCatching { status() }             // bars right away, from the first status
        return Tuned(locked, if (locked) signal?.bars ?: 0 else 0, "tune reply byte 1 = $reply")
    }

    override fun ficRead(): ByteArray? {
        if (ficAvail < FIC_BYTES && clockMs() - statusAt >= MIN_POLL_MS) status()
        if (ficAvail < FIC_BYTES) return null
        ficAvail -= FIC_BYTES
        val r = cmd(le(0x44, 0, FIC_BYTES, 2), FIC_BYTES + 4, readTimeout = 2000)
        if (r.u16(2) != FIC_BYTES) return null            // length field must match, as in YHX's library
        return r.copyOfRange(4, 4 + FIC_BYTES)             // 12 FIBs = one 96 ms transmission frame
    }

    override fun startAudio(subchannel: Int, bitrateKbps: Int) {
        streamOff()
        cif = bitrateKbps * 3
        // 49 00, kHz u32, bitrate u16, sub-channel u8, 01 (= audio); the reply is ignored, as YHX's library does
        cmd(le(0x49, 0, tunedKhz, 4) + le16(bitrateKbps) + byteArrayOf(subchannel.toByte(), 1), 5, readTimeout = 2000, check = false)
        if (dual) cmd(le(0x41, 0, cif, 2), 4, readTimeout = 2000)
        cmd(byteArrayOf(0x43, 0, 0, 1, 0), 4, readTimeout = 2000)          // stream on: the call's result
        mscAvail = 0
    }

    override fun stopAudio() {
        streamOff()
        cmd(byteArrayOf(0x58, 0, 0, 0), 2, readTimeout = 8000)
        cif = 0
    }

    /**
     * Like DABdream: after a status poll that says data is waiting, up to two reads of exactly one CIF each (the reply's
     * length field must equal the request). A read that fails counts as "nothing yet".
     */
    override fun mscRead(): ByteArray {
        if (cif == 0) return EMPTY
        if (clockMs() - statusAt < MIN_POLL_MS && mscAvail == 0) return EMPTY
        status()
        if (mscAvail == 0) return EMPTY
        val out = java.io.ByteArrayOutputStream(2 * cif)
        repeat(2) {
            val r = try { cmd(le(0x40, 0, cif, 2), cif + 4, readTimeout = 2000) } catch (e: IOException) { return out.toByteArray() }
            if (r.u16(2) != cif) return out.toByteArray()
            out.write(r, 4, cif)
        }
        return out.toByteArray()
    }

    override val signal get() = sig1?.let {
        // DABdream's six levels (< 40, 100, 200, 400, 600) folded into our 0..4 bars; >= 600 is its "0 %"
        val bars = when { it < 100 -> 4; it < 200 -> 3; it < 400 -> 2; it < 600 -> 1; else -> 0 }
        SignalReading(bars, none = it >= 600, weak = it >= 400, raw = it)
    }

    /** 0x46: bytes available (MSC [2..3], FIC [4..5]) and the signal value [8..9]; 14 bytes on a dual-antenna stick. */
    private fun status() {
        val r = cmd(byteArrayOf(0x46, 0, 0, 0), if (dual) 14 else 12, readTimeout = 2000)
        mscAvail = r.u16(2); ficAvail = r.u16(4); sig1 = r.u16(8)
        statusAt = clockMs()
    }

    /** Stream off before tune, start and stop: `41 00 00 00` up to firmware level 4, else `43 00 00 00 00`. */
    private fun streamOff() {
        if (fw <= 4) cmd(byteArrayOf(0x41, 0, 0, 0), 4, readTimeout = 2000, check = false)
        else cmd(byteArrayOf(0x43, 0, 0, 0, 0), 4, readTimeout = 2000, check = false)
    }

    private fun cmd(req: ByteArray, replyLen: Int, readTimeout: Int, check: Boolean = true) = command(t, req, replyLen, readTimeout, check = check)

    companion object {
        const val FIC_BYTES = 384
        private const val MIN_POLL_MS = 10L              // no status poll storm when the engine asks again at once
        private val EMPTY = ByteArray(0)

        /**
         * YHX's init: echo, version, identify (0x02: "C2D" = dual antenna), version again; a dual-antenna stick then gets
         * power on (0x10) and its init step 0x59. Returns null (and sends nothing further) unless the stick answers
         * "C2D" or reports firmware level >= 10, the two cases YHX's library can drive.
         */
        fun open(t: Transport, log: (String) -> Unit, sleep: (Long) -> Unit = { Thread.sleep(it) },
                 clockMs: () -> Long = { System.nanoTime() / 1_000_000 }): C100HighLevel? {
            command(t, byteArrayOf(0, 0, 0, 0), 4, readTimeout = 8000, writeTimeout = 2000, check = false)
            command(t, byteArrayOf(1, 0, 0, 0), 4, readTimeout = 10000, writeTimeout = 1000, check = false)
            val id = runCatching { command(t, byteArrayOf(2, 0, 0, 0), 5, readTimeout = 10000, writeTimeout = 2000) }.getOrNull()
            val dual = id != null && id[1] == 'C'.code.toByte() && id[2] == '2'.code.toByte() && id[3] == 'D'.code.toByte()
            // the second version reply counts; a bad one means level 0, as in YHX's library
            val fw = command(t, byteArrayOf(1, 0, 0, 0), 4, readTimeout = 10000, writeTimeout = 1000, check = false).let { if (it.u(0) == 0x81) it.u(3) else 0 }
            log("C100 high level: firmware level $fw, ${if (dual) "dual antenna (C2D)" else "not a dual-antenna stick"}")
            if (!dual && fw < 10) return null
            if (dual) {
                command(t, byteArrayOf(0x10, 0, 0, 1), 4, readTimeout = 8000, writeTimeout = 2000, check = false)
                command(t, byteArrayOf(0x59, 0, 0, 0), 2, readTimeout = 8000, writeTimeout = 2000)
            }
            return C100HighLevel(t, fw, dual, sleep, clockMs)
        }

        /** One command: OUT on EP 0x02, then one IN of [replyLen] on EP 0x82; byte 0 must be cmd | 0x80 ([check]). */
        private fun command(t: Transport, req: ByteArray, replyLen: Int, readTimeout: Int, writeTimeout: Int = 2000,
                            check: Boolean = true): ByteArray {
            t.write(Dtr100.EP_OUT, req, writeTimeout)
            val buf = ByteArray(replyLen)
            val n = t.read(Dtr100.EP_IN, buf, readTimeout)
            val code = (req[0].toInt() and 0xFF) or 0x80
            if (check && (n < 1 || (buf[0].toInt() and 0xFF) != code))
                throw Dtr100.DriverException("0x%02x: unexpected reply %s".format(req[0].toInt() and 0xFF, buf.copyOf(maxOf(n, 0)).hex()))
            return buf
        }

        /** [code] [b1] then [value] little-endian over [bytes] bytes. */
        private fun le(code: Int, b1: Int, value: Int, bytes: Int) =
            byteArrayOf(code.toByte(), b1.toByte()) + ByteArray(bytes) { (value ushr (8 * it)).toByte() }
        private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
        private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF
        private fun ByteArray.u16(i: Int) = u(i) or (u(i + 1) shl 8)
    }
}
