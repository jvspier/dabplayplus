package io.github.jvspier.dabplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Backup for Settings → Start with the car: the main trigger is Android opening the app when the dongle appears at
 * start-up; this covers units that don't announce an already-connected dongle again. Best effort: Android 10+ may not
 * let a receiver open an app (background activity starts), so the dongle path is the one to rely on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // exported (boot broadcasts need that): only the start-up broadcasts count, not any app's explicit intent
        if (intent.action !in START_UP) return
        val app = context.applicationContext as DabApp
        if (!app.settings.state.value.startWithCar) { app.events.i("BootReceiver", "${intent.action}: Start with the car is off"); return }
        app.events.i("BootReceiver", "${intent.action}: Start with the car is on, opening")
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { app.events.w("BootReceiver", "couldn't open the app: ${it.message}") }
    }

    private companion object {
        val START_UP = setOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON")
    }
}
