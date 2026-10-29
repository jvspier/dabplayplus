package io.github.jvspier.dabplay.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which size the design is drawn at, for windows the app was checked on (emulator, 2026-10-29). */
class AdaptiveTest {
    @Test fun theDudu3AndOtherHeadUnitsGetTheDesignAsIs() {
        assertEquals(UiScale.REGULAR, uiScaleFor(1280f, 720f))     // the Dudu3, 1280×720 at 160 dpi
        assertEquals(UiScale.REGULAR, uiScaleFor(1024f, 600f))     // a 1024×600 unit
        assertEquals(UiScale.REGULAR, uiScaleFor(853f, 480f))      // a 1280×720 unit at 240 dpi
        assertEquals(UiScale.REGULAR, uiScaleFor(1920f, 720f))     // an ultra-wide unit
        assertEquals(UiScale.REGULAR, uiScaleFor(1333f, 800f))     // 2000×1200 at 240 dpi, a 1280×800 tablet
    }

    @Test fun aPhoneInLandscapeIsCompact() {
        assertEquals(UiScale.COMPACT, uiScaleFor(914f, 411f))      // a Pixel, 2400×1080 at 420 dpi
        assertEquals(UiScale.COMPACT, uiScaleFor(851f, 392f))
    }

    @Test fun aTallScreenIsLarge() {
        assertEquals(UiScale.LARGE, uiScaleFor(1920f, 1080f))      // 1920×1080 at 160 dpi
        assertEquals(UiScale.LARGE, uiScaleFor(1600f, 900f))
    }
}
