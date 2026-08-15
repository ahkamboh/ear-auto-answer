package com.example.earautoanswer.sensors

import com.example.earautoanswer.core.AutoAnswerConfig
import com.example.earautoanswer.core.MotionFeatures
import kotlin.math.acos
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure-Kotlin feature extraction in [MotionAnalyzer], written from
 * the sensor-maths section of CONTRACT.md.
 *
 * Device axis convention (Android accelerometer, values in m/s^2):
 * flat face-up reads (0, 0, +g), flat face-down reads (0, 0, -g) and a handset
 * standing upright with the earpiece at the top reads (0, +g, 0).
 */
class MotionAnalyzerTest {

    @Test
    fun stillPhoneHasNearZeroMovementEnergy() {
        val analyzer = MotionAnalyzer()

        val features = analyzer.feed(0f, 0f, G, samples = 120, stepMs = 10L)

        assertEquals(0f, features.movementEnergy, 0.05f)
        assertTrue(
            "a motionless phone must never look like a pickup",
            features.movementEnergy < AutoAnswerConfig.DEFAULT.pickupMovementThreshold,
        )
    }

    @Test
    fun flatFaceUpIsZeroTilt() {
        val analyzer = MotionAnalyzer()

        val features = analyzer.feed(0f, 0f, G, samples = 150, stepMs = 10L)

        assertEquals(0f, features.screenTiltDegrees, 2f)
        assertEquals(0f, features.pitchDegrees, 2f)
    }

    @Test
    fun flatFaceDownIsOneEightyTilt() {
        val analyzer = MotionAnalyzer()

        val features = analyzer.feed(0f, 0f, -G, samples = 150, stepMs = 10L)

        assertEquals(180f, features.screenTiltDegrees, 2f)
        assertTrue(
            "face-down must land beyond the face-down cutoff",
            features.screenTiltDegrees >= AutoAnswerConfig.DEFAULT.faceDownTiltDegrees,
        )
    }

    @Test
    fun heldUprightIsNinetyTiltAndPositivePitch() {
        val analyzer = MotionAnalyzer()

        // Earpiece up: gravity lies along the device's +Y axis.
        val features = analyzer.feed(0f, G, 0f, samples = 150, stepMs = 10L)

        assertEquals(90f, features.screenTiltDegrees, 2f)
        assertEquals(90f, features.pitchDegrees, 2f)
        assertTrue(
            "an upright handset must sit inside the accepted ear range",
            features.screenTiltDegrees >= AutoAnswerConfig.DEFAULT.earTiltMinDegrees &&
                features.screenTiltDegrees <= AutoAnswerConfig.DEFAULT.earTiltMaxDegrees,
        )
    }

    @Test
    fun heldUpsideDownGivesNegativePitch() {
        val analyzer = MotionAnalyzer()

        val features = analyzer.feed(0f, -G, 0f, samples = 150, stepMs = 10L)

        assertEquals(90f, features.screenTiltDegrees, 2f)
        assertEquals(-90f, features.pitchDegrees, 2f)
    }

    @Test
    fun tiltedBackFortyFiveDegrees() {
        val analyzer = MotionAnalyzer()

        // Gravity split evenly between +Y and +Z: the screen normal is 45 deg
        // off world-up, and the long axis is 45 deg above the horizontal.
        val component = G * SQRT_HALF
        val features = analyzer.feed(0f, component, component, samples = 150, stepMs = 10L)

        assertEquals(45f, features.screenTiltDegrees, 2f)
        assertEquals(45f, features.pitchDegrees, 2f)
    }

    @Test
    fun shakeSequenceExceedsPickupThreshold() {
        val config = AutoAnswerConfig.DEFAULT
        val analyzer = MotionAnalyzer(config)

        var t = 0L
        var features: MotionFeatures? = null
        repeat(40) { i ->
            // Alternating heavy over- and under-shoot of 1 g, i.e. a phone being
            // snatched off a table.
            val z = if (i % 2 == 0) 20f else 2f
            features = analyzer.onSample(0f, 0f, z, t)
            t += 20L
        }
        val last = features ?: error("no sample was produced")

        assertTrue(
            "a shake must read well above ${config.pickupMovementThreshold}, was ${last.movementEnergy}",
            last.movementEnergy > config.pickupMovementThreshold,
        )
        // acos/asin inputs are clamped, so the angles stay in range even while
        // the accelerometer is reading far more than 1 g.
        assertTrue(last.screenTiltDegrees in 0f..180f)
        assertTrue(last.pitchDegrees in -90f..90f)
    }

    @Test
    fun movementEnergyDecaysOnceTheWindowHasPassed() {
        // A short window keeps the test short; the eviction rule is the point.
        val config = AutoAnswerConfig.DEFAULT.copy(motionWindowMs = 200L)
        val analyzer = MotionAnalyzer(config)

        var t = 0L
        repeat(15) { i ->
            analyzer.onSample(0f, 0f, if (i % 2 == 0) 20f else 2f, t)
            t += 20L
        }

        // 400 ms of stillness: every shake sample is now older than the window.
        var features: MotionFeatures? = null
        repeat(20) {
            features = analyzer.onSample(0f, 0f, G, t)
            t += 20L
        }
        val last = features ?: error("no sample was produced")

        assertEquals(0f, last.movementEnergy, 0.05f)
    }

    @Test
    fun resetClearsMovementHistory() {
        val analyzer = MotionAnalyzer()

        var t = 0L
        repeat(20) { i ->
            analyzer.onSample(0f, 0f, if (i % 2 == 0) 20f else 2f, t)
            t += 20L
        }

        analyzer.reset()
        val features = analyzer.onSample(0f, 0f, G, t + 20L)

        assertEquals(0f, features.movementEnergy, 0.05f)
    }

    @Test
    fun gravityLowPassKeepsTiltStableThroughAnImpulse() {
        val analyzer = MotionAnalyzer()

        analyzer.feed(0f, 0f, G, samples = 120, stepMs = 10L)

        // One violent sideways sample on a phone that is still flat on a table.
        // Taken raw this reads ~57 deg of tilt; with the specified alpha ~= 0.2
        // low-pass the gravity estimate barely moves and the reported tilt stays
        // near flat, which is exactly why the filter exists.
        val lateral = 15f
        val rawTilt = Math.toDegrees(acos(G / sqrt(lateral * lateral + G * G)).toDouble()).toFloat()
        assertTrue("the raw sample really is a big tilt excursion", rawTilt > 50f)

        val features = analyzer.onSample(lateral, 0f, G, 1_210L)

        assertTrue(
            "a single impulse must not swing the gravity estimate (raw was $rawTilt deg, " +
                "filtered was ${features.screenTiltDegrees} deg)",
            features.screenTiltDegrees < 30f,
        )
    }

    @Test
    fun degenerateZeroGravityStaysFinite() {
        val analyzer = MotionAnalyzer()

        // Free fall / a dead sensor: |g| collapses towards zero and the tilt
        // maths must not divide its way into NaN.
        val features = analyzer.feed(0f, 0f, 0f, samples = 60, stepMs = 10L)

        assertFalse("screenTiltDegrees was NaN", features.screenTiltDegrees.isNaN())
        assertFalse("pitchDegrees was NaN", features.pitchDegrees.isNaN())
        assertFalse("movementEnergy was NaN", features.movementEnergy.isNaN())
        assertTrue(features.screenTiltDegrees.isFinite())
        assertTrue(features.pitchDegrees.isFinite())
        assertTrue(features.movementEnergy.isFinite())
        assertTrue(features.screenTiltDegrees in 0f..180f)
        assertTrue(features.pitchDegrees in -90f..90f)
    }

    @Test
    fun afterALiftTheShortWindowSettlesWhileTheLongOneIsStillHot() {
        // This is the exact physical situation at gesture-validation time, and the
        // reason the two windows exist. 500 ms of lift, then 350 ms resting at an
        // ear: long enough to fill the 300 ms settle window but nowhere near long
        // enough to flush the 800 ms motion window.
        val analyzer = MotionAnalyzer()
        val config = AutoAnswerConfig.DEFAULT
        var t = 0L

        repeat(50) { i ->
            val push = if (i % 2 == 0) 6f else -6f
            analyzer.onSample(0f, 0f, G + push, t)
            t += 10L
        }

        var last: MotionFeatures? = null
        repeat(35) {
            last = analyzer.onSample(0f, 0f, G, t)
            t += 10L
        }
        val features = last!!

        assertTrue(
            "the long window must still carry the lift, or this test proves nothing",
            features.movementEnergy > config.maxSettleMovementEnergy,
        )
        assertEquals(0f, features.recentMovementEnergy, 0.05f)
        assertTrue(
            "a phone at rest must read as settled on the short window",
            features.recentMovementEnergy <= config.maxSettleMovementEnergy,
        )
    }
}

// ---------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------

private const val G = 9.81f

private const val SQRT_HALF = 0.70710678f

/** Feeds [samples] identical readings [stepMs] apart and returns the last result. */
private fun MotionAnalyzer.feed(
    x: Float,
    y: Float,
    z: Float,
    samples: Int,
    stepMs: Long,
    startAtMs: Long = 0L,
): MotionFeatures {
    require(samples > 0) { "feed() needs at least one sample" }
    var t = startAtMs
    var last: MotionFeatures? = null
    repeat(samples) {
        last = onSample(x, y, z, t)
        t += stepMs
    }
    return last ?: error("no sample was produced")
}
