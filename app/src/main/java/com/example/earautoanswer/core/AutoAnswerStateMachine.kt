package com.example.earautoanswer.core

import com.example.earautoanswer.call.PhoneCallState

/**
 * The safety-critical heart of the app.
 *
 * A pure, deterministic function of the events it is fed: it owns no clock, no
 * sensors and no Android types. Every instant arrives on the event, every side
 * effect leaves through [emit]. That makes the whole answer decision reproducible
 * on the JVM in unit tests.
 *
 * The state graph only ever moves forward toward [AutoAnswerState.ANSWERING] when
 * every guard passes; any doubt falls back toward RINGING, or to IDLE once the
 * call is gone.
 *
 * Not thread-safe by design; the host confines it to a single dispatcher.
 */
class AutoAnswerStateMachine(
    private val config: AutoAnswerConfig = AutoAnswerConfig.DEFAULT,
    initiallyEnabled: Boolean = true,
    private val emit: (MachineEffect) -> Unit,
) {

    private var _state: AutoAnswerState = AutoAnswerState.IDLE

    /** Current state. Only [onEvent] may change it. */
    val state: AutoAnswerState get() = _state

    // ---- internal memory -------------------------------------------------

    private var enabled: Boolean = initiallyEnabled
    private var ringStartedAtMs: Long? = null
    private var lastPickupAtMs: Long? = null
    private var lastMotion: MotionFeatures? = null
    private var proximityNear: Boolean? = null

    /**
     * Pocket guard state: true once the proximity sensor has been observed FAR at
     * some point after the current call started ringing. A phone that has been
     * covered continuously since before the call arrived never sets this, and can
     * therefore never satisfy a NEAR reading.
     *
     * Necessary but nowhere near sufficient — see [onProximityChanged] and the
     * stillness gate in [onNearDebounceFired].
     */
    private var sawFarSinceRingStart: Boolean = false

    private var answerAttempts: Int = 0

    /**
     * Latched for the remainder of the call once answering has been given up on.
     * Freezes all further gesture progress so the answer path — including the
     * privileged one — can never be hammered by continued motion or proximity.
     */
    private var answerAborted: Boolean = false

    /** The only mutator. */
    fun onEvent(event: MachineEvent) {
        when (event) {
            is MachineEvent.EnabledChanged -> onEnabledChanged(event.enabled)
            is MachineEvent.CallStateChanged -> onCallStateChanged(event.callState, event.atMs)
            is MachineEvent.ProximityChanged -> onProximityChanged(event.near, event.atMs)
            is MachineEvent.MotionSampled -> onMotionSampled(event.features, event.atMs)
            is MachineEvent.TimerFired -> onTimerFired(event.id, event.atMs)
            is MachineEvent.AnswerAttemptFinished -> onAnswerAttemptFinished(event.success)
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        state = _state,
        enabled = enabled,
        proximityNear = proximityNear,
        lastMotion = lastMotion,
        sawFarSinceRingStart = sawFarSinceRingStart,
        answerAttempts = answerAttempts,
    )

    data class Snapshot(
        val state: AutoAnswerState,
        val enabled: Boolean,
        val proximityNear: Boolean?,      // null = no reading yet
        val lastMotion: MotionFeatures?,
        val sawFarSinceRingStart: Boolean,
        val answerAttempts: Int,
    )

    // ---- transitions -----------------------------------------------------

    private fun onEnabledChanged(nowEnabled: Boolean) {
        enabled = nowEnabled
        if (nowEnabled) {
            // Deliberately no state change: we never arm mid-ring, because the
            // pocket guard needs to observe the call from its very beginning.
            log("Auto-answer enabled")
            return
        }
        if (_state != AutoAnswerState.IDLE) {
            cancelAllTimers()
            emit(MachineEffect.UnregisterSensors)
            log("Auto-answer disabled — candidate cancelled")
            resetGestureMemory()
            _state = AutoAnswerState.IDLE
        }
    }

    private fun onCallStateChanged(callState: PhoneCallState, atMs: Long) {
        when (callState) {
            PhoneCallState.RINGING -> {
                if (!enabled) {
                    log("Ringing ignored — auto-answer is off")
                    return
                }
                // Duplicate notification for a call we are already tracking.
                if (_state != AutoAnswerState.IDLE) return

                resetGestureMemory()
                ringStartedAtMs = atMs
                _state = AutoAnswerState.RINGING
                emit(MachineEffect.RegisterSensors)
                log("Call state -> RINGING")
                log("Sensors registered")
            }

            PhoneCallState.ACTIVE -> {
                // Covers the user answering manually, an outgoing call, and our
                // own answer landing.
                if (_state != AutoAnswerState.IDLE) {
                    cancelAllTimers()
                    emit(MachineEffect.UnregisterSensors)
                }
                log("Call state -> ACTIVE")
                _state = AutoAnswerState.CALL_ACTIVE
            }

            PhoneCallState.IDLE -> {
                if (_state == AutoAnswerState.IDLE) return
                cancelAllTimers()
                emit(MachineEffect.UnregisterSensors)
                log("Call state -> IDLE")
                resetGestureMemory()
                _state = AutoAnswerState.IDLE
            }
        }
    }

    private fun onProximityChanged(near: Boolean, atMs: Long) {
        proximityNear = near

        if (!near) {
            if (_state == AutoAnswerState.RINGING ||
                _state == AutoAnswerState.PICKUP_DETECTED ||
                _state == AutoAnswerState.NEAR_CANDIDATE
            ) {
                // The sensor has been uncovered during this call, so a later NEAR
                // is at least capable of being a real approach.
                sawFarSinceRingStart = true
            }
            if (_state == AutoAnswerState.NEAR_CANDIDATE) {
                emit(MachineEffect.CancelTimer(TimerId.NEAR_DEBOUNCE))
                log("Proximity -> FAR — candidate cancelled")
                _state = fallbackState(atMs)
            }
            return
        }

        if (answerAborted) return
        if (_state != AutoAnswerState.RINGING && _state != AutoAnswerState.PICKUP_DETECTED) return

        log("Proximity -> NEAR")

        // Pocket/bag guard. The single most important rule in this file: if the
        // sensor has been covered since before the call arrived, this NEAR carries
        // no information about the user approaching the phone.
        //
        // Note what this guard cannot do, which is why the validation step below
        // also gates on stillness. Proximity history alone cannot separate "phone
        // was lying uncovered, then raised to an ear" from "phone was pulled out
        // of a pocket, looked at, and pushed back in": both are a FAR followed by
        // a NEAR after a movement impulse, and the sensor layer only reports
        // transitions, so in both cases the FAR can be many seconds old. Only the
        // motion signature tells the two apart.
        if (config.requireFarBeforeNear && !sawFarSinceRingStart) {
            log("NEAR rejected — sensor covered since before the call")
            return
        }

        val pickupAt = lastPickupAtMs
        if (pickupAt == null || atMs - pickupAt > config.pickupWindowMs) {
            log("NEAR rejected — no recent pickup")
            return
        }

        _state = AutoAnswerState.NEAR_CANDIDATE
        emit(MachineEffect.StartTimer(TimerId.NEAR_DEBOUNCE, config.proximityDebounceMs))
        log("NEAR debounce started")
    }

    private fun onMotionSampled(features: MotionFeatures, atMs: Long) {
        lastMotion = features

        if (answerAborted) return
        if (_state != AutoAnswerState.RINGING && _state != AutoAnswerState.PICKUP_DETECTED) return
        if (features.movementEnergy < config.pickupMovementThreshold) return

        when (_state) {
            AutoAnswerState.RINGING -> {
                _state = AutoAnswerState.PICKUP_DETECTED
                lastPickupAtMs = atMs
                emit(MachineEffect.StartTimer(TimerId.GESTURE_WINDOW, config.maxGestureWindowMs))
                log("Motion detected — pickup")
            }

            AutoAnswerState.PICKUP_DETECTED -> {
                // Refresh the pickup instant only. The gesture window is a hard
                // deadline and must NOT be restarted, otherwise a phone jostling
                // in a pocket would keep a candidate alive indefinitely.
                lastPickupAtMs = atMs
            }

            else -> Unit
        }
    }

    private fun onTimerFired(id: TimerId, atMs: Long) {
        when (id) {
            TimerId.GESTURE_WINDOW -> {
                if (_state != AutoAnswerState.PICKUP_DETECTED && _state != AutoAnswerState.NEAR_CANDIDATE) return
                emit(MachineEffect.CancelTimer(TimerId.NEAR_DEBOUNCE))
                log("Gesture window expired")
                lastPickupAtMs = null
                _state = AutoAnswerState.RINGING
            }

            TimerId.NEAR_DEBOUNCE -> onNearDebounceFired(atMs)

            TimerId.ANSWER_RETRY -> {
                if (_state != AutoAnswerState.ANSWERING) return
                answerAttempts++
                emit(MachineEffect.AnswerCall)
                log("Attempting answer ($answerAttempts)")
            }
        }
    }

    /**
     * One decimal place, locale-independent. Rejection reasons carry the measured
     * value so the diagnostics log alone is enough to retune a threshold — without
     * it "Orientation not plausible" tells you nothing about how far out it was.
     */
    private fun fmt(value: Float): String = (kotlin.math.round(value * 10f) / 10f).toString()

    private fun onNearDebounceFired(atMs: Long) {
        if (_state != AutoAnswerState.NEAR_CANDIDATE) return

        val pickupAt = lastPickupAtMs
        val motion = lastMotion

        val failure: String? = when {
            proximityNear != true -> "Proximity no longer NEAR"
            pickupAt == null || atMs - pickupAt > config.pickupWindowMs -> "Pickup too old"
            motion == null -> "No motion data"
            motion.screenTiltDegrees >= config.faceDownTiltDegrees ->
                "Phone is face-down (tilt ${fmt(motion.screenTiltDegrees)}deg)"

            motion.screenTiltDegrees < config.earTiltMinDegrees ||
                motion.screenTiltDegrees > config.earTiltMaxDegrees ->
                "Orientation not plausible (tilt ${fmt(motion.screenTiltDegrees)}deg, " +
                    "want ${fmt(config.earTiltMinDegrees)}-${fmt(config.earTiltMaxDegrees)})"

            // Stillness. Everything above this point is also true of a phone that
            // has just been pushed back into a pocket: the insertion is a pickup,
            // the fabric is a NEAR, and a handset standing in a trouser pocket
            // sits squarely inside the ear tilt range. What separates the two is
            // that a phone held at an ear has *stopped* while a pocketed phone is
            // still carrying the insertion impulse and the wearer's own motion.
            //
            // This reads recentMovementEnergy, NOT movementEnergy. The latter is
            // measured over motionWindowMs (800 ms), which at this instant still
            // contains the tail of the lift that just placed the phone at the ear
            // — using it here rejected every genuine gesture.
            motion.recentMovementEnergy > config.maxSettleMovementEnergy ->
                "Phone is still moving (energy ${fmt(motion.recentMovementEnergy)}, " +
                    "want <= ${fmt(config.maxSettleMovementEnergy)})"

            // Earpiece elevation. Rejects only the physically impossible for an
            // ear (pointing steeply down), which is exactly how a phone dropped
            // head-first into a pocket or a bag sits.
            motion.pitchDegrees < config.minEarPitchDegrees ->
                "Earpiece is pointing down (pitch ${fmt(motion.pitchDegrees)}deg)"

            else -> null
        }

        if (failure != null) {
            log("$failure — candidate cancelled")
            _state = fallbackState(atMs)
            return
        }

        log("NEAR debounce passed")
        log("Gesture validation passed")
        emit(MachineEffect.CancelTimer(TimerId.GESTURE_WINDOW))
        answerAttempts = 1
        _state = AutoAnswerState.ANSWERING
        emit(MachineEffect.AnswerCall)
        log("Attempting answer (1)")
    }

    private fun onAnswerAttemptFinished(success: Boolean) {
        if (_state != AutoAnswerState.ANSWERING) return

        if (success) {
            log("Answer successful")
            cancelAllTimers()
            emit(MachineEffect.UnregisterSensors)
            _state = AutoAnswerState.CALL_ACTIVE
            return
        }

        if (answerAttempts <= config.answerRetryCount) {
            emit(MachineEffect.StartTimer(TimerId.ANSWER_RETRY, config.answerRetryDelayMs))
            log("Answer failed — retrying once")
            return
        }

        log("Answer failed — giving up")
        // Latched for the rest of this call: no further motion or proximity event
        // may drive another attempt.
        answerAborted = true
        cancelAllTimers()
        emit(MachineEffect.UnregisterSensors)
        _state = AutoAnswerState.RINGING
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Where a cancelled candidate lands: back on PICKUP_DETECTED while the
     * original pickup is still inside the whole-gesture deadline, otherwise all
     * the way back to plain RINGING.
     */
    private fun fallbackState(atMs: Long): AutoAnswerState {
        val pickupAt = lastPickupAtMs
        return if (pickupAt != null && atMs - pickupAt <= config.maxGestureWindowMs) {
            AutoAnswerState.PICKUP_DETECTED
        } else {
            AutoAnswerState.RINGING
        }
    }

    private fun cancelAllTimers() {
        emit(MachineEffect.CancelTimer(TimerId.NEAR_DEBOUNCE))
        emit(MachineEffect.CancelTimer(TimerId.GESTURE_WINDOW))
        emit(MachineEffect.CancelTimer(TimerId.ANSWER_RETRY))
    }

    private fun resetGestureMemory() {
        lastPickupAtMs = null
        proximityNear = null
        lastMotion = null
        sawFarSinceRingStart = false
        answerAttempts = 0
        answerAborted = false
    }

    private fun log(message: String) {
        emit(MachineEffect.Log(message))
    }
}
