package com.daydreamvr.player.media.thumb

import android.graphics.Bitmap
import com.daydreamvr.player.media.MediaRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Produces a decoded thumbnail for a cache key, or null on a miss. */
interface ThumbnailSource {
    suspend fun load(key: String, ref: MediaRef): Bitmap?
}

/**
 * Non-blocking thumbnail lookup for the paint thread, backed by a byte-bounded
 * [SizedLruCache], a fan-out over async [ThumbnailSource]s, and a repaint
 * coalescer (UI_REDESIGN_REVIEWED_PLAN.md §9).
 */
class ThumbnailCache(
    private val cache: SizedLruCache<String, Bitmap>,
    sources: List<ThumbnailSource>,
    scope: CoroutineScope,
    private val coalescer: RepaintCoalescer = RepaintCoalescer(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val loader = AsyncKeyedLoader(
        cache = cache,
        scope = scope,
        dispatcher = ioDispatcher,
        onLoaded = { coalescer.signal() },
        load = { key, ref -> sources.firstNotNullOfOrNull { runCatching { it.load(key, ref) }.getOrNull() } },
    )

    /** Paint-thread safe, non-blocking, non-allocating. Null → draw the placeholder. */
    fun peek(key: String?): Bitmap? = loader.peek(key)

    /** Idempotent; de-duplicates concurrent requests for the same key. */
    fun request(key: String, ref: MediaRef) = loader.request(key, ref)

    /** Per-frame, on the GL thread: true when a repaint is due (§9.6). */
    fun pollRepaint(nowMs: Long): Boolean = coalescer.poll(nowMs)

    fun trim(level: Int) {
        if (level >= TRIM_CLEAR) cache.clear() else cache.evictToSize(cache.sizeKb() / 2)
    }

    private companion object {
        const val TRIM_CLEAR = 40 // ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
    }
}
