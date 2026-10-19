package io.github.jvspier.dabplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.jvspier.dabplay.data.BackgroundStyle

/** Design tokens from the Claude Design export (design_handoff_radio_head_unit/README.md, kept locally; "Nocturne"). Night is the default theme. */
@Immutable
data class DabColors(
    val bg: Color, val surface: Color, val text: Color, val rail: Color,
    val neutral200: Color, val neutral300: Color, val neutral400: Color, val neutral500: Color, val neutral800: Color,
    val glow1: Color, val glow2: Color, val npGlow: Color,
    val liveSub: Color, val az: Color,
    val liveTileFrom: Color, val liveTileTo: Color, val sticky: Color, val neutral700: Color,
    val panelFrom: Color, val panelMid: Color, val panelTo: Color,
    /** Status text: "No signal", "DAB dongle not connected" (warn) and real problems (error). Day needs darker ones. */
    val warn: Color = Color(0xFFFFB547), val error: Color = Color(0xFFFF3B4E),
    /** Orange / pink as text (Undo, the swipe strip): the accents themselves are too light on white. */
    val orangeInk: Color = Color(0xFFFF6A3D), val pinkInk: Color = Color(0xFFFF4D8A),
    /** Switch track when off: by day neutral800 is nearly the white row it sits on. */
    val switchOff: Color = Color(0xFF3F424D),
)

object Accent {
    val orange = Color(0xFFFF6A3D)      // play/pause, now playing, waveform
    val pink = Color(0xFFFF4D8A)        // favourites
    val amber = Color(0xFFFFB547)       // Stations tab, Display setting
    val green = Color(0xFF34D27B)       // Recent tab, switches
    val blue = Color(0xFF4C8DFF)        // Home tab, Scan
    val lavender = Color(0xFFA99CF0)    // Settings tab
    val playFrom = Color(0xFFFF8A4C)
    val playTo = Color(0xFFFF5A36)
    val onAccent = Color(0xFF14151F)
    val liveRed = Color(0xFFFF3B4E)
    val playIcon = Color(0xFFFFF5F0)
}

val NightColors = DabColors(
    bg = Color(0xFF161826), surface = Color(0xFF232532), text = Color(0xFFE9E9ED), rail = Color(0xFF12131F),
    neutral200 = Color(0xFFE4E7F5), neutral300 = Color(0xFFCFD3E5), neutral400 = Color(0xFFB2B6CA),
    neutral500 = Color(0xFF9397AB), neutral800 = Color(0xFF3F424D),
    glow1 = Color(0xFF3D2244), glow2 = Color(0xFF232846), npGlow = Color(0xFF43264A),
    liveSub = Color(0xFFFF9A73), az = Color(0xFFFFB547),
    liveTileFrom = Color(0xFF4A2A3C), liveTileTo = Color(0xFF2B2135), sticky = Color(0xD1161826), neutral700 = Color(0xFF55596A),
    panelFrom = Color(0xFF43264A), panelMid = Color(0xFF2C2239), panelTo = Color(0xFF1C1E2C),
)

val DayColors = DabColors(
    bg = Color(0xFFF3F2F7), surface = Color(0xFFFFFFFF), text = Color(0xFF1B1C28), rail = Color(0xFFFFFFFF),
    neutral200 = Color(0xFF3F424D), neutral300 = Color(0xFF4A4E5C), neutral400 = Color(0xFF5D6170),
    neutral500 = Color(0xFF6F7385), neutral800 = Color(0xFFDFE2EE),
    glow1 = Color(0xFFFFDCCB), glow2 = Color(0xFFE4E6FF), npGlow = Color(0xFFFFDCCB),
    liveSub = Color(0xFFC8431A), az = Color(0xFFB86E00),
    liveTileFrom = Color(0xFFFFE7DC), liveTileTo = Color(0xFFFFF6F1), sticky = Color(0xDBF3F2F7), neutral700 = Color(0xFFB9BCC9),
    panelFrom = Color(0xFFFFE3D5), panelMid = Color(0xFFF7E8F3), panelTo = Color(0xFFFFFFFF),
    warn = Color(0xFFA35A00), error = Color(0xFFC81E32),   // ≥ 4.5:1 on the light backgrounds
    orangeInk = Color(0xFFC8431A), pinkInk = Color(0xFFC2185B), switchOff = Color(0xFFB9BCC9),
)

val LocalDabColors = staticCompositionLocalOf { NightColors }
val LocalNight = staticCompositionLocalOf { true }

/**
 * One background in one theme (Settings → Background, owner 2026-10-19): the base colour, the glow top left
 * ([DabColors.glow2]), the glow bottom right ([DabColors.glow1]) and the one behind the Now playing artwork.
 */
private class Backdrop(val bg: Long, val topLeft: Long, val bottomRight: Long, val nowPlaying: Long)

private val NightBackdrops = mapOf(
    BackgroundStyle.DUSK to Backdrop(0xFF161826, 0xFF232846, 0xFF3D2244, 0xFF43264A),     // blue and plum
    BackgroundStyle.OCEAN to Backdrop(0xFF131A21, 0xFF1B3045, 0xFF133B3F, 0xFF154247),    // steel blue and teal
    BackgroundStyle.EMBER to Backdrop(0xFF19161D, 0xFF2A2236, 0xFF47271D, 0xFF4D2A20),    // violet and copper
    BackgroundStyle.AURORA to Backdrop(0xFF141A1C, 0xFF1A3A34, 0xFF2C2448, 0xFF2F2850),   // green-teal and violet
)
private val DayBackdrops = mapOf(
    BackgroundStyle.DUSK to Backdrop(0xFFF3F2F7, 0xFFE4E6FF, 0xFFFFDCCB, 0xFFFFDCCB),     // lavender blue and peach
    BackgroundStyle.OCEAN to Backdrop(0xFFF1F4F7, 0xFFDCE8F7, 0xFFD6EEEC, 0xFFD6EEEC),    // pale steel blue and teal
    BackgroundStyle.EMBER to Backdrop(0xFFF8F2F1, 0xFFFBDDE3, 0xFFFFD8BF, 0xFFFFD8BF),    // rose and apricot
    BackgroundStyle.AURORA to Backdrop(0xFFF2F5F3, 0xFFD8F0E6, 0xFFE7E1F7, 0xFFE7E1F7),   // green-teal and violet
)

/**
 * The theme's colours with the chosen background. Only what's behind everything changes (base, glows, the side panel's
 * gradient, the list headers); tiles, text, icons and accents stay. Dusk is the theme as designed. Pure black only
 * applies at night: black, no glows, a neutral panel.
 */
fun dabColors(night: Boolean, style: BackgroundStyle, pureBlackAtNight: Boolean): DabColors {
    val base = if (night) NightColors else DayColors
    if (night && pureBlackAtNight) return base.copy(bg = Color.Black, glow1 = Color.Transparent, glow2 = Color.Transparent,
        npGlow = Color.Transparent, panelFrom = Color(0xFF18181D), panelMid = Color(0xFF101013), panelTo = Color(0xFF0B0B0D),
        sticky = Color.Black.copy(alpha = base.sticky.alpha))
    if (style == BackgroundStyle.DUSK) return base
    val b = (if (night) NightBackdrops else DayBackdrops).getValue(style)
    val bg = Color(b.bg); val np = Color(b.nowPlaying)
    return base.copy(bg = bg, glow1 = Color(b.bottomRight), glow2 = Color(b.topLeft), npGlow = np,
        panelFrom = if (night) np else lerp(np, Color.White, 0.3f),
        panelMid = if (night) lerp(np, bg, 0.5f) else lerp(lerp(Color(b.topLeft), np, 0.5f), Color.White, 0.5f),
        panelTo = if (night) lerp(bg, base.surface, 0.5f) else Color.White,
        sticky = bg.copy(alpha = base.sticky.alpha))
}
