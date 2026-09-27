package io.github.jvspier.dabplay.core

/** DAB band III channel raster (ETSI EN 300 401), kHz. */
object Band3 {
    val CHANNELS: List<Pair<String, Int>> = listOf(
        "5A" to 174928, "5B" to 176640, "5C" to 178352, "5D" to 180064,
        "6A" to 181936, "6B" to 183648, "6C" to 185360, "6D" to 187072,
        "7A" to 188928, "7B" to 190640, "7C" to 192352, "7D" to 194064,
        "8A" to 195936, "8B" to 197648, "8C" to 199360, "8D" to 201072,
        "9A" to 202928, "9B" to 204640, "9C" to 206352, "9D" to 208064,
        "10A" to 209936, "10N" to 210096, "10B" to 211648, "10C" to 213360, "10D" to 215072,
        "11A" to 216928, "11N" to 217088, "11B" to 218640, "11C" to 220352, "11D" to 222064,
        "12A" to 223936, "12N" to 224096, "12B" to 225648, "12C" to 227360, "12D" to 229072,
        "13A" to 230784, "13B" to 232496, "13C" to 234208, "13D" to 235776, "13E" to 237488, "13F" to 239200,
    )
    fun name(khz: Int) = CHANNELS.firstOrNull { it.second == khz }?.first
}
