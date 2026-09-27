package io.github.jvspier.dabplay.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Replays the antenna-out test of 2026-09-27 (per-second AU counts and signal values from the log). */
class ReceptionMonitorTest {
    private class Run(val m: ReceptionMonitor = ReceptionMonitor()) {
        var t = 0L; var good = 0; var bad = 0
        init { m.start(0) }
        /** One second in two 500 ms updates. */
        fun second(g: Int, b: Int, sig: Int): Reception {
            var r = Reception.GOOD
            repeat(2) { good += g / 2 + (if (it == 0) g % 2 else 0); bad += b / 2 + (if (it == 0) b % 2 else 0); t += 500
                r = m.update(t, good, bad, noSignal = sig >= 2000, weakSignal = sig in 700 until 2000) }
            return r
        }
    }

    @Test fun antennaOutAndBack() {
        val r = Run()
        repeat(5) { assertEquals(Reception.GOOD, r.second(24, 0, 0)) }
        // antenna pulled: signal value 2000 at once, one second of (mostly bad) leftovers, then nothing
        assertEquals(Reception.WEAK, r.second(1, 6, 2000))
        repeat(20) { assertEquals(Reception.LOST, r.second(0, 0, 2000)) }
        // antenna back: re-lock over ~3 s, the value briefly reads 2000 again while audio already flows
        assertEquals(Reception.LOST, r.second(0, 4, 950))
        assertEquals(Reception.LOST, r.second(6, 0, 2000))       // 1 s of audio isn't enough yet...
        assertEquals(Reception.WEAK, r.second(15, 3, 670))      // ...recovered, still marginal
        assertEquals(Reception.GOOD, r.second(24, 0, 340))
        assertEquals(Reception.GOOD, r.second(24, 0, 0))
    }

    @Test fun startingAStationIsNotALoss() {
        val r = Run()
        assertEquals(Reception.GOOD, r.second(0, 0, 2000))       // pre-roll, no AUs yet, measurement not settled
        assertEquals(Reception.GOOD, r.second(0, 0, 0))
        assertEquals(Reception.GOOD, r.second(24, 0, 0))
    }

    @Test fun starvedWithoutTheTunerNoticingIsLostToo() {
        val r = Run()
        repeat(4) { r.second(24, 0, 0) }
        assertEquals(Reception.GOOD, r.second(0, 0, 0))          // 1 s without audio: could be a hiccup
        assertEquals(Reception.LOST, r.second(0, 0, 0))
    }

    @Test fun manyBadAusIsWeak() {
        val r = Run()
        repeat(4) { r.second(24, 0, 0) }
        assertEquals(Reception.WEAK, r.second(18, 6, 300))
    }

    @Test fun lostSinceMarksTheStartOfTheOutage() {
        val r = Run()
        repeat(4) { r.second(24, 0, 0) }
        r.second(0, 0, 2000); r.second(0, 0, 2000)
        assertEquals(5500L, r.m.lostSince)                        // no signal for 1 s, no audio
    }
}
