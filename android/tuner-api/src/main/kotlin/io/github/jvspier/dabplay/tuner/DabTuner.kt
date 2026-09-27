package io.github.jvspier.dabplay.tuner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A DAB tuner driver. Everything above this interface (FIC, audio, PAD, UI) is shared by all dongles. */
interface DabTuner {
    val capabilities: Capabilities
    val signal: StateFlow<Signal>

    suspend fun open()
    suspend fun close()

    /** Tune to a centre frequency in kHz (band III: 174928..239200). */
    suspend fun tune(freqKhz: Int): TuneResult

    /** FIC data (multiples of 32-byte FIBs) while tuned. */
    val fic: Flow<ByteArray>

    suspend fun startSubchannel(subchannel: Int, bitrateKbps: Int)
    suspend fun stopSubchannel()

    /** Raw sub-channel (MSC) bytes of the started sub-channel. */
    val msc: Flow<ByteArray>
}

data class Capabilities(val bandIII: Boolean = true, val lBand: Boolean = false, val firmwareLevel: Int? = null)

data class Signal(val bars: Int, val level: Int? = null, val quality: Int? = null) {
    companion object { val NONE = Signal(0) }
}

sealed interface TuneResult {
    data class Locked(val signal: Signal) : TuneResult
    data class NoSignal(val reason: String) : TuneResult
}
