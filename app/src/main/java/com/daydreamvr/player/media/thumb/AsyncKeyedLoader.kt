package com.daydreamvr.player.media.thumb

import com.daydreamvr.player.media.MediaRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keyed async cache: `peek` is non-blocking for the paint thread; `request`
 * launches at most one load per key and de-duplicates concurrent callers
 * (UI_REDESIGN_REVIEWED_PLAN.md §9.5).
 *
 * Value-type-agnostic so it is testable with a plain `String` stand-in for
 * `Bitmap` under `unitTests.isReturnDefaultValues = true`.
 */
class AsyncKeyedLoader<V : Any>(
    private val cache: SizedLruCache<String, V>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val onLoaded: () -> Unit,
    private val load: suspend (key: String, ref: MediaRef) -> V?,
) {
    private val inFlight = ConcurrentHashMap<String, Job>()

    fun peek(key: String?): V? = key?.let { cache.get(it) }

    val inFlightCount: Int get() = inFlight.size

    fun request(key: String, ref: MediaRef) {
        if (cache.get(key) != null) return
        inFlight.computeIfAbsent(key) {
            scope.launch(dispatcher) {
                try {
                    val v = runCatching { load(key, ref) }.getOrNull()
                    if (v != null) {
                        cache.put(key, v)
                        onLoaded()
                    }
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }
}
