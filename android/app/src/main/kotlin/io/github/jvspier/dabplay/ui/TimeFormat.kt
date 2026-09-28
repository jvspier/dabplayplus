package io.github.jvspier.dabplay.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.jvspier.dabplay.data.ClockFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

val LocalClockFormat = staticCompositionLocalOf { ClockFormat.SYSTEM }

/** Wall-clock time, updated on the minute (see [rememberNow]); only what reads it recomposes. */
val LocalNow = compositionLocalOf { System.currentTimeMillis() }

/**
 * The time, updated exactly on each minute change and at once when the clock, the time zone or the 12/24-hour setting
 * changes (a head unit sets its clock from GPS some time after boot; a road trip crosses time zones).
 */
@Composable
fun rememberNow(): Long {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(ctx) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { now = System.currentTimeMillis() } }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)   // system broadcasts still arrive
        now = System.currentTimeMillis()
        onDispose { ctx.unregisterReceiver(r) }
    }
    return now
}

/** Time of day as the user wants it: Android's 12/24-hour setting, or forced 12h / 24h (Settings → Clock). */
@Composable
fun rememberTimeFormatter(): (Long) -> String {
    val ctx = LocalContext.current
    val mode = LocalClockFormat.current
    LocalNow.current                                    // re-read the 12/24-hour setting whenever the time is updated
    val system24 = DateFormat.is24HourFormat(ctx)
    return remember(mode, system24, ctx) {
        val f = when (mode) {
            ClockFormat.SYSTEM -> DateFormat.getTimeFormat(ctx)
            ClockFormat.H12 -> SimpleDateFormat("h:mm a", Locale.getDefault())
            ClockFormat.H24 -> SimpleDateFormat("HH:mm", Locale.getDefault())
        }
        // a DateFormat keeps the zone it was made in: set the current one each time
        { t: Long -> f.timeZone = TimeZone.getDefault(); f.format(t) }
    }
}
