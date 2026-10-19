package io.github.jvspier.dabplay.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.jvspier.dabplay.data.BackgroundStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BackgroundsTest {
    /** Everything except what a background may change, so the rest can be compared with the theme's own colours. */
    private fun DabColors.rest() = copy(bg = Color.Unspecified, glow1 = Color.Unspecified, glow2 = Color.Unspecified, npGlow = Color.Unspecified,
        panelFrom = Color.Unspecified, panelMid = Color.Unspecified, panelTo = Color.Unspecified, sticky = Color.Unspecified)

    @Test fun duskIsTodaysLookInBothThemes() {
        assertEquals(NightColors, dabColors(night = true, BackgroundStyle.DUSK, pureBlackAtNight = false))
        assertEquals(DayColors, dabColors(night = false, BackgroundStyle.DUSK, pureBlackAtNight = false))
    }

    @Test fun aBackgroundChangesOnlyTheBackground() {
        for (style in BackgroundStyle.entries) for (night in listOf(true, false)) for (black in listOf(true, false)) {
            val c = dabColors(night, style, black)
            assertEquals((if (night) NightColors else DayColors).rest(), c.rest(), "$style night=$night black=$black")
        }
    }

    @Test fun everyBackgroundLooksDifferent() {
        for (night in listOf(true, false)) {
            val looks = BackgroundStyle.entries.map { dabColors(night, it, false).let { c -> listOf(c.bg, c.glow1, c.glow2) } }
            assertEquals(looks.size, looks.toSet().size, "night=$night")
        }
    }

    @Test fun pureBlackIsBlackWithoutGlowsAtNightOnly() {
        for (style in BackgroundStyle.entries) {
            val c = dabColors(night = true, style, pureBlackAtNight = true)
            assertEquals(Color.Black, c.bg)
            assertEquals(listOf(0f, 0f, 0f), listOf(c.glow1.alpha, c.glow2.alpha, c.npGlow.alpha), "$style")
            assertEquals(dabColors(night = false, style, false), dabColors(night = false, style, pureBlackAtNight = true), "$style by day")
        }
    }

    @Test fun listHeadersFollowTheBackground() {
        for (style in BackgroundStyle.entries) for (night in listOf(true, false)) for (black in listOf(true, false)) {
            val c = dabColors(night, style, black)
            val base = if (night) NightColors else DayColors
            assertEquals(c.bg.copy(alpha = base.sticky.alpha), c.sticky, "$style night=$night black=$black")
        }
        assertNotEquals(dabColors(true, BackgroundStyle.OCEAN, false).sticky, NightColors.sticky)
    }
}
