package io.github.jvspier.dabplay.engine

import io.github.jvspier.dabplay.core.Reception
import io.github.jvspier.dabplay.data.ServiceKey
import io.github.jvspier.dabplay.data.StationStore
import io.github.jvspier.dabplay.data.StoredEnsemble
import io.github.jvspier.dabplay.data.StoredService
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The engine end to end on the JVM: a scripted tuner delivers a test broadcast (valid FIC and DAB+ superframes) and
 * the tests drive what happens in a car: playing, losing a multiplex, a traffic announcement, a USB error, a dongle
 * dropout, a band scan. Real threads and real (shortened) timings, as on the head unit.
 */
class DabEngineTest {
    private val store = StationStore(File(Files.createTempDirectory("engine").toFile(), "stations.json"))
    private val audios = CopyOnWriteArrayList<FakeAudio>()
    private val focus = FakeFocus()
    private val log = TestLog()
    private val engines = CopyOnWriteArrayList<DabEngine>()
    @Volatile private var trafficOn = false

    private val radio538 = TestService(0x83C7, "538", subchannel = 2)
    private val traffic = TestService(0x8888, "Verkeer", subchannel = 7)
    private val key538 = ServiceKey(0xE3, 0x83C7)
    private val k11C = 220352
    private val k12C = 227360

    private fun stored(eid: Int, channel: String, khz: Int, vararg s: TestService) = StoredEnsemble(eid, channel, khz, "Mux $channel", 0, 0,
        s.map { StoredService(ServiceKey(0xE3, it.sid), it.label, it.subchannel, it.kbps, 63) })

    private fun engine(drivers: Drivers) = DabEngine(store, focus, newAudio = { FakeAudio().also { audios += it } }, events = log,
        resumeOnStart = { false }, followStation = { true }, trafficAnnouncements = { trafficOn }, timings = FAST, drivers = drivers)
        .also { engines += it }

    private fun await(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what; log:\n" + log.lines.takeLast(20).joinToString("\n"))
            Thread.sleep(20)
        }
    }

    private fun DabEngine.startPlaying(key: ServiceKey, dongle: Dongle = FakeDongle("usb-1")) {
        attach(dongle)
        await("the dongle") { state.value.dongle }
        play(key)
        await("audio") { state.value.playing && (audios.lastOrNull()?.aus ?: 0) >= 6 }
    }

    @AfterTest fun stopEngines() = engines.forEach { it.detached(null) }

    @Test fun playsAStationEndToEnd() {
        store.applyScan(listOf(stored(0x8008, "11C", k11C, radio538)), setOf("11C"))
        val tuner = ScriptedTuner(mutableMapOf(k11C to Broadcast.fic(0x8008, "Mux 11C", listOf(radio538))))
        val e = engine(FakeDrivers { tuner })
        e.startPlaying(key538)
        assertTrue("start 2@$k11C" in tuner.calls, tuner.calls.toString())
        assertEquals("538", e.state.value.current?.label)
        Thread.sleep(4_000)                                     // past the reception monitor's 3 s grace
        assertEquals(Reception.GOOD, e.state.value.reception)
        assertEquals(4, e.state.value.signalBars)
        assertEquals(0, audios.last().silenceMs)                // no lost AUs on a clean broadcast
    }

    @Test fun followsTheStationWhenItsMultiplexIsLost() {
        // 538 on two multiplexes; the more recently seen (11C) plays first
        store.applyScan(listOf(stored(0x8009, "12C", k12C, radio538.copy(subchannel = 5))), setOf("12C"))
        Thread.sleep(5)
        store.applyScan(listOf(stored(0x8008, "11C", k11C, radio538)), setOf("11C"))
        val tuner = ScriptedTuner(mutableMapOf(
            k11C to Broadcast.fic(0x8008, "Mux 11C", listOf(radio538)),
            k12C to Broadcast.fic(0x8009, "Mux 12C", listOf(radio538.copy(subchannel = 5)))))
        val e = engine(FakeDrivers { tuner })
        e.startPlaying(key538)
        Thread.sleep(3_500)                                     // past the grace period
        tuner.blocked = setOf(k11C)                             // driving out of 11C's coverage
        await("reception lost") { e.state.value.reception == Reception.LOST }
        await("following to 12C") { e.state.value.current?.ensemble?.channel == "12C" && e.state.value.playing }
        assertTrue("start 5@$k12C" in tuner.calls, tuner.calls.toString())
        val aus = audios.last().aus
        await("audio from 12C") { audios.last().aus > aus + 6 }
        assertTrue(log.lines.any { it.contains("continuing on 12C") })
    }

    @Test fun switchesToATrafficAnnouncementAndBack() {
        trafficOn = true
        store.applyScan(listOf(stored(0x8008, "11C", k11C, radio538, traffic)), setOf("11C"))
        val services = listOf(radio538, traffic)
        val support = Broadcast.trafficSupport(radio538.sid, cluster = 1)
        val tuner = ScriptedTuner(mutableMapOf(k11C to Broadcast.fic(0x8008, "Mux 11C", services, listOf(support))))
        val e = engine(FakeDrivers { tuner })
        e.startPlaying(key538)
        await("the TA badge") { e.state.value.taSupported }

        tuner.ensembles[k11C] = Broadcast.fic(0x8008, "Mux 11C", services, listOf(support, Broadcast.announcementSwitch(1, traffic.subchannel)))
        await("the announcement") { e.state.value.announcement != null && e.state.value.current?.label == "Verkeer" }
        assertEquals(key538, e.state.value.announcement?.returnTo)
        assertTrue("start 7@$k11C" in tuner.calls, tuner.calls.toString())

        tuner.ensembles[k11C] = Broadcast.fic(0x8008, "Mux 11C", services, listOf(support, Broadcast.announcementSwitch(1, traffic.subchannel, asw = 0)))
        await("back to 538") { e.state.value.announcement == null && e.state.value.current?.label == "538" && e.state.value.playing }
        assertEquals("start 2@$k11C", tuner.calls.last { it.startsWith("start") })
    }

    @Test fun noAnnouncementWithTheSettingOff() {
        store.applyScan(listOf(stored(0x8008, "11C", k11C, radio538, traffic)), setOf("11C"))
        val services = listOf(radio538, traffic)
        val support = Broadcast.trafficSupport(radio538.sid, cluster = 1)
        val tuner = ScriptedTuner(mutableMapOf(k11C to Broadcast.fic(0x8008, "Mux 11C", services,
            listOf(support, Broadcast.announcementSwitch(1, traffic.subchannel)))))
        val e = engine(FakeDrivers { tuner })
        e.startPlaying(key538)
        Thread.sleep(1_500)
        assertEquals(null, e.state.value.announcement)
        assertFalse(tuner.calls.any { it.startsWith("start 7") })
    }

    @Test fun reconnectsAfterAUsbErrorAndCarriesOn() {
        store.applyScan(listOf(stored(0x8008, "11C", k11C, radio538)), setOf("11C"))
        val fic = Broadcast.fic(0x8008, "Mux 11C", listOf(radio538))
        val drivers = FakeDrivers { ScriptedTuner(mutableMapOf(k11C to fic)) }
        val dongle = FakeDongle("usb-1")
        val e = engine(drivers)
        e.startPlaying(key538, dongle)
        dongle.failOpens = 1                                    // the first retry fails too (a voltage dip that lasts)
        (drivers.opened.last() as ScriptedTuner).failMscRead = true
        await("a new connection playing 538") {
            drivers.opened.size >= 2 && (drivers.opened.last() as ScriptedTuner).calls.contains("start 2@$k11C") && e.state.value.playing
        }
        assertTrue(log.lines.any { it.startsWith("E engine error") })
        assertEquals("538", e.state.value.current?.label)
    }

    @Test fun aDongleDropoutResumesWhenItIsBack() {
        store.applyScan(listOf(stored(0x8008, "11C", k11C, radio538)), setOf("11C"))
        val fic = Broadcast.fic(0x8008, "Mux 11C", listOf(radio538))
        val drivers = FakeDrivers { ScriptedTuner(mutableMapOf(k11C to fic)) }
        val e = engine(drivers)
        e.startPlaying(key538)
        e.detached("usb-1")                                     // cranking: the dongle is gone for a moment
        await("stopped") { !e.state.value.playing && !e.state.value.dongle }
        assertTrue(e.wantsDongleBack)
        e.attach(FakeDongle("usb-2"))                           // back, under a new device name
        await("538 again") { e.state.value.playing && (drivers.opened.last() as ScriptedTuner).calls.contains("start 2@$k11C") }
        assertFalse(e.wantsDongleBack)
    }

    @Test fun afterAPauseADropoutDoesNotStartTheRadio() {
        store.applyScan(listOf(stored(0x8008, "11C", k11C, radio538)), setOf("11C"))
        val e = engine(FakeDrivers { ScriptedTuner(mutableMapOf(k11C to Broadcast.fic(0x8008, "Mux 11C", listOf(radio538)))) })
        e.startPlaying(key538)
        e.pause()
        await("paused") { !e.state.value.playing }
        e.detached("usb-1")
        assertFalse(e.wantsDongleBack)
    }

    @Test fun aBandScanStoresWhatItFinds() {
        // an empty store: the first connection scans by itself
        val tuner = ScriptedTuner(mutableMapOf(
            k11C to Broadcast.fic(0x8008, "Mux 11C", listOf(radio538, traffic)),
            k12C to Broadcast.fic(0x8009, "Mux 12C", listOf(TestService(0x8301, "NPO Radio 2", 3)))))
        val e = engine(FakeDrivers { tuner })
        e.attach(FakeDongle("usb-1"))
        await("the scan", ms = 30_000) { log.lines.any { it.startsWith("Scan done") } }
        val s = store.state.value
        assertEquals(setOf("11C", "12C"), s.ensembles.map { it.channel }.toSet())
        assertEquals(setOf("538", "Verkeer", "NPO Radio 2"), s.stations.map { it.label }.toSet())
        assertEquals(0x8008, s.ensembles.first { it.channel == "11C" }.eid)
    }

    private companion object {
        val FAST = EngineTimings(followAfterMs = 300, followRetryMs = 2_000, relockEveryMs = 200, moduleSilentMs = 2_000,
            learnEveryMs = 100, reconnectBaseMs = 50, reconnectMaxMs = 200)
    }
}
