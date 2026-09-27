package io.github.jvspier.dabplay.core.pad

import io.github.jvspier.dabplay.core.Golden
import io.github.jvspier.dabplay.core.Golden.q
import io.github.jvspier.dabplay.core.dabplus.SuperframeDecoder
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import kotlin.test.Test

class PadGoldenTest {
    private fun sha12(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(12)

    @Test fun dlsAndSlidesMatchThePythonReference() {
        val files = Golden.raw(".*\\.msc")
        assumeTrue("no MSC captures in ${Golden.root}", files.isNotEmpty())
        for (f in files) {
            val expected = Golden.expected(f).filter { it.startsWith("dls ") || it.startsWith("slide ") || it.startsWith("pad ") }
            val bitrate = Regex("\"bitrate\":\\s*(\\d+)").find(java.io.File(f.path + ".json").readText())!!.groupValues[1].toInt()
            val dec = SuperframeDecoder(bitrate)
            val events = ArrayList<String>()
            val pad = PadDecoder(
                onDls = { events += "dls ${dec.superframes} ${q(it)}" },
                onSlide = { s -> events += "slide ${dec.superframes} tid ${s.transportId} type ${s.contentType}/${s.contentSubtype} " +
                    "name ${q(s.contentName ?: "")} size ${s.body.size} sha1 ${sha12(s.body)}" },
            )
            dec.feed(f.readBytes()) { pad.feedAu(it.data) }
            val actual = events + "pad dl_dg ${pad.dlGroups} dl_crc_bad ${pad.dlCrcBad} mot_dg ${pad.motGroups} mot_crc_bad ${pad.motCrcBad} slides ${pad.slides}"
            Golden.compare(f.name, expected, actual)
            println("  ${f.name}: ${events.count { it.startsWith("dls") }} DLS changes, ${pad.slides} slides, last DLS ${q(pad.label ?: "")}")
        }
    }
}
