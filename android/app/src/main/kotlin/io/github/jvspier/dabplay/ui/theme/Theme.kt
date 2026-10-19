package io.github.jvspier.dabplay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import io.github.jvspier.dabplay.data.BackgroundStyle
import io.github.jvspier.dabplay.data.DisplayMode

@Composable
fun DabPlayTheme(mode: DisplayMode = DisplayMode.AUTO, background: BackgroundStyle = BackgroundStyle.DUSK, pureBlackAtNight: Boolean = false,
                 content: @Composable () -> Unit) {
    val night = when (mode) {
        DisplayMode.NIGHT -> true
        DisplayMode.DAY -> false
        DisplayMode.AUTO -> isSystemInDarkTheme()   // follows the head unit's day/night signal
    }
    val c = remember(night, background, pureBlackAtNight) { dabColors(night, background, pureBlackAtNight) }
    val scheme = if (night) darkColorScheme(background = c.bg, surface = c.surface, onBackground = c.text, onSurface = c.text, primary = Accent.orange)
                 else lightColorScheme(background = c.bg, surface = c.surface, onBackground = c.text, onSurface = c.text, primary = Accent.orange)
    val base = TextStyle(fontFamily = Inter)
    val typo = Typography().run {
        copy(displayLarge = displayLarge.merge(base), headlineLarge = headlineLarge.merge(base), titleLarge = titleLarge.merge(base),
             bodyLarge = bodyLarge.merge(base), bodyMedium = bodyMedium.merge(base), labelLarge = labelLarge.merge(base))
    }
    CompositionLocalProvider(LocalDabColors provides c, LocalNight provides night) {
        MaterialTheme(colorScheme = scheme, typography = typo, content = content)
    }
}
