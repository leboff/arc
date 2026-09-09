package com.daydreamvr.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnginePreRouteTest {

    @Test
    fun hostileExtension_routesToVlc_benignExtension_staysOnMedia3() {
        assertThat(EnginePreRoute.decide("http://server/movies/clip.wmv", null))
            .isEqualTo(PlaybackEngine.VLC)
        assertThat(EnginePreRoute.decide("http://server/movies/clip.mkv", null))
            .isEqualTo(PlaybackEngine.MEDIA3)
    }

    @Test
    fun everyHostileExtensionRoutesToVlc() {
        for (ext in EnginePreRoute.MEDIA3_HOSTILE_EXTENSIONS) {
            assertThat(EnginePreRoute.decide("http://h/a.$ext", null)).isEqualTo(PlaybackEngine.VLC)
        }
    }

    @Test
    fun hostileMimeBeatsBenignExtension() {
        assertThat(EnginePreRoute.decide("http://h/a.mp4", "video/x-ms-asf"))
            .isEqualTo(PlaybackEngine.VLC)
    }

    @Test
    fun extensionMatchIsCaseInsensitiveAndIgnoresQueryString() {
        assertThat(EnginePreRoute.decide("http://h/a.WMV?token=abc123", null))
            .isEqualTo(PlaybackEngine.VLC)
    }

    @Test
    fun opaqueContentUriWithNoExtension_staysOnMedia3() {
        assertThat(EnginePreRoute.decide("content://media/external/video/media/42", null))
            .isEqualTo(PlaybackEngine.MEDIA3)
    }

    @Test
    fun wildcardOrBlankMimeIsIgnored() {
        assertThat(EnginePreRoute.decide("http://h/a.mp4", "*")).isEqualTo(PlaybackEngine.MEDIA3)
        assertThat(EnginePreRoute.decide("http://h/a.mp4", "")).isEqualTo(PlaybackEngine.MEDIA3)
    }

    @Test
    fun benignMimeAndBenignExtension_staysOnMedia3() {
        assertThat(EnginePreRoute.decide("http://h/a.mp4", "video/mp4"))
            .isEqualTo(PlaybackEngine.MEDIA3)
    }
}
