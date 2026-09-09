package com.daydreamvr.player.media.local

import com.daydreamvr.vrcore.render.ProjectionMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM acceptance tests for the local-media cursor mapper
 * (UI_REDESIGN_REVIEWED_PLAN.md §8.4, M1). Each test names the defect it guards.
 */
class MediaCursorMapperTest {

    private fun row(
        id: Long,
        displayName: String = "clip_$id.mp4",
        title: String? = null,
        durationMs: Long = 60_000,
        sizeBytes: Long = 1_000,
        width: Int = 1920,
        height: Int = 1080,
        bucketId: Int = 1,
        bucketName: String? = "Camera",
        relativePath: String? = "DCIM/Camera/",
        dateModifiedSec: Long = 1_700_000_000,
        isPending: Boolean = false,
    ) = VideoRow(
        id = id, displayName = displayName, title = title, durationMs = durationMs,
        sizeBytes = sizeBytes, width = width, height = height, mimeType = "video/mp4",
        bucketId = bucketId, bucketName = bucketName, relativePath = relativePath,
        dateModifiedSec = dateModifiedSec, dateAddedSec = dateModifiedSec, isPending = isPending,
    )

    @Test
    fun rowsSharingABucketIdCollapseToOneFolder() {
        val map = MediaCursorMapper.map(listOf(row(1), row(2)))
        assertThat(map).hasSize(1)
        val folder = map.keys.single()
        assertThat(folder.childCount).isEqualTo(2)
        assertThat(map.getValue(folder)).hasSize(2)
    }

    @Test
    fun sameBucketNameButDifferentBucketIdStaysTwoFolders() {
        // Internal /Camera and SD /Camera share a label but not an id (R: group-by-name merges them).
        val map = MediaCursorMapper.map(
            listOf(row(1, bucketId = 10, bucketName = "Camera"), row(2, bucketId = 20, bucketName = "Camera")),
        )
        assertThat(map).hasSize(2)
    }

    @Test
    fun dateModifiedIsConvertedFromSecondsToMillis() {
        val v = MediaCursorMapper.map(listOf(row(1, dateModifiedSec = 1_700_000_000))).values.first().first()
        assertThat(v.dateModifiedMs).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun durationPassesThroughUnscaled() {
        val v = MediaCursorMapper.map(listOf(row(1, durationMs = 123_456))).values.first().first()
        assertThat(v.durationMs).isEqualTo(123_456L)
    }

    @Test
    fun pendingRowsAreExcluded() {
        val map = MediaCursorMapper.map(listOf(row(1), row(2, isPending = true)))
        assertThat(map.values.flatten()).hasSize(1)
    }

    @Test
    fun blankBucketNameFallsBackToRelativePathThenVideos() {
        val a = MediaCursorMapper.map(listOf(row(1, bucketId = 1, bucketName = "  ", relativePath = "Movies/Trips/")))
        assertThat(a.keys.single().title).isEqualTo("Trips")

        val b = MediaCursorMapper.map(listOf(row(2, bucketId = 2, bucketName = null, relativePath = null)))
        assertThat(b.keys.single().title).isEqualTo("Videos")
    }

    @Test
    fun mediaRefIsTheContentUriTemplate() {
        val v = MediaCursorMapper.map(listOf(row(42))).values.first().first()
        val ref = (v.playback as com.daydreamvr.player.media.PlaybackRef.Local).ref
        assertThat(ref.value).isEqualTo("content://media/external/video/media/42")
    }

    @Test
    fun projectionDetectionUsesNameThenPathThenAspect() {
        val sbs = MediaCursorMapper.map(listOf(row(1, displayName = "clip_180_sbs.mp4"))).values.first().first()
        assertThat(sbs.detectedProjection).isEqualTo(ProjectionMode.EQUIRECT_180_SBS)

        val byPath = MediaCursorMapper.map(
            listOf(row(2, displayName = "clip.mp4", relativePath = "DCIM/VR180/")),
        ).values.first().first()
        assertThat(byPath.detectedProjection).isEqualTo(ProjectionMode.EQUIRECT_180)

        val flat = MediaCursorMapper.map(
            listOf(row(3, displayName = "clip.mp4", relativePath = "DCIM/Camera/", width = 1920, height = 1080)),
        ).values.first().first()
        assertThat(flat.detectedProjection).isEqualTo(ProjectionMode.FLAT)
    }

    @Test
    fun foldersAreOrderedByChildCountDescThenTitleAsc() {
        val map = MediaCursorMapper.map(
            listOf(
                row(1, bucketId = 1, bucketName = "Zebra"),
                row(2, bucketId = 2, bucketName = "Alpha"),
                row(3, bucketId = 2, bucketName = "Alpha"),
                row(4, bucketId = 3, bucketName = "Beta"),
            ),
        )
        assertThat(map.keys.map { it.title }).containsExactly("Alpha", "Beta", "Zebra").inOrder()
    }

    @Test
    fun titleFallsBackToDisplayNameWithoutExtension() {
        val blank = MediaCursorMapper.map(listOf(row(1, displayName = "Trip.mp4", title = "  "))).values.first().first()
        assertThat(blank.title).isEqualTo("Trip")
        val kept = MediaCursorMapper.map(listOf(row(2, displayName = "Trip.mp4", title = "My Trip"))).values.first().first()
        assertThat(kept.title).isEqualTo("My Trip")
    }
}
