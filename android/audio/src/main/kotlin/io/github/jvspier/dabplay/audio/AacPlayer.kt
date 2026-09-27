package io.github.jvspier.dabplay.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Decodes DAB+ access units with MediaCodec (platform FDK decoder, verified on API 26/29) and plays the PCM
 * through an AudioTrack. Not thread-safe: call it from one thread.
 */
class AacPlayer {
    private var codec: MediaCodec? = null
    private var asc: ByteArray? = null
    private var track: AudioTrack? = null
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    var decoderName: String? = null; private set

    /** Queue one AU ([asc] = its AudioSpecificConfig, [outputRate] = e.g. 32000/48000). */
    fun feed(asc: ByteArray, au: ByteArray, outputRate: Int) {
        if (!asc.contentEquals(this.asc)) configure(asc, outputRate)
        val c = codec ?: return
        val i = c.dequeueInputBuffer(20_000)
        if (i >= 0) {
            c.getInputBuffer(i)!!.apply { clear(); put(au) }
            c.queueInputBuffer(i, 0, au.size, pts, 0)
            pts += 960L * 1_000_000 / outputRate * 2   // informational only
        }
        drain()
    }

    private fun configure(asc: ByteArray, outputRate: Int) {
        release()
        val name = AacDecoders.pick() ?: error("no AAC decoder")
        val fmt = MediaFormat.createAudioFormat(AacDecoders.MIME, outputRate, 2).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(asc))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
        }
        codec = MediaCodec.createByCodecName(name).apply { configure(fmt, null, null, 0); start() }
        decoderName = name
        this.asc = asc.copyOf()
        Log.i(TAG, "decoder $name, ASC ${asc.joinToString("") { "%02x".format(it) }}")
    }

    private fun drain() {
        val c = codec ?: return
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    openTrack(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
                o >= 0 -> {
                    val b = c.getOutputBuffer(o)!!
                    val pcm = ByteArray(info.size)
                    b.position(info.offset); b.get(pcm)
                    c.releaseOutputBuffer(o, false)
                    track?.write(pcm, 0, pcm.size)       // blocking: paces the pipeline in real time
                }
                else -> return
            }
        }
    }

    private fun openTrack(rate: Int, channels: Int) {
        track?.release()
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(min * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        Log.i(TAG, "AudioTrack $rate Hz, $channels ch")
    }

    fun release() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        codec = null; asc = null
        runCatching { track?.stop() }; track?.release(); track = null
    }

    private companion object { const val TAG = "AacPlayer" }
}
