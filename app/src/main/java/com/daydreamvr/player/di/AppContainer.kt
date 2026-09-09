package com.daydreamvr.player.di

import android.content.Context
import com.daydreamvr.player.data.ServerStore
import com.daydreamvr.player.data.SettingsStore
import com.daydreamvr.player.net.AndroidNetworkBinder
import com.daydreamvr.player.media.local.LocalMediaRepository
import com.daydreamvr.player.media.thumb.LocalThumbnailSource
import com.daydreamvr.player.media.thumb.SizedLruCache
import com.daydreamvr.player.media.thumb.ThumbnailCache
import com.daydreamvr.player.media.thumb.UpnpThumbnailSource
import com.daydreamvr.player.net.LogcatUpnpLog
import androidx.media3.common.util.UnstableApi
import com.daydreamvr.playback.DecoderCapsProvider
import com.daydreamvr.playback.ExoVideoPlayer
import com.daydreamvr.playback.InMemoryPlaybackEngineStore
import com.daydreamvr.playback.InMemoryResumeStore
import com.daydreamvr.playback.PlaybackEngineRouter
import com.daydreamvr.playback.PlaybackEngineStore
import com.daydreamvr.playback.VideoPlayer
import com.daydreamvr.playback.vlc.VlcVideoPlayer
import com.daydreamvr.upnp.MediaServerDirectory
import com.daydreamvr.upnp.MediaServerDirectoryImpl
import com.daydreamvr.upnp.cds.ContentDirectoryClient
import com.daydreamvr.upnp.cds.ContentDirectoryClientImpl
import com.daydreamvr.upnp.net.DatagramChannelProvider
import com.daydreamvr.upnp.net.HttpTransport
import com.daydreamvr.upnp.ssdp.SsdpClient
import com.daydreamvr.vrcore.input.GamepadProfileResolver
import com.daydreamvr.vrcore.input.InputBindings
import com.daydreamvr.vrcore.profile.DeviceProfile
import com.daydreamvr.vrcore.profile.DeviceProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The whole object graph (~20 objects by the end). Manual constructor injection;
 * no Hilt (ARCHITECTURE.md §2). Grows one field per phase.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Long-lived scope for discovery / browse work (ARCHITECTURE.md §4). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Base (Xbox-layout) button map. The gamepad A/B-swap override is applied on top per settings. */
    val inputBindings: InputBindings = InputBindings()

    val gamepadProfileResolver: GamepadProfileResolver = GamepadProfileResolver()

    /** Active viewer profile. User-selectable from the lobby / settings later. */
    @Volatile
    var deviceProfile: DeviceProfile = DeviceProfiles.DEFAULT

    // ---- Phase 3: UPnP / DLNA control point ----------------------------------

    /** Shared HTTP stack for the UPnP control point (ARCHITECTURE.md §2). */
    val httpTransport: HttpTransport = HttpTransport.okHttpDefault()

    /** Holds the multicast lock + Wi-Fi socket binding for SSDP. */
    val networkBinder: DatagramChannelProvider = AndroidNetworkBinder(appContext)

    val ssdpClient: SsdpClient = SsdpClient(networkBinder, LogcatUpnpLog)

    val mediaServerDirectory: MediaServerDirectory =
        MediaServerDirectoryImpl(ssdpClient, httpTransport, appScope, LogcatUpnpLog)

    val contentDirectoryClient: ContentDirectoryClient =
        ContentDirectoryClientImpl(httpTransport, LogcatUpnpLog)

    // ---- Phase 5: end-to-end player loop -----------------------------------

    val settingsStore: SettingsStore = SettingsStore(appContext)

    val serverStore: ServerStore = ServerStore(appContext)

    /** Shared between [com.daydreamvr.playback.ExoVideoPlayer] and the [EffectRunner]. */
    val resumeStore: InMemoryResumeStore = InMemoryResumeStore()

    /** Sticky per-item engine hint for [PlaybackEngineRouter] (docs/FORMAT_SUPPORT_PLAN.md §6.2). */
    val playbackEngineStore: PlaybackEngineStore = InMemoryPlaybackEngineStore()

    /**
     * The app's [VideoPlayer]: a [PlaybackEngineRouter] with Media3 as the primary
     * engine and a lazily-constructed [VlcVideoPlayer] as the compatibility
     * fallback (docs/FORMAT_SUPPORT_PLAN.md §5, §8.6). `libvlcjni.so` is not
     * loaded until a file actually needs it.
     */
    @UnstableApi
    fun createPlayer(onFatalError: (String) -> Unit): VideoPlayer =
        PlaybackEngineRouter(
            media3 = ExoVideoPlayer(appContext, resumeStore, onFatalError),
            vlcFactory = { VlcVideoPlayer(appContext, resumeStore, onFatalError) },
            engineStore = playbackEngineStore,
            onFatalError = onFatalError,
        )

    val decoderCapsProvider: DecoderCapsProvider = DecoderCapsProvider()

    // ---- Local media (UI_REDESIGN_REVIEWED_PLAN.md §8, §9) ----------------------

    val localMediaRepository: LocalMediaRepository = LocalMediaRepository(appContext, appScope)

    /** Byte-bounded thumbnail LRU: 1/8 of heap, capped at 24 MB (ARCHITECTURE.md §14). */
    private val thumbnailBudgetKb: Int = minOf(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt(),
        24 * 1024,
    )

    val thumbnailCache: ThumbnailCache = ThumbnailCache(
        cache = SizedLruCache(thumbnailBudgetKb) { bmp -> (bmp.allocationByteCount / 1024).coerceAtLeast(1) },
        sources = listOf(
            LocalThumbnailSource(appContext.contentResolver),
            UpnpThumbnailSource(httpTransport),
        ),
        scope = appScope,
    )
}
