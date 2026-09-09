package com.daydreamvr.playback

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import com.daydreamvr.upnp.cds.DecoderCaps

/**
 * Queries the platform once for what the hardware video decoders can actually
 * handle, and hands the result to `ResourceRanker` (ARCHITECTURE.md §9.5).
 *
 * The query is best-effort: on any failure it returns [FALLBACK] (a
 * conservative 1080p / common-container set) rather than throwing.
 */
class DecoderCapsProvider {

    @Volatile
    private var cached: DecoderCaps? = null

    fun caps(): DecoderCaps = cached ?: query().also { cached = it }

    private fun query(): DecoderCaps {
        return try {
            var maxWidth = 0
            var maxHeight = 0
            val mimes = mutableSetOf<String>()

            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecs.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (!type.startsWith("video/")) continue
                    val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                    val video: MediaCodecInfo.VideoCapabilities = caps.videoCapabilities ?: continue
                    maxWidth = maxOf(maxWidth, video.supportedWidths.upper)
                    maxHeight = maxOf(maxHeight, video.supportedHeights.upper)
                    CONTAINERS_BY_VIDEO_MIME[type]?.let { mimes += it }
                }
            }

            if (maxWidth == 0 || maxHeight == 0) return FALLBACK
            DecoderCaps(
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                mimeTypes = if (mimes.isEmpty()) FALLBACK.mimeTypes else mimes,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "decoder capability query failed, using fallback", t)
            FALLBACK
        }
    }

    companion object {
        private const val TAG = "DecoderCapsProvider"

        /** Video sample MIME → the DLNA container MIME types it commonly rides in. */
        private val CONTAINERS_BY_VIDEO_MIME: Map<String, Set<String>> = mapOf(
            "video/avc" to setOf("video/mp4", "video/x-matroska", "video/webm"),
            "video/hevc" to setOf("video/mp4", "video/x-matroska"),
            "video/x-vnd.on2.vp8" to setOf("video/webm", "video/x-matroska"),
            "video/x-vnd.on2.vp9" to setOf("video/webm", "video/x-matroska"),
            "video/av01" to setOf("video/mp4", "video/x-matroska", "video/webm"),
            "video/mp4v-es" to setOf("video/mp4", "video/mpeg", "video/avi"),
            "video/mpeg2" to setOf("video/mpeg"),
        )

        val FALLBACK = DecoderCaps(
            maxWidth = 1920,
            maxHeight = 1080,
            mimeTypes = setOf("video/mp4", "video/x-matroska", "video/webm", "video/mpeg", "video/avi"),
        )
    }
}
