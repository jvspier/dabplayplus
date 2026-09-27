package io.github.jvspier.dabplay.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

/**
 * Decodes DAB+ access units with MediaCodec (platform FDK decoder, verified on API 26/29) and plays the PCM
 * through an AudioTrack, on its own high-priority audio thread.
 *
 * [feed] never blocks, so the USB thread always keeps up with the dongle (a stalled reader lets the MCU buffer
 * overflow: audible skips). DAB+ audio arrives in 120 ms superframe bursts, so playback starts after a
 * [PREROLL_MS] pre-roll into a ~1 s AudioTrack buffer; after an underrun it pauses and pre-rolls again
 * (one clean gap instead of stuttering).
 */
class AacPlayer {
    private class Au(val asc: ByteArray, val data: ByteArray, val rate: Int)

    private val queue = ArrayBlockingQueue<Au>(QUEUE_AUS)
    @Volatile private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile var decoderName: String? = null; private set

    /** Queue one AU ([asc] = its AudioSpecificConfig, [outputRate] = e.g. 32000/48000). Call from one thread. */
    fun feed(asc: ByteArray, au: ByteArray, outputRate: Int) {
        if (worker == null) worker = Thread({ loop() }, "dab-audio").apply { isDaemon = true; start() }
        val item = Au(asc, au, outputRate)
        if (!queue.offer(item)) { queue.poll(); queue.offer(item) }     // full: drop the oldest, never block
    }

    /** Stop now (station switch, pause): silences at once and frees the decoder and track. */
    fun release() {
        val w = worker ?: return
        worker = null
        w.interrupt()
        track?.let { t -> runCatching { t.pause(); t.flush() } }       // unblocks a pending write
        w.join(1000)
        queue.clear()
    }

    private fun loop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            .onFailure { runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } }
        val s = Stream()
        try {
            while (!Thread.currentThread().isInterrupted) s.decode(queue.take())
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "audio thread", e)
        } finally {
            s.release()
        }
    }

    /** Decoder + track state, only touched by the audio thread. */
    private inner class Stream {
        private var codec: MediaCodec? = null
        private var asc: ByteArray? = null
        private val info = MediaCodec.BufferInfo()
        private var pts = 0L
        private var pcm = ByteArray(0)
        private var prerollBytes = 0
        private var buffered = 0          // bytes written since the last (re)start, until playback starts
        private var underruns = 0

        fun decode(au: Au) {
            if (!au.asc.contentEquals(asc)) configure(au.asc, au.rate)
            val c = codec ?: return
            val i = c.dequeueInputBuffer(20_000)
            if (i >= 0) {
                c.getInputBuffer(i)!!.apply { clear(); put(au.data) }
                c.queueInputBuffer(i, 0, au.data.size, pts, 0)
                pts += 960L * 1_000_000 / au.rate * 2   // informational only
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
                        if (pcm.size < info.size) pcm = ByteArray(info.size)
                        b.position(info.offset); b.get(pcm, 0, info.size)
                        c.releaseOutputBuffer(o, false)
                        write(info.size)
                    }
                    else -> return
                }
            }
        }

        private fun write(n: Int) {
            val t = track ?: return
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING && t.underrunCount != underruns) {
                underruns = t.underrunCount
                t.pause(); buffered = 0                      // ran dry: pre-roll again before resuming
                Log.i(TAG, "underrun (queue ${queue.size} AUs), re-buffering")
            }
            t.write(pcm, 0, n)                               // blocking: paces this thread in real time
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                buffered += n
                if (buffered >= prerollBytes) { t.play(); underruns = t.underrunCount }
            }
        }

        private fun openTrack(rate: Int, channels: Int) {
            track?.release()
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val bytesPerSec = rate * channels * 2
            val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            prerollBytes = bytesPerSec * PREROLL_MS / 1000
            buffered = 0
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 4, bytesPerSec * BUFFER_MS / 1000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            Log.i(TAG, "AudioTrack $rate Hz, $channels ch, pre-roll $PREROLL_MS ms")
        }

        fun release() {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            codec = null; asc = null
            track?.let { runCatching { it.stop() }; it.release() }; track = null
        }
    }

    private companion object {
        const val TAG = "AacPlayer"
        const val PREROLL_MS = 400      // > 3 superframes (120 ms each)
        const val BUFFER_MS = 1000
        const val QUEUE_AUS = 128       // ~2.5 s of AUs (20-60 ms each)
    }
}
