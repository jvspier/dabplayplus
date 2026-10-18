package io.github.jvspier.dabplay.logos

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What to look up: the DAB identity of the service plus its label (for the name-matched fallbacks). */
data class LogoQuery(val ecc: Int, val eid: Int, val sid: Int, val label: String, val scids: Int = 0)

enum class LogoSource { RADIODNS, WEB_MANIFEST, WEB_TOUCH_ICON, RADIO_BROWSER }

class FoundLogo(val bytes: ByteArray, val url: String, val source: LogoSource, val info: ImageInfo)

sealed interface LogoResult {
    class Found(val logo: FoundLogo) : LogoResult
    /** Every source answered: there is no usable logo (looked up again after a few days). */
    data object NotFound : LogoResult
    /** Nothing found, and some source didn't answer (timeout, connection gone): try again next time. */
    data object Unreachable : LogoResult
}

/**
 * The logo chain (docs/findings.md, 2026-09-24): RadioDNS SPI (official, keyed by the DAB ids) → the station
 * website's web app manifest icon → its apple-touch-icon → the radio-browser favicon. Every candidate is
 * downloaded and checked ([ImageSniff.usable]) before it counts. Blocking; call it off the main thread.
 * One instance can serve many stations, also in parallel: SI.xml files are cached per host for its lifetime.
 */
class LogoFinder(private val http: Http, private val dns: DnsLookup, private val radioBrowser: RadioBrowser = RadioBrowser(http)) {
    private val spiFiles = ConcurrentHashMap<String, ByteArray>()     // SPI host:port → SI.xml ("" = none)

    /** The station's logo, or why there's none: [LogoResult.Unreachable] when some source didn't answer. */
    fun lookup(q: LogoQuery): LogoResult {
        val net = Tracked(http)
        val logo = find(q, net)
        return when {
            logo != null -> LogoResult.Found(logo)
            net.unreachable -> LogoResult.Unreachable
            else -> LogoResult.NotFound
        }
    }

    fun find(q: LogoQuery): FoundLogo? = (lookup(q) as? LogoResult.Found)?.logo

    private fun find(q: LogoQuery, net: Tracked): FoundLogo? {
        val spi = spiService(q, net)
        spi?.let { pickLogo(it.logos) }?.let { fetch(net, it.url, LogoSource.RADIODNS) }?.let { return it }

        val country = Countries.of(q.ecc, q.sid)
        val rb by lazy { radioBrowser.find(q.label, country, net) }
        val homepage = spi?.homepage ?: rb.firstNotNullOfOrNull { it.homepage }
        homepage?.let { fromWebsite(net, it) }?.let { return it }

        return rb.mapNotNull { it.favicon }.distinct().firstNotNullOfOrNull { fetch(net, it, LogoSource.RADIO_BROWSER) }
    }

    /** One lookup's HTTP: remembers whether any request went unanswered. */
    private class Tracked(private val http: Http) : Http {
        @Volatile var unreachable = false
        override fun get(url: String, maxBytes: Int) = http.get(url, maxBytes).also { if (it == null) unreachable = true }
    }

    /** RadioDNS: CNAME of the service's FQDN → SPI (or EPG) SRV → `SI.xml` → this service's entry. */
    private fun spiService(q: LogoQuery, net: Tracked): SpiService? {
        if (q.eid !in 0..0xFFFF) return null              // a Si4684 module's synthetic ensemble id: RadioDNS needs the real one
        val target = try {
            val cname = dns.cname(RadioDns.dabFqdn(q.ecc, q.eid, q.sid, q.scids)) ?: return null
            (dns.srv("_radiospi._tcp.$cname") + dns.srv("_radioepg._tcp.$cname"))
                .sortedWith(compareBy({ it.priority }, { -it.weight })).firstOrNull() ?: return null
        } catch (e: IOException) { net.unreachable = true; return null }   // the website may still have one
        val key = "${target.host}:${target.port}"
        val xml = spiFiles[key] ?: run {
            // port 443 means HTTPS (Radioplayer); plain HTTP to it just hangs
            val base = when (target.port) { 80 -> "http://${target.host}"; 443 -> "https://${target.host}"; else -> "http://${target.host}:${target.port}" }
            val r = net.get("$base/radiodns/spi/3.1/SI.xml", 4 * 1024 * 1024) ?: return null   // no answer: not cached, tried again
            (r.takeIf { it.code == 200 }?.body ?: ByteArray(0)).also { spiFiles[key] = it }  // e.g. Radioplayer: 401 without an API key
        }
        if (xml.isEmpty()) return null
        return SpiParser.service(xml, RadioDns.gcc(q.ecc, q.sid), q.eid, q.sid, q.scids)
    }

    private fun fromWebsite(net: Http, homepage: String): FoundLogo? {
        val page = net.get(homepage, 1024 * 1024)?.takeIf { it.code == 200 } ?: return null
        val icons = WebIcons.page(String(page.body, Charsets.UTF_8), page.url)
        icons.manifest?.let { m -> net.get(m, 256 * 1024)?.takeIf { it.code == 200 }?.let { r -> WebIcons.manifestIcons(String(r.body), r.url) } }
            ?.let(::bestFirst)?.firstNotNullOfOrNull { fetch(net, it.url, LogoSource.WEB_MANIFEST) }?.let { return it }
        return bestFirst(icons.touchIcons + icons.icons.filter { minOf(it.width, it.height) >= 128 })
            .firstNotNullOfOrNull { fetch(net, it.url, LogoSource.WEB_TOUCH_ICON) }
    }

    /** Declared sizes: closest to [TILE_PX] from above first, then the smaller ones, largest first. */
    private fun bestFirst(l: List<LogoRef>) = l.sortedWith(compareBy({ it.width < TILE_PX }, { if (it.width >= TILE_PX) it.width else -it.width }))

    private fun fetch(net: Http, url: String, source: LogoSource): FoundLogo? {
        val r = net.get(url, 2 * 1024 * 1024)?.takeIf { it.code == 200 } ?: return null
        val info = ImageSniff.info(r.body)
        return if (ImageSniff.usable(info)) FoundLogo(r.body, r.url, source, info!!) else null
    }
}
