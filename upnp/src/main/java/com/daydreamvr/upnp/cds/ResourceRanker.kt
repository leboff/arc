package com.daydreamvr.upnp.cds

import com.daydreamvr.upnp.model.Resource

/** What the phone's hardware video decoder can actually handle (ARCHITECTURE.md §9.5). */
data class DecoderCaps(
    val maxWidth: Int,
    val maxHeight: Int,
    val mimeTypes: Set<String>,
) {
    fun fits(resource: Resource): Boolean {
        val res = resource.resolution ?: return true // unknown resolution: don't penalise
        return res.width <= maxWidth && res.height <= maxHeight
    }

    fun knownMime(resource: Resource): Boolean {
        val mime = resource.mimeType ?: return false
        return mimeTypes.any { it.equals(mime, ignoreCase = true) }
    }
}

/**
 * Ranks the `<res>` candidates of an item best-first. The **full** ordered list is
 * returned (not just the winner) so playback can transparently fall back to the
 * next resource on failure (ARCHITECTURE.md §10.5). Ordering, most significant
 * first:
 *
 *  1. `http-get` (or unspecified) transport before rtsp / internal;
 *  2. resolution that fits the decoder before one that does not (4K rejected when
 *     the decoder tops out at 1080p);
 *  3. a MIME type in the known-good set before an unknown one;
 *  4. higher resolution (pixel area);
 *  5. higher bitrate.
 */
object ResourceRanker {

    fun rank(resources: List<Resource>, caps: DecoderCaps): List<Resource> =
        resources.sortedWith(
            compareByDescending<Resource> { it.isHttpGet }
                .thenByDescending { caps.fits(it) }
                .thenByDescending { caps.knownMime(it) }
                .thenByDescending { it.resolution?.area ?: 0L }
                .thenByDescending { it.bitrate ?: 0 },
        )
}
