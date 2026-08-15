package com.example.earautoanswer.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.earautoanswer.diagnostics.DiagnosticLogger
import com.example.earautoanswer.service.ServiceController
import com.example.earautoanswer.settings.SettingsRepository

/**
 * Brings monitoring back after a reboot or an app update, but only if the user had
 * actually switched auto-answer on. The setting is the single source of truth; this
 * receiver never turns anything on by itself.
 *
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED are both exempt from Android 12+ background
 * foreground-service start restrictions, so a start decided here is permitted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        if (!SettingsRepository.get(context).isAutoAnswerEnabled()) return

        val cause = if (action == Intent.ACTION_BOOT_COMPLETED) "boot" else "update"
        DiagnosticLogger.log("Restoring monitoring after $cause")
        ServiceController.start(context)
    }
}
