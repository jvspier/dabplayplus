package io.github.jvspier.dabplay.core.dabplus

import io.github.jvspier.dabplay.core.Golden
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class SuperframeGoldenTest {
    private fun sha12(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(12)
    private fun bitrate(f: java.io.File) = Regex("\"bitrate\":\\s*(\\d+)").find(java.io.File(f.path + ".json").readText())!!.groupValues[1].toInt()

    @Test fun allAudioCapturesGiveTheSameAccessUnitsAsPython() {
        val files = Golden.raw(".*\\.msc")
        assumeTrue("no MSC captures in ${Golden.root}", files.isNotEmpty())
        for (f in files) {
            val expected = Golden.expected(f).filter { it.startsWith("au ") || it.startsWith("stats ") || it.startsWith("bitrate ") }
            val dec = SuperframeDecoder(bitrate(f))
            val aus = ArrayList<String>()
            dec.feed(f.readBytes()) { au ->
                aus += "au ${aus.size} sf ${dec.superframes} asc ${au.asc.joinToString("") { "%02x".format(it) }} len ${au.data.size} sha1 ${sha12(au.data)}"
            }
            val actual = listOf("bitrate ${bitrate(f)}",
                "stats superframes ${dec.superframes} au_ok ${dec.ausOk} au_bad ${dec.ausBad} rs_fixed ${dec.rsFixed} rs_fail ${dec.rsFail}") + aus
            Golden.compare(f.name, expected, actual)
            println("  ${f.name}: ${dec.superframes} superframes, ${dec.ausOk} AUs, ${dec.format}")
        }
    }

    @Test fun ascMatchesWhatAndroidMediaCodecAccepted() {
        // verified in the API 26/29 emulators (captures/android/*.json)
        assertEquals("141456e5a8", Asc.build(16000, 32000, true, false, true).joinToString("") { "%02x".format(it) })
        assertEquals("131456e598", Asc.build(24000, 48000, true, false, true).joinToString("") { "%02x".format(it) })
        assertEquals("130c56e59d4880", Asc.build(24000, 48000, true, true, false).joinToString("") { "%02x".format(it) })
    }
}
