package com.example.earautoanswer.core

/**
 * Every tunable number in the gesture pipeline lives here. Nothing else in the
 * project may hard-code a threshold or a duration.
 *
 * This is a data class rather than an object so unit tests can shrink the
 * timings (and the Pixel 5 calibration pass can widen the motion thresholds)
 * without touching the state machine.
 */
data class AutoAnswerConfig(
    /** How long proximity must stay NEAR before the reading is trusted. */
    val proximityDebounceMs: Long = 500L,

    /**
     * A NEAR reading only counts if a pickup was detected within this window
     * before it. Proximity that goes NEAR with no recent movement is a pocket,
     * a bag, or a hand — never an ear.
     */
    val pickupWindowMs: Long = 2_000L,

    /**
     * Total budget for the whole gesture. If the sequence
     * pickup -> NEAR -> stable NEAR does not complete inside this window, the
     * candidate is discarded and the machine falls back to plain RINGING.
     */
    val maxGestureWindowMs: Long = 3_000L,

    /** Number of retries after the first failed answer attempt. */
    val answerRetryCount: Int = 1,

    /** Delay before the single retry. */
    val answerRetryDelayMs: Long = 400L,

    /**
     * Rolling accelerometer window used to answer "did it move?" — long enough to
     * capture a whole lift.
     */
    val motionWindowMs: Long = 800L,

    /**
     * Rolling window used to answer the opposite question, "has it stopped?".
     *
     * This MUST stay comfortably shorter than [proximityDebounceMs], or the
     * stillness check becomes unpassable: the settle gate runs
     * [proximityDebounceMs] after the phone reaches the ear, so a window longer
     * than that still contains the tail of the lift that brought it there, and
     * every genuine raise-to-ear is rejected as "still moving". 300 ms against a
     * 500 ms debounce leaves the window entirely inside the settled period.
     */
    val settleWindowMs: Long = 300L,

    /**
     * RMS deviation from 1g, in m/s^2, that counts as "the phone was picked up".
     * Calibrate on the Pixel 5: a phone lying on a table sits near 0.02, a phone
     * being lifted peaks well above 2.
     */
    val pickupMovementThreshold: Float = 2.0f,

    /**
     * Accepted range for the angle between the screen normal and world-up at the
     * moment the gesture is validated. 0 deg is flat face-up on a table, 180 deg
     * is flat face-down, and a handset held against an ear lands near 90 deg.
     */
    val earTiltMinDegrees: Float = 40f,
    val earTiltMaxDegrees: Float = 140f,

    /** At or beyond this tilt the phone is face-down and must never auto-answer. */
    val faceDownTiltDegrees: Float = 150f,

    /**
     * Pocket protection. When true, a NEAR reading is only ever accepted if the
     * sensor was observed FAR shortly before it — within [maxGestureWindowMs], so
     * the FAR -> NEAR pair has to be one continuous approach rather than two
     * unrelated events in the same ring. A phone that has been continuously
     * covered since before the call arrived can therefore never auto-answer, and
     * neither can a phone that was taken out, looked at, and later pushed back
     * into a pocket while the same call was still ringing.
     */
    val requireFarBeforeNear: Boolean = true,

    /**
     * Stillness gate, applied at validation time. A handset resting against an
     * ear has settled: the lift is over and only hand tremor is left, so its RMS
     * energy sits well under 1. A handset that has just been pushed into a moving
     * pocket has not settled — the insertion impulse and the wearer's own motion
     * are still inside the energy window. Anything above this value is therefore
     * not an ear.
     *
     * Deliberately far below [pickupMovementThreshold]: the pickup gate asks
     * "did it move?", this one asks "has it stopped?". Widen it (via the sensor
     * test screen) if talking while walking is being rejected on a given handset.
     */
    val maxSettleMovementEnergy: Float = 1.5f,

    /**
     * Lower bound on the elevation of the +Y axis (the earpiece end) at
     * validation time. A phone at an ear points its earpiece up, or at worst
     * level when the user is lying down; a phone dropped head-first into a
     * pocket or a bag points it steeply down. Kept generous on purpose — this
     * rejects only the physically impossible, so that lying on one's side is
     * still answerable.
     */
    val minEarPitchDegrees: Float = -45f,
) {
    companion object {
        val DEFAULT = AutoAnswerConfig()
    }
}
