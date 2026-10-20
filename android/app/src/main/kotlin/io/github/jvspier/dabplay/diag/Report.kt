package io.github.jvspier.dabplay.diag

import io.github.jvspier.dabplay.engine.DongleKind
import io.github.jvspier.dabplay.engine.DriverRegistry
import io.github.jvspier.dabplay.engine.EndpointInfo

/** A connected USB device as the diagnostics report describes it (Android's UsbDevice, without the platform types). */
data class UsbInfo(val vendorId: Int, val productId: Int, val manufacturer: String?, val product: String?, val interfaces: List<UsbInterfaceInfo>)
data class UsbInterfaceInfo(val interfaceClass: Int, val endpoints: List<UsbEndpointInfo>)
data class UsbEndpointInfo(val address: Int, val type: String)

/**
 * One line per USB device: id, names, interfaces and what DABplay+ makes of it. Unknown devices are listed too: a
 * report from someone with a dongle we don't know yet shows its id straight away.
 */
fun usbLine(d: UsbInfo): String {
    val ifaces = if (d.interfaces.isEmpty()) "no interfaces" else d.interfaces.withIndex().joinToString("; ") { (i, f) ->
        "interface $i class %02x: %s".format(f.interfaceClass,
            f.endpoints.joinToString { "%02x %s".format(it.address, it.type) }.ifEmpty { "no endpoints" })
    }
    val kind = DriverRegistry.kindOf(d.vendorId, d.productId)
    val verdict = when {
        kind == null -> "not a dongle DABplay+ knows"
        DriverRegistry.fits(kind, d.interfaces.firstOrNull()?.endpoints.orEmpty().map { EndpointInfo(it.address, it.type == "bulk") }) -> "${kind.label} dongle"
        else -> "has a ${kind.label} dongle's id, but not its endpoints"
    }
    return "%04x:%04x '%s' / '%s'; %s → %s".format(d.vendorId, d.productId, d.manufacturer.orEmpty(), d.product.orEmpty(), ifaces, verdict)
}

/** The file a user shares with a problem report: [header], the USB devices, then the log, oldest part first. */
fun report(header: List<String>, devices: List<UsbInfo>, older: String?, newer: String?): String = buildString {
    header.forEach { appendLine(it) }
    appendLine()
    appendLine("USB devices connected now:")
    if (devices.isEmpty()) appendLine("  (none)") else devices.forEach { appendLine("  " + usbLine(it)) }
    older?.let { appendLine(); appendLine("--- dabplay.1.log (the older part) ---"); append(it) }
    appendLine(); appendLine("--- dabplay.log ---"); append(newer.orEmpty())
}

private val DongleKind.label get() = when (this) {
    DongleKind.C100 -> "C100"
    DongleKind.SI4684 -> "Si4684"
    DongleKind.SIANO -> "Siano"
}
