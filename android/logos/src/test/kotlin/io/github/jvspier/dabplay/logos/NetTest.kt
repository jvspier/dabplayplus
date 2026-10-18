package io.github.jvspier.dabplay.logos

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import okhttp3.OkHttpClient

/** [OkHttpGet] answers null only when the request went unanswered: that's what tells "try again" from "nothing there". */
class NetTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/big.png") { ex -> ByteArray(4096).let { ex.sendResponseHeaders(200, it.size.toLong()); ex.responseBody.use { o -> o.write(it) } } }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val get = OkHttpGet(OkHttpClient())           // the app's client allows public addresses only

    @AfterTest fun stop() = server.stop(0)

    @Test fun anAnswerTooBigIsAnAnswerNotAFailure() {
        val r = assertNotNull(get.get("$base/big.png", 1024))
        assertEquals(OkHttpGet.TOO_BIG, r.code)
    }

    @Test fun aServerThatIsntThereIsNoAnswer() {
        val closed = ServerSocket(0).use { it.localPort }
        assertNull(get.get("http://127.0.0.1:$closed/logo.png", 1024))
    }

    @Test fun withoutDnsServersALookupFails() {
        assertFailsWith<IOException> { DnsJavaLookup { emptyList() }.cname("0.83c7.8008.8e3.dab.radiodns.org") }
    }
}
