package io.github.jvspier.dabplay.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.jvspier.dabplay.data.LogoStore
import io.github.jvspier.dabplay.data.ServiceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Downloaded logos by station (from LogoStore). Dynamic local: only the logos that change recompose. */
val LocalLogos = compositionLocalOf<Map<ServiceKey, LogoStore.Entry>> { emptyMap() }

/**
 * A decoded logo. [fullBleed]: opaque corners, so the logo is its own tile (538, KINK) and is drawn edge to edge;
 * otherwise it has a transparent background made for light pages (NPO, Qmusic) and sits on a light tile.
 */
class LogoImage(val bitmap: ImageBitmap, val fullBleed: Boolean)

/** Decoded logos in memory, capped by bytes; decoded at most [MAX_PX] so 55 logos stay around 15 MB. */
private object LogoCache {
    const val MAX_PX = 320
    private val lru = object : LruCache<String, LogoImage>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: LogoImage) = value.bitmap.width * value.bitmap.height * 4
    }

    fun get(key: String): LogoImage? = lru.get(key)

    fun load(key: String, e: LogoStore.Entry): LogoImage? = lru.get(key) ?: runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(e.file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        var bmp = BitmapFactory.decodeFile(e.file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > MAX_PX) bmp = Bitmap.createScaledBitmap(bmp, bmp.width * MAX_PX / longest, bmp.height * MAX_PX / longest, true)
        val i = 2.coerceAtMost(minOf(bmp.width, bmp.height) / 4)
        val corners = listOf(bmp.getPixel(i, i), bmp.getPixel(bmp.width - 1 - i, i), bmp.getPixel(i, bmp.height - 1 - i), bmp.getPixel(bmp.width - 1 - i, bmp.height - 1 - i))
        val opaque = corners.all { (it ushr 24) >= 250 } && bmp.width == bmp.height
        LogoImage(bmp.asImageBitmap(), opaque).also { lru.put(key, it) }
    }.getOrNull()
}

/** The station's downloaded logo, or null (not downloaded yet, or still decoding off the main thread). */
@Composable
fun rememberLogo(key: ServiceKey?): LogoImage? {
    val entry = key?.let { LocalLogos.current[it] } ?: return null
    val cacheKey = "${entry.file.path}@${entry.fetched}"
    val img by produceState(LogoCache.get(cacheKey), cacheKey) {
        if (value == null) value = withContext(Dispatchers.IO) { LogoCache.load(cacheKey, entry) }
    }
    return img
}
