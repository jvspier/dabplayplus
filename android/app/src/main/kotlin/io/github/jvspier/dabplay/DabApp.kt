package io.github.jvspier.dabplay

import android.app.Application
import android.hardware.usb.UsbManager
import android.media.AudioManager
import io.github.jvspier.dabplay.data.StationStore
import io.github.jvspier.dabplay.engine.AudioFocus
import io.github.jvspier.dabplay.engine.DabEngine
import java.io.File

/** Manual DI: one engine and one station store for the whole process (UI and media service share them). */
class DabApp : Application() {
    lateinit var store: StationStore; private set
    lateinit var engine: DabEngine; private set

    override fun onCreate() {
        super.onCreate()
        store = StationStore(File(filesDir, "stations.json"))
        engine = DabEngine(getSystemService(UsbManager::class.java), store, AudioFocus(getSystemService(AudioManager::class.java)))
    }
}
