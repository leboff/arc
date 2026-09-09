package com.daydreamvr.player.net

import android.util.Log
import com.daydreamvr.upnp.UpnpLog

/** Bridges the Android-free [UpnpLog] seam (ARCHITECTURE.md §3) to Logcat. */
object LogcatUpnpLog : UpnpLog {
    private const val PREFIX = "upnp/"

    override fun d(tag: String, message: String) {
        Log.d(PREFIX + tag, message)
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        Log.w(PREFIX + tag, message, error)
    }

    override fun e(tag: String, message: String, error: Throwable?) {
        Log.e(PREFIX + tag, message, error)
    }
}
