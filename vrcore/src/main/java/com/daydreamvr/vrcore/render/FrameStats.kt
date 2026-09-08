package com.daydreamvr.vrcore.render

/**
 * Rolling frame-time percentiles over the last [windowSize] frames. Fed one
 * `System.nanoTime()` per frame by [VrRenderer]; read from any thread (e.g. the
 * debug overlay). Budget targets in ARCHITECTURE.md §14.
 */
class FrameStats(private val windowSize: Int = 600) {

    private val samplesNs = LongArray(windowSize)
    private var count = 0
    private var head = 0
    private var lastNs = 0L

    @Synchronized
    fun record(nowNs: Long) {
        if (lastNs != 0L) {
            samplesNs[head] = nowNs - lastNs
            head = (head + 1) % windowSize
            if (count < windowSize) count++
        }
        lastNs = nowNs
    }

    @Synchronized
    fun percentileMs(p: Float): Double {
        if (count == 0) return 0.0
        val sorted = samplesNs.copyOf(count)
        sorted.sort()
        val rank = (p.coerceIn(0f, 1f) * (count - 1)).toInt()
        return sorted[rank] / 1_000_000.0
    }

    val sampleCount: Int
        @Synchronized get() = count

    val p50Ms: Double get() = percentileMs(0.50f)
    val p95Ms: Double get() = percentileMs(0.95f)
    val p99Ms: Double get() = percentileMs(0.99f)

    @Synchronized
    fun reset() {
        count = 0
        head = 0
        lastNs = 0L
    }
}
