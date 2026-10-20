package io.github.jvspier.dabplay.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportTest {
    private val dtr100 = UsbInfo(0x16C0, 0x05DC, "dabusb@126.com", "DAB USB Dongle",
        listOf(UsbInterfaceInfo(0xFF, listOf(UsbEndpointInfo(0x02, "bulk"), UsbEndpointInfo(0x82, "bulk")))))

    @Test fun aC100IsNamedWithItsVerdict() {
        assertEquals("16c0:05dc 'dabusb@126.com' / 'DAB USB Dongle'; interface 0 class ff: 02 bulk, 82 bulk → C100 dongle",
            usbLine(dtr100))
    }

    @Test fun aDeviceWithTheIdButNotTheEndpointsSaysSo() {
        val usbasp = UsbInfo(0x16C0, 0x05DC, "www.fischl.de", "USBasp", listOf(UsbInterfaceInfo(0x00, emptyList())))
        assertEquals("16c0:05dc 'www.fischl.de' / 'USBasp'; interface 0 class 00: no endpoints → has a C100 dongle's id, but not its endpoints",
            usbLine(usbasp))
    }

    @Test fun anUnknownDeviceIsListedToo() {
        val stick = UsbInfo(0x0BDA, 0x2838, null, "RTL2838UHIDIR", listOf(UsbInterfaceInfo(0xFF, listOf(UsbEndpointInfo(0x81, "bulk")))))
        assertEquals("0bda:2838 '' / 'RTL2838UHIDIR'; interface 0 class ff: 81 bulk → not a dongle DABplay+ knows", usbLine(stick))
    }

    @Test fun theReportHasTheHeaderTheDevicesAndBothLogsOldestFirst() {
        val r = report(listOf("DABplay+ 1.3.0", "Android 10"), listOf(dtr100), older = "old line\n", newer = "new line\n")
        assertTrue(r.startsWith("DABplay+ 1.3.0\nAndroid 10\n"))
        assertTrue("USB devices connected now:\n  16c0:05dc" in r)
        assertTrue(r.indexOf("old line") < r.indexOf("new line"), r)
    }

    @Test fun nothingConnectedAndNoOlderLog() {
        val r = report(listOf("DABplay+"), emptyList(), older = null, newer = "only\n")
        assertTrue("USB devices connected now:\n  (none)" in r, r)
        assertTrue("only" in r && "dabplay.1.log" !in r, r)
    }
}
