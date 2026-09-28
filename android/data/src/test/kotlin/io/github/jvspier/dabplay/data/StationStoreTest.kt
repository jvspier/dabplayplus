package io.github.jvspier.dabplay.data

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StationStoreTest {
    private val dir = Files.createTempDirectory("stations").toFile()
    private val file = File(dir, "stations.json")
    private val svc = StoredService(ServiceKey(0xE3, 0x83C7), "538", 3, 96, 63)
    private val ens = StoredEnsemble(0x8008, "11C", 220352, "DAB+", 0, 0, listOf(svc))

    @Test fun concurrentChangesEndUpOnDiskAsTheLatestState() {
        val s = StationStore(file)
        s.applyScan(listOf(ens), setOf("11C"))
        val keys = (1..200).map { ServiceKey(0xE3, 0x8000L + it) }
        val a = thread { keys.take(100).forEach { s.toggleFavourite(it) } }       // the UI thread
        val b = thread { repeat(100) { s.played(svc.key) } }                        // the USB thread
        a.join(); b.join(); s.flush()
        val reloaded = StationStore(file).state.value
        assertEquals(s.state.value.favourites, reloaded.favourites)
        assertEquals(24, reloaded.favourites.size)                                 // the cap
        assertEquals(listOf(svc.key), reloaded.recents.map { it.key })
    }

    @Test fun aCorruptFileIsKeptAsideNotOverwrittenSilently() {
        file.writeText("{\"ensembles\": [ {\"eid\": 1, truncated")
        val s = StationStore(file)
        assertTrue(s.state.value.ensembles.isEmpty())
        assertTrue(File(dir, "stations.json.bad").exists())
    }

    @Test fun aCompleteTmpFromAnInterruptedWriteIsUsed() {
        StationStore(file).apply { applyScan(listOf(ens), setOf("11C")); toggleFavourite(svc.key); flush() }
        file.renameTo(File(dir, "stations.json.tmp"))                               // the rename never happened
        val s = StationStore(file)
        assertEquals(listOf(svc.key), s.state.value.favourites)
    }

    @Test fun clearAndUndoRecents() {
        val s = StationStore(file)
        s.applyScan(listOf(ens), setOf("11C")); s.played(svc.key)
        val old = s.clearRecents()
        assertTrue(s.state.value.recents.isEmpty())
        s.restoreRecents(old)
        assertEquals(listOf(svc.key), s.state.value.recents.map { it.key })
    }
}
