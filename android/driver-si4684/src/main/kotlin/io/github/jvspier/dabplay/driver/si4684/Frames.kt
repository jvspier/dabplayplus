package io.github.jvspier.dabplay.driver.si4684

/**
 * Framing of the Si4684 module protocol (docs/protocol-si4684.md).
 * Host → module: `55 10 L2 L1 L0` + `AA cmd NH NL payload BB` + sum of the AA..BB bytes (mod 256) + `11`; L = N + 5,
 * lengths big-endian. Module → host: `AA type NH NL payload BB`, type = cmd | 0x80 (0xB1 = slideshow, unsolicited).
 */
object Frames {
    fun command(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val n = payload.size
        val inner = byteArrayOf(0xAA.toByte(), cmd.toByte(), (n shr 8).toByte(), n.toByte()) + payload + byteArrayOf(0xBB.toByte())
        val sum = inner.sumOf { it.toInt() and 0xFF } and 0xFF
        val l = inner.size
        return byteArrayOf(0x55, 0x10, (l shr 16).toByte(), (l shr 8).toByte(), l.toByte()) + inner + byteArrayOf(sum.toByte(), 0x11)
    }

    /** The module's 41-channel band III list (the Si468x default), which freqIdx indexes. */
    val BAND3: List<Pair<String, Int>> = listOf(
        "5A" to 174928, "5B" to 176640, "5C" to 178352, "5D" to 180064, "6A" to 181936, "6B" to 183648, "6C" to 185360,
        "6D" to 187072, "7A" to 188928, "7B" to 190640, "7C" to 192352, "7D" to 194064, "8A" to 195936, "8B" to 197648,
        "8C" to 199360, "8D" to 201072, "9A" to 202928, "9B" to 204640, "9C" to 206352, "9D" to 208064, "10A" to 209936,
        "10N" to 210096, "10B" to 211648, "10C" to 213360, "10D" to 215072, "11A" to 216928, "11N" to 217088,
        "11B" to 218640, "11C" to 220352, "11D" to 222064, "12A" to 223936, "12N" to 224096, "12B" to 225648,
        "12C" to 227360, "12D" to 229072, "13A" to 230784, "13B" to 232496, "13C" to 234208, "13D" to 235776,
        "13E" to 237488, "13F" to 239200,
    )
}

/**
 * Module → host frames from a byte stream: a byte-wise state machine whose state survives USB reads, so frames split
 * over reads and several frames per read both work. Resynchronises like DABdream+: a length over [MAX] or a missing
 * 0xBB drops that byte and hunts for the next 0xAA. No checksum is checked (DABdream+ doesn't either).
 */
class FrameParser(private val onFrame: (type: Int, payload: ByteArray) -> Unit) {
    private var state = 0                  // 0 idle, 1 type, 2 len hi, 3 len lo, 4 data, 5 footer
    private var type = 0
    private var len = 0
    private var fill = 0
    private val buf = ByteArray(MAX)

    fun feed(b: ByteArray, n: Int = b.size) {
        for (i in 0 until n) {
            val v = b[i].toInt() and 0xFF
            when (state) {
                0 -> if (v == 0xAA) state = 1
                1 -> { type = v; state = 2 }
                2 -> { len = v shl 8; state = 3 }
                3 -> { len = len or v; fill = 0; state = if (len > MAX) 0 else if (len == 0) 5 else 4 }
                4 -> { buf[fill++] = v.toByte(); if (fill >= len) state = 5 }
                5 -> { if (v == 0xBB) onFrame(type, buf.copyOf(len)); state = 0 }
            }
        }
    }

    companion object { const val MAX = 16384 }
}
