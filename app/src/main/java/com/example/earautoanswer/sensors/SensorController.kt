package com.example.earautoanswer.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.example.earautoanswer.core.AutoAnswerConfig
import com.example.earautoanswer.core.MachineEvent
import com.example.earautoanswer.diagnostics.DiagnosticLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the proximity and accelerometer registrations for the answer pipeline and
 * translates them into the only two sensor events the state machine understands:
 * [MachineEvent.ProximityChanged] and [MachineEvent.MotionSampled].
 *
 * Sensors are live only while a call is ringing — the service starts this on a
 * `RegisterSensors` effect and stops it on `UnregisterSensors`. There is no
 * polling loop of any kind; every event here originates from a platform callback.
 *
 * Every callback lands on a private [HandlerThread], so nothing sensor-related
 * ever runs on the main thread. [onEvent] is therefore invoked on that thread and
 * the host is responsible for hopping to its own dispatcher.
 */
class SensorController(
    context: Context,
    private val config: AutoAnswerConfig = AutoAnswerConfig.DEFAULT,
    private val onEvent: (MachineEvent) -> Unit,
) {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var handlerThread: HandlerThread? = null

    /**
     * Everything a single start/stop cycle mutates, so that it can be created by
     * [start] and then abandoned by [stop] instead of being reset across threads.
     *
     * [MotionAnalyzer] is not thread-safe and its invariant is that only the
     * handler thread ever touches it. Resetting a shared analyzer from [stop] —
     * which runs on the caller's thread — would break exactly that invariant:
     * `quitSafely()` deliberately lets an already-dispatched callback run to
     * completion, so a `window.clear()` from the stopping thread can land in the
     * middle of the analyzer's own deque bookkeeping and throw on a looper thread,
     * which kills the process. Giving each run its own analyzer removes the shared
     * mutable state altogether: a late callback keeps using the instance it was
     * registered with, and that instance is garbage the moment the run ends.
     */
    private class Run(config: AutoAnswerConfig) {
        val analyzer = MotionAnalyzer(config)

        /** Closed by [stop] so a queued callback returns without emitting. */
        @Volatile
        var active = true

        var lastProximityNear: Boolean? = null
        var lastMotionEmittedAtMs = 0L

        /**
         * Raw callback counters, reported when the run ends.
         *
         * Android refuses continuous-reporting sensors to background apps, and a
         * refusal is silent: registerListener still returns true and the callbacks
         * simply never arrive. Without these counters "the gesture did not validate"
         * and "the accelerometer was never delivering" produce an identical log,
         * which is precisely the ambiguity that made a real background failure
         * impossible to diagnose. Atomic because [stop] reads them off-thread.
         */
        val accelCallbacks = AtomicInteger(0)
        val proximityCallbacks = AtomicInteger(0)
    }

    /** The live run, or null while stopped. Written under the class monitor. */
    @Volatile
    private var activeRun: Run? = null

    /** The listener the live run registered, kept so [stop] can unregister it. */
    private var activeListener: SensorEventListener? = null

    val hasProximity: Boolean get() = proximitySensor != null

    val hasAccelerometer: Boolean get() = accelerometer != null

    val proximityMaxRangeCm: Float = proximitySensor?.maximumRange ?: 0f

    val isRunning: Boolean get() = activeRun != null

    /** Idempotent: a second call while already running does nothing. */
    @Synchronized
    fun start() {
        if (activeRun != null) return
        val manager = sensorManager ?: return
        if (proximitySensor == null && accelerometer == null) return

        // Built here, but from this point on read and written only by the handler
        // thread created below (plus the one volatile `active` flag).
        val newRun = Run(config)
        val listener = object : SensorEventListener {

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent?) {
                if (!newRun.active || event == null) return
                when (event.sensor?.type) {
                    Sensor.TYPE_PROXIMITY -> {
                        newRun.proximityCallbacks.incrementAndGet()
                        handleProximity(newRun, event)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        newRun.accelCallbacks.incrementAndGet()
                        handleAccelerometer(newRun, event)
                    }
                    else -> Unit
                }
            }
        }

        val thread = HandlerThread(THREAD_NAME).apply { start() }
        val threadHandler = Handler(thread.looper)
        handlerThread = thread
        activeListener = listener

        // Publish the run before registering so the very first callback — which
        // the platform delivers almost immediately for proximity — is not dropped.
        activeRun = newRun

        proximitySensor?.let {
            manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, threadHandler)
        }
        accelerometer?.let {
            manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, threadHandler)
        }
    }

    /** Idempotent: unregisters everything and ends the handler thread. */
    @Synchronized
    fun stop() {
        val current = activeRun ?: return
        // Close the gate first: any callback already queued on the looper will
        // now return without emitting.
        current.active = false
        activeRun = null

        // The single most diagnostic line in the app. Zero accelerometer callbacks
        // over a whole ring means the platform withheld the sensor, not that the
        // user failed the gesture — and the two are otherwise indistinguishable.
        DiagnosticLogger.log(
            "Sensors stopped — accel callbacks=${current.accelCallbacks.get()}, " +
                "proximity callbacks=${current.proximityCallbacks.get()}",
        )

        // Unregistering the listener with no sensor argument drops it from every
        // sensor it was ever registered against, so nothing can be left behind.
        activeListener?.let { sensorManager?.unregisterListener(it) }
        activeListener = null

        // quitSafely lets the queue drain rather than killing a callback midway,
        // and the listener is already unregistered so nothing new can be posted.
        // Nothing this run owns is touched from here: a callback still executing
        // on that thread is free to finish against its own analyzer.
        handlerThread?.quitSafely()
        handlerThread = null
    }

    private fun handleProximity(run: Run, event: SensorEvent) {
        val centimetres = event.values?.firstOrNull() ?: return

        // Most handsets, the Pixel 5 included, ship an effectively binary
        // proximity part: it reports 0 when covered and its maximum range when
        // clear. "Below maximum range" is therefore the reading that works on both
        // binary and true-distance sensors, and the extra absolute test keeps a
        // part with an implausibly large maximum range honest.
        val near = centimetres < proximityMaxRangeCm && centimetres < NEAR_DISTANCE_CM

        // Deliver the first reading unconditionally — the state machine starts
        // each call with no proximity knowledge at all and the pocket guard
        // depends on learning the current NEAR/FAR value straight away. After
        // that, only genuine transitions are worth an event.
        if (run.lastProximityNear == near) return
        run.lastProximityNear = near
        onEvent(MachineEvent.ProximityChanged(near, nowMs()))
    }

    private fun handleAccelerometer(run: Run, event: SensorEvent) {
        val values = event.values ?: return
        if (values.size < 3) return

        val atMs = nowMs()
        // Every sample feeds the analyzer so the rolling energy window and the
        // gravity low pass stay accurate at the full sensor rate...
        val features = run.analyzer.onSample(values[0], values[1], values[2], atMs)

        // ...but the machine is throttled to 10 Hz. It compares against thresholds
        // and timers measured in hundreds of milliseconds; 50 Hz of events would
        // only burn battery crossing threads.
        if (atMs - run.lastMotionEmittedAtMs < MOTION_EMIT_INTERVAL_MS) return
        run.lastMotionEmittedAtMs = atMs
        onEvent(MachineEvent.MotionSampled(features, atMs))
    }

    /**
     * The single clock for every event this class emits. It must share a base with
     * the clock the host stamps its own events (timers, call state) with, because
     * the machine subtracts one from the other.
     */
    private fun nowMs(): Long = System.currentTimeMillis()

    private companion object {
        const val THREAD_NAME = "EarAutoAnswerSensors"

        /** Upper bound on a plausible "something is touching the earpiece" distance. */
        const val NEAR_DISTANCE_CM = 5f

        const val MOTION_EMIT_INTERVAL_MS = 100L
    }
}
