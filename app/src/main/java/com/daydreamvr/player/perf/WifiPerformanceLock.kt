package com.daydreamvr.player.perf

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Holds a high-performance Wi-Fi lock for the duration of playback
 * (ARCHITECTURE.md §14). Without it the Wi-Fi radio power-save duty cycling
 * causes periodic rebuffering on 1080p LAN streams.
 *
 * Prefers `WIFI_MODE_FULL_LOW_LATENCY` (API 29+, our `minSdk`) and falls back to
 * `WIFI_MODE_FULL_HIGH_PERF` on anything older. Not reference-counted — the caller
 * pairs [acquire]/[release] around the player session and [release] is idempotent.
 */
class WifiPerformanceLock(context: Context) {

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    private val lock: WifiManager.WifiLock? = wifi?.createWifiLock(lockMode(), TAG)?.apply {
        setReferenceCounted(false)
    }

    fun acquire() {
        lock?.takeUnless { it.isHeld }?.acquire()
    }

    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }

    private fun lockMode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }

    private companion object {
        const val TAG = "vrplayer-playback"
    }
}
