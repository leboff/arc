package com.daydreamvr.upnp.cds

import com.daydreamvr.upnp.UpnpLog
import com.daydreamvr.upnp.model.BrowseResult
import com.daydreamvr.upnp.model.DidlContainer
import com.daydreamvr.upnp.model.DidlItem
import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.upnp.model.PageRequest
import com.daydreamvr.upnp.model.UpnpError
import com.daydreamvr.upnp.net.HttpTransport

/**
 * SOAP ContentDirectory client (ARCHITECTURE.md §9.4). The `<Result>` payload is
 * XML-escaped DIDL-Lite inside the SOAP envelope, so we parse the envelope, take
 * the `<Result>` text, unescape, and parse again via [DidlParser].
 */
class ContentDirectoryClientImpl(
    private val http: HttpTransport,
    private val log: UpnpLog = UpnpLog.NoOp,
) : ContentDirectoryClient {

    override suspend fun browse(
        server: MediaServer,
        objectId: String,
        page: PageRequest,
    ): Result<BrowseResult> = runCatching {
        val body = SoapEnvelope.browseRequest(
            serviceType = server.contentDirectoryServiceType,
            objectId = objectId,
            startingIndex = page.start,
            requestedCount = page.count,
        )
        val response = http.post(
            url = server.controlUrl,
            body = body,
            contentType = SoapEnvelope.CONTENT_TYPE,
            headers = mapOf("SOAPACTION" to SoapEnvelope.soapAction(server.contentDirectoryServiceType, "Browse")),
        )
        val text = response.bodyString()

        if (response.status != 200) {
            SoapEnvelope.parseFault(text)?.let { fault ->
                throw UpnpError.fromFaultCode(fault.errorCode, fault.errorDescription)
            }
            throw UpnpError.HttpStatus(response.status)
        }

        // A 200 can still carry a fault on some servers.
        SoapEnvelope.parseFault(text)?.let { fault ->
            throw UpnpError.fromFaultCode(fault.errorCode, fault.errorDescription)
        }

        val args = SoapEnvelope.parseResponse(text, "Browse")
        val didl = DidlParser.unescapeResultPayload(args["Result"].orEmpty())
        val objects = DidlParser.parse(didl).getOrElse { cause ->
            throw UpnpError.MalformedResponse("could not parse DIDL-Lite: ${cause.message}", cause)
        }
        val containers = objects.filterIsInstance<DidlContainer>()
        val items = objects.filterIsInstance<DidlItem>()
        BrowseResult(
            objectId = objectId,
            containers = containers,
            items = items,
            numberReturned = args["NumberReturned"]?.trim()?.toIntOrNull() ?: (containers.size + items.size),
            totalMatches = args["TotalMatches"]?.trim()?.toIntOrNull() ?: 0,
            updateId = args["UpdateID"]?.trim()?.toLongOrNull() ?: 0L,
        )
    }

    override suspend fun search(
        server: MediaServer,
        containerId: String,
        query: String,
    ): Result<BrowseResult> = runCatching {
        val body = SoapEnvelope.searchRequest(
            serviceType = server.contentDirectoryServiceType,
            containerId = containerId,
            searchCriteria = query,
            startingIndex = 0,
            requestedCount = PageRequest.DEFAULT.count,
        )
        val response = http.post(
            url = server.controlUrl,
            body = body,
            contentType = SoapEnvelope.CONTENT_TYPE,
            headers = mapOf("SOAPACTION" to SoapEnvelope.soapAction(server.contentDirectoryServiceType, "Search")),
        )
        val text = response.bodyString()
        if (response.status != 200) {
            SoapEnvelope.parseFault(text)?.let { fault ->
                throw UpnpError.fromFaultCode(fault.errorCode, fault.errorDescription)
            }
            throw UpnpError.HttpStatus(response.status)
        }
        val args = SoapEnvelope.parseResponse(text, "Search")
        val objects = DidlParser.parse(DidlParser.unescapeResultPayload(args["Result"].orEmpty())).getOrThrow()
        val containers = objects.filterIsInstance<DidlContainer>()
        val items = objects.filterIsInstance<DidlItem>()
        BrowseResult(
            objectId = containerId,
            containers = containers,
            items = items,
            numberReturned = args["NumberReturned"]?.trim()?.toIntOrNull() ?: (containers.size + items.size),
            totalMatches = args["TotalMatches"]?.trim()?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Pages through a container until the server stops returning rows. Trusts
     * `NumberReturned` for the loop and tolerates `TotalMatches = 0`
     * (ARCHITECTURE.md §9.4).
     */
    suspend fun browseAll(
        server: MediaServer,
        objectId: String,
        pageSize: Int = 200,
    ): Result<BrowseResult> = runCatching {
        val containers = ArrayList<DidlContainer>()
        val items = ArrayList<DidlItem>()
        var start = 0
        var total = 0
        var guard = 0
        while (true) {
            val page = browse(server, objectId, PageRequest(start, pageSize)).getOrThrow()
            containers += page.containers
            items += page.items
            total = maxOf(total, page.totalMatches)
            val received = page.numberReturned.takeIf { it > 0 }
                ?: (page.containers.size + page.items.size)
            if (received == 0) break
            start += received
            if (total in 1..start) break
            if (++guard > MAX_PAGES) {
                log.w(TAG, "browseAll($objectId) hit page guard at $start")
                break
            }
        }
        BrowseResult(
            objectId = objectId,
            containers = containers,
            items = items,
            numberReturned = containers.size + items.size,
            totalMatches = if (total > 0) total else containers.size + items.size,
        )
    }

    private companion object {
        const val MAX_PAGES = 1000
        const val TAG = "ContentDirectoryClient"
    }
}
