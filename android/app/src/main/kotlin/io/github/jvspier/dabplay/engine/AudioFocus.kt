package io.github.jvspier.dabplay.engine

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Media audio focus (API 26+). Transient losses (navigation prompts) are ducked by the system automatically. */
class AudioFocus(private val am: AudioManager) {
    private var request: AudioFocusRequest? = null

    fun request(onLoss: () -> Unit): Boolean {
        abandon()
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS) onLoss() }
            .build()
        request = r
        return am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() { request?.let { am.abandonAudioFocusRequest(it) }; request = null }
}
