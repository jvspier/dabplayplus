package io.github.jvspier.dabplay

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.SystemClock
import android.hardware.usb.UsbManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.jvspier.dabplay.audio.AudioPlayer
import io.github.jvspier.dabplay.data.LogoStore
import io.github.jvspier.dabplay.diag.Diagnostics
import io.github.jvspier.dabplay.diag.DiagnosticsExport
import io.github.jvspier.dabplay.data.SettingsStore
import io.github.jvspier.dabplay.data.StationStore
import io.github.jvspier.dabplay.engine.AudioFocus
import io.github.jvspier.dabplay.engine.DabEngine
import io.github.jvspier.dabplay.logos.LogoFetcher
import java.io.File

/** Manual DI: one engine and one station store for the whole process (UI and media service share them). */
class DabApp : Application() {
    lateinit var store: StationStore; private set
    lateinit var engine: DabEngine; private set
    lateinit var settings: SettingsStore; private set
    lateinit var logos: LogoStore; private set
    lateinit var logoFetcher: LogoFetcher; private set
    /** The event log (logcat + a file to pull after a drive). */
    lateinit var events: Diagnostics; private set
    val diagnosticsExport by lazy { DiagnosticsExport(this, events) }
    /** MainActivity is on screen (started, not stopped): a dongle appearing then connects without opening anything. */
    @Volatile var uiVisible = false
    /** How long the CPU had been in deep sleep when the screen last went off (see [woke]). */
    private var asleepAtScreenOff = deepSleepSoFar()

    override fun onCreate() {
        super.onCreate()
        events = Diagnostics(getExternalFilesDir("diagnostics") ?: File(filesDir, "diagnostics"))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        events.i("DabApp", "DABplay+ $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        store = StationStore(File(filesDir, "stations.json"), onError = { events.e("DabApp", "saving stations failed", it) })
        settings = SettingsStore(this)
        engine = DabEngine(store, AudioFocus(getSystemService(AudioManager::class.java)),
            newAudio = { AudioPlayer { events.i("AudioPlayer", it) } }, events = events,
            resumeOnStart = { settings.state.value.resumeOnStart }, followStation = { settings.state.value.followStation },
            trafficAnnouncements = { settings.state.value.trafficAnnouncements },
            usbThreadSetup = { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) })
        logos = LogoStore(File(filesDir, "logos"))
        logoFetcher = LogoFetcher(this, store, logos, settings, current = { engine.state.value.current?.key }).also { it.start() }
        // in the app process, not an activity: the radio plays on with no screen open, and an unplug must still stop it
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                events.i("DabApp", "USB device removed: ${dev?.deviceName} ${dev?.let { "%04x:%04x".format(it.vendorId, it.productId) }}")
                engine.detached(dev?.deviceName)                          // only our dongle stops the radio
            }
        }, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Start with the car on head units that sleep instead of switching off (the Dudu3): the app lives on, so a car
        // start brings no boot and no USB event, only the screen coming back on
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) { asleepAtScreenOff = deepSleepSoFar(); events.i("DabApp", "screen off") }
                else woke((deepSleepSoFar() - asleepAtScreenOff) / 1000)
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * The screen is on again after [sleptS] seconds of deep sleep. Only a real sleep (the car was off) opens the radio:
     * the screen going off while something plays keeps the CPU awake, so switching the screen back on opens nothing.
     */
    private fun woke(sleptS: Long) {
        events.i("DabApp", "screen on after $sleptS s of deep sleep")
        val s = settings.state.value
        if (sleptS < WAKE_MIN_SLEEP_S || !s.startWithCar) return
        // Android 10+ lets an app open itself from the background only with "Display over other apps" allowed
        if (Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(this))
            events.w("DabApp", "back from sleep: can't open, \"Display over other apps\" isn't allowed")
        else {
            events.i("DabApp", "back from sleep: Start with the car is on, opening")
            runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { events.w("DabApp", "couldn't open the app: ${it.message}") }
        }
        // as at a cold start: the last station plays again (Settings → Resume last station)
        if (s.resumeOnStart && !engine.state.value.playing) engine.resume()
    }

    private companion object {
        /** Seconds asleep that count as the car having been off. */
        const val WAKE_MIN_SLEEP_S = 10
        /** Elapsed time goes on in deep sleep, uptime doesn't. */
        fun deepSleepSoFar() = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
    }
}
