package io.github.jvspier.dabplay.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import io.github.jvspier.dabplay.tuner.Transport
import java.io.IOException

/** [Transport] over the Android USB host API (bulkTransfer on the claimed interface's endpoints). */
class AndroidUsbTransport(
    private val connection: UsbDeviceConnection,
    private val endpoints: Map<Int, UsbEndpoint>,
) : Transport {
    override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val n = connection.bulkTransfer(ep(endpoint), data, data.size, timeoutMs)
        if (n < 0) throw IOException("bulk OUT to 0x%02x failed".format(endpoint))
        return n
    }

    override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        val n = connection.bulkTransfer(ep(endpoint), buffer, buffer.size, timeoutMs)
        if (n < 0) throw IOException("bulk IN from 0x%02x failed".format(endpoint))
        return n
    }

    override fun close() = connection.close()

    private fun ep(address: Int) = endpoints[address] ?: error("no endpoint 0x%02x".format(address))
}
