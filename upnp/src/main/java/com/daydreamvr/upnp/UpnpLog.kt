package com.daydreamvr.upnp

/**
 * The module's own logging seam. `:upnp` must not touch `android.util.Log`
 * (ARCHITECTURE.md §3), so callers in `:app` pass an adapter that forwards to
 * Logcat; tests use [NoOp].
 */
interface UpnpLog {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)

    /** Discards everything. Default for tests and headless harnesses. */
    object NoOp : UpnpLog {
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, error: Throwable?) = Unit
        override fun e(tag: String, message: String, error: Throwable?) = Unit
    }
}
