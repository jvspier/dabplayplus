package io.github.jvspier.dabplay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

enum class DisplayMode { DAY, AUTO, NIGHT }

@Composable
fun DabPlayTheme(mode: DisplayMode = DisplayMode.AUTO, content: @Composable () -> Unit) {
    val night = when (mode) {
        DisplayMode.NIGHT -> true
        DisplayMode.DAY -> false
        DisplayMode.AUTO -> isSystemInDarkTheme()   // follows the head unit's night mode
    }
    val c = if (night) NightColors else DayColors
    val scheme = if (night) darkColorScheme(background = c.bg, surface = c.surface, onBackground = c.text, onSurface = c.text, primary = Accent.orange)
                 else lightColorScheme(background = c.bg, surface = c.surface, onBackground = c.text, onSurface = c.text, primary = Accent.orange)
    CompositionLocalProvider(LocalDabColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
