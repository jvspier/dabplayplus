package io.github.jvspier.dabplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Design tokens from design_handoff_radio_head_unit/README.md ("Nocturne"). Night is the default theme. */
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
)

val LocalDabColors = staticCompositionLocalOf { NightColors }
val LocalNight = staticCompositionLocalOf { true }
