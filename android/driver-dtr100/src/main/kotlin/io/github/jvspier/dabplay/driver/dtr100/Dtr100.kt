package io.github.jvspier.dabplay.driver.dtr100

import io.github.jvspier.dabplay.tuner.Transport
import java.io.IOException

/**
 * Driver for the wRadio C100 / DTR-100 family (USB 16c0:05dc, manufacturer "dabusb@126.com").
 * A line-by-line port of tools/dtr100.py (protocol: docs/protocol.md). Only transfers found in the vendor
 * library are sent. Blocking: call it from one dedicated I/O thread.
 */
class Dtr100(
    private val t: Transport,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    // driver state, mirrors the libdab.so globals
    var firmwareLevel: Int? = null; private set          // DAT_002025ec: selects the MSC code path
    private var plan: Int? = null                          // DAT_00657c04: active clock plan
    private var bandMode = 0                               // DAT_00657c00: 1 = band III lists loaded
    private var irqMask = 0                                // DAT_00657c51: shadow of page 7 reg 0x62
    private var ficOpen = false                            // DAT_00657c10
    /**
     * Bytes 4..5 of the last 0x42 status reply: what the vendor's dab_api_get_signal returns on fw >= 2
     * (FUN_000773c0 returns DAT_002025e8, set from these bytes in FUN_00077990). Scale still being calibrated.
     */
    @Volatile var mcuSignal: Int? = null; private set
    private var mscSeq = 0                                 // DAT_002025e0: rolling counter in cmd 0x42
    private var mscSize = 0                                // DAT_002025e4 / DAT_00657c38
    private var subch: Int? = null                         // active audio sub-channel

    class DriverException(msg: String) : IOException(msg)

    // --- transport ------------------------------------------------------------------------

    private fun xfer(req: ByteArray, replyLen: Int, timeoutOut: Int = 1000, timeoutIn: Int = 1000): ByteArray {
        t.write(EP_OUT, req, timeoutOut)
        val buf = ByteArray(maxOf(replyLen, 64))          // same read length as tools/dtr100.py
        val n = t.read(EP_IN, buf, timeoutIn)
        return buf.copyOf(n)
    }

    private fun xfer(vararg req: Int, replyLen: Int = 4, timeoutOut: Int = 1000, timeoutIn: Int = 1000) =
        xfer(ByteArray(req.size) { req[it].toByte() }, replyLen, timeoutOut, timeoutIn)

    private fun expect(rep: ByteArray, code: Int, what: String) {
        if (rep.isEmpty() || (rep[0].toInt() and 0xFF) != code) throw DriverException("$what: expected reply 0x%02x, got ${rep.hex()}".format(code))
    }

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    /**
     * Discard replies a previous session left queued on EP 0x82 (app killed between a command and its reply read).
     * Only reads, never writes: nothing is sent to the dongle. Returns the number of stale bytes dropped.
     */
    fun drainStale(maxReads: Int = 64): Int {
        val buf = ByteArray(16 * 1024)
        var total = 0
        repeat(maxReads) {
            val n = try { t.read(EP_IN, buf, 30) } catch (_: IOException) { return total }
            if (n <= 0) return total
            total += n
        }
        return total
    }

    // --- MCU commands ---------------------------------------------------------------------

    /** FUN_00075200: reply bytes 1..3 must be "DAB". */
    fun echo(): Boolean {
        val r = xfer(0, 0, 0, 0, timeoutOut = 2000, timeoutIn = 2000)
        return r.size >= 4 && r[1] == 'D'.code.toByte() && r[2] == 'A'.code.toByte() && r[3] == 'B'.code.toByte()
    }

    /** FUN_00075360: byte 3 = firmware level. */
    fun version(): Int {
        val r = xfer(1, 0, 0, 0)
        expect(r, 0x81, "version")
        if (r.size < 4) throw DriverException("version: short reply ${r.hex()}")
        return r.u(3).also { firmwareLevel = it }
    }

    /** FUN_00076840 (used by dab_api_init). */
    fun powerOn() = expect(xfer(0x10, 0, 0, 1, timeoutOut = 2000, timeoutIn = 4000), 0x90, "power on")

    /** FUN_000756d0. */
    fun powerOff() = expect(xfer(0x10, 0, 0, 0), 0x90, "power off")

    // --- chip register access over SPI ----------------------------------------------------

    /** FUN_00076550: 21 00 00 02 reg val -> A1. */
    fun wreg(reg: Int, value: Int) = expect(xfer(0x21, 0, 0, 2, reg, value and 0xFF, replyLen = 1, timeoutIn = 4000), 0xA1, "write reg 0x%02x".format(reg))

    /** FUN_000764d0 / FUN_00076510 -> FUN_00075d00: 22 00 n_lo n_hi reg -> A2 00 n_lo n_hi data. */
    fun rreg(reg: Int, n: Int = 1): ByteArray {
        val r = xfer(0x22, 0, n and 0xFF, n shr 8, reg, replyLen = n + 4, timeoutIn = 4000)
        expect(r, 0xA2, "read reg 0x%02x".format(reg))
        val got = r.u(2) or (r.u(3) shl 8)
        if (got != n || r.size < 4 + n) throw DriverException("read reg 0x%02x: size mismatch, asked $n, got $got".format(reg))
        return r.copyOfRange(4, 4 + n)
    }

    private fun r1(reg: Int) = rreg(reg)[0].toInt() and 0xFF
    fun page(p: Int) = wreg(0x03, p)
    private fun wlist(vararg p: Int) { var i = 0; while (i < p.size) { wreg(p[i], p[i + 1]); i += 2 } }

    /** FUN_000788a0 / FUN_00077b70. */
    fun checkInterface(): Boolean { page(7); wreg(0x7D, 0x06); return r1(0x7D) == 0x06 }

    // --- DAB chip driver (band III) ---------------------------------------------------------

    private fun clockPlanRegs(p: Int) {                 // common part of FUN_00078b20 / FUN_000789c0
        val c = Dtr100Tables.CLOCK_PLANS[p]
        if (c[0] == 0xFF) throw DriverException("clock plan $p invalid")
        page(0x0F)
        wlist(0xE8, c[0] or 0x40, 0xE9, c[1] or 0x50, 0xEA, c[2], 0xEB, c[3], 0xEC, c[4], 0xED, c[5], 0xEE, c[6])
    }

    private fun setClockPlan(p: Int) {                  // FUN_00078b20(band 1, plan)
        if (p == plan) return
        clockPlanRegs(p)
        var ok = false
        for (k in 0 until 10) { if (r1(0x15) and 1 != 0) { ok = true; break }; sleep(10) }
        if (!ok) throw DriverException("clock plan not applied (reg 0x15 bit 0)")
        val (r6a, p6, r4041, r42) = PLAN_REGS.getValue(p)
        page(4); wreg(0x6A, r6a)
        page(6); wlist(*p6); wlist(0x54, 0x58, 0x40, r4041.first, 0x41, r4041.second, 0x42, r42, 0x43, 0x00)
        plan = p
    }

    /** FUN_00073220. */
    fun systemInit() {
        if (!checkInterface()) throw DriverException("check interface failed")
        page(0x0F); wlist(0x54, 0x1C, 0x52, 0x07, 0x30, 0xF4, 0x2F, 0x71)
        clockPlanRegs(0)
        var ok = false
        for (k in 0 until 10) { if (r1(0x15) and 1 != 0) { ok = true; break }; sleep(1) }
        if (!ok) throw DriverException("clock plan 0 not applied")
        plan = 0
        page(6); wlist(0x07, 0x08, 0x05, 0x17, 0x06, 0x10, 0x0A, 0x00)
        page(4); wlist(0x10, 0x91, 0xE1, 0x00, 0x35, 0x7B, 0x3B, 0x3C, 0x36, 0x68, 0x3A, 0x44, 0x3C, 0x20, 0x3D, 0x0B,
                       0x3D, 0x09, 0xA6, 0x30, 0xAA, 0x01, 0xAF, 0x07)
        page(7); wlist(0x10, 0x00, 0x13, 0x16, 0x14, 0x00, 0x19, 0x0A, 0xF0, 0, 0xF1, 0, 0xF2, 0, 0xF3, 0, 0xF4, 0,
                       0xF5, 0, 0xF6, 0, 0xF7, 0, 0xF8, 0, 0xFB, 0xFF)
        page(6); wlist(0x12, 0x04, 0x13, 0x72, 0x14, 0x63, 0x15, 0x64, 0x16, 0x6C, 0x38, 0x01, 0x1A, 0xB4, 0x20, 0x5B,
                       0x25, 0x09, 0x44, 0x09, 0x46, 0xA0, 0x47, 0x0F, 0x48, 0xB8, 0x49, 0x0B, 0x54, 0x58, 0x55, 0x06,
                       0x56, 0x10, 0x59, 0x51, 0x5A, 0x1C, 0x6D, 0x00, 0x8B, 0x34, 0x6A, 0x1C, 0x6B, 0x2D, 0x85, 0x32,
                       0x8D, 0x0C, 0x8E, 0x01, 0x33, 0x02, 0x53, 0x06, 0x6F, 0x03, 0xBA, 0x08)
        page(4); wreg(0x6A, 0x01)                         // clock plan 0 branch
        page(6); wlist(0x3C, 0x4B, 0x3D, 0x37, 0x3E, 0x89, 0x3F, 0x41, 0x40, 0x8F, 0x41, 0xC2, 0x42, 0xF5, 0x43, 0x00)
        wlist(0x94, 0x08, 0x98, 0x05, 0x99, 0x03, 0x9B, 0xCF, 0x9C, 0x10, 0x9D, 0x1C, 0x9F, 0x32, 0xA0, 0x90, 0xA2, 0xA0,
              0xA3, 0x08, 0xA4, 0x01, 0xA8, 0xF6, 0xA9, 0x89, 0xAA, 0x0C, 0xAB, 0x32, 0xAC, 0x14, 0xAD, 0x09, 0xAE, 0xFF,
              0xEB, 0x6B, 0x93, 0x10, 0x94, 0x29, 0xA2, 0x50, 0xA4, 0x02, 0xAF, 0x01)
        page(9); wlist(0x80, 0x80, 0x81, 0xFF, 0x87, 0x07, 0x45, 0xA1, 0xDD, 0xD0, 0x39, 0x07, 0xE6, 0x10, 0xA5, 0xA0)
        page(9); wreg(0x46, 0x00)                         // FUN_00078540
        wlist(0x09, 0x00, 0x0B, 0x00)                     // FUN_00077d90 (still page 9)
        page(7); wlist(0x28, 0x01, 0x2A, 0x13)
        page(7); wreg(0x29, 0x08)
        page(9); val v = r1(0x31); wreg(0x31, v and 0xFC)
        page(7); wlist(0x77, 0x14, 0x04, 0x28, 0x0C, 0xF5) // FUN_00078830
        rfInitDab()                                       // FUN_00079600(1)
        sleep(100)
        page(0x0F); wreg(0x6B, 0xC5)
    }

    private fun rfInitDab() {                           // FUN_00079600(1)
        bandMode = 1
        page(0x0F)
        wlist(*Dtr100Tables.RF_INIT_DAB)
        wlist(*Dtr100Tables.RF_INIT_DAB_TAIL)
        val hi = r1(0x10); val lo = r1(0x11)
        if ((hi shl 8 or lo) == 0xFFFF) wlist(0x2C, 200, 0x47, 0xB0, 0x35, 0x04, 0x2A, 0x07, 0x2D, 0xEC, 0x61, 0x2A)
        else wlist(0x2C, 0x48, 0x47, 0xE0, 0x35, 0x04, 0x2A, 0x05, 0x2D, 0x8C, 0x61, 0x25)
    }

    private fun demodRestart() { page(6); wreg(0x10, 0x48); sleep(1); wreg(0x10, 0xC9) }
    private fun demodRestartNoWait() { page(6); wreg(0x10, 0x48); wreg(0x10, 0xC9) }

    private fun pllBand3(f: Int): Int {                 // FUN_00079080(1, 0, f); 0 or -13
        if (bandMode != 1) { page(0x0F); wlist(*Dtr100Tables.RF_BAND3); bandMode = 1 }
        val i = band3Index(f)
        if (i !in Dtr100Tables.BAND3_PLL.indices) throw DriverException("$f kHz is not a band III channel")
        val w = Dtr100Tables.BAND3_PLL[i].toLong() and 0xFFFFFFFFL
        val p = Dtr100Tables.BAND3_PLAN[i]
        val ratio = w.toDouble() / f
        if (ratio !in 341.0..341.8) throw DriverException("PLL word 0x%x implausible for $f kHz (index $i)".format(w))
        setClockPlan(p)
        page(0x0F)
        wlist(0x23, (w shr 22 and 0xFF).toInt(), 0x24, (w shr 14 and 0xFF).toInt(), 0x25, (w shr 6 and 0xFF).toInt())
        for (r in intArrayOf(0x23, 0x24, 0x25)) rreg(r)
        wreg(0x26, ((w * 4) or 1).toInt() and 0xFF)
        sleep(20); wreg(0x20, 0x00); sleep(20)
        var locked = false
        for (k in 0 until 10) {
            val r15 = r1(0x15); val r12 = r1(0x12); val r13 = r1(0x13); val r14 = r1(0x14)
            val rb = r12.toLong() * 0x400000 + r13 * 0x4000 + r14 * 0x40 + (r15 shr 2 and 0x3F)
            if (((w xor rb) and 1L.inv()) == 0L || w shr 1 == rb) {
                if (r15 and 2 != 0) { locked = true; break }
                wreg(0x20, 0x00); sleep(20)
            } else { wreg(0x20, 0x00); sleep(10) }
        }
        demodRestart()
        if (!locked) return -13
        sleep(1)
        return 0
    }

    data class TuneInfo(var why: String? = null, var sync: Int? = null, var modeBits: Int? = null, var polls: Int = 0,
                        var u1: Int? = null, var u21: Int? = null, var loopBRounds: Int? = null, var u15: Int? = null,
                        var q: Int? = null)

    /** Result of [tune]: rc 0 = locked (and quality accepted), -4 = rejected (see info.why), -13 = PLL. */
    data class TuneOutcome(val rc: Int, val info: TuneInfo) { val locked get() = rc == 0 }

    private fun thresholds(p: Int?, mode: Int, f: Int): IntArray? {   // (u15, u17, lo, hi, u20, u22)
        val low = f < 0x3D091
        val (u22, u20, special) = when (p) {
            0, 1 -> Triple(0x9B5 + if (low) 0xF5 else 0, 0x163 + if (low) 0x23 else 0,
                           mapOf(2 to intArrayOf(0x50, 0x140, 0x08, 0xF8), 3 to intArrayOf(1000, 4000, 0x1A, 0xE6)))
            2, 3 -> Triple(0x9B5 + if (low) 0xAF else 0, 0x163 + if (low) 0x19 else 0,
                           mapOf(2 to intArrayOf(100, 400, 0x08, 0xF8), 3 to intArrayOf(0x4E2, 5000, 0x1A, 0xE6)))
            else -> return null
        }
        val s = special[mode] ?: if (mode == 1) intArrayOf(0xED, 0x3B6, 0x0E, 0xF2) else intArrayOf(0x659, 0x1964, 0x37, 0xCE)
        return intArrayOf(s[0], s[1], s[2], s[3], u20, u22)
    }

    /** FUN_00072400, literal port (band III). The sync poll is capped by time instead of 9999 iterations. */
    fun tune(f: Int, syncTimeoutMs: Long = 1500): TuneOutcome {
        val info = TuneInfo()
        fun fail(why: String) = TuneOutcome(-4, info.also { it.why = why })

        val rc = pllBand3(f)
        if (rc != 0) return TuneOutcome(rc, info.also { it.why = "pll" })
        page(4); wreg(0x55, 0x0D)
        page(6); wreg(0x54, when (plan) { 0 -> 0x7B; 1 -> 0x78; 3 -> 0x7A; else -> 0x78 }); wreg(0x5A, 0x1B)
        page(0x0F); wreg(0x4B, 0x8F)
        demodRestart()

        val deadline = clockMs() + syncTimeoutMs
        var th: IntArray
        var r38: Int; var r39: Int; var r66: Int; var r67: Int
        while (true) {                                   // LAB_000725bb: sync poll
            info.polls++
            page(6)
            val sync = r1(0xCF)
            if (sync == 0xFF) return fail("sync reg 0xff")
            page(4); r38 = r1(0x38); r39 = r1(0x39)
            page(6)
            val v = r1(0x53); wreg(0x53, v or 0x80)
            r66 = r1(0x66); r67 = r1(0x67)
            val mode = (r1(0x27) and 0x30) shr 4
            wreg(0x1A, MODE_1A[mode])
            th = thresholds(plan, mode, f) ?: return fail("clock plan")
            info.sync = sync; info.modeBits = mode
            if (sync == 3) break
            if (sync == 1 || clockMs() > deadline) {
                page(6); wreg(0x5A, 0x1C)
                return fail(if (sync == 1) "no sync" else "sync timeout")
            }
        }
        val (u15, u17, lo, hi) = th
        var u20 = th[4]; val u22 = th[5]
        val u1 = (r39 shl 8) or r38
        val u21 = (r67 * 4) or (r66 and 3)
        info.u1 = u1; info.u21 = u21
        page(6); wreg(0x5A, 0x1C); rreg(0x18)
        page(0x0F)
        if (r1(0x00) shr 6 == 2) u20 = u22
        if (u21 * 10 < u20 && u21 != 0) return fail("u21 $u21 * 10 < $u20")
        val b23 = u1 != 0

        for (i in 0 until 16) {                          // loop A: signal level gate
            page(0x0F)
            var rf0 = r1(0x00)
            val u16 = if (rf0 shr 6 == 2 && i > 10) u15 else u17
            if (u1 < u16 && b23) return fail("A: u1 $u1 < $u16")
            val weak = u1 < u17
            page(0x0F); rreg(0x00)
            page(6); rreg(0xB1); rreg(0xB5); rreg(0xB6)
            page(0x0F); rf0 = r1(0x00)
            if (rf0 shr 6 != 2 && weak && i > 0x0D && b23) return fail("A: weak")
            if (i == 15) break
            sleep(10)
        }

        var l1d = 0; var cnt37 = 0; var cnt30 = 0; var l1f = 0
        var i = 0
        while (true) {                                   // loop B: settle + latch bits
            sleep(10)
            page(6); val r18 = r1(0x18)
            page(0x0F); val rf0 = r1(0x00)
            if (u1 < u17 && b23 && rf0 shr 6 != 2 && i > 0x19 && (r18 < lo || hi < r18)) return fail("B: reg18 $r18 outside $lo..$hi")
            if (u1 < u17 * 2 && b23 && rf0 shr 6 == 2 && i > 0x19 && (r18 < lo || hi < r18)) return fail("B: reg18 $r18 outside $lo..$hi (rf0 mode 2)")
            page(6); val r30 = r1(0x30) and 0x0F
            if ((r30 > 9 && cnt30 > 4) || (r30 > 8 && cnt30 > 6)) return fail("B: reg30 $r30")
            if (r30 > 7) {
                if (cnt30 > 8 && i > 0x0F) return fail("B: reg30 $r30 persistent")
                cnt30++
            }
            page(6); val r37 = r1(0x37) and 7
            var check = true
            if (u1 < 3000 && u21 < 0x226) { check = r37 == 1; if (check) cnt37++ }
            if (check && r37 == 1 && cnt37 > 0x14 && l1f < 2 && i > 0x1E) return fail("B: reg37 == 1 persistent")
            l1f = r1(0x17) and 0x1F
            val r18b = r1(0x18)
            var v = r1(0x82); wreg(0x82, v or 1)
            val r7e = r1(0x7E); val r7f = r1(0x7F)
            v = r1(0x1C); wreg(0x1C, v or 0x10)
            val r26 = r1(0x26); val r27 = r1(0x27)
            v = r1(0x44); wreg(0x44, v or 0x80)
            val r4c = r1(0x4C); val r4d = r1(0x4D)
            if (l1f == 1 && r37 == 6) {
                if (i > 0x0F && l1d > 1) return fail("B: l1d")
                if ((((r4c - 0x41) + (r4d and 1) * 0x100) and 0xFFFF) > 0x69 || (((r27 and 0x0F) shl 8) or r26) == 0) l1d++
            } else if (l1f > 1 && (r18b < lo || hi < r18b) && (((r7f and 0x1F) shl 8) or r7e) <= 0x8B) break
            i++
            if (i == 0x4B) return fail("B: no settle in 75 rounds")
        }
        info.loopBRounds = i + 1

        var u15b = 0
        for (left in 40 downTo 1) {                      // LAB_00072ead
            sleep(10)
            page(6)
            val r12 = r1(0x12)
            val v = r1(0x82); wreg(0x82, v or 1)
            u15b = r1(0x7E) + (r1(0x7F) and 0x1F) * 0x100
            if (u15b != 0 && r12 and 0x80 != 0) {
                page(6); wreg(0x54, 0x58)
                page(0x0F); wreg(0x4B, 0x80)
                page(4); wreg(0x55, 0x15)
                break
            }
            if (left == 1) {
                if (r12 and 0x80 == 0) { page(6); rreg(0x18); return fail("final: reg12 bit7 never set") }
                break
            }
        }
        info.u15 = u15b

        page(9)                                          // LAB_00072fad: quality verdict
        val fb = r1(0xFB)
        var q4 = intArrayOf(r1(0x83), r1(0x84), r1(0x85), r1(0x86))
        if (fb and 3 != 3) { page(6); rreg(0x18); page(9); q4 = intArrayOf(r1(0x83), r1(0x84), r1(0x85), r1(0x86)) }
        val den = (q4[0] shl 8) or q4[1]
        if (den == 0) return fail("quality: zero denominator")
        val q = ((q4[2] shl 8) or q4[3]) * 1000 / den
        info.q = q
        if (fb and 3 == 3) { if (q < 0xC4 || u15b > 0xB3) return TuneOutcome(0, info) }
        else if (q < 0xC3 && u15b < 0xB4) return TuneOutcome(0, info)
        return fail("quality q=$q u15=$u15b")
    }

    /** FUN_000786d0, once after the first successful tune. */
    fun ficEnable() {
        if (ficOpen) return
        ficOpen = true
        page(9); wreg(0x35, 0x01)
        page(7); irqMask = irqMask and 0xFE; wreg(0x62, irqMask)
        page(9); wreg(0x46, 0x14); wreg(0x46, 0x16)
    }

    /** FUN_00079c20: 384 bytes (12 FIBs) or null when no new FIC is available. */
    fun ficRead(): ByteArray? {
        page(9)
        val st = rreg(0x33, 3)
        if (st[0].toInt() and 1 == 0) return null
        page(0x0A)
        val data = rreg(0x10, 0x180)
        page(9); wreg(0x35, 0x01)
        return data
    }

    /**
     * dab_api_set_subid(subch) + dab_api_set_msc_size(n), n = bytes per CIF = bitrate * 3.
     * FUN_00069560 -> FUN_00072160 -> FUN_000712e0(subch, type 2, n) -> FUN_00077ea0, then FUN_000770e0(n).
     */
    fun startAudio(subchannel: Int, bytesPerCif: Int) {
        require(subchannel in 0..0x3F && bytesPerCif in 1..0xD38) { "bad subch $subchannel / size $bytesPerCif" }
        val fw = firmwareLevel ?: throw DriverException("read the firmware level (version) before starting audio")
        val n = bytesPerCif
        demodRestartNoWait()                             // FUN_00072160, no active sub-channels
        page(9); wlist(0x56, n shr 8 and 0x0F, 0x57, n and 0xFF)   // FUN_000781e0: buffer threshold
        page(9); wlist(0x48, 0x00, 0x48, 0x05)           // FUN_00078240: buffer reset
        page(9); wreg(0x35, 0x70)
        page(7); irqMask = irqMask and 0x8F; wreg(0x62, irqMask)
        page(9); wreg(0x3A, 0x80 or subchannel)          // FUN_000782a0(subch, 1, type == 1)
        page(9); wreg(0x35, 0x7E)                        // FUN_00077ea0
        page(7); wreg(0x62, irqMask)
        mscSize = n
        subch = subchannel
        if (fw > 1) expect(xfer(0x41, 0, n and 0xFF, n shr 8), 0xC1, "set msc size")
        if (fw > 4) expect(xfer(0x43, 0, 0, 1), 0xC3, "msc flag on")   // [hypothesis] set_flag(1) after set_msc_size
    }

    /** dab_api_set_flag(0) -> FUN_00069800(0) -> FUN_000775b0 (stop in the chip, flush the MCU) + FUN_00077240(0). */
    fun stopAudio() {
        subch?.let { s ->
            page(9); wreg(0x3A, s)                        // FUN_000782a0(subch, 0, 0): disable
            page(7); irqMask = irqMask or 0x70; wreg(0x62, irqMask)
            page(7); irqMask = irqMask or 0x0E; wreg(0x62, irqMask)
            subch = null
        }
        val fw = firmwareLevel
        if (fw != null && fw > 1) {
            for (k in 0 until 64) {
                val st = xfer(0x42, 0, 0, nextSeq(), replyLen = 6)
                val avail = if (st.size >= 4) st.u(2) or (st.u(3) shl 8) else 0
                if (avail == 0) break
                val m = minOf(avail, 0x480)
                expect(xfer(0x41, 0, m and 0xFF, m shr 8), 0xC1, "flush size")
                xfer(0x40, 0, m and 0xFF, m shr 8, replyLen = m + 4)
            }
        }
        mscSize = 0
        if (fw != null && fw > 4) expect(xfer(0x43, 0, 0, 0), 0xC3, "msc flag off")
        else if (fw != null && fw > 1) expect(xfer(0x41, 0, 0, 0), 0xC1, "stop msc")
    }

    private fun nextSeq(): Int { val s = mscSeq; mscSeq = (s + 1) and 0xFF; return s }

    /** Raw sub-channel bytes (possibly empty). fw >= 2: FUN_00077990; fw <= 1: FUN_00079e20. */
    fun mscRead(maxLen: Int = 4096): ByteArray {
        val fw = firmwareLevel ?: throw DriverException("firmware level unknown")
        if (fw <= 1) {
            page(9)
            val st = rreg(0x33, 3)[0].toInt() and 0xFF
            if (st and 0x70 == 0) return EMPTY
            if (st and 0x60 != 0) { wlist(0x48, 0x00, 0x48, 0x05); page(9); wreg(0x35, 0x04); return EMPTY }  // overflow
            page(0x0C)
            val data = rreg(0x10, mscSize)
            page(9); wreg(0x35, 0x04)
            return data
        }
        val st = xfer(0x42, 0, 0, nextSeq(), replyLen = 6)
        if (st.size >= 6) mcuSignal = st.u(4) or (st.u(5) shl 8)
        val avail = if (st.size >= 4) st.u(2) or (st.u(3) shl 8) else 0
        if (avail == 0) return EMPTY
        val m = minOf(avail, maxLen)
        if (m < mscSize) return EMPTY
        val r = xfer(0x40, 0, m and 0xFF, m shr 8, replyLen = m + 4)
        expect(r, 0xC0, "msc read")
        if (r.size < 4) throw DriverException("msc read: short reply ${r.hex()}")
        val got = minOf(r.u(2) or (r.u(3) shl 8), r.size - 4)
        return r.copyOfRange(4, 4 + got)
    }

    /** FUN_000719d0: page 9 bit error counters, the vendor's quality value (lower = better; 8000 = none). */
    fun quality(): Int {
        page(9)
        if (r1(0x37) and 1 == 0) return 8000
        var tot = 0L; for (r in 0x88..0x8B) tot = (tot shl 8) or r1(r).toLong()
        var err = 0L; for (r in 0x8C..0x8F) err = (err shl 8) or r1(r).toLong()
        return if (tot != 0L) (err * 1000 / tot * 10).toInt() else 8000
    }

    companion object {
        const val VID = 0x16C0
        const val PID = 0x05DC
        const val MANUFACTURER = "dabusb@126.com"
        const val EP_OUT = 0x02
        const val EP_IN = 0x82

        /** [mcuSignal] value meaning nothing receivable (antenna pulled, 2026-09-27: it jumps straight to 2000). */
        const val SIGNAL_NONE = 2000

        /**
         * Bars from [mcuSignal], the chip's CER (0 = perfect, [SIGNAL_NONE] = nothing). Thresholds from RAON's own
         * antenna-level table (CER 700/400/180, see findings.md); still worth a look in the car.
         */
        fun signalBars(sig: Int) = when { sig >= SIGNAL_NONE -> 0; sig >= 700 -> 1; sig >= 400 -> 2; sig >= 180 -> 3; else -> 4 }
        private val EMPTY = ByteArray(0)
        private val MODE_1A = intArrayOf(0xB4, 0x44, 0x04, 0x84)   // page 6 reg 0x1a by reg 0x27 bits 4..5

        /** FUN_00078b20: page 4 reg 0x6a, page 6 regs, (0x40, 0x41), 0x42 per clock plan. */
        private val PLAN_REGS = mapOf(
            0 to PlanRegs(0x01, intArrayOf(0x3C, 0x4B, 0x3D, 0x37, 0x3E, 0x89, 0x3F, 0x41), 0x8F to 0xC2, 0xF5),
            1 to PlanRegs(0x01, intArrayOf(0x3C, 0x00, 0x3D, 0x00, 0x3E, 0x00, 0x3F, 0x40), 0x00 to 0x00, 0xF0),
            2 to PlanRegs(0x21, intArrayOf(0x3C, 0xB5, 0x3D, 0x14, 0x3E, 0x41, 0x3F, 0x3A), 0x0D to 0x74, 0xDA),
            3 to PlanRegs(0x31, intArrayOf(0x3C, 0x69, 0x3D, 0x03, 0x3E, 0x9D, 0x3F, 0x36), 0xCC to 0xCC, 0xCC),
        )

        private data class PlanRegs(val r6a: Int, val page6: IntArray, val r4041: Pair<Int, Int>, val r42: Int)

        /** Channel index in the band III table (FUN_00079080, band 1). */
        fun band3Index(f: Int): Int {
            val u = Math.floorDiv(f - 174928, 1712)
            return if (f < 224096) { if (f < 217008) u + 1 - (if (f < 210096) 1 else 0) else u + 2 } else u + 3
        }
    }
}

internal fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }
