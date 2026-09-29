package io.github.jvspier.dabplay.tuner

/**
 * A dongle that hands the host the raw ensemble: the FIC and one sub-channel's bytes, which the app decodes
 * (dab-core). The wRadio C100 family: the DTR-100 register-level driver and the YHX high-level command set.
 * Blocking calls, all made from the engine's one USB thread.
 */
interface RawTuner {
    /** For the log and diagnostics, e.g. "C100 register level, firmware level 4". */
    val description: String

    /** Tune to a band III centre frequency (kHz). [retries]: try a marginal lock again (slower; for the band scan). */
    fun tune(khz: Int, retries: Boolean = false): Tuned

    /** FIC bytes (whole 32-byte FIBs) received since the last call, or null when there is nothing new. Only while tuned. */
    fun ficRead(): ByteArray?

    /** Start streaming one sub-channel of the tuned ensemble. */
    fun startAudio(subchannel: Int, bitrateKbps: Int)
    fun stopAudio()

    /** Sub-channel bytes received since the last call; empty when there are none yet. */
    fun mscRead(): ByteArray

    /** Reception as the dongle last reported it (from its status polls, so reading it is free); null before the first. */
    val signal: SignalReading?
}

/** A tune attempt: locked or not, the bars to show right away, and driver details for the log. */
data class Tuned(val locked: Boolean, val bars: Int = 0, val detail: String = "")

/**
 * Reception in the driver's own terms, mapped by the driver: [bars] 0..4, [none] = nothing receivable (antenna gone),
 * [weak] = expect errors. [raw] is the dongle's value, for the field-diagnostics log.
 */
data class SignalReading(val bars: Int, val none: Boolean, val weak: Boolean, val raw: Int)
