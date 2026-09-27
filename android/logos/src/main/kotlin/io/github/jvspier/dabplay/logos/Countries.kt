package io.github.jvspier.dabplay.logos

/**
 * ISO 3166 country for a DAB service: Extended Country Code + the SId's country nibble (ETSI TS 101 756, table 3,
 * Europe: ECC E0..E4). Only NL (E3/8) is verified on air; unknown combinations return null (search without a country).
 */
object Countries {
    private val TABLE: Map<Int, String> = buildMap {
        fun row(ecc: Int, vararg cc: String?) = cc.forEachIndexed { i, c -> if (c != null) put((ecc shl 4) or (i + 1), c) }
        //        1     2     3     4     5     6     7     8     9     A     B     C     D     E     F
        row(0xE0, "DE", "DZ", "AD", "IL", "IT", "BE", "RU", "PS", "AL", "AT", "HU", "MT", "DE", null, "EG")
        row(0xE1, "GR", "CY", "SM", "CH", "JO", "FI", "LU", "BG", "DK", "GI", "IQ", "GB", "LY", "RO", "FR")
        row(0xE2, "MA", "CZ", "PL", "VA", "SK", "SY", "TN", null, "LI", "IS", "MC", "LT", "RS", "ES", "NO")
        row(0xE3, "ME", "IE", "TR", "MK", null, null, null, "NL", "LV", "LB", "AZ", "HR", "KZ", "SE", "BY")
        row(0xE4, "MD", "EE", "KG", null, null, "UA", "XK", "PT", "SI", "AM", "UZ", "GE", null, "TM", "BA")
    }

    fun of(ecc: Int, sid: Int): String? = TABLE[((ecc and 0xFF) shl 4) or ((sid shr 12) and 0xF)]
}
