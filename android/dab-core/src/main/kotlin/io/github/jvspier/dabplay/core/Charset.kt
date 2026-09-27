package io.github.jvspier.dabplay.core

/** DAB text charsets (ETSI TS 101 756): 0 = EBU Latin (complete), 6 = UCS-2 BE, 15 = UTF-8. */
object DabText {
    // The part of EBU Latin that differs from ASCII/Latin-1 and matters for West-European text.
    // [partial; same table as tools/pad.py]
    private val EBU = mapOf(
        0x80 to 'á', 0x81 to 'à', 0x82 to 'é', 0x83 to 'è', 0x84 to 'í', 0x85 to 'ì', 0x86 to 'ó', 0x87 to 'ò',
        0x88 to 'ú', 0x89 to 'ù', 0x8A to 'Ñ', 0x8B to 'Ç', 0x8C to 'Ş', 0x8D to 'ß', 0x8E to '¡', 0x8F to 'Ĳ',
        0x90 to 'â', 0x91 to 'ä', 0x92 to 'ê', 0x93 to 'ë', 0x94 to 'î', 0x95 to 'ï', 0x96 to 'ô', 0x97 to 'ö',
        0x98 to 'û', 0x99 to 'ü', 0x9A to 'ñ', 0x9B to 'ç', 0x9C to 'ş', 0x9D to 'ğ', 0x9E to 'ı', 0x9F to 'ĳ',
        0xC0 to 'Á', 0xC1 to 'À', 0xC2 to 'É', 0xC3 to 'È', 0xC4 to 'Í', 0xC5 to 'Ì', 0xC6 to 'Ó', 0xC7 to 'Ò',
        0xC8 to 'Ú', 0xC9 to 'Ù', 0xCA to 'Ř', 0xCB to 'Č', 0xCC to 'Š', 0xCD to 'Ž', 0xCE to 'Đ', 0xCF to 'Ŀ',
        0xD0 to 'Â', 0xD1 to 'Ä', 0xD2 to 'Ê', 0xD3 to 'Ë', 0xD4 to 'Î', 0xD5 to 'Ï', 0xD6 to 'Ô', 0xD7 to 'Ö',
        0xD8 to 'Û', 0xD9 to 'Ü', 0xDA to 'ř', 0xDB to 'č', 0xDC to 'š', 0xDD to 'ž', 0xDE to 'đ', 0xDF to 'ŀ',
    )

    fun decode(b: ByteArray, from: Int = 0, to: Int = b.size, charset: Int = 0): String = when (charset) {
        15 -> String(b, from, to - from, Charsets.UTF_8)
        6 -> String(b, from, to - from, Charsets.UTF_16BE)
        else -> buildString { for (i in from until to) { val c = b[i].toInt() and 0xFF; append(EBU[c] ?: c.toChar()) } }
    }
}

internal fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
