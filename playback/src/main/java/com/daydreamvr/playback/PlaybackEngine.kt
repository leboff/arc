package com.daydreamvr.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * The two playback engines Arc can route a single item to
 * (docs/FORMAT_SUPPORT_PLAN.md §5, §6).
 *
 *  - [MEDIA3] — the primary engine: hardware decode, vsync-aligned frame release,
 *    low thermal overhead. Handles ≥95 % of playbacks.
 *  - [VLC] — the compatibility fallback: VLC's own demuxer set plus software audio
 *    decode for the long tail Media3 cannot open (WMV/ASF, RealMedia, VC-1,
 *    MPEG-2, truncated/broken containers).
 */
enum class PlaybackEngine { MEDIA3, VLC }

/**
 * Remembers which engine last played an item so the second play of "the file that
 * failed yesterday" goes straight to the engine that worked, with no wasted open
 * on the other (docs/FORMAT_SUPPORT_PLAN.md §6.2 rule 1). Keyed by
 * `PlayRequest.itemKey`.
 */
interface PlaybackEngineStore {
    fun preferred(itemKey: String): PlaybackEngine?
    fun remember(itemKey: String, engine: PlaybackEngine)
}

/** Process-lifetime [PlaybackEngineStore]. The persistent app-layer store wraps one of these. */
class InMemoryPlaybackEngineStore : PlaybackEngineStore {

    private val byItem = ConcurrentHashMap<String, PlaybackEngine>()

    override fun preferred(itemKey: String): PlaybackEngine? = byItem[itemKey]

    override fun remember(itemKey: String, engine: PlaybackEngine) {
        byItem[itemKey] = engine
    }
}
