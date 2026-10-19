package io.github.jvspier.dabplay.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 16c0:05dc is a shared V-USB id: what's behind it decides, not the manufacturer name (issue #1). */
class DriverRegistryTest {
    private val c100 = listOf(EndpointInfo(0x02, bulk = true), EndpointInfo(0x82, bulk = true))

    @Test fun aC100FitsWhateverItsManufacturerName() {
        assertTrue(DriverRegistry.fits(DongleKind.C100, c100))
        assertTrue(DriverRegistry.fits(DongleKind.C100, c100.reversed()))
    }

    @Test fun aUsbaspOnTheSameIdDoesNot() {
        assertFalse(DriverRegistry.fits(DongleKind.C100, emptyList()))                                   // control only
        assertFalse(DriverRegistry.fits(DongleKind.C100, listOf(EndpointInfo(0x81, bulk = false))))      // V-USB HID
    }

    @Test fun bothBulkEndpointsMustBeThere() {
        assertFalse(DriverRegistry.fits(DongleKind.C100, listOf(EndpointInfo(0x02, bulk = true))))
        assertFalse(DriverRegistry.fits(DongleKind.C100, listOf(EndpointInfo(0x02, bulk = true), EndpointInfo(0x82, bulk = false))))
        assertFalse(DriverRegistry.fits(DongleKind.C100, listOf(EndpointInfo(0x01, bulk = true), EndpointInfo(0x81, bulk = true))))
    }

    @Test fun theOtherFamiliesHaveTheirOwnIds() {
        assertTrue(DriverRegistry.fits(DongleKind.SIANO, emptyList()))
        assertTrue(DriverRegistry.fits(DongleKind.SI4684, emptyList()))
    }
}
