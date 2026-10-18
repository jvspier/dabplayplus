package io.github.jvspier.dabplay.logos

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.DClass
import org.xbill.DNS.ExtendedResolver
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.SRVRecord
import org.xbill.DNS.Type

data class HttpResult(val url: String, val code: Int, val contentType: String?, val body: ByteArray)

/** The little HTTP the logo chain needs (GET with a size cap); faked in tests. */
fun interface Http {
    /** null only when there was no answer (no connection, timeout): then it's worth trying again later. */
    fun get(url: String, maxBytes: Int): HttpResult?
}

/** [Http] over OkHttp: follows redirects (also http → https), short timeouts, never throws. */
class OkHttpGet(private val client: OkHttpClient = defaultClient()) : Http {
    override fun get(url: String, maxBytes: Int): HttpResult? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            val body = r.body.byteStream().use { read(it, maxBytes) }
            // more than maxBytes is still an answer (an unusable one), not a reason to try again
            HttpResult(r.request.url.toString(), if (body == null) TOO_BIG else r.code, r.header("Content-Type"), body ?: ByteArray(0))
        }
    }.getOrNull()

    private fun read(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) return null
        }
        return out.toByteArray()
    }

    companion object {
        const val TOO_BIG = 413
        /** radio-browser.info asks clients for a descriptive User-Agent. */
        const val USER_AGENT = "DABplay+/1.1 (+https://github.com/jvspier/dabplayplus)"
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().dns(PublicOnlyDns)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }
}

data class SrvTarget(val host: String, val port: Int, val priority: Int, val weight: Int)

/** CNAME and SRV lookups (RadioDNS); faked in tests. Both throw [IOException] when the DNS didn't answer. */
interface DnsLookup {
    fun cname(name: String): String?
    fun srv(name: String): List<SrvTarget>
}

/**
 * [DnsLookup] with dnsjava, sent straight to the given DNS servers (on Android: the active network's, from
 * LinkProperties). Deliberately skips dnsjava's Lookup/ResolverConfig, whose Android auto-config needs extra setup.
 */
class DnsJavaLookup(private val servers: () -> List<InetAddress>) : DnsLookup {
    /** The answer; empty when the name doesn't exist. Throws when there was no answer (offline, timeout, SERVFAIL). */
    private fun query(name: String, type: Int): List<Record> {
        val question = runCatching { Message.newQuery(Record.newRecord(Name.fromString(fqdn(name)), type, DClass.IN)) }
            .getOrElse { return emptyList() }                     // not a valid name: nothing to find
        val list = servers().ifEmpty { throw IOException("no DNS servers") }
        val resolver = ExtendedResolver(list.map { SimpleResolver(it) }).apply { timeout = java.time.Duration.ofSeconds(4) }
        val reply = try { resolver.send(question) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException(e) }
        return when (reply.rcode) {
            Rcode.NOERROR -> reply.getSection(Section.ANSWER)
            Rcode.NXDOMAIN -> emptyList()
            else -> throw IOException("DNS ${Rcode.string(reply.rcode)} for $name")
        }
    }

    override fun cname(name: String) =
        query(name, Type.CNAME).filterIsInstance<CNAMERecord>().firstOrNull()?.target?.toString()?.trimEnd('.')

    override fun srv(name: String) = query(name, Type.SRV).filterIsInstance<SRVRecord>()
        .map { SrvTarget(it.target.toString().trimEnd('.'), it.port, it.priority, it.weight) }

    private fun fqdn(n: String) = if (n.endsWith(".")) n else "$n."
}

/**
 * DNS that only returns public addresses: a logo URL, a redirect or an SPI host pointing at the car's hotspot router, a
 * LAN device or localhost gets nothing (the logo chain only ever needs the public internet).
 */
object PublicOnlyDns : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = okhttp3.Dns.SYSTEM.lookup(hostname)
        return all.filter(::isPublic).ifEmpty { throw java.net.UnknownHostException("$hostname: no public address") }
    }

    fun isPublic(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is java.net.Inet6Address && (b[0].toInt() and 0xFE) == 0xFC) return false          // fc00::/7 unique local
        if (a is java.net.Inet4Address && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) return false  // 100.64/10 CGNAT
        return true
    }
}
