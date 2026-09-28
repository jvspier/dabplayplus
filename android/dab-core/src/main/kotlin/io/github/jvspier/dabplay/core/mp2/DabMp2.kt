package io.github.jvspier.dabplay.core.mp2

/**
 * Splits a DAB (MP2) sub-channel stream into Layer II frames. The frames sit back to back (one per 24 ms logical frame
 * at 48 kHz, one per two at 24 kHz); the stream from the dongle can start anywhere, so it looks for a header whose
 * frame is followed by another header, locks on, and falls back to searching when a frame doesn't start with one.
 */
class Mp2Framer {
    private var buf = ByteArray(0)
    private var locked = false
    var resyncs = 0; private set

    fun feed(data: ByteArray, out: (frame: ByteArray, header: Mp2Header) -> Unit) {
        buf += data
        var pos = 0
        while (pos + 4 <= buf.size) {
            val h = Mp2Header.parse(buf, pos)
            if (h == null || h.frameBytes < 4) { if (locked) { locked = false; resyncs++ }; pos++; continue }
            val n = h.frameBytes
            if (!locked) {
                // confirm: the next frame must start right after this one (a random 0xFFF in audio data won't)
                if (pos + n + 4 > buf.size) break
                val next = Mp2Header.parse(buf, pos + n)
                if (next == null || next.sampleRate != h.sampleRate || next.lsf != h.lsf) { pos++; continue }
                locked = true
            }
            if (pos + n > buf.size) break
            out(buf.copyOfRange(pos, pos + n), h)
            pos += n
        }
        buf = buf.copyOfRange(pos, buf.size)
        if (buf.size > 1 shl 16) { buf = ByteArray(0); locked = false }     // garbage, never a frame: start over
    }
}

/** PAD in a DAB audio frame (EN 300 401 §7.4): `[audio …][X-PAD, reversed][ScF-CRC][F-PAD (2)]` at the frame's end. */
object DabMp2Pad {
    /**
     * ScF-CRC length: 4 bytes at 48 kHz from 56 kbit/s up, 2 bytes at the half rate and at 32/48 kbit/s.
     * [hypothesis] per EN 300 401 §7.4.3 as other DAB decoders read it; untested on air (no DAB MP2 in NL).
     */
    fun scfCrcLen(h: Mp2Header) = if (!h.lsf && h.bitrateKbps >= 56) 4 else 2

    /** (X-PAD bytes in stored, i.e. reversed, order; F-PAD): the form PadDecoder.feedPad takes. */
    fun split(frame: ByteArray, h: Mp2Header): Pair<ByteArray, ByteArray>? {
        val end = h.frameBytes
        if (frame.size < end || end < 2 + scfCrcLen(h)) return null
        val fpad = frame.copyOfRange(end - 2, end)
        val xEnd = end - 2 - scfCrcLen(h)
        return frame.copyOfRange(maxOf(0, xEnd - MAX_XPAD), xEnd) to fpad
    }

    /** The largest X-PAD a DAB audio frame can carry (EN 300 401 §7.4.2.2: up to 196 bytes). */
    private const val MAX_XPAD = 196
}
