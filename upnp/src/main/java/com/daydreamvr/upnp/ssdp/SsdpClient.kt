package com.daydreamvr.upnp.ssdp

import com.daydreamvr.upnp.UpnpLog
import com.daydreamvr.upnp.net.DatagramChannelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * SSDP discovery / passive notification listening. All socket work is delegated
 * to a [DatagramChannelProvider] so the module stays Android-free
 * (ARCHITECTURE.md §9.2). UDP multicast is lossy, so M-SEARCH is sent three times
 * at 0 / 250 / 750 ms and the socket is read for the full timeout window.
 */
class SsdpClient(
    private val channels: DatagramChannelProvider,
    private val log: UpnpLog = UpnpLog.NoOp,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * Emits [SsdpMessage]s as responses arrive, deduplicated by USN. `ssdp:byebye`
     * / `ssdp:alive` NOTIFYs seen inside the window are forwarded as-is (a server
     * that leaves mid-discovery should be reflected). The flow completes when
     * [timeout] elapses.
     */
    fun search(
        searchTargets: List<String> = DEFAULT_TARGETS,
        timeout: Duration = 5.seconds,
        mxSeconds: Int = 3,
    ): Flow<SsdpMessage> = callbackFlow {
        val worker = launch {
            channels.withMulticastSocket { socket, nif ->
                val group = InetAddress.getByName(GROUP)
                val seenUsn = HashSet<String>()

                val sender = launch {
                    var previousOffset = 0L
                    for (offset in SEND_OFFSETS_MS) {
                        sleep(offset - previousOffset)
                        previousOffset = offset
                        for (target in searchTargets) {
                            val bytes = SsdpMessage.buildSearchRequest(target, mxSeconds)
                            runCatching {
                                socket.send(DatagramPacket(bytes, bytes.size, group, PORT))
                            }.onFailure { log.w(TAG, "M-SEARCH send failed", it) }
                        }
                    }
                }

                val deadline = nowMs() + timeout.inWholeMilliseconds
                try {
                    val buffer = ByteArray(RECEIVE_BUFFER)
                    while (coroutineContext.isActive && nowMs() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val message = SsdpMessage.parse(text, packet.address?.hostAddress) ?: continue
                        when (message.kind) {
                            SsdpMessage.Kind.SEARCH_RESPONSE -> {
                                val usn = message.usn ?: continue
                                if (seenUsn.add(usn)) trySend(message)
                            }
                            SsdpMessage.Kind.NOTIFY_ALIVE,
                            SsdpMessage.Kind.NOTIFY_BYEBYE,
                            -> trySend(message)
                            else -> Unit
                        }
                    }
                } finally {
                    sender.cancel()
                }
            }
            close()
        }
        awaitClose { worker.cancel(CancellationException("search flow closed")) }
    }.flowOn(Dispatchers.IO)

    /**
     * Passive listener for `NOTIFY` advertisements while the browser screen is
     * open, so servers that boot after us appear without a manual refresh.
     */
    fun listenForNotifications(): Flow<SsdpMessage> = callbackFlow {
        val worker = launch {
            channels.withMulticastSocket { socket, _ ->
                val buffer = ByteArray(RECEIVE_BUFFER)
                while (coroutineContext.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val message = SsdpMessage.parse(text, packet.address?.hostAddress) ?: continue
                    if (message.kind == SsdpMessage.Kind.NOTIFY_ALIVE ||
                        message.kind == SsdpMessage.Kind.NOTIFY_BYEBYE
                    ) {
                        trySend(message)
                    }
                }
            }
        }
        awaitClose { worker.cancel() }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val GROUP = "239.255.255.250"
        const val PORT = 1900

        /** M-SEARCH retransmission schedule (ARCHITECTURE.md §9.2). */
        val SEND_OFFSETS_MS = longArrayOf(0L, 250L, 750L)

        val DEFAULT_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "ssdp:all",
            "upnp:rootdevice",
        )

        private const val RECEIVE_BUFFER = 16 * 1024
        private const val TAG = "SsdpClient"
    }
}
