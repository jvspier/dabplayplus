package io.github.jvspier.dabplay

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.os.Build
import android.hardware.usb.UsbManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.jvspier.dabplay.data.LogoStore
import io.github.jvspier.dabplay.diag.Diagnostics
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
    /** MainActivity is on screen (started, not stopped): a dongle appearing then connects without opening anything. */
    @Volatile var uiVisible = false

    override fun onCreate() {
        super.onCreate()
        events = Diagnostics(getExternalFilesDir("diagnostics") ?: File(filesDir, "diagnostics"))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        events.i("DabApp", "DABplay+ $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        store = StationStore(File(filesDir, "stations.json"), onError = { events.e("DabApp", "saving stations failed", it) })
        settings = SettingsStore(this)
        engine = DabEngine(getSystemService(UsbManager::class.java), store, AudioFocus(getSystemService(AudioManager::class.java)),
            resumeOnStart = { settings.state.value.resumeOnStart }, followStation = { settings.state.value.followStation },
            trafficAnnouncements = { settings.state.value.trafficAnnouncements }, events = events)
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
    }
}
