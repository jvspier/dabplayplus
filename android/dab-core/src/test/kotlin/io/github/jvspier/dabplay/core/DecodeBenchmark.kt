package io.github.jvspier.dabplay.core

import io.github.jvspier.dabplay.core.dabplus.SuperframeDecoder
import io.github.jvspier.dabplay.core.fic.FicDecoder
import io.github.jvspier.dabplay.core.mp2.Mp2Decoder
import io.github.jvspier.dabplay.core.mp2.Mp2Header
import io.github.jvspier.dabplay.core.pad.PadDecoder
import java.lang.management.ManagementFactory
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * CPU cost of the host-side decoding, per second of radio (opt-in: BENCH=1). JIT-warmed, single thread, CPU time
 * (not wall time). The head unit's cores are several times slower than a laptop's: see findings.md for the scaling.
 */
class DecodeBenchmark {
    private val cpu = ManagementFactory.getThreadMXBean()
    private fun cpuMs(block: () -> Unit): Double { val t = cpu.currentThreadCpuTime; block(); return (cpu.currentThreadCpuTime - t) / 1e6 }

    /** Best of [runs] after [warm] warm-up runs: ms of CPU per second of audio. */
    private fun bench(name: String, audioSeconds: Double, warm: Int = 5, runs: Int = 7, block: () -> Unit) {
        repeat(warm) { block() }
        val best = (1..runs).minOf { cpuMs(block) }
        println("BENCH %-38s %7.3f ms CPU per s of audio  (%.2f %% of a core)".format(name, best / audioSeconds, best / audioSeconds / 10))
    }

    @Test fun decoding() {
        assumeTrue("set BENCH=1 to run", System.getenv("BENCH") == "1")
        val msc = Golden.raw(".*\\.msc")
        assumeTrue("no captures", msc.isNotEmpty())
        for (f in msc) {
            val br = Regex("\"bitrate\":\\s*(\\d+)").find(java.io.File(f.path + ".json").readText())!!.groupValues[1].toInt()
            val bytes = f.readBytes()
            val secs = bytes.size / (br * 125.0)
            bench("superframes+RS ${f.name.substringAfter("11C-")} $br k", secs) { SuperframeDecoder(br).feed(bytes) { } }
            bench("  + PAD (DLS, slides)", secs) {
                val pad = PadDecoder(onDls = { }, onSlide = { })
                SuperframeDecoder(br).feed(bytes) { pad.feedAu(it.data) }
            }
        }
        // FIC: 96 bytes per 24 ms frame in mode I; this is all of it (during playback only ~15 % is read)
        for (f in Golden.raw(".*\\.fic").take(2)) {
            val b = f.readBytes()
            bench("FIC ${f.name}", b.size / 4000.0) { FicDecoder().feed(b) }
        }
        for (name in listOf("c48_160k", "j48_128k", "s24_64k")) {
            val mp2 = javaClass.getResourceAsStream("/mp2/$name.mp2")!!.readBytes()
            val frames = ArrayList<ByteArray>(); var off = 0; var secs = 0.0
            while (off < mp2.size) {
                val h = Mp2Header.parse(mp2, off)!!
                frames += mp2.copyOfRange(off, off + h.frameBytes); off += h.frameBytes
                secs += 1152.0 / h.sampleRate
            }
            bench("MP2 $name", secs) { val d = Mp2Decoder(); frames.forEach { d.decode(it) } }
        }
    }
}
