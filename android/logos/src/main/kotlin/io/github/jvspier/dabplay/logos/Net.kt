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
    fun get(url: String, maxBytes: Int): HttpResult?
}

/** [Http] over OkHttp: follows redirects (also http → https), short timeouts, never throws. */
class OkHttpGet(private val client: OkHttpClient = defaultClient()) : Http {
    override fun get(url: String, maxBytes: Int): HttpResult? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            val body = r.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("more than $maxBytes bytes")
                }
                out.toByteArray()
            }
            HttpResult(r.request.url.toString(), r.code, r.header("Content-Type"), body)
        }
    }.getOrNull()

    companion object {
        /** radio-browser.info asks clients for a descriptive User-Agent. */
        const val USER_AGENT = "DABplay+/0.1 (+https://github.com/jvspier/dabplayplus)"
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }
}

data class SrvTarget(val host: String, val port: Int, val priority: Int, val weight: Int)

/** CNAME and SRV lookups (RadioDNS); faked in tests. */
interface DnsLookup {
    fun cname(name: String): String?
    fun srv(name: String): List<SrvTarget>
}

/**
 * [DnsLookup] with dnsjava, sent straight to the given DNS servers (on Android: the active network's, from
 * LinkProperties). Deliberately skips dnsjava's Lookup/ResolverConfig, whose Android auto-config needs extra setup.
 */
class DnsJavaLookup(private val servers: () -> List<InetAddress>) : DnsLookup {
    private fun query(name: String, type: Int): List<Record> = runCatching {
        val list = servers().ifEmpty { return emptyList() }
        val resolver = ExtendedResolver(list.map { SimpleResolver(it) }).apply { timeout = java.time.Duration.ofSeconds(4) }
        val reply = resolver.send(Message.newQuery(Record.newRecord(Name.fromString(fqdn(name)), type, DClass.IN)))
        if (reply.rcode != Rcode.NOERROR) emptyList() else reply.getSection(Section.ANSWER)
    }.getOrDefault(emptyList())

    override fun cname(name: String) =
        query(name, Type.CNAME).filterIsInstance<CNAMERecord>().firstOrNull()?.target?.toString()?.trimEnd('.')

    override fun srv(name: String) = query(name, Type.SRV).filterIsInstance<SRVRecord>()
        .map { SrvTarget(it.target.toString().trimEnd('.'), it.port, it.priority, it.weight) }

    private fun fqdn(n: String) = if (n.endsWith(".")) n else "$n."
}
