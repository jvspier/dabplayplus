package io.github.jvspier.dabplay

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.github.jvspier.dabplay.engine.Dongle
import io.github.jvspier.dabplay.engine.DongleKind
import io.github.jvspier.dabplay.engine.DriverRegistry
import io.github.jvspier.dabplay.engine.OpenDongle
import io.github.jvspier.dabplay.tuner.Transport
import io.github.jvspier.dabplay.usb.AndroidUsbTransport

/** The dongle family of a USB device, from its id; null: not one of ours. */
fun usbDongleKind(dev: UsbDevice): DongleKind? = DriverRegistry.kindOf(dev.vendorId, dev.productId)

/** A USB dongle for the engine: opening means USB host access and claiming interface 0's bulk endpoints. */
class AndroidDongle(private val usb: UsbManager, private val dev: UsbDevice) : Dongle {
    override val name: String = dev.deviceName
    override val kind: DongleKind? = usbDongleKind(dev)

    override fun open(): OpenDongle {
        // check before claiming: 16c0:05dc is a shared V-USB id, don't grab someone else's device
        kind?.let(DriverRegistry::requiredManufacturer)?.let { m ->
            check(dev.manufacturerName == m) { "unsupported dongle (manufacturer '${dev.manufacturerName}')" }
        }
        val conn = usb.openDevice(dev) ?: error("cannot open the USB device")
        try {
            val intf = dev.getInterface(0)
            check(conn.claimInterface(intf, true)) { "cannot claim interface 0" }
            val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }.associateBy { it.address }
            val transport = AndroidUsbTransport(conn, eps)
            return object : OpenDongle {
                override val transport: Transport = transport
                override fun close() = conn.close()
            }
        } catch (e: Throwable) {
            conn.close(); throw e
        }
    }
}
