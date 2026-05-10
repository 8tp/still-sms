package dev.chuds.stillsms.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-lifetime LRU is overkill for the load — contact photos are small thumbnails
 * (typically <40 KB each) and the active list rarely exceeds a few dozen distinct photos.
 * A flat ConcurrentHashMap cache keyed by URI is plenty.
 */
private val photoCache = ConcurrentHashMap<String, ImageBitmap>()

/**
 * Separate cache for MMS bubble images. Kept distinct from [photoCache] because MMS
 * payloads are megabytes — pinning them in the same map as 40 KB contact thumbnails
 * would surprise everything else asking for a photo. We bound entries here too: the
 * sample-down step in [loadMmsBitmap] keeps each cache value to ≲600 KB.
 */
private val mmsImageCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun rememberContactPhoto(uri: String?): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val state by produceState<ImageBitmap?>(initialValue = uri?.let { photoCache[it] }, key1 = uri) {
        if (uri == null) {
            value = null
            return@produceState
        }
        photoCache[uri]?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) {
            loadBitmap(context, uri)?.asImageBitmap()?.also { photoCache[uri] = it }
        }
    }
    return state
}

/**
 * Decode an MMS image part into a bubble-sized bitmap. [maxWidthPx] is the target render
 * width in pixels (typically the bubble's `widthIn(max=…)` converted to px). The decoder
 * picks an `inSampleSize` so the loaded bitmap is at most ~2× the target — sharp enough
 * for the bubble, small enough that a long thread doesn't OOM.
 */
@Composable
fun rememberMmsImage(uri: String?, maxWidthPx: Int): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val state by produceState<ImageBitmap?>(initialValue = uri?.let { mmsImageCache[it] }, key1 = uri, key2 = maxWidthPx) {
        if (uri == null) {
            value = null
            return@produceState
        }
        mmsImageCache[uri]?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) {
            loadMmsBitmap(context, uri, maxWidthPx)?.asImageBitmap()?.also { mmsImageCache[uri] = it }
        }
    }
    return state
}

private fun loadBitmap(context: Context, uri: String): Bitmap? = runCatching {
    context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
        // Bound the decode size — contact photos top out around 256 px in the provider,
        // but downstream display sizes are 36–48 dp. inSampleSize=1 keeps rendering cheap.
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeStream(stream, null, opts)
    }
}.getOrNull()

private fun loadMmsBitmap(context: Context, uri: String, maxWidthPx: Int): Bitmap? = runCatching {
    val parsed = Uri.parse(uri)
    // Pass 1: bounds-only decode so we can compute inSampleSize without holding the bytes.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    val srcWidth = bounds.outWidth
    if (srcWidth <= 0) return@runCatching null
    // Pick the largest power-of-two sample step that still keeps the image at least
    // 1× the target width — otherwise the bubble looks soft when the user zooms in
    // on the system display scale.
    var sample = 1
    while (srcWidth / (sample * 2) >= max(maxWidthPx, 1)) sample *= 2

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.contentResolver.openInputStream(parsed)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, opts)
    }
}.getOrNull()
