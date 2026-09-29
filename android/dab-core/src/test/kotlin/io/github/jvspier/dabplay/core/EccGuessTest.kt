package io.github.jvspier.dabplay.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EccGuessTest {
    @Test fun theDevicesCountryWinsWhenItsCountryIdMatches() {
        assertEquals(0xE3, EccGuess.ecc(0x83C7, "NL"))        // 538 in the Netherlands
        assertEquals(0xE4, EccGuess.ecc(0x8123, "PT"))        // country id 8 is Portugal too
        assertEquals(0xE1, EccGuess.ecc(0xC0D1, "GB"))
        assertEquals(0xE2, EccGuess.ecc(0xF201, "NO"))
    }

    @Test fun otherwiseTheLikeliestCountryForTheId() {
        assertEquals(0xE3, EccGuess.ecc(0x83C7, "DE"))        // a Dutch station received in Germany
        assertEquals(0xE0, EccGuess.ecc(0xD210, null))
    }
}
