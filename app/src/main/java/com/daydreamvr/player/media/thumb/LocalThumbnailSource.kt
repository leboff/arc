package com.daydreamvr.player.media.thumb

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import com.daydreamvr.player.media.MediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hardware-accelerated local thumbnails via `ContentResolver.loadThumbnail`
 * (API 29+, minSdk 29 — no version gate). It throws `IOException` on corrupt or
 * unscanned media and can block for hundreds of ms, so it is `Dispatchers.IO`
 * and `runCatching` (UI_REDESIGN_REVIEWED_PLAN.md §9.3).
 *
 * `Size(384, 216)` is 16:9 to match the card poster — no re-scale on draw.
 */
class LocalThumbnailSource(private val resolver: ContentResolver) : ThumbnailSource {
    override suspend fun load(key: String, ref: MediaRef): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { resolver.loadThumbnail(Uri.parse(ref.value), Size(384, 216), null) }
            .getOrNull()
            ?.let { if (it.config == Bitmap.Config.RGB_565) it else it.copy(Bitmap.Config.RGB_565, false) }
    }
}
