package io.github.jvspier.dabplay.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Downloaded station logos, one file per ECC+SId in [dir], kept permanently (a station's logo rarely changes).
 * Misses (no usable logo anywhere) are remembered so a station isn't looked up on every start; they're retried
 * after [RETRY_MISS_MS], or right away after [retryMissing]. [refreshAll] makes every station due again, also across
 * restarts, until each one has been looked up. Thread-safe.
 */
class LogoStore(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    data class Entry(val file: File, val source: String, val url: String, val fetched: Long)

    private val index = File(dir, "index.json")
    private val entries = MutableStateFlow<Map<ServiceKey, Entry>>(emptyMap())
    private val misses = HashMap<ServiceKey, Long>()           // key → when it may be tried again
    private val _missed = MutableStateFlow<Set<ServiceKey>>(emptySet())
    private var refreshBefore = 0L                            // "Refresh all": logos fetched before this are due again

    /** What the UI draws: key → logo file. */
    val state: StateFlow<Map<ServiceKey, Entry>> = entries

    /** Stations looked up without finding a usable logo anywhere (Settings counts them). */
    val missed: StateFlow<Set<ServiceKey>> = _missed

    init { dir.mkdirs(); load() }

    @Synchronized fun needsLookup(key: ServiceKey): Boolean {
        val have = entries.value[key] ?: return (misses[key] ?: 0L) <= now()
        return have.fetched < refreshBefore
    }

    @Synchronized fun save(key: ServiceKey, bytes: ByteArray, source: String, url: String) {
        val f = File(dir, "$key.img")
        val tmp = File(dir, "$key.img.tmp")
        write(tmp, bytes); if (!tmp.renameTo(f)) throw java.io.IOException("cannot store $f")
        misses.remove(key); _missed.value = misses.keys.toSet()
        entries.update { it + (key to Entry(f, source, url, now())) }
        persist()
    }

    /** Nothing usable found: tried again after [RETRY_MISS_MS]. In a refresh, a station with a logo keeps it. */
    @Synchronized fun miss(key: ServiceKey) {
        val have = entries.value[key]
        if (have != null) entries.update { it + (key to have.copy(fetched = now())) }
        else { misses[key] = now() + RETRY_MISS_MS; _missed.value = misses.keys.toSet() }
        persist()
    }

    /** Settings → "Retry missing": the stations without a logo are due again now. */
    @Synchronized fun retryMissing() { misses.clear(); _missed.value = emptySet(); persist() }

    /** Settings → "Refresh all": every station is due again; a logo stays until a new one replaces it. */
    @Synchronized fun refreshAll() { refreshBefore = now(); retryMissing() }

    private fun load() = runCatching {
        if (!index.exists()) return@runCatching
        val o = JSONObject(index.readText())
        val e = o.optJSONObject("logos")
        val found = HashMap<ServiceKey, Entry>()
        e?.keys()?.forEach { k ->
            val v = e.getJSONObject(k); val f = File(dir, v.getString("file"))
            if (f.exists()) found[key(k)] = Entry(f, v.optString("source"), v.optString("url"), v.optLong("fetched"))
        }
        entries.value = found
        o.optJSONObject("misses")?.let { m -> m.keys().forEach { k -> misses[key(k)] = m.getLong(k) } }
        _missed.value = misses.keys.toSet()
        refreshBefore = o.optLong("refreshBefore")
    }

    private fun persist() {
        val o = JSONObject()
        o.put("logos", JSONObject().also { j -> entries.value.forEach { (k, v) ->
            j.put(k.toString(), JSONObject().put("file", v.file.name).put("source", v.source).put("url", v.url).put("fetched", v.fetched)) } })
        o.put("misses", JSONObject().also { j -> misses.forEach { (k, t) -> j.put(k.toString(), t) } })
        o.put("refreshBefore", refreshBefore)
        val tmp = File(dir, "index.json.tmp")
        write(tmp, o.toString(1).toByteArray())
        if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
    }

    /** Whole or nothing: synced before the rename, so a power cut leaves the old file, not an empty one. */
    private fun write(f: File, bytes: ByteArray) = java.io.FileOutputStream(f).use { it.write(bytes); it.fd.sync() }

    private fun key(s: String) = s.split(".").let { ServiceKey(it[0].toInt(16), it[1].toLong(16)) }

    companion object { const val RETRY_MISS_MS = 3L * 24 * 3600 * 1000 }
}
