package io.github.jvspier.dabplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jvspier.dabplay.driver.dtr100.Dtr100
import io.github.jvspier.dabplay.engine.RadioEngine
import io.github.jvspier.dabplay.ui.RadioTestScreen
import io.github.jvspier.dabplay.ui.theme.DabPlayTheme

class MainActivity : ComponentActivity() {
    private lateinit var usb: UsbManager
    private lateinit var engine: RadioEngine

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PERMISSION) findAndStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usb = getSystemService(USB_SERVICE) as UsbManager
        engine = RadioEngine(usb)
        ContextCompat.registerReceiver(this, permissionReceiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            DabPlayTheme {
                val s by engine.state.collectAsStateWithLifecycle()
                RadioTestScreen(s, onPlay = engine::play)
            }
        }
        findAndStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) findAndStart()
    }

    private fun findAndStart() {
        val dev: UsbDevice = usb.deviceList.values.firstOrNull { it.vendorId == Dtr100.VID && it.productId == Dtr100.PID } ?: return
        if (usb.hasPermission(dev)) { engine.start(dev); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags)
        usb.requestPermission(dev, pi)
    }

    override fun onDestroy() { unregisterReceiver(permissionReceiver); super.onDestroy() }

    private companion object { const val ACTION_PERMISSION = "io.github.jvspier.dabplay.USB_PERMISSION" }
}
