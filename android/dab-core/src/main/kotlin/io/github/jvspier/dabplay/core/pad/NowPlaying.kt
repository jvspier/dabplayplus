package io.github.jvspier.dabplay.core.pad

/** What the UI shows: artist + title when the DLS is a recognised song, otherwise the raw text. */
data class NowPlaying(val raw: String, val artist: String? = null, val title: String? = null) {
    val isSong get() = artist != null && title != null
    /** Always "Artist – Title" for songs (design decision), else the raw DLS. */
    val display get() = if (isSong) "$artist – $title" else raw
}

enum class DlsFormat { ARTIST_TITLE, TITLE_ARTIST }

/** A per-station DLS rule: format plus optional prefix to strip (e.g. 100% NL's "Onair: "). */
data class DlsRule(val format: DlsFormat, val prefix: String? = null)

/**
 * Turns DLS text into [NowPlaying]. Only stations with a known rule are split; unknown stations and
 * non-song texts stay raw (never guess). DL Plus tags, when a station sends them, take precedence.
 */
object DlsParser {
    private const val E3 = 0xE3   // Netherlands

    /**
     * Rules by ECC + SId. "verified" = format seen in captures (2026-09-24/25); others are inferred from the
     * broadcaster group (same playout system) and should be confirmed.
     */
    val RULES: Map<Pair<Int, Long>, DlsRule> = buildMap {
        fun put(sid: Long, r: DlsRule) = put(E3 to sid, r)
        val at = DlsRule(DlsFormat.ARTIST_TITLE)
        val ta = DlsRule(DlsFormat.TITLE_ARTIST)
        // NPO (12C): verified Radio 2, 3FM
        for (sid in listOf(0x8201L, 0x8202, 0x8203, 0x8204, 0x8205, 0x8209, 0x8212, 0x8215, 0x8216)) put(sid, at)
        // Talpa: verified 538, Sky Radio, Radio 10
        for (sid in listOf(0x83C7L, 0x8802, 0x88C7, 0x83C6, 0x8808, 0x83D2, 0x88D2, 0x8818, 0x83E1, 0x83B2, 0x82A5, 0x88C6)) put(sid, ta)
        // DPG: verified Qmusic
        for (sid in listOf(0x83C8L, 0x8804, 0x8390, 0x8803, 0x8807, 0x8806, 0x83E7)) put(sid, ta)
        // 100% NL: verified, "Onair: Artist - Title"
        put(0x83D3, DlsRule(DlsFormat.ARTIST_TITLE, prefix = "Onair: "))
        // verified: KINK (9C); Omroep Zeeland, RADIONL, Tukker FM, THE GROOVE (5B)
        for (sid in listOf(0x8358L, 0x841B, 0x8421, 0x8457, 0x84AE)) put(sid, at)
    }

    fun parse(text: String, ecc: Int, sid: Long, stationLabel: String? = null,
              dlPlus: Map<String, String>? = null): NowPlaying {
        val raw = text.trim()
        val a = dlPlus?.get("ITEM.ARTIST")?.trim()
        val t = dlPlus?.get("ITEM.TITLE")?.trim()
        if (!a.isNullOrEmpty() && !t.isNullOrEmpty()) return NowPlaying(raw, smartCase(a), smartCase(t))

        val rule = RULES[ecc to sid] ?: return NowPlaying(raw)
        var s = raw
        rule.prefix?.let { p -> if (s.startsWith(p, ignoreCase = true)) s = s.substring(p.length).trim() }
        if (stationLabel != null && s.contains(stationLabel.trim(), ignoreCase = true)) return NowPlaying(raw)
        val parts = s.split(" - ")
        if (parts.size != 2 || parts.any { it.isBlank() }) return NowPlaying(raw)
        val (first, second) = parts.map { smartCase(it.trim()) }
        return when (rule.format) {
            DlsFormat.ARTIST_TITLE -> NowPlaying(raw, artist = first, title = second)
            DlsFormat.TITLE_ARTIST -> NowPlaying(raw, artist = second, title = first)
        }
    }

    /** ALL-CAPS → Title Case, but only if a word has ≥ 5 letters (keeps ABBA, KISS, AC/DC, U2, R.E.M.). */
    fun smartCase(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty() || letters.any { it.isLowerCase() }) return s
        val longWord = Regex("\\p{L}{5,}").containsMatchIn(s)
        if (!longWord) return s
        return s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
    }
}
