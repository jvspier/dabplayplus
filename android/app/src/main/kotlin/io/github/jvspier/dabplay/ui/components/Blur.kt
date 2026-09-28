package io.github.jvspier.dabplay.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A strong blur that works on API 26+ (Compose's Modifier.blur needs RenderEffect, API 31: a no-op on the Dudu3's
 * Android 10). Shrinks to 1/[factor], box-blurs the tiny copy, and lets bilinear upscaling (FilterQuality.High) do
 * the rest: for a 320×240 slide that's a 40×30 bitmap, so it's instant. Computed once per image.
 */
fun blurredCopy(src: Bitmap, factor: Int = 8, passes: Int = 2): Bitmap {
    val w = maxOf(4, src.width / factor); val h = maxOf(4, src.height / factor)
    val small = Bitmap.createScaledBitmap(src.copy(Bitmap.Config.ARGB_8888, false), w, h, true)
    val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    repeat(passes) { boxBlur(px, tmp, w, h, horizontal = true); boxBlur(tmp, px, w, h, horizontal = false) }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

/** One 3-tap box blur pass along rows or columns (edges clamped), per ARGB channel. */
private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
    for (y in 0 until h) for (x in 0 until w) {
        var a = 0; var r = 0; var g = 0; var b = 0
        for (d in -1..1) {
            val xx = if (horizontal) (x + d).coerceIn(0, w - 1) else x
            val yy = if (horizontal) y else (y + d).coerceIn(0, h - 1)
            val p = src[yy * w + xx]
            a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        dst[y * w + x] = ((a / 3) shl 24) or ((r / 3) shl 16) or ((g / 3) shl 8) or (b / 3)
    }
}

/** [img] blurred, computed off the main thread; null until ready (or when [img] is null). */
@Composable
fun rememberBlurred(img: ImageBitmap?): ImageBitmap? {
    // state per image: a new image starts from null, never from the previous image's blur
    val out = remember(img) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(img) {
        out.value = img?.let { i -> withContext(Dispatchers.Default) { runCatching { blurredCopy(i.asAndroidBitmap()).asImageBitmap() }.getOrNull() } }
    }
    return out.value
}

/** A broadcast slide (JPEG/PNG bytes), decoded off the main thread; null until ready. */
@Composable
fun rememberSlide(slide: ByteArray?): ImageBitmap? {
    val out = remember(slide) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(slide) { out.value = slide?.let { b -> withContext(Dispatchers.Default) { decodeSlide(b) } } }
    return out.value
}

/**
 * Decodes a slide at most [MAX_SLIDE_SIDE] px on its longest side (the engine already rejects bigger ones; this keeps
 * the UI safe on its own): DAB slides are 320×240, a stage is at most 640×480 dp.
 */
private fun decodeSlide(b: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SLIDE_SIDE) sample *= 2
    BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

private const val MAX_SLIDE_SIDE = 1280
