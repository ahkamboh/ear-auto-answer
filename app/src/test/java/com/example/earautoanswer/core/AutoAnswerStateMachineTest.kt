package com.example.earautoanswer.core

import com.example.earautoanswer.call.PhoneCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [AutoAnswerStateMachine], written straight from the
 * transition table in CONTRACT.md.
 *
 * The machine owns no clock and touches no Android API, so every test is just a
 * short sequence of events fed against a fake clock that only ever moves
 * forward, followed by assertions on the effects that came back out.
 */
class AutoAnswerStateMachineTest {

    // 1 ----------------------------------------------------------------------

    @Test
    fun happyPath_pickupThenFarThenNear_answersTheCall() {
        val h = Harness()

        h.ring()
        assertEquals(AutoAnswerState.RINGING, h.state)
        assertEquals(1, h.count(MachineEffect.RegisterSensors))

        h.advance(20)
        h.motion(PICKUP_AT_EAR)
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
        assertEquals(
            listOf(MachineEffect.StartTimer(TimerId.GESTURE_WINDOW, TEST_CONFIG.maxGestureWindowMs)),
            h.startTimers(TimerId.GESTURE_WINDOW),
        )

        h.advance(20)
        h.proximity(near = false)
        assertTrue(h.snapshot().sawFarSinceRingStart)

        h.advance(40)
        h.proximity(near = true)
        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)
        assertEquals(
            listOf(MachineEffect.StartTimer(TimerId.NEAR_DEBOUNCE, TEST_CONFIG.proximityDebounceMs)),
            h.startTimers(TimerId.NEAR_DEBOUNCE),
        )

        h.advance(TEST_CONFIG.proximityDebounceMs)
        // The phone has been against the ear for the whole debounce, so the lift
        // impulse has aged out of the energy window and it now reads as settled.
        h.motion(PICKUP_AT_EAR.settled())
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(AutoAnswerState.ANSWERING, h.state)
        assertEquals(1, h.answerCalls)
        assertEquals(1, h.snapshot().answerAttempts)

        // The gesture deadline must be torn down before we try to answer.
        val cancelIndex = h.effects.indexOf(MachineEffect.CancelTimer(TimerId.GESTURE_WINDOW))
        val answerIndex = h.effects.indexOf(MachineEffect.AnswerCall)
        assertTrue("expected an AnswerCall effect", answerIndex >= 0)
        assertTrue("expected CancelTimer(GESTURE_WINDOW) before AnswerCall", cancelIndex in 0 until answerIndex)

        assertTrue(h.logs.containsAll(
            listOf(
                "Call state -> RINGING",
                "Sensors registered",
                "Motion detected — pickup",
                "Proximity -> NEAR",
                "NEAR debounce started",
                "NEAR debounce passed",
                "Gesture validation passed",
                "Attempting answer (1)",
            ),
        ))
    }

    // 1b ---------------------------------------------------------------------

    @Test
    fun raiseToEar_answersEvenWhileTheLongEnergyWindowStillHoldsTheLift() {
        // Regression, found on a real handset: the settle gate read movementEnergy,
        // which spans motionWindowMs. Validation runs proximityDebounceMs after the
        // phone reaches the ear, and since the debounce is shorter than the window,
        // that figure is guaranteed to still contain the deceleration of the lift.
        // The gate was therefore unpassable and no genuine gesture ever answered.
        // It must read recentMovementEnergy, whose window closes inside the debounce.
        val h = Harness()

        h.ring()
        h.advance(20)
        h.proximity(near = false)
        h.advance(40)
        h.motion(PICKUP_AT_EAR)
        h.advance(30)
        h.proximity(near = true)

        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.motion(
            PICKUP_AT_EAR.copy(
                // Deliberately extreme: a hot long window...
                movementEnergy = 8f,
                // ...over a short window that has genuinely gone quiet.
                recentMovementEnergy = 0.2f,
            ),
        )
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(AutoAnswerState.ANSWERING, h.state)
        assertEquals(1, h.answerCalls)
    }

    @Test
    fun stillMovingOnTheShortWindow_neverAnswers() {
        // The other half of the same rule: a phone whose *recent* energy is still
        // high has not settled anywhere — this is the pocket-reinsertion case, and
        // it must stay rejected.
        val h = Harness()

        h.ring()
        h.advance(20)
        h.proximity(near = false)
        h.advance(40)
        h.motion(PICKUP_AT_EAR)
        h.advance(30)
        h.proximity(near = true)

        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.motion(PICKUP_AT_EAR.copy(movementEnergy = 8f, recentMovementEnergy = 4f))
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(0, h.answerCalls)
        assertTrue(h.logs.any { it.startsWith("Phone is still moving") })
    }

    // 2 ----------------------------------------------------------------------

    @Test
    fun nearWithoutAnyPickupMotion_neverAnswers() {
        val h = Harness()

        h.ring()
        h.advance(20)
        h.proximity(near = false)

        // A phone sitting on a table still produces accelerometer samples; they
        // just never cross the pickup threshold, so they must not arm anything.
        h.advance(20)
        h.motion(STILL_ON_TABLE)
        assertEquals(AutoAnswerState.RINGING, h.state)
        assertTrue(h.startTimers(TimerId.GESTURE_WINDOW).isEmpty())

        h.advance(20)
        h.proximity(near = true)

        assertEquals(AutoAnswerState.RINGING, h.state)
        assertTrue(h.startTimers(TimerId.NEAR_DEBOUNCE).isEmpty())
        assertEquals(0, h.answerCalls)
        assertTrue(h.logs.contains("NEAR rejected — no recent pickup"))
    }

    // 3 ----------------------------------------------------------------------

    @Test
    fun motionWhileProximityStaysFar_neverAnswers() {
        val h = Harness()

        h.ring()
        h.advance(20)
        h.proximity(near = false)

        repeat(4) {
            h.advance(100)
            h.motion(PICKUP_AT_EAR)
            h.advance(50)
            h.proximity(near = false)
        }
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)

        // The gesture budget runs out with proximity never having gone NEAR.
        h.advance(TEST_CONFIG.maxGestureWindowMs)
        h.fire(TimerId.GESTURE_WINDOW)

        assertEquals(AutoAnswerState.RINGING, h.state)
        assertTrue(h.startTimers(TimerId.NEAR_DEBOUNCE).isEmpty())
        assertEquals(0, h.answerCalls)
    }

    // 4 ----------------------------------------------------------------------

    @Test
    fun nearFlipsFarBeforeDebounceFires_cancelsTheCandidate() {
        val h = Harness().armCandidate()
        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)
        h.clearEffects()

        // Proximity drops back to FAR while the debounce is still counting.
        h.advance(TEST_CONFIG.proximityDebounceMs / 2)
        h.proximity(near = false)

        assertTrue(h.effects.contains(MachineEffect.CancelTimer(TimerId.NEAR_DEBOUNCE)))
        assertTrue(h.logs.contains("Proximity -> FAR — candidate cancelled"))
        // The pickup is still inside the gesture window, so we fall back one step.
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)

        // A debounce timer that fires anyway (host race) must do nothing.
        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
        assertEquals(0, h.answerCalls)
    }

    // 5 ----------------------------------------------------------------------

    @Test
    fun proximityNearSinceBeforeTheCall_neverAnswers() {
        val h = Harness()

        h.ring()

        // The pocket case: the very first reading after the sensors come up is
        // already NEAR, so FAR is never observed during this ring.
        h.advance(20)
        h.proximity(near = true)

        repeat(5) {
            h.advance(100)
            h.motion(PICKUP_AT_EAR)
            h.advance(50)
            h.proximity(near = true)
        }

        assertTrue(h.startTimers(TimerId.NEAR_DEBOUNCE).isEmpty())
        assertEquals(0, h.answerCalls)
        assertFalse(h.snapshot().sawFarSinceRingStart)
        assertTrue(h.logs.contains("NEAR rejected — sensor covered since before the call"))
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
    }

    // 6 ----------------------------------------------------------------------

    @Test
    fun faceDownAtValidationTime_doesNotAnswer() {
        val h = Harness().armCandidate(pickupWithTilt(170f))
        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)

        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(0, h.answerCalls)
        // Prefix match: rejection reasons now carry the measured value.
        assertTrue(h.logs.any { it.startsWith("Phone is face-down") })
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
    }

    // 7 ----------------------------------------------------------------------

    @Test
    fun tiltOutsideEarRange_doesNotAnswer() {
        // Below earTiltMinDegrees (flat on a table) and above earTiltMaxDegrees
        // but short of the face-down cutoff — both are implausible for an ear.
        for (tilt in listOf(TEST_CONFIG.earTiltMinDegrees - 10f, TEST_CONFIG.earTiltMaxDegrees + 5f)) {
            val h = Harness().armCandidate(pickupWithTilt(tilt))

            h.advance(TEST_CONFIG.proximityDebounceMs)
            h.fire(TimerId.NEAR_DEBOUNCE)

            assertEquals("tilt=$tilt", 0, h.answerCalls)
            assertTrue("tilt=$tilt", h.logs.any { it.startsWith("Orientation not plausible") })
            assertEquals("tilt=$tilt", AutoAnswerState.PICKUP_DETECTED, h.state)
        }
    }

    // 8 ----------------------------------------------------------------------

    @Test
    fun gestureWindowExpiry_returnsToRinging() {
        val h = Harness()

        h.ring()
        h.advance(20)
        h.proximity(near = false)
        h.advance(20)
        h.motion(PICKUP_AT_EAR)
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
        h.clearEffects()

        h.advance(TEST_CONFIG.maxGestureWindowMs)
        h.fire(TimerId.GESTURE_WINDOW)

        assertEquals(AutoAnswerState.RINGING, h.state)
        assertTrue(h.effects.contains(MachineEffect.CancelTimer(TimerId.NEAR_DEBOUNCE)))
        assertTrue(h.logs.contains("Gesture window expired"))

        // The pickup timestamp is gone, so a later NEAR has nothing to lean on.
        h.advance(20)
        h.proximity(near = true)

        assertEquals(AutoAnswerState.RINGING, h.state)
        assertTrue(h.startTimers(TimerId.NEAR_DEBOUNCE).isEmpty())
        assertEquals(0, h.answerCalls)
        assertTrue(h.logs.contains("NEAR rejected — no recent pickup"))
    }

    // 9 ----------------------------------------------------------------------

    @Test
    fun callEndsMidCandidate_unregistersSensorsAndGoesIdle() {
        val h = Harness().armCandidate()
        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)
        h.clearEffects()

        h.advance(30)
        h.callIdle()

        assertEquals(AutoAnswerState.IDLE, h.state)
        assertTrue(h.effects.contains(MachineEffect.UnregisterSensors))
        assertEquals(ALL_TIMERS, h.cancelledTimers())
        assertTrue(h.logs.contains("Call state -> IDLE"))
        assertEquals(0, h.answerCalls)

        // Gesture memory is wiped.
        val snapshot = h.snapshot()
        assertNull(snapshot.proximityNear)
        assertNull(snapshot.lastMotion)
        assertFalse(snapshot.sawFarSinceRingStart)
        assertEquals(0, snapshot.answerAttempts)

        // A debounce timer landing after the call is gone must stay inert.
        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.fire(TimerId.NEAR_DEBOUNCE)
        assertEquals(AutoAnswerState.IDLE, h.state)
        assertEquals(0, h.answerCalls)
    }

    // 10 ---------------------------------------------------------------------

    @Test
    fun disablingMidCandidate_cancelsImmediately() {
        val h = Harness().armCandidate()
        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)
        h.clearEffects()

        h.advance(30)
        h.setEnabled(false)

        assertEquals(AutoAnswerState.IDLE, h.state)
        assertFalse(h.snapshot().enabled)
        assertTrue(h.effects.contains(MachineEffect.UnregisterSensors))
        assertEquals(ALL_TIMERS, h.cancelledTimers())
        assertTrue(h.logs.contains("Auto-answer disabled — candidate cancelled"))
        assertEquals(0, h.answerCalls)

        // The still-ringing call must not be able to re-arm anything.
        h.clearEffects()
        h.advance(30)
        h.ring()
        assertEquals(AutoAnswerState.IDLE, h.state)
        assertEquals(0, h.count(MachineEffect.RegisterSensors))
        assertTrue(h.logs.contains("Ringing ignored — auto-answer is off"))
    }

    // 11 ---------------------------------------------------------------------

    @Test
    fun duplicateRingingNotification_registersSensorsOnce() {
        val h = Harness()

        h.ring()
        h.advance(10)
        h.ring()

        h.advance(10)
        h.proximity(near = false)
        h.advance(20)
        h.motion(PICKUP_AT_EAR)
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)

        // A third notification in the middle of a candidate must be swallowed
        // without resetting anything that has been learned so far.
        h.advance(10)
        h.ring()

        assertEquals(1, h.count(MachineEffect.RegisterSensors))
        assertEquals(1, h.logs.count { it == "Call state -> RINGING" })
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
        assertTrue(h.snapshot().sawFarSinceRingStart)

        h.advance(20)
        h.proximity(near = true)
        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.motion(PICKUP_AT_EAR.settled())
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(AutoAnswerState.ANSWERING, h.state)
        assertEquals(1, h.answerCalls)
    }

    // 12 ---------------------------------------------------------------------

    @Test
    fun answerFailure_retriesExactlyOnceThenAborts() {
        val h = Harness().armCandidate().fireDebounce()
        assertEquals(AutoAnswerState.ANSWERING, h.state)
        assertEquals(1, h.answerCalls)

        h.advance(30)
        h.answerFinished(success = false)
        assertEquals(AutoAnswerState.ANSWERING, h.state)
        assertEquals(
            listOf(MachineEffect.StartTimer(TimerId.ANSWER_RETRY, TEST_CONFIG.answerRetryDelayMs)),
            h.startTimers(TimerId.ANSWER_RETRY),
        )
        assertTrue(h.logs.contains("Answer failed — retrying once"))

        h.advance(TEST_CONFIG.answerRetryDelayMs)
        h.fire(TimerId.ANSWER_RETRY)
        assertEquals(2, h.answerCalls)
        assertEquals(2, h.snapshot().answerAttempts)
        assertTrue(h.logs.contains("Attempting answer (2)"))

        h.clearEffects()
        h.advance(30)
        h.answerFinished(success = false)

        assertEquals(AutoAnswerState.RINGING, h.state)
        assertTrue(h.logs.contains("Answer failed — giving up"))
        assertTrue(h.effects.contains(MachineEffect.UnregisterSensors))
        assertEquals(ALL_TIMERS, h.cancelledTimers())
        assertEquals(0, h.answerCalls) // effects were cleared: no third attempt

        // A stray retry timer after the abort must not resurrect the attempt.
        h.advance(TEST_CONFIG.answerRetryDelayMs)
        h.fire(TimerId.ANSWER_RETRY)
        assertEquals(0, h.answerCalls)
        assertEquals(AutoAnswerState.RINGING, h.state)
    }

    // 13 ---------------------------------------------------------------------

    @Test
    fun afterAbort_furtherGestureEventsNeverAnswerAgain() {
        val h = Harness().armCandidate().fireDebounce()
        h.advance(30)
        h.answerFinished(success = false)
        h.advance(TEST_CONFIG.answerRetryDelayMs)
        h.fire(TimerId.ANSWER_RETRY)
        h.advance(30)
        h.answerFinished(success = false)
        assertEquals(AutoAnswerState.RINGING, h.state)

        h.clearEffects()

        // The whole gesture, replayed from scratch, must now be inert.
        repeat(3) {
            h.advance(50)
            h.proximity(near = false)
            h.advance(50)
            h.motion(PICKUP_AT_EAR)
            h.advance(50)
            h.proximity(near = true)
            h.advance(TEST_CONFIG.proximityDebounceMs)
            h.fire(TimerId.NEAR_DEBOUNCE)
        }

        assertEquals(0, h.answerCalls)
        assertTrue(h.startTimers(TimerId.NEAR_DEBOUNCE).isEmpty())
        assertTrue(h.startTimers(TimerId.GESTURE_WINDOW).isEmpty())
        assertEquals(AutoAnswerState.RINGING, h.state)
    }

    // 14 ---------------------------------------------------------------------

    @Test
    fun answerSuccess_unregistersSensorsAndGoesCallActive() {
        val h = Harness().armCandidate().fireDebounce()
        assertEquals(AutoAnswerState.ANSWERING, h.state)
        h.clearEffects()

        h.advance(40)
        h.answerFinished(success = true)

        assertEquals(AutoAnswerState.CALL_ACTIVE, h.state)
        assertTrue(h.effects.contains(MachineEffect.UnregisterSensors))
        assertEquals(ALL_TIMERS, h.cancelledTimers())
        assertTrue(h.logs.contains("Answer successful"))
        assertEquals(0, h.answerCalls) // effects were cleared: nothing new
    }

    // 15 ---------------------------------------------------------------------

    @Test
    fun outgoingCall_neverAnswers() {
        val h = Harness()

        // Straight from IDLE to ACTIVE: there was never a ring to answer, so no
        // sensors were ever registered and nothing needs tearing down.
        h.advance(20)
        h.callActive()

        assertEquals(AutoAnswerState.CALL_ACTIVE, h.state)
        assertEquals(listOf<MachineEffect>(MachineEffect.Log("Call state -> ACTIVE")), h.effects)

        h.clearEffects()
        h.advance(100)
        h.motion(PICKUP_AT_EAR)
        h.advance(50)
        h.proximity(near = false)
        h.advance(50)
        h.proximity(near = true)

        assertEquals(AutoAnswerState.CALL_ACTIVE, h.state)
        assertEquals(0, h.answerCalls)
        assertEquals(0, h.count(MachineEffect.RegisterSensors))
        assertTrue(h.startTimers(TimerId.NEAR_DEBOUNCE).isEmpty())

        h.clearEffects()
        h.advance(50)
        h.callIdle()
        assertEquals(AutoAnswerState.IDLE, h.state)
        assertTrue(h.effects.contains(MachineEffect.UnregisterSensors))
        assertEquals(ALL_TIMERS, h.cancelledTimers())
    }

    // Extra coverage of table rows the fifteen scenarios only brush against ---

    @Test
    fun enablingMidRing_doesNotArm() {
        val h = Harness(initiallyEnabled = false)

        h.ring()
        assertEquals(AutoAnswerState.IDLE, h.state)
        assertTrue(h.logs.contains("Ringing ignored — auto-answer is off"))

        h.advance(50)
        h.setEnabled(true)

        assertTrue(h.snapshot().enabled)
        assertTrue(h.logs.contains("Auto-answer enabled"))
        // Arming mid-ring is forbidden: the call has to end and ring again.
        assertEquals(AutoAnswerState.IDLE, h.state)
        assertEquals(0, h.count(MachineEffect.RegisterSensors))

        h.advance(50)
        h.proximity(near = false)
        h.advance(50)
        h.motion(PICKUP_AT_EAR)
        h.advance(50)
        h.proximity(near = true)

        assertEquals(AutoAnswerState.IDLE, h.state)
        assertEquals(0, h.answerCalls)
    }

    @Test
    fun secondPickupRefreshesPickupTimeButNotTheGestureWindow() {
        val h = Harness()

        h.ring()
        h.advance(20)
        h.proximity(near = false)
        h.advance(20)
        h.motion(PICKUP_AT_EAR)

        // A second pickup 900 ms later: the pickup timestamp moves forward, the
        // hard gesture deadline does not restart.
        h.advance(900)
        h.motion(PICKUP_AT_EAR)
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
        assertEquals(1, h.startTimers(TimerId.GESTURE_WINDOW).size)

        // 1500 ms after the first pickup but only 600 ms after the second, so
        // this NEAR is only accepted if the timestamp really was refreshed.
        h.advance(600)
        h.proximity(near = true)

        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)
        assertEquals(1, h.startTimers(TimerId.NEAR_DEBOUNCE).size)
    }

    @Test
    fun pickupTooOldWhenDebounceFires_cancelsTheCandidate() {
        val h = Harness().armCandidate()

        // The host's debounce timer lands very late; the pickup that justified
        // the candidate has aged out of the pickup window by now.
        h.advance(TEST_CONFIG.pickupWindowMs + 200)
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(0, h.answerCalls)
        assertTrue(h.logs.contains("Pickup too old — candidate cancelled"))
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
    }

    @Test
    fun gestureWindowExpiryFromNearCandidate_cancelsDebounceAndReturnsToRinging() {
        val h = Harness().armCandidate()
        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)
        h.clearEffects()

        h.advance(TEST_CONFIG.maxGestureWindowMs)
        h.fire(TimerId.GESTURE_WINDOW)

        assertTrue(h.effects.contains(MachineEffect.CancelTimer(TimerId.NEAR_DEBOUNCE)))
        assertTrue(h.logs.contains("Gesture window expired"))
        assertEquals(AutoAnswerState.RINGING, h.state)
        assertEquals(0, h.answerCalls)
    }

    /**
     * The false positive the stillness gate exists for: the user pulls the phone
     * out of a pocket to see who is calling, decides not to answer, and slides it
     * back in while the call is still ringing.
     *
     * Every earlier guard passes — a FAR was seen when the phone came out, the
     * insertion is a genuine movement impulse, the fabric is a genuine NEAR, and a
     * handset standing in a trouser pocket sits squarely inside the ear tilt
     * range. The only thing that separates it from an ear is that it never comes
     * to rest.
     */
    @Test
    fun phonePutBackInThePocketMidRing_neverAnswers() {
        val h = Harness()

        h.ring()

        // Pulled out and looked at: proximity goes FAR.
        h.advance(20)
        h.proximity(near = false)
        h.advance(2_500)

        // Pushed back in: a real impulse, at a pocket-plausible orientation.
        val inThePocket = MotionFeatures(
            movementEnergy = 5f,
            screenTiltDegrees = 88f,
            pitchDegrees = 60f,
        )
        h.motion(inThePocket)
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)

        h.advance(300)
        h.proximity(near = true)
        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)

        // The wearer is still moving, so the energy window never falls quiet.
        //
        // Both fields are set. recentMovementEnergy defaults to movementEnergy, so
        // a copy() that names only movementEnergy silently inherits the 5f from the
        // instance above — and since the settle gate reads recentMovementEnergy, the
        // rejection would then be driven by that inherited 5f rather than by the
        // 2.4f chosen here to sit just above maxSettleMovementEnergy. This test
        // would have gone on passing with the threshold raised to 3 or 4, which on a
        // real handset is exactly what lets the pocket case through.
        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.motion(inThePocket.copy(movementEnergy = 2.4f, recentMovementEnergy = 2.4f))
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(0, h.answerCalls)
        assertTrue(h.logs.any { it.startsWith("Phone is still moving") })
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
    }

    /**
     * The other side of the same threshold, so `maxSettleMovementEnergy` is pinned
     * from both directions rather than only from above.
     *
     * Identical to the test above except that the settle energy sits *at* the limit
     * instead of just over it. Together the pair fail if the gate is loosened and
     * fail if it is tightened, which is what makes them a calibration guard rather
     * than a smoke test.
     */
    @Test
    fun settleEnergyExactlyAtTheLimit_stillAnswers() {
        val h = Harness()

        h.ring()

        h.advance(20)
        h.proximity(near = false)
        h.advance(2_500)

        val lift = MotionFeatures(
            movementEnergy = 5f,
            screenTiltDegrees = 88f,
            pitchDegrees = 60f,
        )
        h.motion(lift)
        h.advance(300)
        h.proximity(near = true)
        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)

        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.motion(
            lift.copy(
                // The long window still holds the lift; the short one has gone quiet
                // to exactly the boundary. The gate is strictly-greater-than.
                recentMovementEnergy = TEST_CONFIG.maxSettleMovementEnergy,
            ),
        )
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(1, h.answerCalls)
        assertEquals(AutoAnswerState.ANSWERING, h.state)
    }

    /** The same gate, reached from the shared fixture. */
    @Test
    fun stillMovingWhenTheDebounceFires_doesNotAnswer() {
        val h = Harness().armCandidate()
        assertEquals(AutoAnswerState.NEAR_CANDIDATE, h.state)

        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.motion(PICKUP_AT_EAR)
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(0, h.answerCalls)
        assertTrue(h.logs.any { it.startsWith("Phone is still moving") })
        assertEquals(AutoAnswerState.PICKUP_DETECTED, h.state)
    }

    @Test
    fun earpiecePointingDownAtValidation_doesNotAnswer() {
        // Tilt inside the ear range but the +Y axis steeply down: a handset that
        // went head-first into a pocket or a bag, never one held to an ear.
        val h = Harness().armCandidate(
            MotionFeatures(movementEnergy = 6f, screenTiltDegrees = 90f, pitchDegrees = -80f),
        )

        h.advance(TEST_CONFIG.proximityDebounceMs)
        h.fire(TimerId.NEAR_DEBOUNCE)

        assertEquals(0, h.answerCalls)
        assertTrue(h.logs.any { it.startsWith("Earpiece is pointing down") })
    }

    @Test
    fun farWithStalePickup_fallsBackToRingingNotPickupDetected() {
        val h = Harness().armCandidate()

        // Longer than the whole gesture budget: the pickup is unusable, so the
        // cancelled candidate drops all the way back to plain RINGING.
        h.advance(TEST_CONFIG.maxGestureWindowMs + 500)
        h.proximity(near = false)

        assertEquals(AutoAnswerState.RINGING, h.state)
        assertTrue(h.logs.contains("Proximity -> FAR — candidate cancelled"))
        assertEquals(0, h.answerCalls)
    }
}

// ---------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------

/** Shrunk timings so the intent of each test reads off the numbers directly. */
private val TEST_CONFIG = AutoAnswerConfig(
    proximityDebounceMs = 100L,
    pickupWindowMs = 1_000L,
    maxGestureWindowMs = 2_000L,
    answerRetryCount = 1,
    answerRetryDelayMs = 50L,
    motionWindowMs = 400L,
    pickupMovementThreshold = 2.0f,
    earTiltMinDegrees = 40f,
    earTiltMaxDegrees = 140f,
    faceDownTiltDegrees = 150f,
    requireFarBeforeNear = true,
    maxSettleMovementEnergy = 1.5f,
    minEarPitchDegrees = -45f,
)

private val ALL_TIMERS: Set<TimerId> = TimerId.entries.toSet()

/** Well over the pickup threshold, held at a tilt that is plausible for an ear. */
private val PICKUP_AT_EAR = MotionFeatures(
    movementEnergy = 6f,
    screenTiltDegrees = 90f,
    pitchDegrees = 75f,
)

/** Below the pickup threshold: a phone lying still on a table. */
private val STILL_ON_TABLE = MotionFeatures(
    movementEnergy = 0.05f,
    screenTiltDegrees = 2f,
    pitchDegrees = 0f,
)

/** A real pickup impulse, but at an arbitrary orientation. */
private fun pickupWithTilt(tilt: Float) = MotionFeatures(
    movementEnergy = 6f,
    screenTiltDegrees = tilt,
    pitchDegrees = 0f,
)

/**
 * The same orientation, once the phone has come to rest. This is what the sensor
 * layer reports a few hundred milliseconds after a lift ends: the impulse has
 * aged out of the rolling energy window and only hand tremor is left.
 */
private fun MotionFeatures.settled(): MotionFeatures = copy(
    // The long window is STILL HOT here, and that is the whole point. It spans
    // motionWindowMs (800 ms on a device), while validation happens only
    // proximityDebounceMs (500 ms) after the phone arrives — so it necessarily
    // still contains the tail of the lift. Modelling this as quiet was the flaw
    // that let a settle gate reading movementEnergy pass its unit tests and then
    // reject every real gesture on a handset.
    movementEnergy = 3f,
    // The short settle window, however, has gone quiet: the phone is resting
    // against an ear and only hand tremor is left.
    recentMovementEnergy = 0.3f,
)

/**
 * Drives one [AutoAnswerStateMachine] with a recording effect collector and a
 * fake clock that only moves forward. Every event carries [now]; the machine is
 * never allowed to read a real clock.
 */
private class Harness(
    config: AutoAnswerConfig = TEST_CONFIG,
    initiallyEnabled: Boolean = true,
    startAtMs: Long = 10_000L,
) {
    val effects = mutableListOf<MachineEffect>()

    private val machine = AutoAnswerStateMachine(
        config = config,
        initiallyEnabled = initiallyEnabled,
        emit = { effect -> effects += effect },
    )

    var now: Long = startAtMs
        private set

    val state: AutoAnswerState get() = machine.state

    fun snapshot(): AutoAnswerStateMachine.Snapshot = machine.snapshot()

    fun advance(ms: Long): Harness {
        require(ms >= 0) { "the fake clock only moves forward" }
        now += ms
        return this
    }

    fun ring() = machine.onEvent(MachineEvent.CallStateChanged(PhoneCallState.RINGING, now))

    fun callActive() = machine.onEvent(MachineEvent.CallStateChanged(PhoneCallState.ACTIVE, now))

    fun callIdle() = machine.onEvent(MachineEvent.CallStateChanged(PhoneCallState.IDLE, now))

    fun proximity(near: Boolean) = machine.onEvent(MachineEvent.ProximityChanged(near, now))

    fun motion(features: MotionFeatures) = machine.onEvent(MachineEvent.MotionSampled(features, now))

    fun fire(id: TimerId) = machine.onEvent(MachineEvent.TimerFired(id, now))

    fun answerFinished(success: Boolean) =
        machine.onEvent(MachineEvent.AnswerAttemptFinished(success, now))

    fun setEnabled(value: Boolean) = machine.onEvent(MachineEvent.EnabledChanged(value, now))

    fun clearEffects() = effects.clear()

    val logs: List<String>
        get() = effects.filterIsInstance<MachineEffect.Log>().map { it.message }

    val answerCalls: Int get() = count(MachineEffect.AnswerCall)

    fun count(effect: MachineEffect): Int = effects.count { it == effect }

    fun startTimers(id: TimerId): List<MachineEffect.StartTimer> =
        effects.filterIsInstance<MachineEffect.StartTimer>().filter { it.id == id }

    fun cancelledTimers(): Set<TimerId> =
        effects.filterIsInstance<MachineEffect.CancelTimer>().map { it.id }.toSet()

    /**
     * RINGING -> FAR observed -> pickup -> NEAR, i.e. armed and debouncing, with
     * the phone then coming to rest at whatever orientation [features] describes.
     * Real hardware keeps sampling at 50 Hz throughout the debounce, so the last
     * motion the machine validates against is always a post-lift one.
     */
    fun armCandidate(features: MotionFeatures = PICKUP_AT_EAR): Harness {
        ring()
        advance(10)
        proximity(near = false)
        advance(20)
        motion(features)
        advance(30)
        proximity(near = true)
        motion(features.settled())
        return this
    }

    /** Lets the NEAR debounce elapse and delivers its timer. */
    fun fireDebounce(): Harness {
        advance(TEST_CONFIG.proximityDebounceMs)
        fire(TimerId.NEAR_DEBOUNCE)
        return this
    }
}
