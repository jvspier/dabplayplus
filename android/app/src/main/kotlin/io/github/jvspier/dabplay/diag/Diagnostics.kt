package io.github.jvspier.dabplay.diag

import android.util.Log
import io.github.jvspier.dabplay.engine.EngineLog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Keep listening content out of it (song texts): the folder is readable by apps with storage permission on Android 10.
 *
 * The app's own event log: everything also goes to logcat, and to a file on the device, so a drive can be read back
 * afterwards (`adb pull /sdcard/Android/data/io.github.jvspier.dabplay/files/diagnostics/`), where watching logcat in
 * a moving car isn't an option. Two files of at most [MAX_BYTES] (the current one and the one before), written on a
 * background thread; a full or missing disk only loses log lines.
 */
class Diagnostics(private val dir: File) : EngineLog {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "diagnostics").apply { isDaemon = true } }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val file = File(dir, "dabplay.log")

    override fun i(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg) }
    override fun w(tag: String, msg: String, t: Throwable?) { Log.w(tag, msg, t); write("W", tag, withCause(msg, t)) }
    override fun e(tag: String, msg: String, t: Throwable?) { Log.e(tag, msg, t); write("E", tag, withCause(msg, t)) }
    override fun logcatOnly(tag: String, msg: String) { Log.i(tag, msg) }

    private fun withCause(msg: String, t: Throwable?) = if (t == null) msg else "$msg\n${t.stackTraceToString().take(4000)}"

    private fun write(level: String, tag: String, msg: String) {
        val line = "${synchronized(time) { time.format(Date()) }} $level/$tag: $msg\n"
        writer.execute {
            runCatching {
                dir.mkdirs()
                if (file.length() > MAX_BYTES) file.renameTo(File(dir, "dabplay.1.log"))   // replaces the older one
                FileOutputStream(file, true).use { it.write(line.toByteArray()) }
            }
        }
    }

    companion object { const val MAX_BYTES = 2L * 1024 * 1024 }
}
