package io.github.jvspier.dabplay.logos

import java.net.URI
import org.json.JSONObject

/** Icons a station's website declares: the web app manifest, apple-touch-icons and plain icons. */
data class PageIcons(val manifest: String?, val touchIcons: List<LogoRef>, val icons: List<LogoRef>)

object WebIcons {
    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private fun attr(tag: String, name: String) =
        Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)
            ?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] } }

    /** `<link>` tags from the page's HTML; hrefs resolved against [base] (the page's final URL after redirects). */
    fun page(html: String, base: String): PageIcons {
        var manifest: String? = null
        val touch = mutableListOf<LogoRef>(); val icons = mutableListOf<LogoRef>()
        for (m in LINK.findAll(html.take(512 * 1024))) {
            val tag = m.value
            val rel = attr(tag, "rel")?.lowercase()?.split(Regex("\\s+")) ?: continue
            val href = attr(tag, "href")?.let { resolve(base, decode(it)) } ?: continue
            val (w, h) = size(attr(tag, "sizes"))
            when {
                "manifest" in rel -> if (manifest == null) manifest = href
                rel.any { it.startsWith("apple-touch-icon") } -> touch += LogoRef(href, w ?: 180, h ?: 180)
                "icon" in rel -> icons += LogoRef(href, w ?: 0, h ?: 0)
            }
        }
        return PageIcons(manifest, touch, icons)
    }

    /** Icons from a web app manifest (JSON), resolved against the manifest's own URL. Maskable-only icons last. */
    fun manifestIcons(json: String, base: String): List<LogoRef> = runCatching {
        val arr = JSONObject(json).optJSONArray("icons") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val src = o.optString("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = o.optString("type")
            if (type.isNotEmpty() && type !in setOf("image/png", "image/jpeg", "image/webp")) return@mapNotNull null
            // sizes can list several ("192x192 512x512"): take the largest
            val sizes = o.optString("sizes").split(Regex("\\s+")).map { size(it) }.filter { it.first != null }
            val (w, h) = sizes.maxByOrNull { it.first!! } ?: (null to null)
            val maskableOnly = o.optString("purpose").split(" ").let { "maskable" in it && "any" !in it }
            Triple(LogoRef(resolve(base, src) ?: return@mapNotNull null, w ?: 0, h ?: 0), maskableOnly, i)
        }.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
    }.getOrDefault(emptyList())

    /** "192x192" → (192, 192); "any" or garbage → nulls. */
    private fun size(s: String?): Pair<Int?, Int?> {
        val m = s?.let { Regex("(\\d+)[xX](\\d+)").find(it) } ?: return null to null
        return m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull()     // "99999999999x1": no crash
    }

    private fun decode(s: String) = s.replace("&amp;", "&").trim()

    fun resolve(base: String, href: String): String? = runCatching {
        val u = URI(base).resolve(URI(href.replace(" ", "%20")))
        u.toString().takeIf { u.scheme == "http" || u.scheme == "https" }
    }.getOrNull()
}
