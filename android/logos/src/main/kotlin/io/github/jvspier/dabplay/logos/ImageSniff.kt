package io.github.jvspier.dabplay.logos

data class ImageInfo(val mime: String, val width: Int, val height: Int)

/**
 * Reads image type and size from the header bytes, so the logo chain can reject what isn't a usable bitmap
 * (ICO, SVG, GIF, HTML error pages served as 200, tiny favicons) without decoding it.
 */
object ImageSniff {
    fun info(b: ByteArray): ImageInfo? = png(b) ?: jpeg(b) ?: webp(b)

    /** Big enough for a tile, and not a banner: the tile fits it inside a square. */
    fun usable(i: ImageInfo?) = i != null && minOf(i.width, i.height) >= MIN_SIDE &&
        maxOf(i.width, i.height).toFloat() / minOf(i.width, i.height) <= 2.5f

    const val MIN_SIDE = 96

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) = (be16(b, i) shl 16) or be16(b, i + 2)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = le16(b, i) or (u8(b, i + 2) shl 16)

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    private fun png(b: ByteArray): ImageInfo? {
        if (b.size < 24 || !b.copyOfRange(0, 8).contentEquals(PNG_SIG)) return null
        return ImageInfo("image/png", be32(b, 16), be32(b, 20))
    }

    private fun jpeg(b: ByteArray): ImageInfo? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var i = 2
        while (i + 9 < b.size) {
            if (u8(b, i) != 0xFF) return null
            val m = u8(b, i + 1)
            if (m == 0xFF) { i++; continue }
            val len = be16(b, i + 2)
            // SOF0..SOF15 except DHT (C4), JPG (C8), DAC (CC)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return ImageInfo("image/jpeg", be16(b, i + 7), be16(b, i + 5))
            i += 2 + len
        }
        return null
    }

    private fun webp(b: ByteArray): ImageInfo? {
        if (b.size < 30 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WEBP") return null
        return when (String(b, 12, 4)) {
            "VP8 " -> ImageInfo("image/webp", le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF)
            "VP8L" -> {
                val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                ImageInfo("image/webp", (bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
            }
            "VP8X" -> ImageInfo("image/webp", le24(b, 24) + 1, le24(b, 27) + 1)
            else -> null
        }
    }
}
