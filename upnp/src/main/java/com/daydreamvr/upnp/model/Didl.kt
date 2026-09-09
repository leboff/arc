package com.daydreamvr.upnp.model

import kotlinx.serialization.Serializable
import java.net.URI

/** Pixel dimensions parsed from a `res@resolution` ("1920x1080"). */
@Serializable
data class Size(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height.toLong()
}

/** Common shape of a DIDL-Lite `<container>` / `<item>`. */
sealed interface DidlObject {
    val id: String
    val parentId: String
    val title: String
    val upnpClass: String
}

data class DidlContainer(
    override val id: String,
    override val parentId: String,
    override val title: String,
    override val upnpClass: String,
    val childCount: Int?,
) : DidlObject

data class DidlItem(
    override val id: String,
    override val parentId: String,
    override val title: String,
    override val upnpClass: String,
    val resources: List<Resource>,
    val durationMs: Long?,
    val mimeType: String?,
    val resolution: Size?,
    val albumArtUri: URI?,
    val sizeBytes: Long?,
) : DidlObject {
    /**
     * Only `object.item.videoItem*` classes are playable by this app
     * (ARCHITECTURE.md §9.5). Music, images and text items are listed but not
     * offered for playback.
     */
    val isPlayableVideo: Boolean
        get() = upnpClass.startsWith("object.item.videoItem")
}

data class Resource(
    val uri: URI,
    val protocolInfo: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val resolution: Size?,
    val bitrate: Int?,
) {
    private val protocolFields: List<String> = protocolInfo.split(':')

    /** Lower-cased transport, e.g. `http-get`, `rtsp-rtp-udp`, `internal`. */
    val protocol: String?
        get() = protocolFields.getOrNull(0)?.trim()?.lowercase()?.ifBlank { null }

    /** The MIME type field of `protocolInfo`, or null when absent / wildcard. */
    val mimeType: String?
        get() = protocolFields.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() && it != "*" }

    /** The 4th `protocolInfo` field ("DLNA.ORG_PN=...;DLNA.ORG_OP=01"). */
    val dlnaAttributes: String?
        get() = protocolFields.drop(3).joinToString(":").ifBlank { null }

    val isHttpGet: Boolean get() = protocol == null || protocol == "http-get"

    val isThumbnail: Boolean
        get() = dlnaAttributes?.contains("JPEG_TN") == true || dlnaAttributes?.contains("PNG_TN") == true
}
