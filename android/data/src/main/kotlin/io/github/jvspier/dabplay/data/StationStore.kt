package io.github.jvspier.dabplay.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** A service as found on one ensemble. */
data class StoredService(val key: ServiceKey, val label: String, val subchannel: Int, val bitrateKbps: Int, val ascty: Int)

data class StoredEnsemble(
    val eid: Int, val channel: String, val freqKhz: Int, val label: String,
    val lastSeen: Long, val missedScans: Int, val services: List<StoredService>,
)

data class Recent(val key: ServiceKey, val time: Long)

/** The same service announced on another ensemble (FIG 0/24 + 0/21), learned while listening; for service following. */
data class AltFreq(val eid: Int, val khz: Int)

/** A station as the UI sees it: one service, on the ensemble where it was seen most recently. */
data class Station(val service: StoredService, val ensemble: StoredEnsemble) {
    val key get() = service.key
    val label get() = service.label
}

data class Snapshot(
    val ensembles: List<StoredEnsemble> = emptyList(),
    val favourites: List<ServiceKey> = emptyList(),
    val recents: List<Recent> = emptyList(),
    val lastPlayed: ServiceKey? = null,
    val alternatives: Map<ServiceKey, List<AltFreq>> = emptyMap(),
) {
    val stations: List<Station> by lazy {
        ensembles.sortedByDescending { it.lastSeen }
            .flatMap { e -> e.services.map { Station(it, e) } }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
    }
    fun station(key: ServiceKey) = stations.firstOrNull { it.key == key }
    val favouriteStations get() = favourites.mapNotNull(::station)
}

/**
 * Local station database (offline first): scan results per ensemble, favourites (ordered), recents, last played.
 * Stored as one JSON file; small enough that rewriting it on every change is fine.
 */
class StationStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    // one writer thread: callers (the USB thread among them) never wait for the disk, writes never overlap, and a
    // burst of changes becomes one write of the latest state
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "station-store").apply { isDaemon = true } }
    private val dirty = AtomicBoolean(false)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Snapshot> = _state

    /**
     * Merge a band scan: found ensembles replace their old entry; ensembles on scanned channels that weren't found
     * are kept until they've been missed [DROP_AFTER_MISSES] times (marginal multiplexes come and go).
     */
    fun applyScan(found: List<StoredEnsemble>, scannedChannels: Set<String>) = change { s ->
        val foundEids = found.map { it.eid }.toSet()
        val kept = s.ensembles.filter { it.eid !in foundEids }.mapNotNull { e ->
            if (e.channel !in scannedChannels) e
            else e.copy(missedScans = e.missedScans + 1).takeIf { it.missedScans < DROP_AFTER_MISSES }
        }
        s.copy(ensembles = found.map { it.copy(lastSeen = now(), missedScans = 0) } + kept)
    }

    fun toggleFavourite(key: ServiceKey) = change { s ->
        s.copy(favourites = if (key in s.favourites) s.favourites - key else (s.favourites + key).take(MAX_FAVOURITES))
    }

    fun moveFavourite(from: Int, to: Int) = change { s ->
        val l = s.favourites.toMutableList(); if (from in l.indices && to in l.indices) l.add(to, l.removeAt(from)); s.copy(favourites = l)
    }

    fun played(key: ServiceKey) = change { s ->
        s.copy(lastPlayed = key, recents = (listOf(Recent(key, now())) + s.recents.filter { it.key != key }).take(MAX_RECENTS))
    }

    /** Merge learned alternatives (key → other ensembles); only writes when something changed. */
    fun learnAlternatives(found: Map<ServiceKey, List<AltFreq>>) {
        val cur = _state.value.alternatives
        if (found.all { (k, v) -> cur[k] == v }) return
        change { it.copy(alternatives = it.alternatives + found) }
    }

    fun clearRecents(): List<Recent> = synchronized(lock) { _state.value.recents.also { change { s -> s.copy(recents = emptyList()) } } }
    fun restoreRecents(r: List<Recent>) = change { it.copy(recents = r) }

    private fun change(f: (Snapshot) -> Snapshot) {
        synchronized(lock) { _state.update(f) }
        if (dirty.compareAndSet(false, true)) writer.execute {
            dirty.set(false)
            try { save(_state.value) } catch (e: Exception) { onError(e) }
        }
    }

    /** Waits for pending writes (tests; a clean shutdown). */
    fun flush() { writer.submit {}.get() }

    /**
     * The stored stations. A file that won't parse is kept as `.bad` (never silently replaced by an empty list on the
     * next save); a complete `.tmp` from a write that missed its rename is used instead if there is one.
     */
    private fun load(): Snapshot {
        parse(file)?.let { return it }
        if (file.exists()) file.renameTo(File(file.path + ".bad"))
        return parse(File(file.path + ".tmp")) ?: Snapshot()
    }

    private fun parse(f: File): Snapshot? = if (!f.exists()) null else runCatching {
        val o = JSONObject(f.readText())
        Snapshot(
            ensembles = o.optJSONArray("ensembles").objs().map { e ->
                StoredEnsemble(e.getInt("eid"), e.getString("channel"), e.getInt("freqKhz"), e.getString("label"),
                    e.getLong("lastSeen"), e.optInt("missedScans"), e.getJSONArray("services").objs().map { sv ->
                        StoredService(key(sv.getString("key")), sv.getString("label"), sv.getInt("subch"), sv.getInt("bitrate"), sv.getInt("ascty"))
                    })
            },
            favourites = o.optJSONArray("favourites").strings().map(::key),
            recents = o.optJSONArray("recents").objs().map { Recent(key(it.getString("key")), it.getLong("time")) },
            lastPlayed = o.optString("lastPlayed").takeIf { it.isNotEmpty() }?.let(::key),
            alternatives = o.optJSONObject("alternatives")?.let { a -> a.keys().asSequence().associate { k ->
                key(k) to a.getJSONArray(k).objs().map { AltFreq(it.getInt("eid"), it.getInt("khz")) } } }.orEmpty(),
        )
    }.getOrNull()

    private fun save(s: Snapshot) {
        val o = JSONObject()
        o.put("ensembles", JSONArray(s.ensembles.map { e ->
            JSONObject().put("eid", e.eid).put("channel", e.channel).put("freqKhz", e.freqKhz).put("label", e.label)
                .put("lastSeen", e.lastSeen).put("missedScans", e.missedScans)
                .put("services", JSONArray(e.services.map { sv ->
                    JSONObject().put("key", sv.key.toString()).put("label", sv.label).put("subch", sv.subchannel)
                        .put("bitrate", sv.bitrateKbps).put("ascty", sv.ascty) }))
        }))
        o.put("favourites", JSONArray(s.favourites.map { it.toString() }))
        o.put("recents", JSONArray(s.recents.map { JSONObject().put("key", it.key.toString()).put("time", it.time) }))
        s.lastPlayed?.let { o.put("lastPlayed", it.toString()) }
        o.put("alternatives", JSONObject().also { j -> s.alternatives.forEach { (k, v) ->
            j.put(k.toString(), JSONArray(v.map { JSONObject().put("eid", it.eid).put("khz", it.khz) })) } })
        // whole or nothing: write, sync to the disk, then rename over the old file (power can go at any moment in a car)
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out -> out.write(o.toString(1).toByteArray()); out.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IOException("cannot replace $file") }
    }

    private fun JSONArray?.objs() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    private fun key(s: String) = s.split(".").let { ServiceKey(it[0].toInt(16), it[1].toLong(16)) }

    companion object {
        const val MAX_FAVOURITES = 24
        const val MAX_RECENTS = 30
        const val DROP_AFTER_MISSES = 3
    }
}
