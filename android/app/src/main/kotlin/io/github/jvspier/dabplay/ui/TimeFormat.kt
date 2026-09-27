package io.github.jvspier.dabplay.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.jvspier.dabplay.data.ClockFormat
import java.text.SimpleDateFormat
import java.util.Locale

val LocalClockFormat = staticCompositionLocalOf { ClockFormat.SYSTEM }

/** Time of day as the user wants it: Android's 12/24-hour setting, or forced 12h / 24h (Settings → Clock). */
@Composable
fun rememberTimeFormatter(): (Long) -> String {
    val ctx = LocalContext.current
    val mode = LocalClockFormat.current
    return remember(mode, ctx) {
        val f = when (mode) {
            ClockFormat.SYSTEM -> DateFormat.getTimeFormat(ctx)
            ClockFormat.H12 -> SimpleDateFormat("h:mm a", Locale.getDefault())
            ClockFormat.H24 -> SimpleDateFormat("HH:mm", Locale.getDefault())
        }
        { t: Long -> f.format(t) }
    }
}
