package com.example.earautoanswer.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/**
 * The preferred — and on stock Android 14 the only necessary — way to answer a
 * **SIM** call.
 *
 * ANSWER_PHONE_CALLS is an ordinary runtime permission, so once the user grants
 * it [TelecomManager.acceptRingingCall] works on an unrooted device. This
 * answerer is therefore always tried before the privileged fallback.
 *
 * ## Known limitation — it reports success for a call it did not answer
 *
 * [TelecomManager.acceptRingingCall] returns `void`, and the platform *silently
 * ignores* it for a self-managed call — the kind WhatsApp, Telegram and Messenger
 * register. `TelecomServiceImpl.acceptRingingCallInternal()` reads, with no
 * privileged escape hatch:
 *
 * ```java
 * if (call.isSelfManaged()) {
 *     Log.addEvent(call, LogUtils.Events.REQUEST_ACCEPT, "self-mgd accept ignored from " + packageName);
 *     return;
 * }
 * ```
 *
 * Nothing throws, so [answer] returns `true` for a VoIP call it did nothing to.
 * There is no API that can tell the difference from here: the only signal
 * available is "did not throw". Gating on `isInManagedCall()` inside this class
 * was considered and rejected — that flag is not reliably settled at the RINGING
 * edge, so it would risk breaking ordinary SIM answering, which is far worse than
 * a missing VoIP feature.
 *
 * **The fix is structural and lives one level up.** The caller decides which
 * subsystem owns the ring *before* an answerer is tried, so this class is only
 * ever asked to answer a call telecom can actually accept. A flat
 * [ChainedCallAnswerer] cannot work for VoIP for exactly this reason: it stops at
 * the first `true` and so would stop here, forever. Consequently this answerer —
 * like [RootCallAnswerer], whose `KEYCODE_CALL` injection routes to the same
 * blocked call — must **never** appear in the router's VoIP chain.
 */
class TelecomCallAnswerer(context: Context) : CallAnswerer {

    private val appContext: Context = context.applicationContext

    private val telecomManager: TelecomManager? =
        appContext.getSystemService(TelecomManager::class.java)

    override val name: String = "telecom"

    override suspend fun isAvailable(): Boolean =
        telecomManager != null && hasAnswerPermission()

    override suspend fun answer(): Boolean {
        val manager = telecomManager ?: return false
        // Re-check rather than trusting isAvailable(): the user can revoke the
        // permission from Settings while a call is ringing.
        if (!hasAnswerPermission()) return false

        return try {
            @Suppress("MissingPermission")
            manager.acceptRingingCall()
            // acceptRingingCall() is void and asynchronous. Not throwing is the
            // strongest signal available here; the state machine confirms the
            // real outcome when the call state turns ACTIVE.
            //
            // NOTE: for a self-managed (VoIP) call the platform ignores the
            // request and this still returns true — see the class KDoc.
            // The caller's routing, not this return value, is what keeps calls it
            // from this path.
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            false
        } catch (e: RuntimeException) {
            // A dead system-server binder surfaces here. Never propagate.
            false
        }
    }

    private fun hasAnswerPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
}
