package com.daydreamvr.upnp.cds

import com.daydreamvr.upnp.model.Resource
import com.daydreamvr.upnp.model.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.URI

class ResourceRankerTest {

    private val caps1080p = DecoderCaps(
        maxWidth = 1920,
        maxHeight = 1080,
        mimeTypes = setOf("video/mp4", "video/x-matroska"),
    )

    private fun res(
        uri: String,
        protocol: String = "http-get",
        mime: String = "video/mp4",
        resolution: Size? = null,
        bitrate: Int? = null,
    ) = Resource(
        uri = URI(uri),
        protocolInfo = "$protocol:*:$mime:*",
        sizeBytes = null,
        durationMs = null,
        resolution = resolution,
        bitrate = bitrate,
    )

    @Test
    fun httpGetIsPreferredOverRtsp_butRtspIsStillReturned() {
        val rtsp = res("rtsp://host/stream", protocol = "rtsp-rtp-udp")
        val http = res("http://host/file.mp4")

        val ranked = ResourceRanker.rank(listOf(rtsp, http), caps1080p)

        assertThat(ranked.first()).isEqualTo(http)
        assertThat(ranked).containsExactly(http, rtsp).inOrder()
    }

    @Test
    fun fourKisRejectedWhenDecoderMaxesAt1080p() {
        val uhd = res("http://host/uhd.mp4", resolution = Size(3840, 2160), bitrate = 40_000_000)
        val fhd = res("http://host/fhd.mp4", resolution = Size(1920, 1080), bitrate = 8_000_000)

        val ranked = ResourceRanker.rank(listOf(uhd, fhd), caps1080p)

        assertThat(ranked.first()).isEqualTo(fhd)
        assertThat(ranked).hasSize(2) // full list kept for fallback
    }

    @Test
    fun amongEqualsHigherBitrateWins() {
        val low = res("http://host/low.mp4", resolution = Size(1280, 720), bitrate = 2_000_000)
        val high = res("http://host/high.mp4", resolution = Size(1280, 720), bitrate = 6_000_000)

        val ranked = ResourceRanker.rank(listOf(low, high), caps1080p)

        assertThat(ranked).containsExactly(high, low).inOrder()
    }

    @Test
    fun higherFittingResolutionBeatsLowerResolution() {
        val sd = res("http://host/sd.mp4", resolution = Size(720, 480), bitrate = 9_000_000)
        val hd = res("http://host/hd.mp4", resolution = Size(1920, 1080), bitrate = 4_000_000)

        assertThat(ResourceRanker.rank(listOf(sd, hd), caps1080p).first()).isEqualTo(hd)
    }

    @Test
    fun knownMimeTypeBeatsUnknown_whenOtherwiseEqual() {
        val unknown = res("http://host/a.avi", mime = "video/avi", resolution = Size(1280, 720))
        val known = res("http://host/b.mp4", mime = "video/mp4", resolution = Size(1280, 720))

        assertThat(ResourceRanker.rank(listOf(unknown, known), caps1080p).first()).isEqualTo(known)
    }

    @Test
    fun fullRankedListIsReturned_notJustTheWinner() {
        val all = listOf(
            res("http://host/1.mp4", resolution = Size(3840, 2160)),
            res("rtsp://host/2", protocol = "rtsp"),
            res("http://host/3.mp4", resolution = Size(1920, 1080), bitrate = 5_000),
            res("http://host/4.mkv", mime = "video/x-matroska", resolution = Size(1280, 720)),
        )
        assertThat(ResourceRanker.rank(all, caps1080p)).hasSize(4)
    }
}
