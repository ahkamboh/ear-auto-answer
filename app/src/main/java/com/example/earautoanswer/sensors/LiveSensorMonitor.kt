package com.example.earautoanswer.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.example.earautoanswer.core.MotionFeatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Raw sensor feed for the developer test screen, and nothing else.
 *
 * Deliberately independent of [SensorController] and the answer pipeline: the
 * test screen must be able to show what the hardware is doing without arming
 * anything, and starting it must never interfere with a live call. It runs its
 * own [HandlerThread] and its own [MotionAnalyzer].
 */
class LiveSensorMonitor(context: Context) {

    /**
     * Vectors are [List] rather than `FloatArray` on purpose: a data class with an
     * array member gets identity-based equals, which would defeat both
     * [MutableStateFlow]'s conflation and Compose's recomposition skipping.
     */
    data class Reading(
        val proximityCm: Float? = null,
        val proximityNear: Boolean? = null,
        val accel: List<Float>? = null,
        val gyro: List<Float>? = null,
        val features: MotionFeatures? = null,
        val hasGyroscope: Boolean = false,
        val proximityMaxRangeCm: Float = 0f,
    )

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /**
     * Per-run state. [MotionAnalyzer] is not thread-safe and must only ever be
     * touched from the handler thread that feeds it, so each start/stop cycle gets
     * its own instead of a shared one being reset from the stopping thread —
     * `quitSafely()` lets an in-flight callback finish, and a cross-thread reset
     * landing inside it would throw on a looper thread and kill the process.
     */
    private class Run {
        val analyzer = MotionAnalyzer()

        @Volatile
        var active = true

        var lastVectorEmittedAtMs = 0L
    }

    private val proximityMaxRangeCm: Float = proximitySensor?.maximumRange ?: 0f

    private val _readings = MutableStateFlow(
        Reading(
            hasGyroscope = gyroscope != null,
            proximityMaxRangeCm = proximityMaxRangeCm,
        ),
    )

    /** Latest values. Emissions arrive on the monitor's handler thread. */
    val readings: StateFlow<Reading> = _readings.asStateFlow()

    private var handlerThread: HandlerThread? = null

    @Volatile
    private var activeRun: Run? = null

    private var activeListener: SensorEventListener? = null

    /** Idempotent. */
    @Synchronized
    fun start() {
        if (activeRun != null) return
        val manager = sensorManager ?: return

        val newRun = Run()
        val listener = object : SensorEventListener {

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent?) {
                if (!newRun.active || event == null) return
                when (event.sensor?.type) {
                    Sensor.TYPE_PROXIMITY -> handleProximity(event)
                    Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(newRun, event)
                    Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
                    else -> Unit
                }
            }
        }

        val thread = HandlerThread(THREAD_NAME).apply { start() }
        val threadHandler = Handler(thread.looper)
        handlerThread = thread
        activeListener = listener
        activeRun = newRun

        proximitySensor?.let {
            manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, threadHandler)
        }
        accelerometer?.let {
            manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, threadHandler)
        }
        gyroscope?.let {
            manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, threadHandler)
        }
    }

    /** Idempotent. Unregisters every sensor and ends the handler thread. */
    @Synchronized
    fun stop() {
        val current = activeRun ?: return
        current.active = false
        activeRun = null
        activeListener?.let { sensorManager?.unregisterListener(it) }
        activeListener = null
        // Nothing this run owns is touched from here; its analyzer simply goes
        // away with it, so a callback still draining on that thread is safe.
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private fun handleProximity(event: SensorEvent) {
        val centimetres = event.values?.firstOrNull() ?: return
        // Same NEAR test as the answer pipeline uses, so the test screen shows the
        // decision the state machine would actually make rather than a prettier one.
        val near = centimetres < proximityMaxRangeCm && centimetres < NEAR_DISTANCE_CM
        _readings.value = _readings.value.copy(
            proximityCm = centimetres,
            proximityNear = near,
        )
    }

    private fun handleAccelerometer(run: Run, event: SensorEvent) {
        val values = event.values ?: return
        if (values.size < 3) return

        val atMs = System.currentTimeMillis()
        // Feed every sample so movementEnergy on screen matches what the pipeline
        // would compute, then publish at a rate a human eye and Compose can follow.
        val features = run.analyzer.onSample(values[0], values[1], values[2], atMs)
        if (atMs - run.lastVectorEmittedAtMs < EMIT_INTERVAL_MS) return
        run.lastVectorEmittedAtMs = atMs

        _readings.value = _readings.value.copy(
            accel = listOf(values[0], values[1], values[2]),
            features = features,
        )
    }

    private fun handleGyroscope(event: SensorEvent) {
        val values = event.values ?: return
        if (values.size < 3) return
        _readings.value = _readings.value.copy(
            gyro = listOf(values[0], values[1], values[2]),
        )
    }

    private companion object {
        const val THREAD_NAME = "EarAutoAnswerLiveSensors"
        const val NEAR_DISTANCE_CM = 5f

        /** ~20 Hz: fast enough to look live, slow enough not to thrash recomposition. */
        const val EMIT_INTERVAL_MS = 50L
    }
}
