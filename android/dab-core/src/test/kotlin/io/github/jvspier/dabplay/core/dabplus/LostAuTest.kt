package io.github.jvspier.dabplay.core.dabplus

import io.github.jvspier.dabplay.core.Golden
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bad reception doesn't remove bytes (the dongle keeps delivering at the sub-channel rate), it garbles them. Every AU
 * must then be either delivered or reported lost, so the player can fill the gap with silence and keep time.
 */
class LostAuTest {
    @Test fun garbledSuperframesAreReportedAsLostAus() {
        val f = Golden.raw(".*538\\.msc").firstOrNull()
        assumeTrue("538 capture missing", f != null)
        val clean = f!!.readBytes()
        val ref = SuperframeDecoder(80); var refAus = 0
        ref.feed(clean) { refAus++ }
        val per = ref.format!!.ausPerSuperframe

        val sfLen = 120 * 80 / 8
        val bad = clean.copyOf()
        val from = 40 * sfLen + 333                                     // mid-superframe, 3.5 superframes of noise
        Random(1).nextBytes(ByteArray(sfLen * 7 / 2)).copyInto(bad, from)
        val dec = SuperframeDecoder(80)
        var ok = 0; var lost = 0
        dec.feed(bad, lost = { lost += it }) { ok++ }

        assertTrue(lost >= 3 * per, "lost $lost")                       // at least the 3 fully garbled superframes
        assertEquals(refAus, ok + lost, "delivered + lost must cover every AU (per superframe: $per)")
    }

    /** Silence for a long garbled stretch is reported while it lasts, a superframe at a time, not all at the end. */
    @Test fun lostAusAreReportedWhileTheGarbageLasts() {
        val f = Golden.raw(".*538\\.msc").firstOrNull()
        assumeTrue("538 capture missing", f != null)
        val clean = f!!.readBytes()
        val sfLen = 120 * 80 / 8
        val dec = SuperframeDecoder(80)
        val events = ArrayList<Int>()
        dec.feed(clean.copyOf(10 * sfLen), lost = { events += it }) { }
        val per = dec.format!!.ausPerSuperframe
        events.clear()
        val garbage = Random(7).nextBytes(ByteArray(8 * sfLen))
        for (chunk in garbage.toList().chunked(sfLen / 3)) dec.feed(chunk.toByteArray(), lost = { events += it }) { }
        // still inside the garbage: about 8 superframes' worth reported already, none of it bigger than one superframe
        assertTrue(events.sum() >= 6 * per, "only ${events.sum()} AUs reported during 8 garbled superframes")
        assertTrue(events.all { it <= per }, "a report bigger than one superframe: $events")
    }
}
