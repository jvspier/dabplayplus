package io.github.jvspier.dabplay.engine

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

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

/**
 * Media audio focus (API 26+). We duck ourselves (setWillPauseWhenDucked) instead of leaving it to the system, so it's
 * the same smooth fade on every head unit, and transient losses (calls) are silenced, which the system doesn't do.
 */
class AudioFocus(private val am: AudioManager) {
    private var request: AudioFocusRequest? = null
    @Volatile private var onChange: (FocusChange) -> Unit = {}

    /**
     * Ask for focus, or keep it if we hold it already: abandoning and asking again on every station change would fail
     * during a phone call (the radio then stayed off after the call). [onChange] replaces the previous callback.
     */
    @Synchronized
    fun request(onChange: (FocusChange) -> Unit): Boolean {
        this.onChange = onChange
        if (request != null) return true
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener {
                when (it) {
                    AudioManager.AUDIOFOCUS_LOSS -> { abandon(); this.onChange(FocusChange.LOST) }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> this.onChange(FocusChange.SILENCE)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> this.onChange(FocusChange.DUCK)
                    AudioManager.AUDIOFOCUS_GAIN -> this.onChange(FocusChange.RESTORE)
                }
            }
            .build()
        if (am.requestAudioFocus(r) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        request = r
        return true
    }

    @Synchronized
    fun abandon() { request?.let { am.abandonAudioFocusRequest(it) }; request = null }
}
