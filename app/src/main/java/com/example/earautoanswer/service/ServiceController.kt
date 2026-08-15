package com.example.earautoanswer.service

import android.content.Context
import android.content.Intent
import com.example.earautoanswer.diagnostics.DiagnosticLogger

/**
 * The only supported way to start or stop [CallMonitorService].
 *
 * Both entry points are safe to call redundantly: starting an already-running
 * service just re-delivers [CallMonitorService.onStartCommand], and stopping a
 * service that is not running does nothing.
 */
object ServiceController {

    /**
     * The catch is deliberately on [Throwable] rather than on
     * `ForegroundServiceStartNotAllowedException` by name: that avoids any
     * API-level class-reference concern and also covers the `SecurityException` and
     * `IllegalStateException` variants the platform throws from the same site. This
     * is a policy refusal, not a bug — report it, never crash the caller.
     *
     * Note that returning normally is not proof of anything. `startForegroundService()`
     * can succeed and the service still be refused when it calls `startForeground()`
     * itself; that second throw site is handled inside [CallMonitorService].
     */
    fun start(context: Context) {
        val app = context.applicationContext
        try {
            app.startForegroundService(Intent(app, CallMonitorService::class.java))
        } catch (t: Throwable) {
            DiagnosticLogger.log("Service start refused by the system (${t.javaClass.simpleName})")
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        try {
            app.stopService(Intent(app, CallMonitorService::class.java))
        } catch (t: Throwable) {
            DiagnosticLogger.log("Service stop failed (${t.javaClass.simpleName})")
        }
    }
}
