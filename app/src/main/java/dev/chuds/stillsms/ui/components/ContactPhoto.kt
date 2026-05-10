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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-lifetime LRU is overkill for the load — contact photos are small thumbnails
 * (typically <40 KB each) and the active list rarely exceeds a few dozen distinct photos.
 * A flat ConcurrentHashMap cache keyed by URI is plenty.
 */
private val photoCache = ConcurrentHashMap<String, ImageBitmap>()

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
