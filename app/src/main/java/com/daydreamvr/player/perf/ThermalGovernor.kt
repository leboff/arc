package com.daydreamvr.player.perf

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/**
 * The render-quality ladder (ARCHITECTURE.md §14). A phone sealed in a headset
 * with the screen at full brightness, Wi-Fi and the hardware decoder hot, for two
 * hours, throttles as its *steady state* — so quality steps down with thermal
 * status, and independently with a low battery.
 *
 * [qualityFor] is pure; [ThermalMonitor] is the thin Android listener that feeds
 * it live `PowerManager` / `BatteryManager` readings.
 */
object ThermalGovernor {

    /** Mirrors `PowerManager.THERMAL_STATUS_*` so the decision stays Android-free. */
    const val THERMAL_NONE = 0
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3
    const val THERMAL_CRITICAL = 4
    const val THERMAL_EMERGENCY = 5
    const val THERMAL_SHUTDOWN = 6

    /** Below this the render scale drops regardless of temperature. */
    const val LOW_BATTERY_PERCENT = 15

    data class Quality(
        val renderScale: Float,
        val msaa: Int,
        val chromatic: Boolean,
    )

    /**
     * The quality for a given thermal status (`THERMAL_*`) and battery percentage
     * (0–100; pass a negative value when unknown to skip the battery rule).
     */
    fun qualityFor(
        thermalStatus: Int,
        batteryPercent: Int,
        supersampling: Boolean = false,
    ): Quality {
        val baseScale = if (supersampling) 1.30f else 1.15f
        val thermal = when (thermalStatus.coerceIn(THERMAL_NONE, THERMAL_SHUTDOWN)) {
            THERMAL_NONE, THERMAL_LIGHT -> Quality(renderScale = baseScale, msaa = 4, chromatic = true)
            THERMAL_MODERATE -> Quality(renderScale = 1.0f, msaa = 0, chromatic = true)
            THERMAL_SEVERE -> Quality(renderScale = 0.85f, msaa = 0, chromatic = false)
            else -> Quality(renderScale = 0.7f, msaa = 0, chromatic = false)
        }

        if (batteryPercent in 0 until LOW_BATTERY_PERCENT) {
            return thermal.copy(
                renderScale = minOf(thermal.renderScale, 0.85f),
                msaa = 0,
            )
        }
        return thermal
    }
}

/**
 * Registers a [PowerManager.OnThermalStatusChangedListener] and reports a fresh
 * [ThermalGovernor.Quality] on every thermal transition (and once on [start]).
 * The battery percentage is sampled from [BatteryManager] at each callback.
 */
class ThermalMonitor(
    context: Context,
    private val onQuality: (ThermalGovernor.Quality) -> Unit,
) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val battery = appContext.getSystemService(BatteryManager::class.java)
    private var isSupersampling = false
    private var lastThermalStatus = ThermalGovernor.THERMAL_NONE

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        lastThermalStatus = status
        onQuality(ThermalGovernor.qualityFor(status, batteryPercent(), isSupersampling))
    }

    fun setSupersampling(enabled: Boolean) {
        if (isSupersampling == enabled) return
        isSupersampling = enabled
        onQuality(ThermalGovernor.qualityFor(lastThermalStatus, batteryPercent(), isSupersampling))
    }

    fun start() {
        power?.addThermalStatusListener(listener)
        lastThermalStatus = power?.currentThermalStatus ?: ThermalGovernor.THERMAL_NONE
        onQuality(ThermalGovernor.qualityFor(lastThermalStatus, batteryPercent(), isSupersampling))
    }

    fun stop() {
        power?.removeThermalStatusListener(listener)
    }

    private fun batteryPercent(): Int =
        battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
}
