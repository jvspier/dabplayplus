package io.github.jvspier.dabplay.logos

import kotlin.test.Test
import kotlin.test.assertEquals

class RadioDnsTest {
    @Test fun names538AsVerifiedWithDns() {
        // resolved to talpa_network_radio.radiodns.metadata.radio on 2026-09-24
        assertEquals("0.83c7.8008.8e3.dab.radiodns.org", RadioDns.dabFqdn(ecc = 0xE3, eid = 0x8008, sid = 0x83C7))
    }

    @Test fun namesOmroepZeeland() {
        assertEquals("0.841b.805b.8e3.dab.radiodns.org", RadioDns.dabFqdn(0xE3, 0x805B, 0x841B))
    }
}
