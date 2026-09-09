package com.daydreamvr.upnp.model

/**
 * Typed failures crossing the `:upnp` boundary. Nothing throws across a module
 * edge (ARCHITECTURE.md §15) — these are carried inside `Result.failure`.
 */
sealed class UpnpError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** UPnP fault 701. */
    class NoSuchObject(val objectId: String? = null) :
        UpnpError("No such object" + (objectId?.let { ": $it" } ?: ""))

    /** UPnP fault 402. */
    class InvalidArgs(val detail: String? = null) :
        UpnpError("Invalid arguments" + (detail?.let { ": $it" } ?: ""))

    /** UPnP fault 720. */
    class CannotProcess(val detail: String? = null) :
        UpnpError("Cannot process the request" + (detail?.let { ": $it" } ?: ""))

    /** Any other `<UPnPError>` code. */
    class ActionFailed(val code: Int, val detail: String? = null) :
        UpnpError("UPnP action failed ($code)" + (detail?.let { ": $it" } ?: ""))

    /** Non-fault HTTP error status with no parseable UPnP fault body. */
    class HttpStatus(val status: Int) : UpnpError("HTTP $status")

    /** Response body exceeded the size cap (ARCHITECTURE.md §16.1). */
    class ResponseTooLarge(val limitBytes: Long) :
        UpnpError("Response exceeded $limitBytes bytes")

    /** XML that could not be parsed, or was rejected by the hardened parser. */
    class MalformedResponse(detail: String, cause: Throwable? = null) :
        UpnpError(detail, cause)

    /** A host answered but is not a UPnP MediaServer. */
    class NotAMediaServer(detail: String) : UpnpError(detail)

    /** Socket / connection / timeout failure. */
    class Transport(detail: String, cause: Throwable? = null) : UpnpError(detail, cause)

    companion object {
        /** Maps the well-known ContentDirectory fault codes (ARCHITECTURE.md §9.4). */
        fun fromFaultCode(code: Int, detail: String? = null): UpnpError = when (code) {
            701 -> NoSuchObject(detail)
            402 -> InvalidArgs(detail)
            720 -> CannotProcess(detail)
            else -> ActionFailed(code, detail)
        }
    }
}
