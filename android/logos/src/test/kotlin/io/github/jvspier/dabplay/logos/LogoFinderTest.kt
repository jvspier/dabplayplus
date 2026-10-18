package io.github.jvspier.dabplay.logos

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Synthetic fixtures shaped like the real responses checked on 2026-09-24/27 (Talpa SPI, KINK website). */
class LogoFinderTest {
    private fun png(w: Int, h: Int) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()) +
        byteArrayOf((w shr 24).toByte(), (w shr 16).toByte(), (w shr 8).toByte(), w.toByte(), (h shr 24).toByte(), (h shr 16).toByte(), (h shr 8).toByte(), h.toByte()) +
        ByteArray(16)

    /** A page that doesn't answer (timeout, connection dropped). */
    private object Timeout

    private class FakeHttp(pages: Map<String, Any>) : Http {
        val pages = pages.toMutableMap()
        val asked = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int): HttpResult? {
            asked += url
            return when (val v = pages[url]) {
                null -> HttpResult(url, 404, null, ByteArray(0))
                is Int -> HttpResult(url, v, null, ByteArray(0))
                is String -> HttpResult(url, 200, "text/html", v.toByteArray())
                is ByteArray -> HttpResult(url, 200, "image/png", v)
                Timeout -> null
                else -> error("bad fixture")
            }
        }
    }

    private class FakeDns(val cnames: Map<String, String> = emptyMap(), val srvs: Map<String, List<SrvTarget>> = emptyMap(),
                          val down: Boolean = false) : DnsLookup {
        override fun cname(name: String) = if (down) throw IOException("DNS timeout") else cnames[name]
        override fun srv(name: String) = if (down) throw IOException("DNS timeout") else srvs[name].orEmpty()
    }

    private val rbServers = "https://all.api.radio-browser.info/json/servers" to """[{"name":"rb1.example"}]"""

    private fun spiXml(bearer: String) = """<?xml version="1.0"?>
        <serviceInformation xmlns="http://www.worlddab.org/schemas/spi"><services>
          <service><shortName>Other</shortName><bearer id="dab:8e3.8008.83c6.0"/>
            <mediaDescription><multimedia type="logo_unrestricted" width="600" height="600" url="http://logos.example/other/600.png"/></mediaDescription></service>
          <service><shortName>538</shortName>
            <mediaDescription><multimedia type="logo_colour_square" url="http://logos.example/538/32.png"/></mediaDescription>
            <mediaDescription><multimedia type="logo_unrestricted" width="160" height="90" url="http://logos.example/538/160x90.png"/></mediaDescription>
            <mediaDescription><multimedia type="logo_unrestricted" width="300" height="300" url="http://logos.example/538/300.png"/></mediaDescription>
            <mediaDescription><multimedia type="logo_unrestricted" width="600" height="600" url="http://logos.example/538/600.png"/></mediaDescription>
            <link description="homepageUri" uri="https://www.538.example/"/>
            <bearer id="fm:8e3.83c7.10180"/><bearer id="$bearer"/></service>
        </services></serviceInformation>"""

    private val talpaDns = FakeDns(
        cnames = mapOf("0.83c7.8008.8e3.dab.radiodns.org" to "talpa.radiodns.example"),
        srvs = mapOf("_radioepg._tcp.talpa.radiodns.example" to listOf(SrvTarget("talpa.radiodns.example", 80, 0, 5))),
    )

    @Test fun radioDnsPicksTheSmallestSquareOfAtLeast256() {
        val http = FakeHttp(mapOf(
            "http://talpa.radiodns.example/radiodns/spi/3.1/SI.xml" to spiXml("dab:8e3.8008.83c7.0"),
            "http://logos.example/538/300.png" to png(300, 300),
            "http://logos.example/538/600.png" to png(600, 600),
        ))
        val l = assertNotNull(LogoFinder(http, talpaDns).find(LogoQuery(0xE3, 0x8008, 0x83C7, "538")))
        assertEquals(LogoSource.RADIODNS, l.source)
        assertEquals("http://logos.example/538/300.png", l.url)
    }

    @Test fun radioDnsMatchesTheSameServiceOnAnotherEnsemble() {
        val http = FakeHttp(mapOf(
            "http://talpa.radiodns.example/radiodns/spi/3.1/SI.xml" to spiXml("dab:8e3.8001.83c7.0"),
            "http://logos.example/538/300.png" to png(300, 300),
        ))
        assertEquals("http://logos.example/538/300.png", LogoFinder(http, talpaDns).find(LogoQuery(0xE3, 0x8008, 0x83C7, "538"))?.url)
    }

    private val kinkSite = mapOf(
        rbServers,
        "https://rb1.example/json/stations/search?name=KINK&countrycode=NL&hidebroken=true&order=votes&reverse=true&limit=30" to
            """[{"name":"KINK - http","homepage":"https://other.example/","favicon":"","votes":9},
                {"name":"KINK","homepage":"https://kink.example/","favicon":"https://kink.example/static/icon.png","votes":1865}]""",
        "https://kink.example/" to """<html><head>
            <link rel="manifest" href="/static/site.webmanifest"/>
            <link rel="apple-touch-icon" href="/static/apple-touch-icon.png" sizes="180x180"/>
            <link rel='icon' href='/static/favicon-32x32.png' sizes='32x32' type='image/png'/></head></html>""",
        "https://kink.example/static/site.webmanifest" to
            """{"name":"KINK","icons":[{"src":"/static/android-chrome-192x192.png","sizes":"192x192","type":"image/png"},
               {"src":"android-chrome-512x512.png","sizes":"512x512","type":"image/png"}]}""",
        "https://kink.example/static/android-chrome-512x512.png" to png(512, 512),
        "https://kink.example/static/android-chrome-192x192.png" to png(192, 192),
        "https://kink.example/static/apple-touch-icon.png" to png(180, 180),
    )

    @Test fun websiteManifestIconWhenThereIsNoRadioDns() {
        val l = assertNotNull(LogoFinder(FakeHttp(kinkSite), FakeDns()).find(LogoQuery(0xE3, 0x8018, 0x8358, "KINK")))
        assertEquals(LogoSource.WEB_MANIFEST, l.source)
        assertEquals("https://kink.example/static/android-chrome-512x512.png", l.url)   // resolved relative to the manifest
    }

    @Test fun touchIconWhenTheManifestIconsAreUnusable() {
        val site = kinkSite + mapOf("https://kink.example/static/android-chrome-512x512.png" to "<html>not found</html>",
            "https://kink.example/static/android-chrome-192x192.png" to png(48, 48))
        val l = assertNotNull(LogoFinder(FakeHttp(site), FakeDns()).find(LogoQuery(0xE3, 0x8018, 0x8358, "KINK")))
        assertEquals(LogoSource.WEB_TOUCH_ICON, l.source)
    }

    @Test fun radioplayer401FallsThroughToTheWebsite() {
        val dns = FakeDns(cnames = mapOf("0.8358.8018.8e3.dab.radiodns.org" to "c1.radioplayer.example"),
            srvs = mapOf("_radiospi._tcp.c1.radioplayer.example" to listOf(SrvTarget("rp.example", 80, 0, 0))))
        val site = kinkSite + ("http://rp.example/radiodns/spi/3.1/SI.xml" to 401)
        assertEquals(LogoSource.WEB_MANIFEST, LogoFinder(FakeHttp(site), dns).find(LogoQuery(0xE3, 0x8018, 0x8358, "KINK"))?.source)
    }

    private val tinySite = mapOf(rbServers,
        "https://rb1.example/json/stations/search?name=Tiny+FM&countrycode=NL&hidebroken=true&order=votes&reverse=true&limit=30" to
            """[{"name":"Tiny FM","homepage":"","favicon":"https://tiny.example/favicon.ico","votes":3}]""",
        "https://tiny.example/favicon.ico" to ByteArray(64))
    private val tiny = LogoQuery(0xE3, 0x8018, 0x8999, "Tiny FM")

    @Test fun nothingUsableMeansNoLogo() {
        assertNull(LogoFinder(FakeHttp(tinySite), FakeDns()).find(tiny))
    }

    @Test fun everySourceAnsweredMeansNotFound() {
        assertIs<LogoResult.NotFound>(LogoFinder(FakeHttp(tinySite), FakeDns()).lookup(tiny))
    }

    @Test fun aTimeoutMeansTryAgainNotNotFound() {
        val http = FakeHttp(tinySite + ("https://tiny.example/favicon.ico" to Timeout))
        assertIs<LogoResult.Unreachable>(LogoFinder(http, FakeDns()).lookup(tiny))
    }

    @Test fun aDnsFailureMeansTryAgain() {
        assertIs<LogoResult.Unreachable>(LogoFinder(FakeHttp(tinySite), FakeDns(down = true)).lookup(tiny))
    }

    @Test fun aLogoFoundElsewhereWinsOverADnsFailure() {
        val r = assertIs<LogoResult.Found>(LogoFinder(FakeHttp(kinkSite), FakeDns(down = true)).lookup(LogoQuery(0xE3, 0x8018, 0x8358, "KINK")))
        assertEquals(LogoSource.WEB_MANIFEST, r.logo.source)
    }

    @Test fun aFailedSpiFileIsTriedAgainForTheNextStation() {
        val si = "http://talpa.radiodns.example/radiodns/spi/3.1/SI.xml"
        val http = FakeHttp(mapOf(si to Timeout, "http://logos.example/538/300.png" to png(300, 300)))
        val f = LogoFinder(http, talpaDns)
        val q = LogoQuery(0xE3, 0x8008, 0x83C7, "538")
        assertIs<LogoResult.Unreachable>(f.lookup(q))
        http.pages[si] = spiXml("dab:8e3.8008.83c7.0")
        assertEquals(LogoSource.RADIODNS, assertIs<LogoResult.Found>(f.lookup(q)).logo.source)
    }

    @Test fun spiFileIsFetchedOncePerHost() {
        val http = FakeHttp(mapOf("http://talpa.radiodns.example/radiodns/spi/3.1/SI.xml" to spiXml("dab:8e3.8008.83c7.0"),
            "http://logos.example/538/300.png" to png(300, 300)))
        val f = LogoFinder(http, talpaDns)
        repeat(3) { f.find(LogoQuery(0xE3, 0x8008, 0x83C7, "538")) }
        assertEquals(1, http.asked.count { it.endsWith("SI.xml") })
    }
}
