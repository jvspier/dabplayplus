package io.github.jvspier.dabplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import io.github.jvspier.dabplay.data.StationStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jvspier.dabplay.ui.theme.LocalNight
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.SystemBarStyle
import androidx.annotation.OptIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.jvspier.dabplay.driver.dtr100.Dtr100
import io.github.jvspier.dabplay.playback.RadioService
import io.github.jvspier.dabplay.ui.AppShell
import io.github.jvspier.dabplay.ui.RadioActions
import io.github.jvspier.dabplay.ui.components.LocalLogos
import io.github.jvspier.dabplay.ui.theme.DabPlayTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as DabApp
    private lateinit var usb: UsbManager
    private var controller: ListenableFuture<MediaController>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // granted: attach; denied: don't ask again straight away (that looped the dialog). Unplugs: DabApp.
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) findDongle(ask = false)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usb = getSystemService(UsbManager::class.java)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Connect to the media service so it runs (and becomes the active MediaSession for the steering-wheel keys)
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, RadioService::class.java))).buildAsync()
        fullScreen = app.settings.state.value.fullScreen
        applySystemBars()
        // Compose paints the whole window itself: the theme's window background would be one more full-screen layer per frame
        window.setBackgroundDrawable(null)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            DabPlayTheme(settings.display, settings.background, settings.pureBlackAtNight) {
                val night = LocalNight.current
                LaunchedEffect(settings.fullScreen, night) {
                    // icons in the head unit's bars readable on our background (the app's theme, not the system's)
                    val style = if (night) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(style, style)
                    fullScreen = settings.fullScreen
                    applySystemBars()
                }
                val s by app.engine.state.collectAsStateWithLifecycle()
                val db by app.store.state.collectAsStateWithLifecycle()
                // one stable instance: the lambdas read current state when called, so nothing below recomposes because of them
                val actions = remember {
                    val e = app.engine
                    RadioActions(
                        play = e::play,
                        togglePlay = { if (e.state.value.playing) e.pause() else e.resume() },
                        skip = e::skip,
                        toggleFavourite = { k ->
                            if (!app.store.toggleFavourite(k))
                                Toast.makeText(this, "${StationStore.MAX_FAVOURITES} favorites is the maximum: remove one first", Toast.LENGTH_LONG).show()
                        },
                        moveFavourite = app.store::moveFavourite,
                        scan = e::scan,
                        clearRecents = app.store::clearRecents,
                        restoreRecents = app.store::restoreRecents,
                        updateSettings = app.settings::update,
                        skipAnnouncement = e::skipAnnouncement,
                        retryLogos = app.logoFetcher::retryMissing,
                        refreshLogos = app.logoFetcher::refreshAll,
                        shareDiagnostics = { app.diagnosticsExport.share(this) },
                        diagnosticsFileName = app.diagnosticsExport::fileName,
                        saveDiagnostics = app.diagnosticsExport::saveTo,
                    )
                }
                val logos by app.logos.state.collectAsStateWithLifecycle()
                val logoStatus by app.logoFetcher.status.collectAsStateWithLifecycle()
                // "Download station logos" off = initials everywhere, as its Settings line says; the cache stays for when
                // it's switched back on
                CompositionLocalProvider(LocalLogos provides if (settings.downloadLogos) logos else emptyMap()) {
                    AppShell(s, db, settings, actions, logoStatus)
                }
            }
        }
        findDongle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) findDongle()   // via UsbAttachActivity
    }

    /**
     * Settings → Full screen, the standard way: hide the bars, and hide them again whenever the window has focus back
     * (Android shows them for another app, the USB prompt, a share screen, and doesn't hide them on return; issue #2).
     * A swipe from an edge shows them for a moment. Off: they stay. Either way AppShell fits the app inside whatever
     * bars are on screen, so a head unit that shows them anyway never covers anything.
     */
    private var fullScreen = true

    private fun applySystemBars() {
        // Before Android 11 the edge-to-edge setup asks for "stable" insets: the bars' space as if they were always
        // shown. Without that flag the insets are the bars actually on screen, which is what AppShell fits inside.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 30) window.decorView.run { systemUiVisibility = systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_STABLE.inv() }
        WindowCompat.getInsetsController(window, window.decorView).run {
            if (fullScreen) {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullScreen) applySystemBars()
    }

    override fun onStart() { super.onStart(); app.uiVisible = true }
    override fun onStop() { super.onStop(); app.uiVisible = false }

    override fun onResume() {
        super.onResume()
        findDongle(ask = false)                              // back in front: attach if it's there and allowed
    }

    private fun findDongle(ask: Boolean = true) {
        val dev = usb.deviceList.values.firstOrNull { usbDongleKind(it) != null } ?: return
        if (usb.hasPermission(dev)) { app.engine.attach(AndroidDongle(usb, dev)); return }
        if (!ask) return
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags))
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        controller?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    private companion object {
        const val ACTION_PERMISSION = "io.github.jvspier.dabplay.USB_PERMISSION"
    }
}
