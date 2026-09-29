package io.github.jvspier.dabplay.engine

import android.hardware.usb.UsbDevice
import io.github.jvspier.dabplay.driver.dtr100.Dtr100
import io.github.jvspier.dabplay.driver.si4684.Si4684

/** The dongle families the app drives, by USB id (see README "Supported dongles"). */
enum class DongleKind {
    /** wRadio C100 family: DTR-100 (tested) and the YHX Dual Antenna. The host decodes. */
    C100,
    /** Si4684 modules (Eonon A503, XFA-DAB220): the module decodes. Untested. */
    SI4684;

    companion object {
        fun of(dev: UsbDevice): DongleKind? = when {
            dev.vendorId == Dtr100.VID && dev.productId == Dtr100.PID -> C100
            dev.vendorId == Si4684.VID && dev.productId == Si4684.PID -> SI4684
            else -> null
        }
    }
}
