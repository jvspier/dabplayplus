package io.github.jvspier.dabplay.engine

import io.github.jvspier.dabplay.core.Announcement
import io.github.jvspier.dabplay.core.dabplus.SuperframeDecoder
import io.github.jvspier.dabplay.core.fic.FicDecoder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The test broadcaster must produce what the real decoders accept, or the engine tests would test nothing. */
class BroadcastTest {
    @Test fun superframesDecodeToTwoGoodAusEach() {
        val dec = SuperframeDecoder(80)
        var aus = 0
        repeat(5) { dec.feed(Broadcast.superframe(80)) { aus++ } }
        assertEquals(5, dec.superframes); assertEquals(10, aus); assertEquals(0, dec.ausBad); assertEquals(0, dec.rsFail)
    }

    @Test fun theFicDescribesTheEnsemble() {
        val f = FicDecoder()
        f.feed(Broadcast.fic(0x8008, "Test mux", listOf(TestService(0x83C7, "538", 2), TestService(0x8888, "Traffic", 7)),
            extraFig0 = listOf(Broadcast.trafficSupport(0x83C7, 1))))
        assertEquals(0, f.fibsBad)
        assertEquals(0x8008, f.eid); assertEquals("Test mux", f.ensembleLabel)
        assertEquals(mapOf(0x83C7L to "538", 0x8888L to "Traffic"), f.services)
        assertEquals(2, f.components.getValue(0x83C7).single().subchannel)
        assertEquals(80, f.subchannels.getValue(2).bitrateKbps)
        assertEquals(Announcement.TRAFFIC, f.announcementSupport.getValue(0x83C7).asu and Announcement.TRAFFIC)
    }
}
