package com.daydreamvr.playback

/**
 * One remembered playback position (ARCHITECTURE.md §10.4, §13).
 *
 * @param itemKey `"${serverUdn}|${objectId}"`.
 * @param finishedAtMs wall-clock time the item crossed [FINISHED_FRACTION], or
 *                     null while it is still "continue watching".
 */
data class ResumeEntry(
    val itemKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val finishedAtMs: Long?,
) {
    val isFinished: Boolean get() = finishedAtMs != null

    val fractionWatched: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Stores and retrieves resume positions, keyed by item. LRU-capped so a large
 * library does not grow the blob without bound.
 */
interface ResumeStore {

    fun get(itemKey: String): ResumeEntry?

    /** Records [positionMs]; marks the item finished when past [FINISHED_FRACTION]. Returns the stored entry. */
    fun put(itemKey: String, positionMs: Long, durationMs: Long, nowMs: Long): ResumeEntry

    fun remove(itemKey: String)

    /** All entries, most-recently-touched last. */
    fun all(): List<ResumeEntry>

    fun clear()

    companion object {
        const val FINISHED_FRACTION = 0.95f
        const val DEFAULT_MAX_ENTRIES = 500
    }
}

/**
 * In-memory [ResumeStore] with access-order LRU eviction. The persistent
 * app-layer store (DataStore JSON blob, ARCHITECTURE.md §13) wraps one of these
 * and rehydrates it via [restore] on startup.
 */
class InMemoryResumeStore(
    private val maxEntries: Int = ResumeStore.DEFAULT_MAX_ENTRIES,
) : ResumeStore {

    private val entries = LinkedHashMap<String, ResumeEntry>()

    @Synchronized
    override fun get(itemKey: String): ResumeEntry? {
        val existing = entries.remove(itemKey) ?: return null
        entries[itemKey] = existing // touch: move to most-recent
        return existing
    }

    @Synchronized
    override fun put(itemKey: String, positionMs: Long, durationMs: Long, nowMs: Long): ResumeEntry {
        val pos = positionMs.coerceAtLeast(0L)
        val fraction = if (durationMs > 0L) pos.toFloat() / durationMs else 0f
        val finishedAt = if (fraction >= ResumeStore.FINISHED_FRACTION) nowMs else null

        val entry = ResumeEntry(itemKey, pos, durationMs.coerceAtLeast(0L), finishedAt)
        entries.remove(itemKey)
        entries[itemKey] = entry
        evict()
        return entry
    }

    @Synchronized
    override fun remove(itemKey: String) {
        entries.remove(itemKey)
    }

    @Synchronized
    override fun all(): List<ResumeEntry> = entries.values.toList()

    @Synchronized
    override fun clear() = entries.clear()

    /** Replaces all contents with [snapshot], oldest first, then evicts to the cap. */
    @Synchronized
    fun restore(snapshot: List<ResumeEntry>) {
        entries.clear()
        snapshot.forEach { entries[it.itemKey] = it }
        evict()
    }

    private fun evict() {
        while (entries.size > maxEntries) {
            val eldest = entries.keys.iterator().next()
            entries.remove(eldest)
        }
    }
}
