package io.github.jvspier.dabplay.core

/** CRCs used by DAB (ETSI EN 300 401, TS 102 563). Ported from tools/fic.py and tools/dabplus.py. */
object Crc {
    /** CRC-16 CCITT (poly 0x1021, init 0xFFFF), as used for FIBs, AUs, PAD data groups. */
    fun crc16Ccitt(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    /** A FIB is 30 data bytes + CRC-16 CCITT stored inverted. */
    fun fibOk(fib: ByteArray, offset: Int = 0): Boolean {
        val crc = crc16Ccitt(fib, offset, offset + 30) xor 0xFFFF
        val stored = ((fib[offset + 30].toInt() and 0xFF) shl 8) or (fib[offset + 31].toInt() and 0xFF)
        return crc == stored
    }

    /** DAB+ superframe fire code over 9 bytes, g(x) = x^16+x^14+x^13+x^12+x^11+x^5+x^3+x^2+x+1. */
    fun firecode(data: ByteArray, from: Int, len: Int = 9): Int {
        var crc = 0
        for (i in from until from + len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x782F else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }
}
