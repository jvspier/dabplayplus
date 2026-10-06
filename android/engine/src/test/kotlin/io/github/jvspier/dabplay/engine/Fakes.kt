package io.github.jvspier.dabplay.engine

import io.github.jvspier.dabplay.tuner.ModuleListener
import io.github.jvspier.dabplay.tuner.ModuleTuner
import io.github.jvspier.dabplay.tuner.RawTuner
import io.github.jvspier.dabplay.tuner.SignalReading
import io.github.jvspier.dabplay.tuner.Transport
import io.github.jvspier.dabplay.tuner.Tuned
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A raw tuner with a scripted band: [ensembles] (kHz → FIC) lock unless [blocked]; while a sub-channel streams, a
 * superframe arrives every 120 ms as on air. Every call is recorded in [calls].
 */
class ScriptedTuner(val ensembles: MutableMap<Int, ByteArray>) : RawTuner {
    override val description = "scripted tuner"
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var blocked = emptySet<Int>()                 // frequencies that don't lock (and stop streaming)
    @Volatile var failMscRead = false                       // a USB error on the next MSC read
    @Volatile private var tuned = 0
    @Volatile private var started: Pair<Int, Int>? = null
    private var nextSuperframeAt = 0L

    override fun tune(khz: Int, retries: Boolean): Tuned {
        calls += "tune $khz"
        val locked = khz in ensembles && khz !in blocked
        tuned = if (locked) khz else 0
        return Tuned(locked, if (locked) 4 else 0)
    }

    override fun ficRead(): ByteArray? = if (tuned != 0 && tuned !in blocked) ensembles[tuned] else null

    override fun startAudio(subchannel: Int, bitrateKbps: Int) { calls += "start $subchannel@$tuned"; started = subchannel to bitrateKbps }
    override fun stopAudio() { calls += "stop"; started = null }

    override fun mscRead(): ByteArray {
        if (failMscRead) { failMscRead = false; throw IOException("bulk IN failed (scripted)") }
        val s = started ?: return ByteArray(0)
        if (tuned == 0 || tuned in blocked) return ByteArray(0)
        val now = System.currentTimeMillis()
        if (now < nextSuperframeAt) return ByteArray(0)
        nextSuperframeAt = now + 120
        return Broadcast.superframe(s.second)
    }

    override val signal: SignalReading?
        get() = if (tuned == 0 || tuned in blocked) SignalReading(0, none = true, weak = true, raw = 2000)
                else SignalReading(4, none = false, weak = false, raw = 0)
}

/** Hands the engine a fresh tuner per connection from [newTuner] (raw dongles only). */
class FakeDrivers(private val newTuner: () -> RawTuner) : Drivers {
    val opened = CopyOnWriteArrayList<RawTuner>()
    override fun rawTuner(kind: DongleKind, t: Transport, log: (String) -> Unit): RawTuner = newTuner().also { opened += it }
    override fun moduleTuner(kind: DongleKind, t: Transport, listener: ModuleListener, log: (String) -> Unit): ModuleTuner? = null
}

/** A dongle that opens (after [failOpens] failures) with a transport nobody uses: the scripted tuner stands in. */
class FakeDongle(override val name: String, @Volatile var failOpens: Int = 0) : Dongle {
    override val kind = DongleKind.C100
    override fun open(): OpenDongle {
        if (failOpens > 0) { failOpens--; throw IllegalStateException("cannot open the USB device (scripted)") }
        return object : OpenDongle {
            override val transport = object : Transport {
                override fun write(endpoint: Int, data: ByteArray, timeoutMs: Int) = data.size
                override fun read(endpoint: Int, buffer: ByteArray, timeoutMs: Int) = 0
                override fun close() {}
            }
            override fun close() {}
        }
    }
}

class FakeAudio : AudioOut {
    @Volatile var aus = 0
    @Volatile var silenceMs = 0
    @Volatile var lastLevel = 1f
    @Volatile var releases = 0
    override fun feedAac(asc: ByteArray, au: ByteArray, outputRate: Int) { aus++ }
    override fun feedMp2(frame: ByteArray) {}
    override fun feedPcm(data: ByteArray, rate: Int, channels: Int) {}
    override fun feedSilence(ms: Int) { silenceMs += ms }
    override fun setLevel(level: Float) { lastLevel = level }
    override fun release() { releases++ }
}

class FakeFocus : Focus {
    @Volatile var onChange: (FocusChange) -> Unit = {}
    override fun request(onChange: (FocusChange) -> Unit): Boolean { this.onChange = onChange; return true }
    override fun abandon() {}
}

class TestLog : EngineLog {
    val lines = CopyOnWriteArrayList<String>()
    override fun i(tag: String, msg: String) { lines += msg }
    override fun w(tag: String, msg: String, t: Throwable?) { lines += "W $msg" }
    override fun e(tag: String, msg: String, t: Throwable?) { lines += "E $msg" }
    override fun logcatOnly(tag: String, msg: String) {}
}
