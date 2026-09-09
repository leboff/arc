package com.daydreamvr.upnp

import com.daydreamvr.upnp.device.DeviceDescriptionParser
import com.daydreamvr.upnp.model.MediaServer
import com.daydreamvr.upnp.model.UpnpError
import com.daydreamvr.upnp.net.HttpTransport
import com.daydreamvr.upnp.ssdp.SsdpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Discovers servers and returns parsed folder listings (ARCHITECTURE.md §9.1). */
interface MediaServerDirectory {
    val servers: StateFlow<List<MediaServer>>
    suspend fun discover(timeout: Duration = 5.seconds)
    suspend fun addManual(hostPort: String): Result<MediaServer>
    suspend fun refresh(server: MediaServer): Result<MediaServer>
}

class MediaServerDirectoryImpl(
    private val ssdp: SsdpClient,
    private val http: HttpTransport,
    private val scope: CoroutineScope,
    private val log: UpnpLog = UpnpLog.NoOp,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MediaServerDirectory {

    private val _servers = MutableStateFlow<List<MediaServer>>(emptyList())
    override val servers: StateFlow<List<MediaServer>> = _servers.asStateFlow()

    /**
     * Re-probes previously known servers (from persistence) in parallel, so a user
     * with a fixed server IP is browsing before SSDP even finishes
     * (ARCHITECTURE.md §9.6). Call this before [discover].
     */
    suspend fun reprobeKnown(descriptionUrls: List<URI>) = coroutineScope {
        descriptionUrls.map { url ->
            async { fetchDescription(url).onSuccess(::upsert) }
        }.awaitAll()
        Unit
    }

    override suspend fun discover(timeout: Duration) {
        ssdp.search(timeout = timeout).collect { message ->
            when {
                message.isByeBye -> message.udn?.let(::remove)
                else -> {
                    val location = message.location?.let { runCatching { URI(it) }.getOrNull() } ?: return@collect
                    scope.launch {
                        fetchDescription(location)
                            .onSuccess { srv ->
                                log.d("Directory", "Discovered: ${srv.friendlyName} ($location)")
                                upsert(srv)
                            }
                            .onFailure { err ->
                                log.w("Directory", "Failed to parse $location: ${err.message}", err)
                            }
                    }
                }
            }
        }
    }

    override suspend fun addManual(hostPort: String): Result<MediaServer> {
        val trimmed = hostPort.trim()
        val direct = if (looksLikeDescriptionUrl(trimmed)) {
            fetchDescription(URI(trimmed))
        } else {
            val base = baseUriOf(trimmed)
                ?: return Result.failure(UpnpError.NotAMediaServer("cannot parse host:port '$hostPort'"))
            probeCandidates(trimmed, base)
        }
        return direct.onSuccess(::upsert)
    }

    override suspend fun refresh(server: MediaServer): Result<MediaServer> =
        fetchDescription(server.descriptionUrl).onSuccess(::upsert)

    // ---- internals ------------------------------------------------------------

    private suspend fun probeCandidates(input: String, defaultBase: URI): Result<MediaServer> = coroutineScope {
        val hasPort = input.removePrefix("http://").removePrefix("https://").contains(":")
        val baseUris = if (hasPort) {
            listOf(defaultBase)
        } else {
            val host = defaultBase.host
            listOfNotNull(
                defaultBase,
                host?.let { URI("http://$it:49152/") }, // Gerbera default
                host?.let { URI("http://$it:8200/") },  // MiniDLNA
                host?.let { URI("http://$it:32469/") }, // Plex DLNA
                host?.let { URI("http://$it:8096/") },  // Jellyfin / Emby
            )
        }
        val attempts = baseUris.flatMap { b ->
            PROBE_PATHS.map { path ->
                async {
                    withTimeoutOrNull(PROBE_BUDGET_MS) {
                        fetchDescription(b.resolve(path)).getOrNull()
                    }
                }
            }
        }
        val found = attempts.awaitAll().firstOrNull { it != null }
        found?.let { Result.success(it) }
            ?: Result.failure(UpnpError.NotAMediaServer("no MediaServer under $input"))
    }

    private suspend fun fetchDescription(location: URI): Result<MediaServer> = runCatching {
        val response = http.get(location)
        if (response.status != 200) throw UpnpError.HttpStatus(response.status)
        val text = response.bodyString()
        if (!text.contains(MEDIA_SERVER_MARKER)) {
            throw UpnpError.NotAMediaServer("$location is not a UPnP MediaServer")
        }
        DeviceDescriptionParser.parse(text, location).getOrThrow().copy(lastSeenEpochMs = nowMs())
    }

    private fun upsert(server: MediaServer) {
        _servers.update { current ->
            val without = current.filterNot { it.udn == server.udn }
            (without + server).sortedBy { it.friendlyName.lowercase() }
        }
    }

    private fun remove(udn: String) {
        _servers.update { current -> current.filterNot { it.udn == udn } }
    }

    private fun looksLikeDescriptionUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) && value.endsWith(".xml", ignoreCase = true)

    private fun baseUriOf(hostPort: String): URI? {
        val withScheme = if (hostPort.startsWith("http://", ignoreCase = true)) hostPort else "http://$hostPort"
        return runCatching {
            val u = URI(withScheme)
            URI("http", null, u.host ?: return null, if (u.port > 0) u.port else 80, "/", null, null)
        }.getOrNull()
    }

    companion object {
        const val MEDIA_SERVER_MARKER = "urn:schemas-upnp-org:device:MediaServer"

        /** Probe order covers Gerbera, MiniDLNA, Synology, Plex, UMS, Jellyfin (ARCHITECTURE.md §9.6). */
        val PROBE_PATHS = listOf(
            "/upnp/description.xml",
            "/description.xml",
            "/rootDesc.xml",
            "/dev/description.xml",
            "/DeviceDescription.xml",
            "/upnp/desc.xml",
            "/",
        )

        private const val PROBE_BUDGET_MS = 4_000L
    }
}
