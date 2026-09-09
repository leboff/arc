package com.daydreamvr.upnp.cds

import com.daydreamvr.upnp.model.BrowseResult
import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.upnp.model.PageRequest

/** Reads folder listings from a [MediaServer]'s ContentDirectory (ARCHITECTURE.md §9.1). */
interface ContentDirectoryClient {

    suspend fun browse(
        server: MediaServer,
        objectId: String = "0",
        page: PageRequest = PageRequest.DEFAULT,
    ): Result<BrowseResult>

    suspend fun search(
        server: MediaServer,
        containerId: String,
        query: String,
    ): Result<BrowseResult>
}
