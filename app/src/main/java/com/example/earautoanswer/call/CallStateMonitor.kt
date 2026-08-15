package com.example.earautoanswer.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * Watches the platform call state and reports it as a [PhoneCallState].
 *
 * Deliberately minimal: it uses [TelephonyCallback.CallStateListener], which
 * carries no phone number at all on API 31+, so there is no number to read, log
 * or accidentally retain. Nothing call-identifying ever leaves this class.
 *
 * Duplicate notifications are swallowed — [onState] only fires when the mapped
 * state actually changes, so the state machine never sees a repeated RINGING.
 */
class CallStateMonitor(context: Context, private val onState: (PhoneCallState) -> Unit) {

    private val appContext: Context = context.applicationContext

    private val telephonyManager: TelephonyManager? =
        appContext.getSystemService(TelephonyManager::class.java)

    /**
     * Callbacks are delivered on the main thread. The body is trivial (map,
     * compare, hand off) and the consumer immediately re-posts onto its own
     * dispatcher, so this costs nothing and keeps delivery strictly ordered.
     */
    private val executor: Executor = ContextCompat.getMainExecutor(appContext)

    @Volatile
    private var current: PhoneCallState = PhoneCallState.IDLE

    private var callback: CallStateCallback? = null

    /**
     * Registers for call-state updates. Idempotent.
     *
     * Returns false — rather than throwing — when READ_PHONE_STATE is missing or
     * telephony is unavailable, so a permission the user has revoked degrades the
     * feature instead of killing the service.
     */
    fun start(): Boolean {
        if (callback != null) return true

        val manager = telephonyManager ?: return false
        if (!hasPhoneStatePermission()) return false

        val newCallback = CallStateCallback()
        return try {
            manager.registerTelephonyCallback(executor, newCallback)
            callback = newCallback
            // Registration does deliver an immediate state, but it comes from the
            // *per-subscription* record, which a self-managed (WhatsApp) call is not
            // written into — so a VoIP call already ringing when the service starts
            // would be invisible until its next transition. The deprecated aggregate
            // getter is the fix: its javadoc says explicitly that it considers
            // self-managed calls, it needs only READ_PHONE_STATE, and it is read
            // after registration so a callback that has already landed wins.
            seedCurrentState(manager)
            true
        } catch (e: SecurityException) {
            // Permission was revoked between the check and the call.
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    /** Unregisters. Idempotent, and safe to call from any thread. */
    fun stop() {
        val active = callback ?: return
        callback = null
        try {
            telephonyManager?.unregisterTelephonyCallback(active)
        } catch (e: SecurityException) {
            // Nothing useful to do; the callback is dropped either way.
        } catch (e: IllegalStateException) {
            // Same.
        }
    }

    /** Last mapped state seen. IDLE until the first callback arrives. */
    fun currentState(): PhoneCallState = current

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Reads the aggregate call state once, at startup.
     *
     * `getCallState()` is deprecated in favour of the per-subscription
     * `TelephonyManager.getCallStateForSubscription()`, which is exactly what makes
     * it useful here: the per-subscription path excludes the default-subscription
     * record a self-managed call is reported under. Never fails the start — an
     * unreadable state simply leaves the monitor at IDLE, which is where it began.
     */
    @Suppress("DEPRECATION")
    private fun seedCurrentState(manager: TelephonyManager) {
        try {
            val mapped = map(manager.callState)
            if (mapped == current) return
            current = mapped
            onState(mapped)
        } catch (t: Throwable) {
            // Leave `current` as it is.
        }
    }

    private fun map(state: Int): PhoneCallState = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> PhoneCallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> PhoneCallState.ACTIVE
        else -> PhoneCallState.IDLE
    }

    private inner class CallStateCallback :
        TelephonyCallback(),
        TelephonyCallback.CallStateListener {

        override fun onCallStateChanged(state: Int) {
            val mapped = map(state)
            // Deduplicate. OFFHOOK in particular is re-announced by the platform.
            if (mapped == current) return
            current = mapped
            onState(mapped)
        }
    }
}
