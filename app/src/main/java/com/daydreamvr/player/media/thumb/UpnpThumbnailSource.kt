package com.daydreamvr.player.media.thumb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.daydreamvr.player.media.MediaRef
import com.daydreamvr.upnp.net.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Fetches `albumArtUri` / `JPEG_TN` art over HTTP for UPnP items
 * (UI_REDESIGN_REVIEWED_PLAN.md §9.4). Response capped at 2 MB; decoded straight
 * to `RGB_565` with an `inSampleSize` for a ~384 px target.
 *
 * Reuses the app's [HttpTransport] — the same stack UPnP browse already goes
 * through — rather than constructing a fresh unbound `OkHttpClient` (§8.6 note).
 */
class UpnpThumbnailSource(private val http: HttpTransport) : ThumbnailSource {

    override suspend fun load(key: String, ref: MediaRef): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = http.get(URI(ref.value), maxBytes = MAX_BYTES).body
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = sampleSizeFor(bounds.outWidth, TARGET_PX)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    private fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var s = 1
        while (sourceWidth / (s * 2) >= targetWidth) s *= 2
        return s
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        const val TARGET_PX = 384
    }
}
