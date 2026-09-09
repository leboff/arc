package com.daydreamvr.player.media

import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.vrcore.render.ProjectionMode

/**
 * Maps the UPnP DIDL types onto the unified [MediaNode] shape
 * (UI_REDESIGN_REVIEWED_PLAN.md §7.3). Pure — no IO, no Android.
 */
object UpnpAdapter {

    fun folder(c: DidlContainer): MediaNode.Folder = MediaNode.Folder(
        id = c.id,
        title = c.title,
        parentId = c.parentId,
        childCount = c.childCount,
    )

    fun video(i: DidlItem): MediaNode.Video {
        val w = i.resolution?.width ?: 0
        val h = i.resolution?.height ?: 0
        val thumb = i.albumArtUri?.let { MediaRef(it.toString()) }
            ?: i.resources.firstOrNull { it.isThumbnail }?.let { MediaRef(it.uri.toString()) }
        return MediaNode.Video(
            id = i.id,
            title = i.title,
            parentId = i.parentId,
            playback = PlaybackRef.Upnp(i.resources),
            durationMs = i.durationMs,
            sizeBytes = i.sizeBytes,
            width = w,
            height = h,
            mimeType = i.mimeType,
            detectedProjection = ProjectionMode.detect(i.title, w, h),
            thumbnailKey = thumb?.let { "upnp:${i.id}" },
            thumbnailRef = thumb,
        )
    }
}
