package io.github.jvspier.dabplay.logos

import java.io.File
import java.net.InetAddress
import kotlin.test.Test
import org.junit.Assume.assumeTrue

/** Real network smoke test, off by default: `LIVE_LOGOS=1 ./gradlew :logos:test --tests '*LiveLogoTest*' -i`. */
class LiveLogoTest {
    @Test fun findsLogosForStationsReceivedHere() {
        assumeTrue(System.getenv("LIVE_LOGOS") == "1")
        val servers = File("/etc/resolv.conf").readLines().mapNotNull { Regex("^nameserver\\s+(\\S+)").find(it)?.groupValues?.get(1) }
            .map { InetAddress.getByName(it) }
        val real = OkHttpGet()
        val http = Http { url, max -> val t = System.currentTimeMillis(); real.get(url, max).also { println("    GET $url -> ${it?.code} ${it?.body?.size} (${System.currentTimeMillis() - t} ms)") } }
        val f = LogoFinder(if (System.getenv("LIVE_LOGOS_TRACE") == "1") http else real, DnsJavaLookup { servers })
        for (q in listOf(LogoQuery(0xE3, 0x8008, 0x83C7, "538"), LogoQuery(0xE3, 0x8001, 0x8203, "NPO 3FM"),
            LogoQuery(0xE3, 0x809C, 0x8358, "KINK"), LogoQuery(0xE3, 0x8008, 0x83C8, "Qmusic"),
            LogoQuery(0xE3, 0x805B, 0x841B, "Omroep Zeeland"), LogoQuery(0xE3, 0x8008, 0x83D3, "100% NL"))) {
            val t0 = System.currentTimeMillis()
            val l = f.find(q)
            println("${q.label.padEnd(15)} ${l?.source ?: "-"} ${l?.info?.let { "${it.width}x${it.height} ${it.mime}" } ?: ""} ${l?.url ?: ""} (${System.currentTimeMillis() - t0} ms)")
        }
    }
}
