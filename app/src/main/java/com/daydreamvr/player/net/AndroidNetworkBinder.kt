package com.daydreamvr.player.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.daydreamvr.upnp.net.DatagramChannelProvider
import com.daydreamvr.upnp.ssdp.SsdpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * The Android side of the [DatagramChannelProvider] seam (ARCHITECTURE.md §9.2).
 * For the duration of [withMulticastSocket] it:
 *
 *  1. holds a [WifiManager.MulticastLock] (without it the Wi-Fi chip filters
 *     multicast frames and discovery silently returns nothing);
 *  2. resolves the active Wi-Fi interface (preferring wlan/eth over cellular);
 *  3. binds an ephemeral [MulticastSocket] with broadcast enabled and joins
 *     the SSDP multicast group on the Wi-Fi interface.
 *
 * All resources are released/closed before returning.
 */
class AndroidNetworkBinder(
    context: Context,
    private val networkRequestTimeoutMs: Long = 2_000L,
) : DatagramChannelProvider {

    private val appContext = context.applicationContext
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun <T> withMulticastSocket(
        block: suspend (MulticastSocket, NetworkInterface) -> T,
    ): T {
        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        var released = false
        var socket: MulticastSocket? = null

        try {
            val network = findWifiNetwork()
            val nif = wifiNetworkInterface(network)
                ?: firstMulticastInterface()
                ?: error("no multicast-capable network interface")

            Log.i(TAG, "Opening multicast socket on interface: ${nif.name} (display: ${nif.displayName}) network=$network")

            val group = InetAddress.getByName(SsdpClient.GROUP)
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                // Bind to ephemeral port: UPnP M-SEARCH responses are unicast to the sender's source port.
                // Binding port 1900 breaks unicast reception on Android due to system/Play Services conflicts.
                bind(null)
                soTimeout = POLL_TIMEOUT_MS
                timeToLive = 4
                network?.let { net -> runCatching { net.bindSocket(this) } }
                runCatching { networkInterface = nif }
                runCatching { joinGroup(InetSocketAddress(group, SsdpClient.PORT), nif) }
            }

            return block(socket, nif)
        } finally {
            socket?.let { s ->
                runCatching {
                    val group = InetAddress.getByName(SsdpClient.GROUP)
                    val nif = wifiNetworkInterface(null)
                    s.leaveGroup(InetSocketAddress(group, SsdpClient.PORT), nif)
                }
                runCatching { s.close() }
            }
            if (!released && lock.isHeld) {
                runCatching { lock.release() }
                released = true
            }
        }
    }

    /**
     * Finds the active Wi-Fi [Network] immediately without waiting for an asynchronous callback.
     */
    private fun findWifiNetwork(): Network? {
        val active = runCatching { connectivityManager.activeNetwork }.getOrNull()
        if (active != null) {
            val caps = runCatching { connectivityManager.getNetworkCapabilities(active) }.getOrNull()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return active
            }
        }
        return runCatching {
            connectivityManager.allNetworks.firstOrNull { net ->
                connectivityManager.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrNull() ?: active
    }

    private fun wifiNetworkInterface(network: Network?): NetworkInterface? {
        val linkProperties = network?.let { runCatching { connectivityManager.getLinkProperties(it) }.getOrNull() }
        val ifName = linkProperties?.interfaceName
        if (ifName != null) {
            val nif = runCatching { NetworkInterface.getByName(ifName) }.getOrNull()
            if (nif != null && nif.isUp) return nif
        }
        return firstMulticastInterface()
    }

    /**
     * Scans interfaces and strongly prefers Wi-Fi (wlan*) or Ethernet (eth*), avoiding
     * cellular interfaces (rmnet*) which can steal multicast packets on phones.
     */
    private fun firstMulticastInterface(): NetworkInterface? =
        runCatching {
            val all = NetworkInterface.getNetworkInterfaces().toList()
            val upAndMulticast = all.filter { nif ->
                runCatching {
                    nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                        nif.inetAddresses.asSequence().any { it.address.size == 4 }
                }.getOrDefault(false)
            }
            upAndMulticast.firstOrNull { it.name.startsWith("wlan", ignoreCase = true) }
                ?: upAndMulticast.firstOrNull { it.name.startsWith("eth", ignoreCase = true) }
                ?: upAndMulticast.firstOrNull { !it.name.startsWith("rmnet", ignoreCase = true) && !it.name.startsWith("dummy", ignoreCase = true) }
                ?: upAndMulticast.firstOrNull()
        }.getOrNull()

    private companion object {
        const val MULTICAST_LOCK_TAG = "vrplayer-ssdp"
        const val POLL_TIMEOUT_MS = 250
        const val TAG = "AndroidNetworkBinder"
    }
}
