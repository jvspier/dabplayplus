package io.github.jvspier.dabplay.tuner

/**
 * A dongle that decodes by itself (Si4684 modules): it scans, keeps the station list and delivers finished audio,
 * text and slides. There is no FIC, so no real ensemble id, no ECC and no alternative frequencies on the host side;
 * the module does its own service following.
 */
interface ModuleTuner {
    /** For the log and diagnostics. */
    val description: String

    /**
     * The module's own band scan. Blocks until it reports 100 % and hands over its list; [progress] gets the percentage
     * and the number of services found so far. Throws when the module doesn't finish within [timeoutMs].
     */
    fun scan(progress: (percent: Int, found: Int) -> Unit, timeoutMs: Long = 300_000): List<ModuleService>

    /**
     * Select a service. Audio, text and slides follow through the [ModuleListener] once the module confirms it.
     * [serviceFollowing]: let the module switch to another frequency of the same service by itself.
     */
    fun play(service: ModuleService, serviceFollowing: Boolean)

    /** Stop forwarding audio, text and slides. */
    fun stop()

    /** Reception as the module last reported it; null before its first status. */
    val signal: SignalReading?

    /** True while the module's service following is switching frequency. */
    val following: Boolean

    /** Time since the module last sent anything. It answers continuously while alive, so a long silence means a dead link. */
    val silentForMs: Long

    fun close()
}

/** Called from the driver's own threads: [onPcm] from the audio thread (always the same one), the rest from another. */
interface ModuleListener {
    /** 16-bit little-endian interleaved PCM, whole sample frames. */
    fun onPcm(data: ByteArray, sampleRate: Int, channels: Int)
    fun onStarted() {}
    fun onServiceNotFound() {}
    /** Dynamic label text (DLS), already decoded. */
    fun onText(text: String) {}
    /** A slideshow image as the module delivered it (JPEG or PNG bytes). */
    fun onSlide(bytes: ByteArray) {}
}

/**
 * A service in the module's list. [freqIdx] indexes the module's 41-channel band III table; [cid] is the module's
 * handle for the service component, echoed back when selecting it.
 */
data class ModuleService(
    val freqIdx: Int, val freqKhz: Int, val channel: String, val sid: Long, val cid: Int,
    val label: String, val ensembleLabel: String, val pty: Int,
)
