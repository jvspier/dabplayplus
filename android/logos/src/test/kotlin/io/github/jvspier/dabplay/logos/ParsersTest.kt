package io.github.jvspier.dabplay.logos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {
    @Test fun sniffsJpegSizeFromTheSofMarker() {
        // SOI, APP0 (len 16), SOF0: len 17, precision 8, height 0x00F0, width 0x0140
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16) + ByteArray(14) +
            byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, 0, 0xF0.toByte(), 1, 0x40) + ByteArray(12)
        assertEquals(ImageInfo("image/jpeg", 320, 240), ImageSniff.info(jpg))
    }

    @Test fun sniffsWebpVp8x() {
        val b = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + ByteArray(8) +
            byteArrayOf(0xFF.toByte(), 0x01, 0, 0xFF.toByte(), 0x01, 0) + ByteArray(4)
        assertEquals(ImageInfo("image/webp", 512, 512), ImageSniff.info(b))
    }

    @Test fun rejectsIcoSvgAndTinyOrBannerImages() {
        assertNull(ImageSniff.info(byteArrayOf(0, 0, 1, 0, 1, 0) + ByteArray(40)))          // ICO
        assertNull(ImageSniff.info("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()))
        assertFalse(ImageSniff.usable(ImageInfo("image/png", 32, 32)))
        assertFalse(ImageSniff.usable(ImageInfo("image/png", 600, 100)))
        assertTrue(ImageSniff.usable(ImageInfo("image/png", 320, 240)))
    }

    @Test fun pageLinksResolveAgainstTheFinalUrl() {
        val p = WebIcons.page("""<LINK REL="Apple-Touch-Icon" HREF="icons/touch.png">
            <link href="//cdn.example/m.json" rel="manifest"><link rel="shortcut icon" href="/f.ico">""", "https://www.station.example/nl/")
        assertEquals("https://cdn.example/m.json", p.manifest)
        assertEquals("https://www.station.example/nl/icons/touch.png", p.touchIcons.single().url)
        assertEquals("https://www.station.example/f.ico", p.icons.single().url)
    }

    @Test fun manifestSkipsSvgAndPutsMaskableOnlyLast() {
        val icons = WebIcons.manifestIcons("""{"icons":[
            {"src":"m.png","sizes":"512x512","type":"image/png","purpose":"maskable"},
            {"src":"a.svg","sizes":"any","type":"image/svg+xml"},
            {"src":"b.png","sizes":"192x192 384x384","type":"image/png","purpose":"any maskable"}]}""", "https://s.example/app/manifest.json")
        assertEquals(listOf("https://s.example/app/b.png" to 384, "https://s.example/app/m.png" to 512), icons.map { it.url to it.width })
    }

    @Test fun picksLogosForTiles() {
        val l = listOf(LogoRef("32", 32, 32), LogoRef("rect", 112, 32), LogoRef("128", 128, 128), LogoRef("320x240", 320, 240))
        assertEquals("128", pickLogo(l)?.url)                          // a square beats a sharper 4:3
        assertEquals("320x240", pickLogo(l.filter { it.url != "128" })?.url)
        assertNull(pickLogo(listOf(LogoRef("32", 32, 32))))
    }

    @Test fun countriesFromEccAndSid() {
        assertEquals("NL", Countries.of(0xE3, 0x83C7))
        assertEquals("DE", Countries.of(0xE0, 0xD312))
        assertEquals("GB", Countries.of(0xE1, 0xC479))
        assertNull(Countries.of(0xA0, 0x1234))
    }

    @Test fun normalisesStationNames() {
        assertEquals(RadioBrowser.normalise("100%NL"), RadioBrowser.normalise("100% NL"))
        assertEquals("npo3fm", RadioBrowser.normalise(" NPO 3FM "))
        assertEquals(RadioBrowser.loose("Omroep Zeeland"), RadioBrowser.loose("Omroep Zeeland Radio"))
        assertEquals(RadioBrowser.loose("538"), RadioBrowser.loose("Radio 538"))
        assertEquals("", RadioBrowser.loose("Radio FM"))
    }
}
