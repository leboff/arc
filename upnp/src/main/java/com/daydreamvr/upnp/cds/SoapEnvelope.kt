package com.daydreamvr.upnp.cds

import com.daydreamvr.upnp.model.UpnpError
import com.daydreamvr.upnp.net.SafeXml
import com.daydreamvr.upnp.net.SafeXml.childElements
import com.daydreamvr.upnp.net.SafeXml.descendantsNamed

/** A parsed SOAP action response: the direct children of `<u:...Response>` as text. */
data class SoapResponse(val action: String, val args: Map<String, String>) {
    operator fun get(name: String): String? = args[name]
}

/** A parsed `<UPnPError>` fault body. */
data class SoapFault(val errorCode: Int, val errorDescription: String?)

/**
 * SOAP 1.1 request building and response/fault parsing for ContentDirectory
 * (ARCHITECTURE.md §9.4). Requests use CRLF line endings and a fixed element
 * order — pinned by a golden in `ContentDirectoryClientTest`.
 */
object SoapEnvelope {

    private const val CRLF = "\r\n"
    const val CONTENT_TYPE = "text/xml; charset=\"utf-8\""

    fun soapAction(serviceType: String, action: String): String = "\"$serviceType#$action\""

    fun browseRequest(
        serviceType: String,
        objectId: String,
        browseFlag: String = "BrowseDirectChildren",
        filter: String = "*",
        startingIndex: Int,
        requestedCount: Int,
        sortCriteria: String = "",
    ): ByteArray = envelope(
        serviceType = serviceType,
        action = "Browse",
        arguments = listOf(
            "ObjectID" to objectId,
            "BrowseFlag" to browseFlag,
            "Filter" to filter,
            "StartingIndex" to startingIndex.toString(),
            "RequestedCount" to requestedCount.toString(),
            "SortCriteria" to sortCriteria,
        ),
    )

    fun searchRequest(
        serviceType: String,
        containerId: String,
        searchCriteria: String,
        filter: String = "*",
        startingIndex: Int,
        requestedCount: Int,
        sortCriteria: String = "",
    ): ByteArray = envelope(
        serviceType = serviceType,
        action = "Search",
        arguments = listOf(
            "ContainerID" to containerId,
            "SearchCriteria" to searchCriteria,
            "Filter" to filter,
            "StartingIndex" to startingIndex.toString(),
            "RequestedCount" to requestedCount.toString(),
            "SortCriteria" to sortCriteria,
        ),
    )

    private fun envelope(
        serviceType: String,
        action: String,
        arguments: List<Pair<String, String>>,
    ): ByteArray {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>").append(CRLF)
            append(
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">",
            ).append(CRLF)
            append("<s:Body>").append(CRLF)
            append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">").append(CRLF)
            for ((name, value) in arguments) {
                append("<").append(name).append(">")
                append(escapeXml(value))
                append("</").append(name).append(">").append(CRLF)
            }
            append("</u:").append(action).append(">").append(CRLF)
            append("</s:Body>").append(CRLF)
            append("</s:Envelope>").append(CRLF)
        }
        return body.toByteArray(Charsets.UTF_8)
    }

    /** Extracts the arguments of `<u:{action}Response>` (or the first Body child). */
    fun parseResponse(xml: String, action: String): SoapResponse {
        val doc = SafeXml.parse(xml)
        val body = doc.documentElement.descendantsNamed("Body").firstOrNull()
            ?: throw UpnpError.MalformedResponse("SOAP response has no <Body>")
        val responseEl = body.childElements().firstOrNull { SafeXml.localName(it) == "${action}Response" }
            ?: body.childElements().firstOrNull()
            ?: throw UpnpError.MalformedResponse("SOAP <Body> is empty")
        val args = responseEl.childElements().associate { child ->
            SafeXml.localName(child) to child.textContent
        }
        return SoapResponse(action, args)
    }

    /** Returns a [SoapFault] when [xml] carries a `<UPnPError>`, else null. */
    fun parseFault(xml: String): SoapFault? {
        val doc = runCatching { SafeXml.parse(xml) }.getOrNull() ?: return null
        val error = doc.documentElement.descendantsNamed("UPnPError").firstOrNull() ?: return null
        val code = error.descendantsNamed("errorCode").firstOrNull()?.textContent?.trim()?.toIntOrNull()
            ?: return null
        val description = error.descendantsNamed("errorDescription").firstOrNull()?.textContent?.trim()
        return SoapFault(code, description?.ifEmpty { null })
    }

    fun escapeXml(value: String): String = buildString(value.length + 16) {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
