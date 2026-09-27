package io.github.jvspier.dabplay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrcTest {
    @Test fun crc16CcittCheckValue() {
        // standard check value of CRC-16/CCITT-FALSE
        assertEquals(0x29B1, Crc.crc16Ccitt("123456789".toByteArray()))
    }

    @Test fun fibWithCorrectCrcIsAccepted() {
        val fib = ByteArray(32) { 0xFF.toByte() }
        val crc = Crc.crc16Ccitt(fib, 0, 30) xor 0xFFFF
        fib[30] = (crc shr 8).toByte(); fib[31] = crc.toByte()
        assertTrue(Crc.fibOk(fib))
        fib[5] = 0x12
        assertFalse(Crc.fibOk(fib))
    }

    @Test fun firecodeMatchesPythonReference() {
        // value computed with tools/dabplus.firecode(bytes(range(1, 10)))
        assertEquals(14548, Crc.firecode(ByteArray(9) { (it + 1).toByte() }, 0))
    }
}
