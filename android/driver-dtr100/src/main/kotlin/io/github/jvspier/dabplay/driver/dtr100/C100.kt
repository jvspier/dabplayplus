package io.github.jvspier.dabplay.driver.dtr100

import io.github.jvspier.dabplay.tuner.RawTuner
import io.github.jvspier.dabplay.tuner.SignalReading
import io.github.jvspier.dabplay.tuner.Transport
import io.github.jvspier.dabplay.tuner.Tuned
import java.io.IOException

/**
 * Opens a wRadio C100 family dongle (16c0:05dc, "dabusb@126.com"). First the register-level driver ([Dtr100], verified
 * on the owner's DTR-100); only when its init fails, the YHX high-level command set of the "Dual Antenna" C100, the
 * same order DABdream+ uses: only when the register check itself fails ([Dtr100.NotRegisterLevel]), never on a
 * timeout elsewhere (a voltage dip while cranking), and never again once a register-level init worked in this process.
 * The high-level identify command (0x02) therefore only reaches a stick that failed the register check. YHX's library sends it to every stick it opens, firmware level 4 included (it has a code path
 * for those), so it is that maker's normal "who are you" question.
 */
object C100 {
    /** A register-level stick worked in this process: a later failure is a glitch, not a different kind of stick. */
    @Volatile internal var registerLevelSeen = false
    /** This process's stick needed [Dtr100.chipAudio]: after a reconnect it reads from the chip straight away. */
    @Volatile internal var chipAudioNeeded = false

    fun open(t: Transport, log: (String) -> Unit = {}, newDriver: (Transport) -> Dtr100 = { Dtr100(it) },
             highLevel: (Transport, (String) -> Unit) -> RawTuner? = { tr, l -> C100HighLevel.open(tr, l) }): RawTuner {
        val d = newDriver(t)
        d.drainStale().takeIf { it > 0 }?.let { log("dropped $it stale bytes from a previous session") }
        val fw = d.version()
        log("version reply ${d.versionReply.hex()}")
        try {
            d.powerOn()
            d.systemInit()
            if (d.interfaceChecks > 1) log("chip answered after ${d.interfaceChecks} interface checks")
            registerLevelSeen = true
            if (chipAudioNeeded && fw > 1) { d.useChipAudio(); log("reading audio from the DAB chip, as before the reconnect") }
            return Dtr100Tuner(d, fw, log)
        } catch (e: Dtr100.NotRegisterLevel) {
            if (registerLevelSeen) throw e
            log("register-level init failed (${e.message}); trying the C100 high-level command set")
            d.drainStale()                                  // replies the failed attempt may have left queued
            val h = try { highLevel(t, log) } catch (e2: IOException) { log("C100 high level: ${e2.message}"); null }
            if (h != null) return h
            throw e
        }
    }
}

/**
 * [Dtr100] as a [RawTuner]: the retry and FIC rules the engine used to apply itself, unchanged.
 *
 * Audio comes through the MCU's buffer, as the vendor's own app reads it (verified on the owner's DTR-100). A stick
 * whose MCU passes nothing on (a Play tester's DTR-100, 2026-10-26: tunes and scans, then no byte and "no signal")
 * falls back to reading it from the DAB chip, as DABdream+ does on every C100: only while this connection has never
 * delivered audio through the MCU, [MCU_GRACE_MS] after a station started. A working stick delivers within a second
 * (0.8 s on the owner's), and from then on the fallback is off until the stick is reconnected.
 */
class Dtr100Tuner(private val d: Dtr100, private val fw: Int, private val log: (String) -> Unit = {},
                  private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) : RawTuner {
    override val description = "C100 register level, firmware level $fw"
    private var ficEnabled = false
    /** Audio came through the MCU on this connection: it works, the fallback is off for good. */
    private var mcuDelivered = false
    /** When the current audio started, while the fallback watches; -1 otherwise. */
    private var audioAt = -1L
    private var chipSig: Int? = null
    private var chipSigAt = 0L

    override fun tune(khz: Int, retries: Boolean): Tuned {
        var t = d.tune(khz); var tries = 1
        // same retry rules as tools/dtr100.py scan: sync 3 -> 2 tries, promising (u1 >= 1000) -> 3 tries
        while (!t.locked && retries && tries < (if ((t.info.u1 ?: 0) >= 1000) 3 else if (t.info.sync == 3) 2 else 1)) { t = d.tune(khz); tries++ }
        if (t.locked && !ficEnabled) { d.ficEnable(); ficEnabled = true }
        return Tuned(t.locked, if (t.locked) lockBars(t.info.u1 ?: 0) else 0, t.info.toString())
    }

    /** Bars right after a lock, before the first status poll: from the tune's u1 value. */
    private fun lockBars(u1: Int) = when { u1 < 4000 -> 1; u1 < 9000 -> 2; u1 < 15000 -> 3; else -> 4 }

    override fun ficRead() = d.ficRead()

    override fun startAudio(subchannel: Int, bitrateKbps: Int) {
        d.startAudio(subchannel, bitrateKbps * 3)                    // bytes per 24 ms CIF
        audioAt = if (fw > 1 && !d.chipAudio && !mcuDelivered) clockMs() else -1
    }

    override fun stopAudio() { audioAt = -1; d.stopAudio() }

    override fun mscRead(): ByteArray {
        val data = d.mscRead()
        if (d.chipAudio) {
            val now = clockMs()                                        // the signal, twice a second as the engine asks
            if (chipSig == null || now - chipSigAt >= 500) { chipSigAt = now; chipSig = d.chipSignal() }
        } else if (data.isNotEmpty()) {
            mcuDelivered = true; audioAt = -1
        } else if (audioAt >= 0 && clockMs() - audioAt >= MCU_GRACE_MS) {
            audioAt = -1
            log("no audio from the stick's controller ${MCU_GRACE_MS / 1000} s after the start (status ${d.lastMcuStatus.hex()}, " +
                "chip status 0x%02x): reading it from the DAB chip instead".format(d.lastChipStatus))
            C100.chipAudioNeeded = true                                // first: a USB error in the switch reconnects
            d.useChipAudio()
        }
        return data
    }

    override val signal get() = (if (d.chipAudio) chipSig else d.mcuSignal)?.let {
        SignalReading(Dtr100.signalBars(it), none = it >= Dtr100.SIGNAL_NONE, weak = it >= Dtr100.SIGNAL_WEAK, raw = it)
    }

    companion object {
        /** No audio through the MCU this long after the start: read it from the chip ([Dtr100.useChipAudio]). */
        const val MCU_GRACE_MS = 2000L
    }
}
