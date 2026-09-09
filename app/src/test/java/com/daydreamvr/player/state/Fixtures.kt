package com.daydreamvr.player.state

import com.daydreamvr.playback.PlaybackSnapshot
import com.daydreamvr.upnp.model.BrowseResult
import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.upnp.model.Resource
import com.daydreamvr.vrcore.input.InputAction
import java.net.URI

/**
 * Shared builders + a tiny state-machine driver for the pure reducer tests.
 * Everything here is JVM-only: no Android, no clock, no IO.
 */
object Fx {

    fun server(udn: String = "uuid:s1", name: String = "Server ${udn.substringAfter(':')}") = MediaServer(
        udn = udn,
        friendlyName = name,
        manufacturer = null,
        modelName = null,
        descriptionUrl = URI("http://10.0.0.2:8200/desc.xml"),
        controlUrl = URI("http://10.0.0.2:8200/ctl/ContentDirectory"),
        contentDirectoryServiceType = "urn:schemas-upnp-org:service:ContentDirectory:1",
    )

    fun container(id: String, title: String = "Folder $id", parent: String = "0") = DidlContainer(
        id = id,
        parentId = parent,
        title = title,
        upnpClass = "object.container.storageFolder",
        childCount = null,
    )

    fun video(id: String, title: String = "Video $id", parent: String = "0") = DidlItem(
        id = id,
        parentId = parent,
        title = title,
        upnpClass = "object.item.videoItem",
        resources = listOf(
            Resource(URI("http://10.0.0.2:8200/$id.mp4"), "http-get:*:video/mp4:*", null, null, null, null),
        ),
        durationMs = 60_000L,
        mimeType = "video/mp4",
        resolution = null,
        albumArtUri = null,
        sizeBytes = null,
    )

    fun videos(n: Int, from: Int = 1): List<DidlItem> = (from until from + n).map { video("v$it") }

    fun result(
        objectId: String,
        containers: List<DidlContainer> = emptyList(),
        items: List<DidlItem> = emptyList(),
        total: Int = containers.size + items.size,
    ) = BrowseResult(objectId, containers, items, containers.size + items.size, total)

    fun playing(itemKey: String, title: String = "Video $itemKey") =
        PlaybackSnapshot(itemKey = itemKey, title = title, isPlaying = true, durationMs = 60_000L)

    // input shorthands ------------------------------------------------------
    val up = InputAction.Nav(InputAction.Dir.UP, repeat = false)
    val down = InputAction.Nav(InputAction.Dir.DOWN, repeat = false)
    val left = InputAction.Nav(InputAction.Dir.LEFT, repeat = false)
    val right = InputAction.Nav(InputAction.Dir.RIGHT, repeat = false)
    val confirm = InputAction.Confirm(long = false)
    val cancel = InputAction.Cancel(long = false)
    val longCancel = InputAction.Cancel(long = true)
}

/** Threads [AppState] + collected effects through [AppStateMachine.reduce] one event at a time. */
class Driver(initial: AppState = AppState.INITIAL) {

    var state: AppState = initial
        private set

    /** Effects produced by the most recent [send]. */
    var stepEffects: List<Effect> = emptyList()
        private set

    fun send(event: Event): Driver {
        val (next, fx) = AppStateMachine.reduce(state, event)
        state = next
        stepEffects = fx
        return this
    }

    fun input(action: InputAction): Driver = send(Event.Input(action))

    fun tick(nowMs: Long): Driver = send(Event.Tick(nowMs))
}
