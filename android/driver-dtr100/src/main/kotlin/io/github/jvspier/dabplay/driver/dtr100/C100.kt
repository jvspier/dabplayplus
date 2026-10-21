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

    fun open(t: Transport, log: (String) -> Unit = {}, newDriver: (Transport) -> Dtr100 = { Dtr100(it) },
             highLevel: (Transport, (String) -> Unit) -> RawTuner? = { tr, l -> C100HighLevel.open(tr, l) }): RawTuner {
        val d = newDriver(t)
        d.drainStale().takeIf { it > 0 }?.let { log("dropped $it stale bytes from a previous session") }
        val fw = d.version()
        try {
            d.powerOn()
            d.systemInit()
            if (d.interfaceChecks > 1) log("chip answered after ${d.interfaceChecks} interface checks")
            registerLevelSeen = true
            return Dtr100Tuner(d, fw)
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

/** [Dtr100] as a [RawTuner]: the retry and FIC rules the engine used to apply itself, unchanged. */
class Dtr100Tuner(private val d: Dtr100, fw: Int) : RawTuner {
    override val description = "C100 register level, firmware level $fw"
    private var ficEnabled = false

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
    override fun startAudio(subchannel: Int, bitrateKbps: Int) = d.startAudio(subchannel, bitrateKbps * 3)   // bytes per 24 ms CIF
    override fun stopAudio() = d.stopAudio()
    override fun mscRead() = d.mscRead()

    override val signal get() = d.mcuSignal?.let {
        SignalReading(Dtr100.signalBars(it), none = it >= Dtr100.SIGNAL_NONE, weak = it >= Dtr100.SIGNAL_WEAK, raw = it)
    }
}
