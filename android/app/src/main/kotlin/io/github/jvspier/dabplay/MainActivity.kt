package io.github.jvspier.dabplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.jvspier.dabplay.driver.dtr100.Dtr100
import io.github.jvspier.dabplay.playback.RadioService
import io.github.jvspier.dabplay.ui.AppShell
import io.github.jvspier.dabplay.ui.RadioActions
import io.github.jvspier.dabplay.ui.theme.DabPlayTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as DabApp
    private lateinit var usb: UsbManager
    private var controller: ListenableFuture<MediaController>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> findDongle()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> app.engine.detached()
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usb = getSystemService(UsbManager::class.java)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        // Connect to the media service so it runs (and becomes the active MediaSession for the steering-wheel keys)
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, RadioService::class.java))).buildAsync()
        // head unit: the whole 1280×720 is ours; the system bars come back with a swipe from the edge
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            DabPlayTheme(settings.display) {
                val s by app.engine.state.collectAsStateWithLifecycle()
                val db by app.store.state.collectAsStateWithLifecycle()
                // one stable instance: the lambdas read current state when called, so nothing below recomposes because of them
                val actions = remember {
                    val e = app.engine
                    RadioActions(
                        play = e::play,
                        togglePlay = { if (e.state.value.playing) e.pause() else e.resume() },
                        skip = e::skip,
                        toggleFavourite = app.store::toggleFavourite,
                        moveFavourite = app.store::moveFavourite,
                        scan = e::scan,
                        clearRecents = app.store::clearRecents,
                        restoreRecents = app.store::restoreRecents,
                        updateSettings = app.settings::update,
                    )
                }
                AppShell(s, db, settings, actions)
            }
        }
        findDongle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) findDongle()
    }

    private fun findDongle() {
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == Dtr100.VID && it.productId == Dtr100.PID } ?: return
        if (usb.hasPermission(dev)) { app.engine.attach(dev); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags))
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        controller?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    private companion object { const val ACTION_PERMISSION = "io.github.jvspier.dabplay.USB_PERMISSION" }
}
