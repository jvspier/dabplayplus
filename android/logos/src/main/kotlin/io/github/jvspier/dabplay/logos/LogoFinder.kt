package io.github.jvspier.dabplay.logos

import java.util.concurrent.ConcurrentHashMap

/** What to look up: the DAB identity of the service plus its label (for the name-matched fallbacks). */
data class LogoQuery(val ecc: Int, val eid: Int, val sid: Int, val label: String, val scids: Int = 0)

enum class LogoSource { RADIODNS, WEB_MANIFEST, WEB_TOUCH_ICON, RADIO_BROWSER }

class FoundLogo(val bytes: ByteArray, val url: String, val source: LogoSource, val info: ImageInfo)

/**
 * The logo chain (docs/findings.md, 2026-09-24): RadioDNS SPI (official, keyed by the DAB ids) → the station
 * website's web app manifest icon → its apple-touch-icon → the radio-browser favicon. Every candidate is
 * downloaded and checked ([ImageSniff.usable]) before it counts. Blocking; call it off the main thread.
 * One instance can serve many stations: SI.xml files are cached per host for its lifetime.
 */
class LogoFinder(private val http: Http, private val dns: DnsLookup, private val radioBrowser: RadioBrowser = RadioBrowser(http)) {
    private val spiFiles = ConcurrentHashMap<String, ByteArray>()     // SPI host:port → SI.xml ("" = none)

    fun find(q: LogoQuery): FoundLogo? {
        val spi = spiService(q)
        spi?.let { pickLogo(it.logos) }?.let { fetch(it.url, LogoSource.RADIODNS) }?.let { return it }

        val country = Countries.of(q.ecc, q.sid)
        val rb by lazy { radioBrowser.find(q.label, country) }
        val homepage = spi?.homepage ?: rb.firstNotNullOfOrNull { it.homepage }
        homepage?.let { fromWebsite(it) }?.let { return it }

        return rb.mapNotNull { it.favicon }.distinct().firstNotNullOfOrNull { fetch(it, LogoSource.RADIO_BROWSER) }
    }

    /** RadioDNS: CNAME of the service's FQDN → SPI (or EPG) SRV → `SI.xml` → this service's entry. */
    private fun spiService(q: LogoQuery): SpiService? {
        val cname = dns.cname(RadioDns.dabFqdn(q.ecc, q.eid, q.sid, q.scids)) ?: return null
        val target = (dns.srv("_radiospi._tcp.$cname") + dns.srv("_radioepg._tcp.$cname"))
            .sortedWith(compareBy({ it.priority }, { -it.weight })).firstOrNull() ?: return null
        val key = "${target.host}:${target.port}"
        val xml = spiFiles.getOrPut(key) {
            // port 443 means HTTPS (Radioplayer); plain HTTP to it just hangs
            val base = when (target.port) { 80 -> "http://${target.host}"; 443 -> "https://${target.host}"; else -> "http://${target.host}:${target.port}" }
            http.get("$base/radiodns/spi/3.1/SI.xml", 4 * 1024 * 1024)
                ?.takeIf { it.code == 200 }?.body ?: ByteArray(0)       // e.g. Radioplayer answers 401 without an API key
        }
        if (xml.isEmpty()) return null
        return SpiParser.service(xml, RadioDns.gcc(q.ecc, q.sid), q.eid, q.sid, q.scids)
    }

    private fun fromWebsite(homepage: String): FoundLogo? {
        val page = http.get(homepage, 1024 * 1024)?.takeIf { it.code == 200 } ?: return null
        val icons = WebIcons.page(String(page.body, Charsets.UTF_8), page.url)
        icons.manifest?.let { m -> http.get(m, 256 * 1024)?.takeIf { it.code == 200 }?.let { r -> WebIcons.manifestIcons(String(r.body), r.url) } }
            ?.let(::bestFirst)?.firstNotNullOfOrNull { fetch(it.url, LogoSource.WEB_MANIFEST) }?.let { return it }
        return bestFirst(icons.touchIcons + icons.icons.filter { minOf(it.width, it.height) >= 128 })
            .firstNotNullOfOrNull { fetch(it.url, LogoSource.WEB_TOUCH_ICON) }
    }

    /** Declared sizes: closest to [TILE_PX] from above first, then the smaller ones, largest first. */
    private fun bestFirst(l: List<LogoRef>) = l.sortedWith(compareBy({ it.width < TILE_PX }, { if (it.width >= TILE_PX) it.width else -it.width }))

    private fun fetch(url: String, source: LogoSource): FoundLogo? {
        val r = http.get(url, 2 * 1024 * 1024)?.takeIf { it.code == 200 } ?: return null
        val info = ImageSniff.info(r.body)
        return if (ImageSniff.usable(info)) FoundLogo(r.body, r.url, source, info!!) else null
    }
}
