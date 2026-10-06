package io.github.jvspier.dabplay

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import io.github.jvspier.dabplay.engine.DongleKind

/**
 * Where Android sends "a dongle appeared" (the USB prompt's "always open" tick makes it do so at every car start).
 * Invisible and in its own task: it opens the radio only when the driver wants that (Settings → Start with the car) or
 * when the radio is on screen anyway; otherwise nothing comes forward and nothing plays, so CarPlay stays in front.
 * Android has granted USB access by then, so opening DABplay+ later connects without a prompt.
 */
class UsbAttachActivity : Activity() {
    private companion object { const val TAG = "UsbAttachActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DabApp
        val usb = getSystemService(UsbManager::class.java)
        val dev = usb.deviceList.values.firstOrNull { DongleKind.of(it) != null && usb.hasPermission(it) }
        when {
            app.settings.state.value.startWithCar || app.uiVisible ->
                startActivity(Intent(this, MainActivity::class.java).setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    .putExtras(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // it dropped out while playing (a voltage dip, a loose plug): carry on in the background, nothing comes forward
            app.engine.wantsDongleBack && dev != null -> { Log.i(TAG, "dongle back: resuming in the background"); app.engine.attach(dev) }
            else -> Log.i(TAG, "dongle appeared; Start with the car is off and the radio isn't on screen: not opening")
        }
        finish()
    }
}
