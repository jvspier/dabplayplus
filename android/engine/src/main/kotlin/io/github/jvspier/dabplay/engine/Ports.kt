package io.github.jvspier.dabplay.engine

import io.github.jvspier.dabplay.tuner.Transport

/**
 * A dongle as the engine sees it. The app opens it (USB permission, interface claim: AndroidDongle); tests hand in
 * fakes with a scripted [Transport].
 */
interface Dongle {
    /** Stable for this connection (Android's device name): an unplug report is matched against it. */
    val name: String
    /** The family, from the USB id ([DriverRegistry]); null: not one of ours. */
    val kind: DongleKind?
    /** For the log: USB id, manufacturer and product, so a report from someone else's dongle says what it is. */
    val details: String get() = name
    /** Open and claim it. Throws when that fails (the engine then retries, as after any dongle error), or [UnsupportedDongle]. */
    fun open(): OpenDongle
}

/** A device with a dongle's USB id that isn't one its driver can talk to ([DriverRegistry.fits]): reported, not retried. */
class UnsupportedDongle(message: String) : Exception(message)

/** An opened dongle: its transport, and closing the connection afterwards. */
interface OpenDongle {
    val transport: Transport
    fun close()
}

/** Where decoded audio goes (the app's AudioPlayer). Fed from one thread per kind of feed; never blocks. */
interface AudioOut {
    fun feedAac(asc: ByteArray, au: ByteArray, outputRate: Int)
    fun feedMp2(frame: ByteArray)
    fun feedPcm(data: ByteArray, rate: Int, channels: Int)
    fun feedSilence(ms: Int)
    fun setLevel(level: Float)
    fun release()
}

/** What another app's audio means for the radio. */
enum class FocusChange {
    /** Another media app took over for good: pause. */
    LOST,
    /** A phone call or voice assistant, for a while: go silent (keep receiving, so it's live again at once). */
    SILENCE,
    /** A navigation prompt: turn down while it speaks. */
    DUCK,
    /** Focus is back: normal volume. */
    RESTORE,
}

/** Media audio focus (the app's AudioFocus). */
interface Focus {
    /** Ask for focus, or keep it if held; [onChange] replaces the previous callback. False: denied. */
    fun request(onChange: (FocusChange) -> Unit): Boolean
    fun abandon()
}

/** The engine's log (the app's Diagnostics: logcat plus a file to pull after a drive). */
interface EngineLog {
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
    /** Listening content (song texts): logcat only, never the file. */
    fun logcatOnly(tag: String, msg: String)
}

/** The engine's waits; tests shorten them. Defaults are the values tested in the car emulator. */
data class EngineTimings(
    /** Signal gone this long before trying another ensemble. */
    val followAfterMs: Long = 6_000L,
    /** Then again every so often while it stays gone. */
    val followRetryMs: Long = 30_000L,
    /** Retune attempts on the station's own frequency while nothing locks. */
    val relockEveryMs: Long = 5_000L,
    /** No reply from a Si4684 module for this long while playing: reconnect. */
    val moduleSilentMs: Long = 10_000L,
    /** Store learned alternatives at most this often. */
    val learnEveryMs: Long = 10_000L,
    /** Reconnect backoff after a dongle error: base, doubling, up to max. */
    val reconnectBaseMs: Long = 500L,
    val reconnectMaxMs: Long = 8_000L,
)
