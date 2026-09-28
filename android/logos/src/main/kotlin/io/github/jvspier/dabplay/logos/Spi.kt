package io.github.jvspier.dabplay.logos

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class LogoRef(val url: String, val width: Int, val height: Int)

/** One service from a RadioDNS SPI `SI.xml`: its logos and homepage. */
data class SpiService(val logos: List<LogoRef>, val homepage: String?)

/** RadioDNS Service and Programme Information (ETSI TS 102 818), just the parts we use. */
object SpiParser {
    /**
     * The service whose DAB bearer is `dab:<gcc>.<eid>.<sid>.<scids>`; failing that, one with the same gcc+SId on
     * another ensemble (a station that moved multiplexes). Null if the file doesn't list it.
     */
    fun service(xml: ByteArray, gcc: String, eid: Int, sid: Int, scids: Int = 0): SpiService? {
        // no DTDs or entities, ever: Android's parser ignores the "disallow-doctype-decl" feature below, so an entity
        // expansion bomb from a hostile (or cleartext-intercepted) SI.xml would get through; SI.xml never needs them
        val text = String(xml, Charsets.ISO_8859_1)              // the whole file (≤ 4 MB): a DTD could hide behind comments
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) return null
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // no DTDs or external entities from a server we don't control
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(xml.inputStream())
        }.getOrNull() ?: return null
        val exact = "dab:%s.%04x.%04x.%x".format(gcc, eid, sid, scids)
        val sameSid = Regex("dab:%s\\.[0-9a-f]{4}\\.%04x\\.".format(Regex.escape(gcc), sid))
        val services = doc.getElementsByTagName("service").elements()
        fun bearers(s: Element) = s.getElementsByTagName("bearer").elements().map { it.getAttribute("id").lowercase() }
        val svc = services.firstOrNull { s -> bearers(s).any { it == exact } }
            ?: services.firstOrNull { s -> bearers(s).any { sameSid.containsMatchIn(it) } } ?: return null
        val logos = svc.getElementsByTagName("multimedia").elements().mapNotNull { m ->
            val url = m.getAttribute("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (m.getAttribute("type")) {
                "logo_colour_square" -> LogoRef(url, 32, 32)
                "logo_colour_rectangle" -> LogoRef(url, 112, 32)
                else -> {
                    val w = m.getAttribute("width").toIntOrNull(); val h = m.getAttribute("height").toIntOrNull()
                    if (w != null && h != null) LogoRef(url, w, h) else null
                }
            }
        }
        val home = svc.getElementsByTagName("link").elements()
            .firstOrNull { it.getAttribute("description") == "homepageUri" }?.getAttribute("uri")?.takeIf { it.startsWith("http") }
        return SpiService(logos, home)
    }

    private fun org.w3c.dom.NodeList.elements() = (0 until length).map { item(it) }.filterIsInstance<Element>()
}

/**
 * The best logo for a tile: square first (the tile is square), the smallest one that is at least [TILE_PX] (sharp on
 * the artwork frame without wasting memory); else the largest square; else the largest that isn't a banner.
 */
fun pickLogo(logos: List<LogoRef>): LogoRef? {
    fun square(l: LogoRef) = l.width > 0 && l.height > 0 && maxOf(l.width, l.height) <= minOf(l.width, l.height) * 11 / 10
    val squares = logos.filter(::square)
    return squares.filter { it.width >= TILE_PX }.minByOrNull { it.width }
        ?: squares.filter { it.width >= ImageSniff.MIN_SIDE }.maxByOrNull { it.width }
        ?: logos.filter { minOf(it.width, it.height) >= ImageSniff.MIN_SIDE && maxOf(it.width, it.height) <= minOf(it.width, it.height) * 5 / 2 }
            .maxByOrNull { it.width * it.height }
}

const val TILE_PX = 256
