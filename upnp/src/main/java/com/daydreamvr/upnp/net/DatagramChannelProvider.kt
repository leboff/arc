package com.daydreamvr.upnp.net

import com.daydreamvr.upnp.ssdp.SsdpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * The seam that keeps `:upnp` Android-free (ARCHITECTURE.md §9.2). The Android
 * implementation (`AndroidNetworkBinder` in `:app`) holds a `WifiManager`
 * MulticastLock and binds the socket to the Wi-Fi network for the duration of
 * [withMulticastSocket]; a plain-JVM implementation ([DefaultDatagramChannelProvider])
 * is used by test harnesses and the manual-add path.
 */
interface DatagramChannelProvider {
    /**
     * Opens a [MulticastSocket] bound to the LAN interface and joined to the SSDP
     * group `239.255.255.250:1900`, invokes [block], and tears everything down
     * (socket closed, multicast lock released) before returning — even on failure.
     */
    suspend fun <T> withMulticastSocket(
        block: suspend (MulticastSocket, NetworkInterface) -> T,
    ): T
}

/**
 * Plain-JVM provider: picks the first non-loopback, up, multicast-capable IPv4
 * interface. Fine for a developer machine or an integration harness; on a phone
 * use `AndroidNetworkBinder` so the multicast lock and Wi-Fi binding are held.
 */
class DefaultDatagramChannelProvider(
    private val preferredInterface: NetworkInterface? = null,
) : DatagramChannelProvider {

    override suspend fun <T> withMulticastSocket(
        block: suspend (MulticastSocket, NetworkInterface) -> T,
    ): T {
        val nif = preferredInterface ?: firstUsableInterface()
            ?: error("No multicast-capable network interface found")
        val group = InetAddress.getByName(SsdpClient.GROUP)
        val socket = MulticastSocket(SsdpClient.PORT).apply {
            reuseAddress = true
            soTimeout = POLL_TIMEOUT_MS
            runCatching { networkInterface = nif }
            timeToLive = 4
            joinGroup(InetSocketAddress(group, SsdpClient.PORT), nif)
        }
        return try {
            block(socket, nif)
        } finally {
            runCatching { socket.leaveGroup(InetSocketAddress(group, SsdpClient.PORT), nif) }
            runCatching { socket.close() }
        }
    }

    private fun firstUsableInterface(): NetworkInterface? =
        NetworkInterface.getNetworkInterfaces().toList().firstOrNull { nif ->
            runCatching {
                nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                    nif.inetAddresses.toList().any { it.address.size == 4 }
            }.getOrDefault(false)
        }

    private companion object {
        const val POLL_TIMEOUT_MS = 250
    }
}
