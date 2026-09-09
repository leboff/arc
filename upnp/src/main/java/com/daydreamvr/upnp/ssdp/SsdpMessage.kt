package com.daydreamvr.upnp.ssdp

/**
 * A parsed SSDP datagram — either an M-SEARCH response (`HTTP/1.1 200 OK`) or a
 * `NOTIFY` advertisement (`ssdp:alive` / `ssdp:byebye` / `ssdp:update`).
 * Header keys are upper-cased so lookups are case-insensitive.
 */
data class SsdpMessage(
    val kind: Kind,
    val headers: Map<String, String>,
    val remoteAddress: String? = null,
) {
    enum class Kind { SEARCH_RESPONSE, NOTIFY_ALIVE, NOTIFY_BYEBYE, NOTIFY_UPDATE, UNKNOWN }

    val location: String? get() = headers["LOCATION"]
    val usn: String? get() = headers["USN"]
    val server: String? get() = headers["SERVER"]

    /** Search target (responses) or notification type (NOTIFY). */
    val target: String? get() = headers["ST"] ?: headers["NT"]

    /** UDN extracted from the USN, e.g. `uuid:abcd-...` (the part before `::`). */
    val udn: String?
        get() = usn?.substringBefore("::")?.trim()?.takeIf { it.isNotEmpty() }

    val maxAgeSeconds: Long?
        get() = headers["CACHE-CONTROL"]
            ?.substringAfter("max-age", "")
            ?.dropWhile { it == '=' || it == ' ' }
            ?.takeWhile { it.isDigit() }
            ?.toLongOrNull()

    val isByeBye: Boolean get() = kind == Kind.NOTIFY_BYEBYE

    companion object {
        const val DEFAULT_HOST = "239.255.255.250:1900"
        const val USER_AGENT = "Android/16 UPnP/1.0 DaydreamVrPlayer/1.0"
        const val MAN = "\"ssdp:discover\""

        private val CRLF = "\r\n"

        /**
         * Builds the exact M-SEARCH datagram from ARCHITECTURE.md §9.2 — CRLF line
         * endings, a trailing blank line, header order fixed (some servers are
         * strict). Pinned by `SsdpMessageTest`.
         */
        fun buildSearchRequest(
            searchTarget: String,
            mxSeconds: Int = 3,
            host: String = DEFAULT_HOST,
            userAgent: String = USER_AGENT,
        ): ByteArray = buildString {
            append("M-SEARCH * HTTP/1.1").append(CRLF)
            append("HOST: ").append(host).append(CRLF)
            append("MAN: ").append(MAN).append(CRLF)
            append("MX: ").append(mxSeconds).append(CRLF)
            append("ST: ").append(searchTarget).append(CRLF)
            append("USER-AGENT: ").append(userAgent).append(CRLF)
            append(CRLF)
        }.toByteArray(Charsets.US_ASCII)

        fun parse(raw: String, remoteAddress: String? = null): SsdpMessage? {
            val normalized = raw.replace("\r\n", "\n").trimStart()
            val lines = normalized.split("\n")
            if (lines.isEmpty()) return null
            val startLine = lines.first().trim()

            val kind = when {
                startLine.startsWith("HTTP/1.", ignoreCase = true) -> Kind.SEARCH_RESPONSE
                startLine.startsWith("NOTIFY", ignoreCase = true) -> Kind.UNKNOWN // refined below
                startLine.startsWith("M-SEARCH", ignoreCase = true) -> return null // our own request
                else -> return null
            }

            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim().uppercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }

            val resolvedKind = if (kind == Kind.SEARCH_RESPONSE) {
                kind
            } else {
                when (headers["NTS"]?.lowercase()) {
                    "ssdp:alive" -> Kind.NOTIFY_ALIVE
                    "ssdp:byebye" -> Kind.NOTIFY_BYEBYE
                    "ssdp:update" -> Kind.NOTIFY_UPDATE
                    else -> Kind.UNKNOWN
                }
            }
            return SsdpMessage(resolvedKind, headers, remoteAddress)
        }
    }
}
