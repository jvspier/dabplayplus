package io.github.jvspier.dabplay.core

enum class Reception { GOOD, WEAK, LOST }

/**
 * Reception while playing, from what actually arrives: good/bad audio units (AU CRC) plus the tuner's own verdict.
 * Measured with the antenna pulled (docs/findings.md, 2026-09-27): the tuner reports "no signal" at once, audio data
 * stops within ~2 s, and after reconnecting the chip re-locks by itself in ~2 s, its signal value briefly jumping
 * back to "no signal" while audio already flows. Hence the hysteresis: LOST is entered on a sustained loss and left
 * only after audio has flowed for [recoverMs].
 */
class ReceptionMonitor(
    private val lostAfterMs: Long = 1500,
    private val noSignalForMs: Long = 1000,
    private val recoverMs: Long = 1000,
    private val graceMs: Long = 3000,          // after (re)starting a station: pre-roll, first superframes
) {
    private var startedAt = 0L
    private var lastGoodAt = 0L
    private var noSignalSince = -1L
    private var flowingSince = -1L
    private var prevGood = 0
    private var prevBad = 0
    var state = Reception.GOOD; private set
    /** When the current LOST period began (for service following), or -1. */
    var lostSince = -1L; private set

    fun start(now: Long) {
        startedAt = now; lastGoodAt = now; noSignalSince = -1; flowingSince = -1
        prevGood = 0; prevBad = 0; state = Reception.GOOD; lostSince = -1
    }

    /**
     * Call every ~500 ms with the decoder's running totals of good and bad AUs and the tuner's view:
     * [noSignal] = nothing receivable, [weakSignal] = marginal.
     */
    fun update(now: Long, goodAus: Int, badAus: Int, noSignal: Boolean, weakSignal: Boolean): Reception {
        val good = goodAus - prevGood; val bad = badAus - prevBad
        prevGood = goodAus; prevBad = badAus
        if (good > 0) lastGoodAt = now
        noSignalSince = if (noSignal) (if (noSignalSince < 0) now else noSignalSince) else -1
        flowingSince = if (good > 0) (if (flowingSince < 0) now else flowingSince) else -1
        val inGrace = now - startedAt < graceMs

        // just (re)started: no verdicts yet (the dtr100 reports "no signal" for ~0.5 s until its measurement settles)
        if (inGrace && state != Reception.LOST) { lostSince = -1; state = Reception.GOOD; return state }
        state = when (state) {
            Reception.LOST -> if (flowingSince >= 0 && now - flowingSince >= recoverMs) judge(good, bad, weakSignal) else Reception.LOST
            else -> {
                val starved = !inGrace && now - lastGoodAt >= lostAfterMs
                val gone = noSignalSince >= 0 && now - noSignalSince >= noSignalForMs && good == 0
                if (starved || gone) Reception.LOST else judge(good, bad, weakSignal)
            }
        }
        lostSince = if (state == Reception.LOST) (if (lostSince < 0) now else lostSince) else -1
        return state
    }

    private fun judge(good: Int, bad: Int, weak: Boolean) =
        if (weak || (good + bad > 0 && bad * 10 > good + bad)) Reception.WEAK else Reception.GOOD
}
