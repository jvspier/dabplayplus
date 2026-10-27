package io.github.jvspier.dabplay.core.pad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Every DLS text seen in the 2026-09-24/25 captures, with what the UI should show. */
class DlsParserTest {
    private fun show(text: String, sid: Long, label: String) = DlsParser.parse(text, 0xE3, sid, label).display

    @Test fun songsAreAlwaysArtistDashTitle() {
        assertEquals("Jamiroquai – Canned Heat", show("Jamiroquai - Canned Heat", 0x8202, "NPO Radio 2"))
        assertEquals("Son Mieux – Feels Pt. 2", show("Son Mieux - Feels Pt. 2", 0x8202, "NPO Radio 2"))
        assertEquals("Nothing But Thieves – A Different Kind Of Happiness", show("Nothing But Thieves - A Different Kind Of Happiness", 0x8203, "NPO 3FM"))
        assertEquals("Edward Maya & Vika Jigulina – Stereo Love", show("Stereo Love - Edward Maya & Vika Jigulina", 0x83C7, "538"))
        assertEquals("Adele – Hello", show("Hello - Adele", 0x83C6, "Sky Radio"))
        assertEquals("Foreigner – That Was Yesterday", show("That Was Yesterday - Foreigner", 0x83D2, "Radio 10"))
        assertEquals("Bebe Rexha & Faithless – New Religion", show("New Religion - BEBE REXHA & FAITHLESS", 0x83C8, "Qmusic"))
        assertEquals("Enrique Iglesias & Sean Paul – Bailando", show("Bailando - ENRIQUE IGLESIAS & SEAN PAUL", 0x83C8, "Qmusic"))
        assertEquals("Nielson & Jiggy Dje – Hotelsuite", show("Onair: Nielson & Jiggy Dje - Hotelsuite", 0x83D3, "100% NL"))
        assertEquals("Nirvana – Stay Away", show("Nirvana - Stay Away", 0x8358, "KINK"))
        assertEquals("Sienna Spiro – Great Expectation", show("Sienna Spiro - Great Expectation", 0x841B, "Omroep Zeeland"))
        assertEquals("Normaal – Hiekikkowokkan", show("NORMAAL - HIEKIKKOWOKKAN", 0x8421, "RADIONL"))
        assertEquals("Rien Salet – Onze Warme Bakker", show("RIEN SALET - ONZE WARME BAKKER", 0x8457, "Tukker FM"))
        assertEquals("Kenny Rogers – She believes in me", show("Kenny Rogers - She believes in me", 0x84AE, "THE GROOVE"))
    }

    @Test fun slogansAndShowsStayRaw() {
        for ((text, sid, label) in listOf(
            Triple("Alternative Rock Radio - KINK non-stop", 0x8358L, "KINK"),
            Triple("Alternative Rock Radio - Mick Hummel", 0x8358L, "KINK"),
            Triple("NPO Radio 2 - Met Morad - PowNed", 0x8202L, "NPO Radio 2"),
            Triple("De Grootste Hits Aller Tijden", 0x83D2L, "Radio 10"),
            Triple("Non-Stop Greatest Hits", 0x83C6L, "Sky Radio"),
            Triple("RADIO = 538", 0x83C7L, "538"),
            Triple("De Hits van Nederland", 0x83D3L, "100% NL"),
            Triple("Je luistert naar Omroep Zeeland", 0x841BL, "Omroep Zeeland"),
            Triple("Nieuws, tips, foto's of video's? App ons:0118 499 499", 0x841BL, "Omroep Zeeland"),
        )) {
            val np = DlsParser.parse(text, 0xE3, sid, label)
            assertFalse(np.isSong, text)
            assertEquals(text, np.display)
        }
    }

    @Test fun unknownStationsAreNeverSplit() {
        assertEquals("Some Artist - Some Title", show("Some Artist - Some Title", 0x9999, "Unknown FM"))
    }

    @Test fun dlPlusWins() {
        val np = DlsParser.parse("whatever", 0xE3, 0x9999, "X", mapOf("ITEM.ARTIST" to "ABBA", "ITEM.TITLE" to "Waterloo"))
        assertEquals("ABBA – Waterloo", np.display)
    }

    /** KINK tags its slogan as a song too (captures/raw 2026-09-24-9C-kink: ITEM.ARTIST "Alternative Rock Radio"). */
    @Test fun aStationsOwnSloganStaysRawEvenWhenTaggedAsASong() {
        val np = DlsParser.parse("Alternative Rock Radio - KINK non-stop", 0xE3, 0x8358, "KINK",
            mapOf("ITEM.ARTIST" to "Alternative Rock Radio", "ITEM.TITLE" to "KINK non-stop"))
        assertFalse(np.isSong)
        assertEquals("Alternative Rock Radio - KINK non-stop", np.display)
        val song = DlsParser.parse("Nirvana - Stay Away", 0xE3, 0x8358, "KINK", mapOf("ITEM.ARTIST" to "Nirvana", "ITEM.TITLE" to "Stay Away"))
        assertEquals("Nirvana – Stay Away", song.display)
    }

    @Test fun smartCaseKeepsShortBandNames() {
        assertEquals("ABBA", DlsParser.smartCase("ABBA"))
        assertEquals("AC/DC", DlsParser.smartCase("AC/DC"))
        assertEquals("U2", DlsParser.smartCase("U2"))
        assertEquals("Metallica", DlsParser.smartCase("METALLICA"))
        assertEquals("Mixed Case stays", DlsParser.smartCase("Mixed Case stays"))
    }
}
