package com.daydreamvr.playback

import com.daydreamvr.upnp.model.Resource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Test
import java.net.URI

class PlaybackEngineRouterTest {

    private val media3 = FakeVideoPlayer()
    private val vlc = FakeVideoPlayer()
    private val fatalErrors = mutableListOf<String>()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val router = PlaybackEngineRouter(
        media3 = media3,
        vlcFactory = { vlc },
        engineStore = InMemoryPlaybackEngineStore(),
        onFatalError = { fatalErrors += it },
        scope = testScope,
    )

    private val sampleRequest = PlayRequest(
        itemKey = "item1",
        title = "Test Video",
        rankedResources = listOf(
            Resource(
                uri = URI.create("http://192.168.1.10:49152/media/1.mp4"),
                protocolInfo = "http-get:*:video/mp4:*",
                sizeBytes = 1000L,
                durationMs = 60000L,
                resolution = null,
                bitrate = null,
            )
        ),
        startAtMs = 0L,
    )

    @Test
    fun switchesFromMedia3ToVlcWithoutLeakingFailureOrCallingFatalError() {
        router.play(sampleRequest)
        assertThat(media3.lastPlayRequest).isNotNull()
        assertThat(vlc.lastPlayRequest).isNull()

        // Media3 encounters an unsupported container error at 15 seconds
        media3.emitSnapshot(
            PlaybackSnapshot(
                itemKey = "item1",
                title = "Test Video",
                state = PlaybackState.IDLE,
                positionMs = 15000L,
                failure = PlaybackFailure.UnsupportedContainer("wmv3"),
            )
        )

        // Router must suppress the failure so the UI never receives an Error overlay,
        // and must transition to VLC at the last known position
        assertThat(router.snapshot.value.failure).isNull()
        assertThat(router.snapshot.value.isBuffering).isTrue()
        assertThat(fatalErrors).isEmpty()

        assertThat(vlc.lastPlayRequest).isNotNull()
        assertThat(vlc.lastPlayRequest?.startAtMs).isEqualTo(15000L)
        assertThat(media3.isStopped).isTrue()

        // When VLC starts successfully, its snapshot is published clean
        vlc.emitSnapshot(
            PlaybackSnapshot(
                itemKey = "item1",
                title = "Test Video",
                state = PlaybackState.READY,
                isPlaying = true,
                positionMs = 15000L,
                failure = null,
            )
        )

        assertThat(router.snapshot.value.failure).isNull()
        assertThat(router.snapshot.value.isPlaying).isTrue()
        assertThat(router.snapshot.value.state).isEqualTo(PlaybackState.READY)
        assertThat(fatalErrors).isEmpty()
    }

    @Test
    fun givesUpAndReportsFatalErrorWhenBothEnginesFail() {
        router.play(sampleRequest)

        // Media3 fails -> router switches to VLC
        media3.emitSnapshot(
            PlaybackSnapshot(
                itemKey = "item1",
                failure = PlaybackFailure.UnsupportedContainer("video/x-matroska"),
            )
        )
        assertThat(fatalErrors).isEmpty()
        assertThat(router.snapshot.value.failure).isNull()

        // VLC also fails -> now router gives up and emits the fatal error
        vlc.emitSnapshot(
            PlaybackSnapshot(
                itemKey = "item1",
                failure = PlaybackFailure.Unknown("LibVLC decoder crashed"),
            )
        )

        assertThat(router.snapshot.value.failure).isInstanceOf(PlaybackFailure.Unknown::class.java)
        assertThat(fatalErrors).containsExactly("LibVLC decoder crashed")
    }

    @Test
    fun givesUpImmediatelyOnNonSwitchableFailure() {
        router.play(sampleRequest)

        // Generic non-switchable error with 0 remaining resources
        media3.emitSnapshot(
            PlaybackSnapshot(
                itemKey = "item1",
                failure = PlaybackFailure.Unknown("Generic device decoder crash"),
            )
        )

        assertThat(vlc.lastPlayRequest).isNull()
        assertThat(router.snapshot.value.failure).isEqualTo(PlaybackFailure.Unknown("Generic device decoder crash"))
        assertThat(fatalErrors).containsExactly("Generic device decoder crash")
    }

    private class FakeVideoPlayer(
        initialSnapshot: PlaybackSnapshot = PlaybackSnapshot.EMPTY
    ) : VideoPlayer {
        val _snapshot = MutableStateFlow(initialSnapshot)
        override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()
        var attachedSurface: android.view.Surface? = null
        var lastPlayRequest: PlayRequest? = null
        var isStopped = false
        var isReleased = false

        override fun attach(surface: android.view.Surface) { attachedSurface = surface }
        override fun detach() { attachedSurface = null }
        override fun play(request: PlayRequest) {
            lastPlayRequest = request
            isStopped = false
            _snapshot.value = _snapshot.value.copy(
                itemKey = request.itemKey,
                title = request.title,
                state = PlaybackState.BUFFERING,
                isBuffering = true,
                positionMs = request.startAtMs,
            )
        }
        override fun playPause() {}
        override fun pause() {}
        override fun stop() { isStopped = true }
        override fun seekBy(deltaMs: Long) {}
        override fun seekTo(positionMs: Long, exact: Boolean) {}
        override fun setSpeed(speed: Float) {}
        override fun selectAudioTrack(id: String?) {}
        override fun selectSubtitleTrack(id: String?) {}
        override fun release() { isReleased = true }

        fun emitSnapshot(snap: PlaybackSnapshot) {
            _snapshot.value = snap
        }
    }
}
