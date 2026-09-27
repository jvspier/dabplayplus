package io.github.jvspier.dabplay.audio

import android.media.MediaCodecList

/** DAB+ AAC decoding goes through MediaCodec (verified on API 26/29); prefer the platform FDK decoder. */
object AacDecoders {
    val PREFERRED = listOf("c2.android.aac.decoder", "OMX.google.aac.decoder")
    const val MIME = "audio/mp4a-latm"

    fun pick(): String? {
        val names = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
            .map { it.name }
        return PREFERRED.firstOrNull { it in names } ?: names.firstOrNull()
    }
}
