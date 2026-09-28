package io.github.jvspier.dabplay.core

import io.github.jvspier.dabplay.core.fic.FicDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnouncementTest {
    /** A FIB with the given FIG 0 bodies (header byte + data), padded, with its CRC. */
    private fun fib(vararg fig0: ByteArray): ByteArray {
        val b = ByteArray(32) { 0xFF.toByte() }
        var i = 0
        for (f in fig0) { b[i++] = ((0 shl 5) or f.size).toByte(); f.copyInto(b, i); i += f.size }
        val crc = Crc.crc16Ccitt(b, 0, 30) xor 0xFFFF
        b[30] = (crc shr 8).toByte(); b[31] = crc.toByte()
        return b
    }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    // FIG 0/18: Omroep Zeeland-like service 841B takes part in traffic (ASu 0002), cluster 3
    private val support = hex("12" + "841B" + "0002" + "01" + "03")
    // FIG 0/19 exactly as broadcast on 5B (2026-09-24): clusters 4,1,3,2,5 idle (ASw 0000) on sub-channels 6,7,1,8,4
    private val idle = hex("13" + "0400000601000007030000010200000805000004")
    // the same cluster 3, a traffic announcement on sub-channel 1
    private val traffic = hex("13" + "03000201")

    @Test fun parsesSupportAndSwitching() {
        val f = FicDecoder().apply { feed(fib(support)); feed(fib(idle)) }
        assertEquals(AnnouncementSupport(0x0002, setOf(3)), f.announcementSupport[0x841B])
        assertEquals(listOf(4 to 6, 1 to 7, 3 to 1, 2 to 8, 5 to 4), f.announcementSwitching.map { (c, s) -> c to s.subchannel })
        assertTrue(f.announcementSwitching.values.all { it.asw == 0 })
    }

    @Test fun switchesForTrafficOnlyWhenWanted() {
        val f = FicDecoder().apply { feed(fib(support)) }
        val w = AnnouncementWatcher()
        assertNull(w.update(0, f, 0x841B, ownSubchannel = 5, wanted = Announcement.TRAFFIC or Announcement.ALARM))   // nothing on yet
        f.feed(fib(traffic))
        assertEquals(ActiveAnnouncement(3, 1, Announcement.TRAFFIC), w.update(100, f, 0x841B, 5, Announcement.TRAFFIC or Announcement.ALARM))
        assertNull(w.update(200, f, 0x841B, 5, Announcement.ALARM))                  // TA setting off: only alarms
    }

    @Test fun endsWhenTheCarouselGoesQuietOrStale() {
        val f = FicDecoder().apply { feed(fib(support)); feed(fib(traffic)) }
        val w = AnnouncementWatcher(staleMs = 3000)
        val all = Announcement.TRAFFIC or Announcement.ALARM
        assertEquals(1, w.update(0, f, 0x841B, 5, all)?.subchannel)
        assertEquals(1, w.update(2900, f, 0x841B, 5, all)?.subchannel)             // not repeated for 2.9 s: still on
        assertNull(w.update(3100, f, 0x841B, 5, all))                               // 3 s without it: over
        f.feed(fib(traffic)); assertEquals(1, w.update(3200, f, 0x841B, 5, all)?.subchannel)
        f.feed(fib(idle)); assertNull(w.update(3300, f, 0x841B, 5, all))            // ASw 0: over
    }

    @Test fun skippedStaysSkippedUntilTheClusterGoesQuiet() {
        val f = FicDecoder().apply { feed(fib(support)); feed(fib(traffic)) }
        val w = AnnouncementWatcher(); val all = Announcement.TRAFFIC or Announcement.ALARM
        w.skip(3)
        f.feed(fib(traffic)); assertNull(w.update(100, f, 0x841B, 5, all))
        f.feed(fib(idle)); assertNull(w.update(200, f, 0x841B, 5, all))
        f.feed(fib(traffic)); assertEquals(3, w.update(300, f, 0x841B, 5, all)?.cluster)   // the next one counts again
    }

    @Test fun ownSubchannelNeedsNoSwitchAndBadgeFollowsSupport() {
        val f = FicDecoder().apply { feed(fib(support)); feed(fib(traffic)) }
        val w = AnnouncementWatcher()
        assertNull(w.update(0, f, 0x841B, ownSubchannel = 1, wanted = Announcement.TRAFFIC))
        assertTrue(w.supports(f, 0x841B, Announcement.TRAFFIC))
        assertFalse(w.supports(f, 0x83C7, Announcement.TRAFFIC))
    }
}
