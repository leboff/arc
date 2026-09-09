package com.daydreamvr.player.media.local

import com.daydreamvr.player.media.MediaNode
import com.daydreamvr.player.media.MediaRef
import com.daydreamvr.player.media.PlaybackRef
import com.daydreamvr.vrcore.render.ProjectionMode

/**
 * One row of the `MediaStore.Video` cursor, mirrored as a pure data class so the
 * mapping logic below is JVM-testable with no `ContentResolver` and no
 * Robolectric (UI_REDESIGN_REVIEWED_PLAN.md §8.3, §8.4).
 *
 * `dateModifiedSec` / `dateAddedSec` are **seconds** exactly as MediaStore hands
 * them out; the mapper converts to milliseconds (R23).
 */
data class VideoRow(
    val id: Long,
    val displayName: String,
    val title: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val bucketId: Int,
    val bucketName: String?,
    val relativePath: String?,
    val dateModifiedSec: Long,
    val dateAddedSec: Long,
    val isPending: Boolean,
)

/**
 * Local-file projection detection: the filename wins, then the folder path, then
 * the frame aspect (the existing [ProjectionMode.detect] rule). Catches
 * `.../Movies/VR180/clip.mp4` where the filename carries no token
 * (UI_REDESIGN_REVIEWED_PLAN.md §8.3).
 */
fun detectLocalProjection(displayName: String, relativePath: String?, w: Int, h: Int): ProjectionMode {
    val direct = ProjectionMode.detect(displayName, w, h)
    if (direct != ProjectionMode.FLAT) return direct
    return relativePath?.let { ProjectionMode.detect(it, 0, 0) } ?: ProjectionMode.FLAT
}

object MediaCursorMapper {
    /** Content URI template, injected so the mapper stays JVM-pure. */
    const val URI_TEMPLATE = "content://media/external/video/media/%d"

    private fun label(row: VideoRow): String {
        row.bucketName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        row.relativePath
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?.lastOrNull()
            ?.let { return it }
        return "Videos"
    }

    private fun toVideo(row: VideoRow): MediaNode.Video {
        val title = row.title?.ifBlank { null } ?: row.displayName.substringBeforeLast('.')
        return MediaNode.Video(
            id = row.id.toString(),
            title = title,
            parentId = row.bucketId.toString(),
            playback = PlaybackRef.Local(MediaRef(URI_TEMPLATE.format(row.id))),
            durationMs = row.durationMs,
            sizeBytes = row.sizeBytes,
            width = row.width,
            height = row.height,
            mimeType = row.mimeType,
            dateModifiedMs = row.dateModifiedSec * 1000L,
            detectedProjection = detectLocalProjection(row.displayName, row.relativePath, row.width, row.height),
            thumbnailKey = "local:${row.id}",
            thumbnailRef = MediaRef(URI_TEMPLATE.format(row.id)),
        )
    }

    /**
     * Groups cursor rows into virtual folders keyed by `bucketId` (never by
     * `bucketName` — two directories can share a label, §8.3). Result iteration
     * order is folders by `childCount` desc, then title asc.
     */
    fun map(rows: List<VideoRow>): Map<MediaNode.Folder, List<MediaNode.Video>> {
        val byBucket = LinkedHashMap<Int, MutableList<VideoRow>>()
        for (row in rows) {
            if (row.isPending) continue
            byBucket.getOrPut(row.bucketId) { mutableListOf() }.add(row)
        }

        val folders = byBucket.entries
            .map { (bucketId, group) ->
                val folder = MediaNode.Folder(
                    id = bucketId.toString(),
                    title = label(group.first()),
                    parentId = null,
                    childCount = group.size,
                )
                folder to group.map { toVideo(it) }
            }
            .sortedWith(compareByDescending<Pair<MediaNode.Folder, List<MediaNode.Video>>> { it.first.childCount ?: 0 }
                .thenBy { it.first.title.lowercase() })

        val out = LinkedHashMap<MediaNode.Folder, List<MediaNode.Video>>()
        for ((folder, videos) in folders) out[folder] = videos
        return out
    }
}
