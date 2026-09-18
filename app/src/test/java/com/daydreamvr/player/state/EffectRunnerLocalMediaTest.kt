package com.daydreamvr.player.state

import android.content.Context
import android.content.ContextWrapper
import android.view.Surface
import com.daydreamvr.playback.InMemoryResumeStore
import com.daydreamvr.playback.PlayRequest
import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.playback.ResumeEntry
import com.daydreamvr.playback.ResumeStore
import com.daydreamvr.playback.VideoPlayer
import com.daydreamvr.player.data.ServerStore
import com.daydreamvr.player.data.SettingsStore
import com.daydreamvr.player.media.MediaKey
import com.daydreamvr.vrcore.render.ProjectionMode
import com.daydreamvr.upnp.MediaServerDirectory
import com.daydreamvr.upnp.cds.ContentDirectoryClient
import com.daydreamvr.upnp.cds.DecoderCaps
import com.daydreamvr.upnp.model.BrowseResult
import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.upnp.model.PageRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class EffectRunnerLocalMediaTest {

    @Test
    fun loadLocalMediaCallsLoaderAndDispatchesItsEvent() = runTest {
        val loaded = Event.LocalMediaLoaded(emptyList(), emptyMap())
        val dispatched = mutableListOf<Event>()
        var calls = 0
        val runner = runner(dispatched) {
            calls++
            loaded
        }

        runner.run(Effect.LoadLocalMedia)
        advanceUntilIdle()

        assertThat(calls).isEqualTo(1)
        assertThat(dispatched).containsExactly(loaded)
    }

    @Test
    fun loadLocalMediaDispatchesFailureWhenLoaderThrows() = runTest {
        val dispatched = mutableListOf<Event>()
        val runner = runner(dispatched) { error("scan failed") }

        runner.run(Effect.LoadLocalMedia)
        advanceUntilIdle()

        assertThat(dispatched).containsExactly(Event.LocalMediaFailed("scan failed"))
    }

    @Test
    fun loadLocalMediaDispatchesFailureReturnedByLoader() = runTest {
        val dispatched = mutableListOf<Event>()
        val failure = Event.LocalMediaFailed("permission revoked")
        val runner = runner(dispatched) { failure }

        runner.run(Effect.LoadLocalMedia)
        advanceUntilIdle()

        assertThat(dispatched).containsExactly(failure)
    }

    @Test
    fun flushResumePersistsAllCurrentEntries() = runTest {
        val entries = CompletableDeferred<List<ResumeEntry>>()
        val resumeStore = InMemoryResumeStore().apply {
            put("movie", 12_000L, 60_000L, 123L)
        }
        val runner = runner(
            dispatched = mutableListOf(),
            loader = { error("unused") },
            resumeStore = resumeStore,
            saveResume = { entries.complete(it) },
        )

        runner.flushResume()

        assertThat(withTimeout(5_000L) { entries.await() }).containsExactly(
            ResumeEntry("movie", 12_000L, 60_000L, null),
        )
    }

    @Test
    fun persistProjectionOverrideInvokesSaveProjectionOverride() = runTest {
        val saved = CompletableDeferred<Pair<String, ProjectionMode?>>()
        val runner = runner(
            dispatched = mutableListOf(),
            loader = { error("unused") },
            saveProjectionOverride = { key, mode -> saved.complete(key to mode) },
        )

        runner.run(Effect.PersistProjectionOverride(MediaKey("local", "10"), ProjectionMode.SBS_FULL))

        assertThat(withTimeout(5_000L) { saved.await() })
            .isEqualTo("local|10" to ProjectionMode.SBS_FULL)
    }

    private fun TestScope.runner(
        dispatched: MutableList<Event>,
        resumeStore: ResumeStore = InMemoryResumeStore(),
        saveResume: suspend (List<ResumeEntry>) -> Unit = {},
        saveProjectionOverride: suspend (String, ProjectionMode?) -> Unit = { _, _ -> },
        loader: suspend () -> Event,
    ) = EffectRunner(
        directory = object : MediaServerDirectory {
            override val servers: StateFlow<List<MediaServer>> = MutableStateFlow(emptyList())
            override suspend fun discover(timeout: Duration) = Unit
            override suspend fun addManual(hostPort: String): Result<MediaServer> = error("unused")
            override suspend fun refresh(server: MediaServer): Result<MediaServer> = error("unused")
        },
        contentDirectory = object : ContentDirectoryClient {
            override suspend fun browse(server: MediaServer, objectId: String, page: PageRequest): Result<BrowseResult> = error("unused")
            override suspend fun search(server: MediaServer, containerId: String, query: String): Result<BrowseResult> = error("unused")
        },
        player = object : VideoPlayer {
            override val snapshot: StateFlow<PlaybackSnapshot> = MutableStateFlow(PlaybackSnapshot.EMPTY)
            override fun attach(surface: Surface) = Unit
            override fun detach() = Unit
            override fun play(request: PlayRequest) = Unit
            override fun playPause() = Unit
            override fun pause() = Unit
            override fun stop() = Unit
            override fun seekBy(deltaMs: Long) = Unit
            override fun seekTo(positionMs: Long, exact: Boolean) = Unit
            override fun setSpeed(speed: Float) = Unit
            override fun selectAudioTrack(id: String?) = Unit
            override fun selectSubtitleTrack(id: String?) = Unit
            override fun release() = Unit
        },
        decoderCaps = { DecoderCaps(0, 0, emptySet()) },
        resumeStore = resumeStore,
        serverStore = ServerStore(TestContext()),
        settingsStore = SettingsStore(TestContext()),
        scope = this,
        dispatch = dispatched::add,
        ioDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        saveResume = saveResume,
        saveProjectionOverride = saveProjectionOverride,
        localMediaLoader = loader,
    )

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
