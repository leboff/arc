package com.daydreamvr.player.media

import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.upnp.model.Resource
import com.daydreamvr.vrcore.render.ProjectionMode
import com.daydreamvr.vrcore.ui.Icon

/**
 * Unified media domain (UI_REDESIGN_REVIEWED_PLAN.md §7).
 *
 * PURE Kotlin — **no `android.*` imports**. That constraint is what keeps the
 * reducer JVM-testable under `unitTests.isReturnDefaultValues = true`, and is the
 * reason a [MediaRef] `String` stands in for `android.net.Uri` (R6) and a cache
 * key stands in for `android.graphics.Bitmap` (R5).
 */

/**
 * An opaque, JVM-safe reference to playable bytes. Parsed to `android.net.Uri`
 * only inside the effect layer.
 */
@JvmInline
value class MediaRef(val value: String) {
    val isContent: Boolean get() = value.startsWith("content://")
}

/** Stable identity across source switches, sorts, pages and process restarts. */
data class MediaKey(val sourceId: String, val nodeId: String) {
    /** The ResumeStore / projection-override key. Format is frozen — it is persisted. */
    fun storageKey(): String = "$sourceId|$nodeId"
}

sealed interface MediaSource {
    val id: String
    val title: String
    val icon: Icon

    data class Upnp(val server: MediaServer) : MediaSource {
        override val id get() = "upnp:${server.udn}"
        override val title get() = server.friendlyName
        override val icon get() = Icon.NETWORK
    }

    data object Local : MediaSource {
        override val id = "local"
        override val title = "Device"
        override val icon = Icon.PHONE
    }

    data object Favourites : MediaSource {
        override val id = "favourites"
        override val title = "Favourites"
        override val icon = Icon.STAR
    }
}

sealed interface MediaNode {
    val id: String
    val title: String
    val parentId: String?

    data class Folder(
        override val id: String,
        override val title: String,
        override val parentId: String? = null,
        val childCount: Int? = null,
        val icon: Icon = Icon.FOLDER,
    ) : MediaNode

    data class Video(
        override val id: String,
        override val title: String,
        override val parentId: String? = null,
        val playback: PlaybackRef,
        val durationMs: Long? = null,
        val sizeBytes: Long? = null,
        val width: Int = 0,
        val height: Int = 0,
        val mimeType: String? = null,
        /** Epoch MILLISECONDS. MediaStore hands out seconds; the mapper multiplies (R23). */
        val dateModifiedMs: Long? = null,
        /** Auto-detected. A user override lives in the override store, not here. */
        val detectedProjection: ProjectionMode = ProjectionMode.FLAT,
        /** Cache LOOKUP key only. Never a Bitmap (R5). */
        val thumbnailKey: String? = null,
        /** Remote art to fetch when the local cache misses. Null for local files. */
        val thumbnailRef: MediaRef? = null,
    ) : MediaNode {
        val resolutionLabel: String? get() = if (width > 0 && height > 0) "$width×$height" else null
    }
}

/** How to actually open this video — the one place the two backends differ. */
sealed interface PlaybackRef {
    data class Upnp(val resources: List<Resource>) : PlaybackRef
    data class Local(val ref: MediaRef) : PlaybackRef
}
