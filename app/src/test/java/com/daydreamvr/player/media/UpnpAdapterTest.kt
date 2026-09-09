package com.daydreamvr.player.media

import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.Resource
import com.daydreamvr.upnp.model.Size
import com.daydreamvr.vrcore.render.ProjectionMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.URI

class UpnpAdapterTest {

    private fun item(
        id: String = "17\$3",
        title: String = "Trip clip",
        albumArt: URI? = null,
        resources: List<Resource> = listOf(
            Resource(URI("http://h/$id.mp4"), "http-get:*:video/mp4:*", 4_200L, 90_000L, Size(3840, 2160), null),
        ),
    ) = DidlItem(
        id = id, parentId = "3", title = title, upnpClass = "object.item.videoItem.movie",
        resources = resources, durationMs = 90_000L, mimeType = "video/mp4",
        resolution = Size(3840, 2160), albumArtUri = albumArt, sizeBytes = 4_200L,
    )

    @Test
    fun containerRoundTripsToFolder() {
        val c = DidlContainer("5", "0", "Movies", "object.container.storageFolder", childCount = 12)
        val f = UpnpAdapter.folder(c)
        assertThat(f.id).isEqualTo("5")
        assertThat(f.title).isEqualTo("Movies")
        assertThat(f.parentId).isEqualTo("0")
        assertThat(f.childCount).isEqualTo(12)
    }

    @Test
    fun itemRoundTripsToVideoPreservingCoreFields() {
        val v = UpnpAdapter.video(item())
        assertThat(v.id).isEqualTo("17\$3")
        assertThat(v.title).isEqualTo("Trip clip")
        assertThat(v.durationMs).isEqualTo(90_000L)
        assertThat(v.sizeBytes).isEqualTo(4_200L)
        assertThat(v.width).isEqualTo(3840)
        assertThat(v.height).isEqualTo(2160)
        assertThat(v.mimeType).isEqualTo("video/mp4")
        assertThat((v.playback as PlaybackRef.Upnp).resources).hasSize(1)
    }

    @Test
    fun projectionIsDetectedFromTheTitle() {
        assertThat(UpnpAdapter.video(item(title = "Beach 180 sbs")).detectedProjection)
            .isEqualTo(ProjectionMode.EQUIRECT_180_SBS)
    }

    @Test
    fun albumArtBecomesThumbnailRef() {
        val v = UpnpAdapter.video(item(albumArt = URI("http://h/art.jpg")))
        assertThat(v.thumbnailRef).isEqualTo(MediaRef("http://h/art.jpg"))
    }

    @Test
    fun withoutAlbumArtItFallsBackToTheJpegTnResource() {
        val tn = Resource(URI("http://h/tn.jpg"), "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN", null, null, null, null)
        val v = UpnpAdapter.video(item(resources = listOf(tn) + item().resources))
        assertThat(v.thumbnailRef).isEqualTo(MediaRef("http://h/tn.jpg"))
    }

    @Test
    fun withNeitherArtNorThumbnailResourceItIsNull() {
        val v = UpnpAdapter.video(item(albumArt = null))
        assertThat(v.thumbnailRef).isNull()
        assertThat(v.thumbnailKey).isNull()
    }
}
