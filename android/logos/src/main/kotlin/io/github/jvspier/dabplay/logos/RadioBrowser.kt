package io.github.jvspier.dabplay.logos

import java.net.URLEncoder
import org.json.JSONArray

data class RbStation(val name: String, val homepage: String?, val favicon: String?, val votes: Int)

/**
 * radio-browser.info (community station database, public API). Used only to find a station's homepage and
 * favicon by name when RadioDNS has nothing. Mirrors come from `all.api.radio-browser.info/json/servers`.
 */
class RadioBrowser(private val http: Http, private val fallbackHosts: List<String> = listOf("de1.api.radio-browser.info")) {
    @Volatile private var hosts: List<String>? = null

    private fun hosts(): List<String> = hosts ?: run {
        val found = http.get("https://all.api.radio-browser.info/json/servers", 64 * 1024)
            ?.takeIf { it.code == 200 }
            ?.let { r -> runCatching { JSONArray(String(r.body)).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } } }.getOrNull() }
            ?.distinct()?.shuffled()?.ifEmpty { null }
        (found ?: fallbackHosts).also { hosts = it }
    }

    /**
     * Stations whose name matches [label] after normalising (case, spaces, punctuation), or failing that without
     * generic words like "radio"; exact matches first, then most voted.
     * [country] (ISO 3166 alpha-2) narrows the search when known. [via] carries the search request (the logo finder
     * passes its own, to see whether it was answered).
     */
    fun find(label: String, country: String?, via: Http = http): List<RbStation> {
        val want = normalise(label).ifEmpty { return emptyList() }
        val wantLoose = loose(label)
        val q = buildString {
            append("/json/stations/search?name=").append(URLEncoder.encode(label.trim(), "UTF-8"))
            if (country != null) append("&countrycode=").append(country)
            append("&hidebroken=true&order=votes&reverse=true&limit=30")
        }
        for (h in hosts()) {
            val r = via.get("https://$h$q", 512 * 1024) ?: continue
            if (r.code != 200) continue
            val arr = runCatching { JSONArray(String(r.body)) }.getOrNull() ?: continue
            return (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { RbStation(it.optString("name"), it.optString("homepage").ifBlank { null }, it.optString("favicon").ifBlank { null }, it.optInt("votes")) }
                .filter { normalise(it.name) == want || (wantLoose.isNotEmpty() && loose(it.name) == wantLoose) }
                .sortedWith(compareBy({ normalise(it.name) != want }, { -it.votes }))    // exact name first
        }
        return emptyList()
    }

    companion object {
        /** "100% NL" and "100%NL" → "100nl"; "Qmusic " → "qmusic". Diacritics kept (they're letters). */
        fun normalise(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** Like [normalise], minus generic words: "Omroep Zeeland Radio" and "Radio 538" match the DAB labels. */
        fun loose(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in GENERIC }.joinToString("")

        private val GENERIC = setOf("radio", "fm", "dab", "live", "online", "stream", "hd", "the")
    }
}
