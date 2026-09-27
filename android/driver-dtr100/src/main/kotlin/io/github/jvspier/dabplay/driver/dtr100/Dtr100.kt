package io.github.jvspier.dabplay.driver.dtr100

import io.github.jvspier.dabplay.tuner.Transport

/**
 * Driver for the wRadio C100 / DTR-100 family (USB 16c0:05dc, manufacturer "dabusb@126.com").
 * Protocol: docs/protocol.md; reference implementation: tools/dtr100.py. Only transfers found in the
 * vendor library are sent.
 */
class Dtr100(private val t: Transport) {
    var firmwareLevel: Int? = null
        private set

    private val rx = ByteArray(4096 + 64)

    private fun xfer(req: ByteArray, timeoutOut: Int = 1000, timeoutIn: Int = 1000): ByteArray {
        t.write(EP_OUT, req, timeoutOut)
        val n = t.read(EP_IN, rx, timeoutIn)
        return rx.copyOf(n)
    }

    /** FUN_00075200: 00 00 00 00 -> reply bytes 1..3 must be "DAB". */
    fun echo(): Boolean {
        val r = xfer(byteArrayOf(0, 0, 0, 0), 2000, 2000)
        return r.size >= 4 && r[1] == 'D'.code.toByte() && r[2] == 'A'.code.toByte() && r[3] == 'B'.code.toByte()
    }

    /** FUN_00075360: 01 00 00 00 -> 81 .. .. level. Byte 3 selects the MSC code path. */
    fun version(): Int {
        val r = xfer(byteArrayOf(1, 0, 0, 0))
        check(r.size >= 4 && r[0] == 0x81.toByte()) { "version: unexpected reply ${r.hex()}" }
        return (r[3].toInt() and 0xFF).also { firmwareLevel = it }
    }

    companion object {
        const val VID = 0x16C0
        const val PID = 0x05DC
        const val MANUFACTURER = "dabusb@126.com"
        const val EP_OUT = 0x02
        const val EP_IN = 0x82
    }
}

internal fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }
