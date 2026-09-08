package com.daydreamvr.vrcore.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.daydreamvr.vrcore.math.Quat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * 3DOF head orientation for the renderer (ARCHITECTURE.md §7).
 *
 * Implementations run their sensor callbacks on a dedicated thread and publish a
 * lock-free latest pose; [poseFor] is called from the GL thread once per frame
 * and applies recentre + prediction.
 */
interface HeadTracker {

    /** Registers sensors. Call from `onResume`. */
    fun start()

    /** Unregisters sensors immediately. Call from `onPause`. */
    fun stop()

    /** Re-aligns yaw so the current gaze is dead ahead (slewed, §7.3). */
    fun recenter()

    /**
     * Writes the head orientation `R_W_H` predicted to [targetTimeNs] into
     * [outRotationMatrix] (column-major 4×4). Never allocates; never blocks.
     */
    fun poseFor(targetTimeNs: Long, outRotationMatrix: FloatArray)

    /** False until the first usable sample has arrived. */
    val isCalibrated: StateFlow<Boolean>

    /** Which sensor is driving tracking. */
    val sensorKind: SensorKind

    var predictionEnabled: Boolean

    /** Seconds of no input + stillness before a slow auto-recentre; 0 = off. */
    var autoRecenterIdleSeconds: Int
}

/**
 * [HeadTracker] backed by `SensorManager`. Registers the best available fused
 * rotation sensor at 200 Hz with zero batching latency on a
 * `THREAD_PRIORITY_URGENT_DISPLAY` [HandlerThread] (ARCHITECTURE.md §7.1).
 */
class SensorHeadTracker(
    context: Context,
    private val displayRotationProvider: () -> Int,
    private val clockNs: () -> Long,
) : HeadTracker {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    private val frameConverter = FrameConverter()
    private val predictor = PosePredictor()
    private val recenterController = RecenterController()
    private val recenterLock = Any()

    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    override val sensorKind: SensorKind = when (rotationSensor?.type) {
        Sensor.TYPE_GAME_ROTATION_VECTOR -> SensorKind.GAME_ROTATION_VECTOR
        Sensor.TYPE_ROTATION_VECTOR -> SensorKind.ROTATION_VECTOR
        else -> SensorKind.NONE
    }

    private val _isCalibrated = MutableStateFlow(false)
    override val isCalibrated: StateFlow<Boolean> = _isCalibrated.asStateFlow()

    @Volatile
    override var predictionEnabled: Boolean = true

    @Volatile
    override var autoRecenterIdleSeconds: Int = 0

    private val latest = AtomicReference<PoseSample?>(null)
    private val previous = AtomicReference<PoseSample?>(null)

    @Volatile
    private var lastOmega: FloatArray? = null

    @Volatile
    private var displayRotation: Int = FrameConverter.ROTATION_90

    @Volatile
    private var lastActivityNs: Long = 0L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var started = false

    private var lastPoseForNs = 0L

    // Scratch buffers for poseFor (GL thread only).
    private val predMatrix = FloatArray(16)
    private val poseQuat = FloatArray(4)
    private val outQuat = FloatArray(4)

    // Scratch for recenter() (input thread only).
    private val poseQuatForRecenter = FloatArray(4)

    private val sensorListener = object : SensorEventListener {
        private val matrix = FloatArray(16)

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    lastOmega = floatArrayOf(event.values[0], event.values[1], event.values[2])
                }
                else -> {
                    frameConverter.sensorToGlWorld(event.values, displayRotation, matrix)
                    val sample = PoseSample(
                        timestampNs = event.timestamp,
                        rotation = matrix.copyOf(),
                        omega = lastOmega?.copyOf(),
                    )
                    previous.set(latest.getAndSet(sample))
                    if (!_isCalibrated.value) _isCalibrated.value = true
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            displayRotation = displayRotationProvider()
        }
    }

    override fun start() {
        if (started || rotationSensor == null || sensorManager == null) return
        started = true
        displayRotation = displayRotationProvider()

        val t = HandlerThread("head-tracker", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        t.start()
        val h = Handler(t.looper)
        thread = t
        handler = h

        sensorManager.registerListener(sensorListener, rotationSensor, SAMPLING_PERIOD_US, 0, h)
        gyroSensor?.let {
            sensorManager.registerListener(sensorListener, it, SAMPLING_PERIOD_US, 0, h)
        }
        displayManager?.registerDisplayListener(displayListener, h)
    }

    override fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(sensorListener)
        displayManager?.unregisterDisplayListener(displayListener)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun recenter() {
        val sample = latest.get() ?: return
        synchronized(recenterLock) {
            Quat.fromMatrix(sample.rotation, poseQuatForRecenter)
            recenterController.recenterTo(poseQuatForRecenter)
        }
        lastActivityNs = clockNs()
    }

    override fun poseFor(targetTimeNs: Long, outRotationMatrix: FloatArray) {
        val sample = latest.get()
        if (sample == null) {
            identity(outRotationMatrix)
            return
        }

        val now = clockNs()
        val dt = if (lastPoseForNs == 0L) 0f else (now - lastPoseForNs) / 1e9f
        lastPoseForNs = now

        maybeAutoRecenter(now)

        if (predictionEnabled) {
            predictor.predict(previous.get(), sample, targetTimeNs, predMatrix)
        } else {
            System.arraycopy(sample.rotation, 0, predMatrix, 0, 16)
        }

        synchronized(recenterLock) {
            recenterController.update(dt)
            Quat.fromMatrix(predMatrix, poseQuat)
            recenterController.apply(poseQuat, outQuat)
        }
        Quat.toMatrix(outQuat, outRotationMatrix)
    }

    private fun maybeAutoRecenter(now: Long) {
        val idle = autoRecenterIdleSeconds
        if (idle <= 0 || lastActivityNs == 0L) return
        if (now - lastActivityNs < idle * 1_000_000_000L) return
        val omega = lastOmega ?: return
        val speed = sqrt(omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2])
        if (speed > AUTO_RECENTER_MAX_RAD_PER_SEC) return
        recenter()
    }

    /** Notifies the tracker of gamepad input, deferring any idle auto-recentre. */
    fun onUserActivity() {
        lastActivityNs = clockNs()
    }

    private fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f
        m[5] = 1f
        m[10] = 1f
        m[15] = 1f
    }

    companion object {
        /** 200 Hz. */
        private const val SAMPLING_PERIOD_US = 5_000

        /** ~2°/s — below this the head is "still" for auto-recentre. */
        private const val AUTO_RECENTER_MAX_RAD_PER_SEC = 0.035f
    }
}
