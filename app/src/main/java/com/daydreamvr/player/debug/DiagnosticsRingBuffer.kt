package com.daydreamvr.player.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A bounded in-memory diagnostics log the user can export as a file
 * (ARCHITECTURE.md §16 — "no analytics, no crash reporting SDK"). Thread-safe;
 * oldest entries fall off once [capacity] is reached.
 */
class DiagnosticsRingBuffer(private val capacity: Int = 512) {

    data class Entry(val timestampMs: Long, val tag: String, val message: String)

    private val entries = ArrayDeque<Entry>(capacity)
    private val lock = Any()

    fun log(tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), tag, message)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    /** A plain-text dump, one line per entry, newest last. */
    fun export(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return snapshot().joinToString("\n") { e ->
            "${fmt.format(Date(e.timestampMs))}  ${e.tag}: ${e.message}"
        }
    }
}
