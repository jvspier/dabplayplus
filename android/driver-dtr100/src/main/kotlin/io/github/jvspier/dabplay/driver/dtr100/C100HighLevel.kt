package io.github.jvspier.dabplay.driver.dtr100

import io.github.jvspier.dabplay.tuner.RawTuner
import io.github.jvspier.dabplay.tuner.Transport

/** The YHX high-level command set ("C100 Dual Antenna"). Placeholder: declines until the protocol is written up. */
internal object C100HighLevel {
    fun open(t: Transport, log: (String) -> Unit): RawTuner? = null
}
