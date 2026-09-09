package com.daydreamvr.player.di

import android.content.Context
import com.daydreamvr.player.data.ServerStore
import com.daydreamvr.player.data.SettingsStore
import com.daydreamvr.player.net.AndroidNetworkBinder
import com.daydreamvr.player.net.LogcatUpnpLog
import com.daydreamvr.playback.DecoderCapsProvider
import com.daydreamvr.playback.InMemoryResumeStore
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

    val decoderCapsProvider: DecoderCapsProvider = DecoderCapsProvider()
}
