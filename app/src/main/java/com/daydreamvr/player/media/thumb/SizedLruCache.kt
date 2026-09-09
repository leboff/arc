package com.daydreamvr.player.media.thumb

/**
 * An LRU cache bounded by a caller-supplied **size** (KB), not an entry count
 * (UI_REDESIGN_REVIEWED_PLAN.md §9.2, R9).
 *
 * `LruCache(64)` would let 64 × 320×240 ARGB_8888 thumbnails grow to ~19.6 MB
 * with no ceiling; this caps total bytes and evicts least-recently-*used* first.
 *
 * Pure — no Android types — so eviction is assertable in a plain JUnit test.
 * Synchronised: the thumbnail loader writes from `Dispatchers.IO` while the paint
 * thread reads.
 */
class SizedLruCache<K : Any, V : Any>(
    private val maxSizeKb: Int,
    private val sizeOfKb: (V) -> Int,
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var sizeKb = 0

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V): V? {
        val previous = map.put(key, value)
        if (previous != null) sizeKb -= sizeOfKb(previous).coerceAtLeast(0)
        sizeKb += sizeOfKb(value).coerceAtLeast(0)
        evictToSize(maxSizeKb)
        return previous
    }

    @Synchronized
    fun evictToSize(targetKb: Int) {
        val it = map.entries.iterator()
        while (sizeKb > targetKb && it.hasNext()) {
            val e = it.next()
            sizeKb -= sizeOfKb(e.value).coerceAtLeast(0)
            it.remove()
        }
    }

    @Synchronized
    fun sizeKb(): Int = sizeKb

    @Synchronized
    fun clear() {
        map.clear()
        sizeKb = 0
    }
}
