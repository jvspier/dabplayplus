package io.github.jvspier.dabplay.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogoStoreTest {
    private val dir = File(Files.createTempDirectory("logos").toFile(), "logos")
    private var t = 1_000_000L
    private fun store() = LogoStore(dir) { t }
    private val kink = ServiceKey(0xE3, 0x8358)
    private val png = byteArrayOf(1, 2, 3)

    @Test fun aStationWithoutALogoIsTriedAgainAfterThreeDays() {
        val s = store()
        s.miss(kink)
        assertFalse(s.needsLookup(kink))
        t += LogoStore.RETRY_MISS_MS
        assertTrue(s.needsLookup(kink))
    }

    @Test fun retryMissingMakesMissedStationsDueAgainRightAway() {
        val s = store()
        s.miss(kink)
        s.retryMissing()
        assertTrue(s.needsLookup(kink))
        assertTrue(store().needsLookup(kink), "after a restart too")
    }

    @Test fun refreshAllMakesStationsWithALogoDueAgainUntilLookedUp() {
        val s = store()
        s.save(kink, png, "RADIODNS", "http://logos.example/kink.png")
        assertFalse(s.needsLookup(kink))
        t += 1000
        s.refreshAll()
        assertTrue(s.needsLookup(kink))
        assertTrue(store().needsLookup(kink), "an interrupted refresh carries on after a restart")
        t += 1000
        s.save(kink, png, "RADIODNS", "http://logos.example/kink.png")
        assertFalse(s.needsLookup(kink))
    }

    @Test fun aRefreshThatFindsNothingKeepsTheLogo() {
        val s = store()
        s.save(kink, png, "RADIODNS", "http://logos.example/kink.png")
        t += 1000
        s.refreshAll()
        t += 1000
        s.miss(kink)
        assertTrue(kink in s.state.value, "the logo stays")
        assertFalse(s.needsLookup(kink), "and isn't looked up again in this refresh")
        assertFalse(kink in s.missed.value)
    }

    @Test fun missedListsTheStationsWithoutALogoAnywhere() {
        val s = store()
        s.miss(kink)
        assertEquals(setOf(kink), s.missed.value)
        assertEquals(setOf(kink), store().missed.value, "after a restart too")
        s.retryMissing()
        assertEquals(emptySet(), s.missed.value)
        s.miss(kink)
        s.save(kink, png, "WEB_MANIFEST", "https://kink.example/icon.png")
        assertEquals(emptySet(), s.missed.value)
    }
}
