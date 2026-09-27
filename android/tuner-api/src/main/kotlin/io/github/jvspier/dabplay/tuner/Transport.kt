package io.github.jvspier.dabplay.tuner

/**
 * Raw USB bulk transport to a dongle. Android implements it with UsbDeviceConnection (:usb-android);
 * tests implement it with recorded transfers. Keeps the drivers free of Android types.
 */
interface Transport {
    /** Bulk OUT; returns the number of bytes sent, or throws. */
    fun write(endpoint: Int, data: ByteArray, timeoutMs: Int): Int

    /** Bulk IN into [buffer]; returns the number of bytes read, or throws. */
    fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int

    fun close()
}
