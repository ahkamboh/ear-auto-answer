package com.example.earautoanswer.core

import com.example.earautoanswer.call.PhoneCallState

/**
 * Explicit states, per the spec. No scattered booleans.
 *
 * IDLE -> RINGING -> PICKUP_DETECTED -> NEAR_CANDIDATE -> ANSWERING -> CALL_ACTIVE
 *
 * Any invalid condition falls back toward RINGING (or IDLE if the call is gone),
 * never forward into ANSWERING.
 */
enum class AutoAnswerState {
    IDLE,
    RINGING,
    PICKUP_DETECTED,
    NEAR_CANDIDATE,
    ANSWERING,
    CALL_ACTIVE,
}

/** Timers the machine asks its host to run. The machine itself owns no clock. */
enum class TimerId {
    /** Proximity must remain NEAR for [AutoAnswerConfig.proximityDebounceMs]. */
    NEAR_DEBOUNCE,

    /** Whole-gesture deadline, armed when a pickup is first detected. */
    GESTURE_WINDOW,

    /** Delay before the single answer retry. */
    ANSWER_RETRY,
}

/**
 * Derived accelerometer features. The sensor layer computes these; the state
 * machine only compares them against [AutoAnswerConfig].
 */
data class MotionFeatures(
    /**
     * RMS deviation of the accelerometer magnitude from 1g over the recent
     * window, in m/s^2. Zero when the phone is perfectly still.
     */
    val movementEnergy: Float,

    /**
     * The same RMS figure over the much shorter [AutoAnswerConfig.settleWindowMs].
     *
     * [movementEnergy] answers "did this phone move recently?" and drives pickup
     * detection. This one answers "is it moving right now?" and drives the settle
     * gate — a handset resting against an ear reads near zero here while its
     * 800 ms figure is still elevated by the lift that just ended.
     *
     * Defaults to [movementEnergy] so callers that do not distinguish the two
     * (tests, and any future producer) get the conservative old behaviour.
     */
    val recentMovementEnergy: Float = movementEnergy,

    /**
     * Angle between the device's screen normal (+Z) and world-up, in degrees.
     * 0 = lying flat face-up, 90 = screen vertical, 180 = lying flat face-down.
     */
    val screenTiltDegrees: Float,

    /**
     * Elevation of the device's long axis (+Y, pointing out of the earpiece)
     * above the horizontal plane, in degrees. +90 = held upright, -90 = inverted.
     */
    val pitchDegrees: Float,
)

/** Everything that can move the machine. Time is always supplied, never read. */
sealed interface MachineEvent {
    val atMs: Long

    data class CallStateChanged(val callState: PhoneCallState, override val atMs: Long) : MachineEvent
    data class ProximityChanged(val near: Boolean, override val atMs: Long) : MachineEvent
    data class MotionSampled(val features: MotionFeatures, override val atMs: Long) : MachineEvent
    data class TimerFired(val id: TimerId, override val atMs: Long) : MachineEvent
    data class AnswerAttemptFinished(val success: Boolean, override val atMs: Long) : MachineEvent
    data class EnabledChanged(val enabled: Boolean, override val atMs: Long) : MachineEvent
}

/** Side effects the host performs. The machine never touches Android directly. */
sealed interface MachineEffect {
    data object RegisterSensors : MachineEffect
    data object UnregisterSensors : MachineEffect
    data object AnswerCall : MachineEffect
    data class StartTimer(val id: TimerId, val delayMs: Long) : MachineEffect
    data class CancelTimer(val id: TimerId) : MachineEffect
    data class Log(val message: String) : MachineEffect
}
