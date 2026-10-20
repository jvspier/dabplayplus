package io.github.jvspier.dabplay.diag

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Settings → Diagnostics log: one text file for a problem report (header, the USB devices connected now, the log),
 * shared through Android's share sheet or saved where the user picks (head units often have no mail app). Built off
 * the main thread; a toast says when it's done or what went wrong.
 */
class DiagnosticsExport(private val context: Context, private val log: Diagnostics) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "diagnostics-export").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** A file name with the date, for saving. */
    fun fileName(): String = "dabplay-diagnostics-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date())}.txt"

    fun share(activity: Activity) = worker.execute {
        runCatching {
            val dir = File(context.cacheDir, "reports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, fileName()).apply { writeText(build()) }
            FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        }.onSuccess { uri ->
            main.post {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "DABplay+ diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri("", uri)
                runCatching { activity.startActivity(Intent.createChooser(send, "Share the diagnostics log")) }
                    .onFailure { toast("No app to share with: use Save instead") }
            }
        }.onFailure { toast("Couldn't make the diagnostics file: ${it.message}") }
    }

    fun saveTo(uri: Uri) = worker.execute {
        runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(build().toByteArray()) } }
            .onSuccess { toast("Diagnostics log saved") }
            .onFailure { toast("Couldn't save the diagnostics log: ${it.message}") }
    }

    private fun build(): String {
        val (older, newer) = log.snapshot()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val header = listOf(
            "DABplay+ diagnostics, ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}",
            "App: DABplay+ $version",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        val usb = context.getSystemService(UsbManager::class.java)
        return report(header, usb?.deviceList?.values?.map(::info).orEmpty(), older, newer)
    }

    private fun info(d: UsbDevice) = UsbInfo(d.vendorId, d.productId,
        runCatching { d.manufacturerName }.getOrNull(), runCatching { d.productName }.getOrNull(),
        (0 until d.interfaceCount).map { i ->
            val f = d.getInterface(i)
            UsbInterfaceInfo(f.interfaceClass, (0 until f.endpointCount).map { e ->
                val ep = f.getEndpoint(e)
                UsbEndpointInfo(ep.address, when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"; UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"; else -> "control"
                })
            })
        })

    private fun toast(msg: String) = main.post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
}
