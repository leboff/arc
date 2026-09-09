package com.daydreamvr.playback

/**
 * Static pre-routing (docs/FORMAT_SUPPORT_PLAN.md §6.2 rule 2): if the source URI
 * extension or the DLNA `protocolInfo` MIME names a container Media3 has no
 * extractor for, start straight on [PlaybackEngine.VLC] rather than burn a
 * guaranteed-to-fail Media3 open.
 *
 * Pure — no Android, no ExoPlayer, no `libvlcjni.so` — so the whole routing table
 * is JVM-unit-testable, the same discipline [FallbackPolicy] follows.
 */
object EnginePreRoute {

    /** Containers with no Media3 extractor at all (§2.7). */
    val MEDIA3_HOSTILE_EXTENSIONS = setOf("wmv", "asf", "rm", "rmvb", "ogm", "divx")

    val MEDIA3_HOSTILE_MIMES = setOf(
        "video/x-ms-wmv",
        "video/x-ms-asf",
        "video/x-msvideo-ms",
        "application/vnd.rn-realmedia",
        "application/vnd.rn-realmedia-vbr",
        "audio/x-ms-wma",
    )

    /**
     * @param sourceUri the resource URI, in any scheme. Query and fragment are
     *                  stripped before the extension is read; an opaque URI with
     *                  no file extension (`content://media/external/video/media/42`)
     *                  always routes to [PlaybackEngine.MEDIA3] — routing never
     *                  guesses from a path segment.
     * @param mimeType  the declared MIME type, if known. A hostile MIME wins over
     *                  a benign extension.
     */
    fun decide(sourceUri: String, mimeType: String?): PlaybackEngine {
        val mime = mimeType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "*" }
        if (mime != null && mime in MEDIA3_HOSTILE_MIMES) return PlaybackEngine.VLC

        val ext = sourceUri
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase()
        if (ext.isNotEmpty() && ext in MEDIA3_HOSTILE_EXTENSIONS) return PlaybackEngine.VLC

        return PlaybackEngine.MEDIA3
    }
}
