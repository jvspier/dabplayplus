package io.github.jvspier.dabplay.engine

import io.github.jvspier.dabplay.driver.dtr100.C100
import io.github.jvspier.dabplay.driver.dtr100.Dtr100
import io.github.jvspier.dabplay.driver.si4684.Si4684
import io.github.jvspier.dabplay.driver.siano.Siano
import io.github.jvspier.dabplay.tuner.ModuleListener
import io.github.jvspier.dabplay.tuner.ModuleTuner
import io.github.jvspier.dabplay.tuner.RawTuner
import io.github.jvspier.dabplay.tuner.Transport

/** The dongle families the app drives (see README "Supported dongles"). */
enum class DongleKind {
    /** wRadio C100 family: DTR-100 (tested) and the YHX Dual Antenna. The host decodes. */
    C100,
    /** Si4684 modules (Eonon A503, XFA-DAB220): the module decodes. Untested. */
    SI4684,
    /** Siano sticks with firmware on board (Rhine800, Denver TDMB): the host decodes. Untested. */
    SIANO,
}

/**
 * The one place that knows the drivers: which USB ids are which family, and how to open each. A new dongle family is
 * a new [DongleKind] and a line here; the engine only sees [RawTuner] / [ModuleTuner].
 */
object DriverRegistry {
    fun kindOf(vendorId: Int, productId: Int): DongleKind? = when {
        vendorId == Dtr100.VID && productId == Dtr100.PID -> DongleKind.C100
        vendorId == Si4684.VID && productId == Si4684.PID -> DongleKind.SI4684
        vendorId == Siano.VID && productId in Siano.PIDS -> DongleKind.SIANO
        else -> null
    }

    /** A manufacturer string the device must report too, for USB ids shared with other devices (null: none). */
    fun requiredManufacturer(kind: DongleKind): String? = if (kind == DongleKind.C100) Dtr100.MANUFACTURER else null

    /** The family's driver if it hands over the raw ensemble (the host decodes); null for module dongles. */
    fun rawTuner(kind: DongleKind, t: Transport, log: (String) -> Unit): RawTuner? = when (kind) {
        DongleKind.C100 -> C100.open(t, log = log)
        DongleKind.SIANO -> Siano.open(t, log = log)
        DongleKind.SI4684 -> null
    }

    /** The family's driver if the dongle decodes by itself; null for raw ones. */
    fun moduleTuner(kind: DongleKind, t: Transport, listener: ModuleListener, log: (String) -> Unit): ModuleTuner? = when (kind) {
        DongleKind.SI4684 -> Si4684.open(t, listener, log)
        else -> null
    }
}
