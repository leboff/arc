package com.daydreamvr.playback

/**
 * Diagnostic and capability helper for the optional Media3 FFmpeg audio decoder extension
 * (docs/FORMAT_SUPPORT_PLAN.md §8.5, §11).
 *
 * Uses reflection so the [playback] module does not take a direct compile-time dependency
 * on the extension AAR.
 */
object FfmpegExtension {

    private const val FFMPEG_LIBRARY_CLASS = "androidx.media3.decoder.ffmpeg.FfmpegLibrary"

    val isAvailable: Boolean
        get() = try {
            val clazz = Class.forName(FFMPEG_LIBRARY_CLASS)
            val method = clazz.getMethod("isAvailable")
            method.invoke(null) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }

    val version: String?
        get() = try {
            val clazz = Class.forName(FFMPEG_LIBRARY_CLASS)
            val method = clazz.getMethod("getVersion")
            method.invoke(null) as? String
        } catch (_: Throwable) {
            null
        }

    fun supportsFormat(mimeType: String): Boolean =
        try {
            val clazz = Class.forName(FFMPEG_LIBRARY_CLASS)
            val method = clazz.getMethod("supportsFormat", String::class.java)
            method.invoke(null, mimeType) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
}
