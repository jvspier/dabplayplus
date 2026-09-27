package io.github.jvspier.dabplay.logos

/** RadioDNS (ETSI TS 103 270) names for DAB services. */
object RadioDns {
    /**
     * FQDN for a DAB service: `<scids>.<sid>.<eid>.<gcc>.dab.radiodns.org`, where gcc = the SId's
     * country nibble + ECC, e.g. 538 (ECC E3, EId 8008, SId 83C7) -> 0.83c7.8008.8e3.dab.radiodns.org.
     */
    fun dabFqdn(ecc: Int, eid: Int, sid: Int, scids: Int = 0): String {
        val gcc = "%x%02x".format((sid shr 12) and 0xF, ecc and 0xFF)
        return "%x.%04x.%04x.%s.dab.radiodns.org".format(scids, sid and 0xFFFF, eid and 0xFFFF, gcc)
    }
}
