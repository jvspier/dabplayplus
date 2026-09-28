package io.github.jvspier.dabplay.core.mp2

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DabMp2Test {
    private fun res(name: String) = javaClass.getResourceAsStream("/mp2/$name")!!.readBytes()

    private fun frames(stream: ByteArray, chunk: (Int) -> Int): List<ByteArray> {
        val f = Mp2Framer(); val out = ArrayList<ByteArray>()
        var i = 0; var n = 0
        while (i < stream.size) { val len = minOf(chunk(n++), stream.size - i); f.feed(stream.copyOfRange(i, i + len)) { fr, _ -> out += fr }; i += len }
        return out
    }

    @Test fun findsEveryFrameInOddChunksAfterJunk() {
        val mp2 = res("c48_160k.mp2")                       // 21 frames of 480 bytes
        val junk = Random(1).nextBytes(333).also { it[100] = 0xFF.toByte(); it[101] = 0xFD.toByte() }   // a fake sync in there
        val rnd = Random(2)
        val got = frames(junk + mp2) { 1 + rnd.nextInt(700) }
        assertEquals(21, got.size)                            // all of them: once locked, no need to wait for the next header
        got.forEachIndexed { k, fr -> assertContentEquals(mp2.copyOfRange(k * 480, k * 480 + 480), fr) }
    }

    @Test fun resyncsAfterABrokenFrame() {
        val mp2 = res("c48_160k.mp2").copyOf()
        mp2[480 * 5] = 0                                     // frame 5 loses its sync word
        val f = Mp2Framer(); val out = ArrayList<ByteArray>()
        f.feed(mp2) { fr, _ -> out += fr }
        assertEquals(20, out.size)                            // only the broken frame is lost, then it locks again
        assertTrue(f.resyncs >= 1)
        assertContentEquals(mp2.copyOfRange(480 * 6, 480 * 7), out.first { it.contentEquals(mp2.copyOfRange(480 * 6, 480 * 7)) })
    }

    @Test fun padSitsBeforeTheScfCrcAtTheEnd() {
        val h = Mp2Header.parse(res("c48_160k.mp2"))!!       // 48 kHz, 160 kbit/s → 4 bytes ScF-CRC
        assertEquals(4, DabMp2Pad.scfCrcLen(h))
        val frame = ByteArray(h.frameBytes) { (it % 251).toByte() }
        val (x, f) = DabMp2Pad.split(frame, h)!!
        assertContentEquals(frame.copyOfRange(478, 480), f)
        assertContentEquals(frame.copyOfRange(474 - 196, 474), x)
        assertEquals(2, DabMp2Pad.scfCrcLen(Mp2Header.parse(res("s24_64k.mp2"))!!))   // half rate
        assertEquals(2, DabMp2Pad.scfCrcLen(Mp2Header.parse(res("s48_64k.mp2"))!!.copy(bitrateKbps = 48)))
    }
}
