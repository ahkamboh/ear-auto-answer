package com.example.earautoanswer.sensors

import com.example.earautoanswer.core.AutoAnswerConfig
import com.example.earautoanswer.core.MotionFeatures
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Turns a stream of raw accelerometer samples into the three features the state
 * machine reasons about.
 *
 * Pure Kotlin on purpose: no `android.*` anywhere, so the whole of the gesture
 * maths is unit-testable on the JVM without Robolectric.
 *
 * Not thread-safe. [SensorController] and [LiveSensorMonitor] each own a private
 * instance and only ever touch it from their own handler thread.
 */
class MotionAnalyzer(private val config: AutoAnswerConfig = AutoAnswerConfig.DEFAULT) {

    /** One entry in the rolling energy window: when it arrived, and how far off 1g it was. */
    private class Sample(val atMs: Long, val deviation: Float)

    private val window = ArrayDeque<Sample>()

    // Exponentially low-pass-filtered gravity estimate, in device axes.
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var hasGravity = false

    // Last orientation we were confident about; reused whenever the gravity
    // estimate degenerates (see updateOrientation).
    private var lastScreenTiltDegrees = 0f
    private var lastPitchDegrees = 0f

    /** Feed a raw accelerometer sample (m/s^2, device axes). Returns derived features. */
    fun onSample(x: Float, y: Float, z: Float, atMs: Long): MotionFeatures {
        updateGravity(x, y, z)
        updateEnergyWindow(x, y, z, atMs)
        updateOrientation()
        return MotionFeatures(
            // Two windows over the same samples, answering two opposite questions:
            // the long one "did it move?" (pickup), the short one "has it stopped?"
            // (settled at an ear). Measuring both off one deque keeps them exactly
            // consistent with each other.
            movementEnergy = rmsOver(config.motionWindowMs, atMs),
            recentMovementEnergy = rmsOver(config.settleWindowMs, atMs),
            screenTiltDegrees = lastScreenTiltDegrees,
            pitchDegrees = lastPitchDegrees,
        )
    }

    fun reset() {
        window.clear()
        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f
        hasGravity = false
        lastScreenTiltDegrees = 0f
        lastPitchDegrees = 0f
    }

    /**
     * Movement energy is the RMS deviation of the raw accelerometer magnitude
     * from 1g across [AutoAnswerConfig.motionWindowMs]. Subtracting gravity's
     * magnitude rather than its vector makes the figure orientation-independent:
     * a phone resting on a table reads ~0 no matter which way up it is, while any
     * real linear acceleration — the lift toward an ear — pushes it well above 2.
     */
    private fun updateEnergyWindow(x: Float, y: Float, z: Float, atMs: Long) {
        val magnitude = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)
        window.addLast(Sample(atMs, (magnitude - EARTH_GRAVITY).toFloat()))

        // Evict everything that has aged out of the longest window we report over.
        while (window.isNotEmpty() && atMs - window.first().atMs > config.motionWindowMs) {
            window.removeFirst()
        }
        // Belt-and-braces bound. If the supplied clock ever steps backwards the
        // age test above stops evicting, and an unbounded deque in a foreground
        // service is a leak. At ~50 Hz the real window holds ~40 samples, so this
        // cap never binds in normal operation.
        while (window.size > MAX_SAMPLES) {
            window.removeFirst()
        }
    }

    /**
     * RMS deviation across the newest [windowMs] of samples. Walks backwards from
     * the most recent and stops at the first one that is too old, so the short
     * window costs a handful of iterations rather than a full scan.
     */
    private fun rmsOver(windowMs: Long, atMs: Long): Float {
        var sumOfSquares = 0.0
        var count = 0
        for (i in window.indices.reversed()) {
            val sample = window[i]
            if (atMs - sample.atMs > windowMs) break
            sumOfSquares += sample.deviation.toDouble() * sample.deviation
            count++
        }
        if (count == 0) return 0f
        return sqrt(sumOfSquares / count).toFloat()
    }

    /**
     * Simple one-pole exponential low pass. The pickup impulse itself is a large,
     * short-lived acceleration; without this filter it would swing the apparent
     * gravity vector around and corrupt the tilt reading at exactly the moment
     * the tilt matters most.
     */
    private fun updateGravity(x: Float, y: Float, z: Float) {
        if (!hasGravity) {
            // Seed from the first sample rather than ramping up from zero, which
            // would otherwise report a meaningless tilt for the first few hundred ms.
            gravityX = x
            gravityY = y
            gravityZ = z
            hasGravity = true
            return
        }
        gravityX += GRAVITY_FILTER_ALPHA * (x - gravityX)
        gravityY += GRAVITY_FILTER_ALPHA * (y - gravityY)
        gravityZ += GRAVITY_FILTER_ALPHA * (z - gravityZ)
    }

    private fun updateOrientation() {
        val magnitude = sqrt(
            gravityX.toDouble() * gravityX +
                gravityY.toDouble() * gravityY +
                gravityZ.toDouble() * gravityZ,
        )
        // In free fall — or with a wedged sensor reporting zeroes — the gravity
        // vector has no direction to speak of and dividing by its magnitude would
        // produce garbage angles (or a division by zero). Keep reporting the last
        // orientation we were actually confident about instead.
        if (magnitude < MIN_GRAVITY_MAGNITUDE) return

        // +Z is the screen normal, so the angle between gravity and +Z is 0 deg
        // when the phone lies flat face-up and 180 deg when it lies face-down.
        // The division can still land a hair outside [-1, 1] through float
        // rounding, and acos() of 1.0000001 is NaN, so clamp before the call.
        lastScreenTiltDegrees = (acos(clampToUnit(gravityZ / magnitude)) * RAD_TO_DEG).toFloat()

        // +Y runs along the device's long axis, out through the earpiece. Its
        // elevation above the horizontal plane is +90 deg with the handset held
        // upright against an ear and -90 deg with it upside down.
        lastPitchDegrees = (asin(clampToUnit(gravityY / magnitude)) * RAD_TO_DEG).toFloat()
    }

    private fun clampToUnit(value: Double): Double = value.coerceIn(-1.0, 1.0)

    private companion object {
        /** Standard gravity in m/s^2, matching the units Android reports. */
        const val EARTH_GRAVITY = 9.81

        /** Smoothing factor of the gravity low pass; higher tracks faster and noisier. */
        const val GRAVITY_FILTER_ALPHA = 0.2f

        /** Below this |g| (m/s^2) the gravity direction is not trustworthy. */
        const val MIN_GRAVITY_MAGNITUDE = 0.5

        const val RAD_TO_DEG = 180.0 / PI

        const val MAX_SAMPLES = 512
    }
}
