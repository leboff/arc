package com.daydreamvr.player.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import com.daydreamvr.upnp.net.DatagramChannelProvider
import com.daydreamvr.upnp.ssdp.SsdpClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * The Android side of the `DatagramChannelProvider` seam (ARCHITECTURE.md §9.2).
 * For the duration of [withMulticastSocket] it:
 *
 *  1. holds a `WifiManager` MulticastLock (without it the Wi-Fi chip filters
 *     multicast frames and discovery silently returns nothing);
 *  2. requests and binds to the Wi-Fi `Network`, so an unbound socket cannot
 *     egress via cellular when mobile data is up;
 *  3. joins `239.255.255.250:1900` on the Wi-Fi `NetworkInterface`.
 *
 * All three are released/closed before returning, even on failure — checked by
 * `UpnpSmokeTest` and the "no lock leak across 20 discovery cycles" criterion.
 */
class AndroidNetworkBinder(
    context: Context,
    private val networkRequestTimeoutMs: Long = 4_000L,
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
            setReferenceCounted(true)
            acquire()
        }

        var released = false
        var socket: MulticastSocket? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null

        try {
            val (network, callback) = requestWifiNetwork()
            networkCallback = callback
            val nif = wifiNetworkInterface(network)
                ?: firstMulticastInterface()
                ?: error("no multicast-capable network interface")

            val group = InetAddress.getByName(SsdpClient.GROUP)
            socket = MulticastSocket(SsdpClient.PORT).apply {
                reuseAddress = true
                soTimeout = POLL_TIMEOUT_MS
                network?.bindSocket(this)
                runCatching { networkInterface = nif }
                timeToLive = 4
                joinGroup(InetSocketAddress(group, SsdpClient.PORT), nif)
            }

            return block(socket, nif)
        } finally {
            socket?.let { s ->
                runCatching {
                    s.leaveGroup(
                        InetSocketAddress(InetAddress.getByName(SsdpClient.GROUP), SsdpClient.PORT),
                        wifiNetworkInterface(null),
                    )
                }
                runCatching { s.close() }
            }
            networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
            if (!released && lock.isHeld) {
                lock.release()
                released = true
            }
        }
    }

    /** @return the Wi-Fi [Network] (or null if none arrived in time) plus the callback to unregister. */
    private suspend fun requestWifiNetwork(): Pair<Network?, ConnectivityManager.NetworkCallback?> {
        var callback: ConnectivityManager.NetworkCallback? = null
        val network = try {
            withTimeoutOrNull(networkRequestTimeoutMs) {
                suspendCancellableCoroutine<Network> { cont ->
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(available: Network) {
                            if (cont.isActive) cont.resume(available)
                        }
                    }
                    callback = cb
                    connectivityManager.requestNetwork(request, cb)
                    cont.invokeOnCancellation {
                        runCatching { connectivityManager.unregisterNetworkCallback(cb) }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            null
        }
        if (network == null) {
            callback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
            callback = null
            Log.w(TAG, "no Wi-Fi network bound; SSDP will use the default route")
        }
        return network to callback
    }

    private fun wifiNetworkInterface(network: Network?): NetworkInterface? {
        val linkProperties = network?.let { connectivityManager.getLinkProperties(it) }
        val ifName = linkProperties?.interfaceName
        if (ifName != null) {
            runCatching { NetworkInterface.getByName(ifName) }.getOrNull()?.let { return it }
        }
        return firstMulticastInterface()
    }

    private fun firstMulticastInterface(): NetworkInterface? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { nif ->
                runCatching {
                    nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                        nif.inetAddresses.asSequence().any { it.address.size == 4 }
                }.getOrDefault(false)
            }
        }.getOrNull()

    private companion object {
        const val MULTICAST_LOCK_TAG = "vrplayer-ssdp"
        const val POLL_TIMEOUT_MS = 250
        const val TAG = "AndroidNetworkBinder"
    }
}
