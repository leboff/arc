package com.daydreamvr.upnp.model

import java.net.URI

/**
 * A discovered (or manually added) UPnP MediaServer with a usable
 * ContentDirectory control endpoint. See ARCHITECTURE.md §9.1.
 */
data class MediaServer(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val descriptionUrl: URI,
    val controlUrl: URI,
    /** e.g. `urn:schemas-upnp-org:service:ContentDirectory:1` (any version accepted). */
    val contentDirectoryServiceType: String,
    val searchCapabilities: Set<String> = emptySet(),
    val sortCapabilities: Set<String> = emptySet(),
    val iconUrl: URI? = null,
    val lastSeenEpochMs: Long = 0L,
) {
    val supportsTitleSort: Boolean
        get() = sortCapabilities.any { it.equals("dc:title", ignoreCase = true) || it == "*" }
}
