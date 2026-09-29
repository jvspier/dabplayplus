package io.github.jvspier.dabplay.core

/**
 * An Extended Country Code for dongles that don't pass the FIC on (Si4684 modules), so stations still get a stable
 * ECC+SId key and RadioDNS can find their logos. The SId's first hex digit is the country id, which several countries
 * share (with different ECCs; ETSI TS 101 756). So: the device's country when its country id matches, else the most
 * likely one per country id (DABdream+'s table).
 */
object EccGuess {
    /** ISO country → (country id, ECC) for the DAB countries. */
    private val COUNTRIES = mapOf(
        "NL" to (0x8 to 0xE3), "BE" to (0x6 to 0xE0), "LU" to (0x7 to 0xE1), "DE" to (0xD to 0xE0), "FR" to (0xF to 0xE1),
        "GB" to (0xC to 0xE1), "IE" to (0x2 to 0xE3), "CH" to (0x4 to 0xE1), "AT" to (0xA to 0xE0), "IT" to (0x5 to 0xE0),
        "ES" to (0xE to 0xE2), "PT" to (0x8 to 0xE4), "DK" to (0x9 to 0xE1), "NO" to (0xF to 0xE2), "SE" to (0xE to 0xE3),
        "FI" to (0x6 to 0xE1), "PL" to (0x3 to 0xE2), "CZ" to (0x2 to 0xE2), "SK" to (0x5 to 0xE2), "SI" to (0x9 to 0xE4),
        "HR" to (0xC to 0xE3), "HU" to (0xB to 0xE0), "MT" to (0xC to 0xE0),
    )
    private val BY_COUNTRY_ID = intArrayOf(0xE0, 0xE0, 0xE0, 0xE0, 0xE4, 0xE0, 0xE0, 0xE0, 0xE3, 0xE1, 0xE0, 0xE0, 0xE1, 0xE0, 0xE3, 0xE2)

    fun ecc(sid: Long, deviceCountry: String?): Int {
        val id = ((sid shr 12) and 0xF).toInt()
        COUNTRIES[deviceCountry?.uppercase()]?.let { (cid, ecc) -> if (cid == id) return ecc }
        return BY_COUNTRY_ID[id]
    }
}
