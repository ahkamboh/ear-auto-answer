package com.example.earautoanswer.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.example.earautoanswer.diagnostics.DiagnosticLogger
import com.example.earautoanswer.service.CallMonitorService
import com.example.earautoanswer.service.ServiceController
import com.example.earautoanswer.settings.SettingsRepository

/**
 * Revives the monitor service when a SIM call rings and it is not running.
 *
 * WHY: until this existed, the only three things that ever started the monitor were
 * opening the app, flipping the toggle, and rebooting. Nothing started it when a
 * call actually arrived. So the moment the service died — swiped from recents,
 * memory pressure, a platform timeout — the app went silently deaf, and the only
 * cure was opening it again. That is exactly the reported symptom: "it only works
 * while the app is on screen."
 *
 * This is deliberately NOT the v1.2 wake-on-call design. It claims no call-screening
 * role and no overlay permission, it does not replace the always-on service, and it
 * never stops anything. It is a one-line safety net: if a call is ringing and the
 * monitor is missing, start it.
 *
 * ACTION_PHONE_STATE_CHANGED is exempt from the Android 8 implicit-broadcast ban and
 * is delivered to manifest receivers, so this fires even from a cached process. The
 * foreground-service start may still be refused by the platform when the app has no
 * background-start allowance; that is caught and logged rather than crashing, and
 * the ring is simply left for the user — the same outcome as before this existed.
 */
class PhoneStateWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        // Only a ring is worth waking for. An idle or off-hook transition means the
        // moment has already passed.
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        // No number is read from this intent, ever. EXTRA_INCOMING_NUMBER is
        // deliberately never touched — see the privacy rule in CONTRACT.md.

        val enabled = try {
            SettingsRepository.get(context).isAutoAnswerEnabled()
        } catch (t: Throwable) {
            false
        }
        if (!enabled) return
        if (CallMonitorService.isRunning.value) return

        DiagnosticLogger.log("Ring arrived with the monitor down — starting it")
        try {
            ServiceController.start(context.applicationContext)
        } catch (t: Throwable) {
            DiagnosticLogger.log("Monitor start refused: ${t.javaClass.simpleName}")
        }
    }
}
