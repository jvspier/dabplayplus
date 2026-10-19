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

/** Opening a dongle's driver by family: [DriverRegistry] in the app, scripted tuners in tests. */
interface Drivers {
    /** The family's driver if it hands over the raw ensemble (the host decodes); null for module dongles. */
    fun rawTuner(kind: DongleKind, t: Transport, log: (String) -> Unit): RawTuner?
    /** The family's driver if the dongle decodes by itself; null for raw ones. */
    fun moduleTuner(kind: DongleKind, t: Transport, listener: ModuleListener, log: (String) -> Unit): ModuleTuner?
}

/**
 * The one place that knows the drivers: which USB ids are which family, and how to open each. A new dongle family is
 * a new [DongleKind] and a line here; the engine only sees [RawTuner] / [ModuleTuner].
 */
/** One endpoint of a dongle's interface 0, from its USB descriptors. */
data class EndpointInfo(val address: Int, val bulk: Boolean)

object DriverRegistry : Drivers {
    /**
     * Whether a device with the family's USB id has what its driver talks to. 16c0:05dc is a shared V-USB id (USBasp
     * programmers and other hobby devices use it too): a C100 has bulk OUT 0x02 and bulk IN 0x82, which low-speed V-USB
     * devices can't have. The manufacturer name differs between C100 makers, and the vendor apps and DABdream+ don't
     * look at it (issue #1). The other families have ids of their own.
     */
    fun fits(kind: DongleKind, endpoints: List<EndpointInfo>): Boolean = when (kind) {
        DongleKind.C100 -> listOf(Dtr100.EP_OUT, Dtr100.EP_IN).all { ep -> endpoints.any { it.bulk && it.address == ep } }
        else -> true
    }

    fun kindOf(vendorId: Int, productId: Int): DongleKind? = when {
        vendorId == Dtr100.VID && productId == Dtr100.PID -> DongleKind.C100
        vendorId == Si4684.VID && productId == Si4684.PID -> DongleKind.SI4684
        vendorId == Siano.VID && productId in Siano.PIDS -> DongleKind.SIANO
        else -> null
    }


    override fun rawTuner(kind: DongleKind, t: Transport, log: (String) -> Unit): RawTuner? = when (kind) {
        DongleKind.C100 -> C100.open(t, log = log)
        DongleKind.SIANO -> Siano.open(t, log = log)
        DongleKind.SI4684 -> null
    }

    override fun moduleTuner(kind: DongleKind, t: Transport, listener: ModuleListener, log: (String) -> Unit): ModuleTuner? = when (kind) {
        DongleKind.SI4684 -> Si4684.open(t, listener, log)
        else -> null
    }
}
