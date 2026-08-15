package com.example.earautoanswer.service

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.example.earautoanswer.call.CallAnswerer
import com.example.earautoanswer.call.CallStateMonitor
import com.example.earautoanswer.call.ChainedCallAnswerer
import com.example.earautoanswer.call.RootCallAnswerer
import com.example.earautoanswer.call.TelecomCallAnswerer
import com.example.earautoanswer.core.AutoAnswerConfig
import com.example.earautoanswer.core.AutoAnswerState
import com.example.earautoanswer.core.AutoAnswerStateMachine
import com.example.earautoanswer.core.MachineEffect
import com.example.earautoanswer.core.MachineEvent
import com.example.earautoanswer.core.TimerId
import com.example.earautoanswer.diagnostics.DiagnosticLogger
import com.example.earautoanswer.sensors.SensorController
import com.example.earautoanswer.settings.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The wiring hub.
 *
 * Owns the one [AutoAnswerStateMachine] instance and every piece of Android that
 * feeds it or is driven by it. The machine is deliberately not thread-safe, so this
 * service confines it to a single-threaded dispatcher: sensor callbacks arrive on a
 * HandlerThread, telephony callbacks on a binder pool thread, settings changes on
 * whichever thread wrote the preference, and *all* of them are funnelled through
 * [deliverAsync] before touching the machine.
 *
 * The service is up for exactly as long as auto-answer is switched on. Call-state
 * monitoring costs nothing, so it runs for the service's whole lifetime; the
 * sensors — which are not free — are registered only while a call is ringing.
 *
 * There is exactly one source of "a call is ringing": telephony, via
 * [CallStateMonitor]. App-to-app (VoIP) calls are deliberately out of scope — see
 * README.md, "Why VoIP/WhatsApp calls are not supported".
 */
class CallMonitorService : Service() {

    /**
     * One thread, for the machine and for every effect it emits. `newSingleThreadContext`
     * is not in the main coroutines artifact, so a parallelism-1 view of the default
     * dispatcher does the same job without owning a thread outright.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val machineDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + machineDispatcher)

    /**
     * Live timers, keyed by id. Written only from [machineDispatcher]; concurrent
     * because [onDestroy] runs on the main thread and drains it there.
     */
    private val timers: MutableMap<TimerId, Job> = ConcurrentHashMap()

    private val config = AutoAnswerConfig.DEFAULT

    private lateinit var settings: SettingsRepository
    private lateinit var machine: AutoAnswerStateMachine
    private lateinit var sensors: SensorController
    private lateinit var callMonitor: CallStateMonitor

    /**
     * The answer path: the standard telecom accept first, the privileged fallback
     * only if it is unavailable or fails. Both are suspending and both are invoked
     * off [machineDispatcher] — see [handleEffect].
     */
    private val answerer: CallAnswerer by lazy {
        ChainedCallAnswerer(listOf(TelecomCallAnswerer(this), RootCallAnswerer()))
    }

    /** Text currently on the notification. Read and written only on [machineDispatcher]. */
    private var notifiedState: AutoAnswerState? = null

    /**
     * Set at the very top of [onDestroy]. `scope.cancel()` cannot preempt a
     * coroutine that is already running, and the machine's effect handling has no
     * suspension point in it, so a `RegisterSensors` in flight when teardown begins
     * would otherwise re-register the accelerometer after [sensors] had been
     * stopped and leak it for the life of the process.
     */
    @Volatile
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        ServiceNotifications.ensureChannel(this)

        settings = SettingsRepository.get(this)

        machine = AutoAnswerStateMachine(
            config = config,
            initiallyEnabled = settings.isAutoAnswerEnabled(),
            emit = ::handleEffect,
        )

        // Sensor callbacks land on the controller's own HandlerThread.
        sensors = SensorController(this, config) { event -> deliverAsync(event) }

        // Telephony callbacks land on a binder thread; hop to machineDispatcher
        // before touching the machine, which is confined to it.
        callMonitor = CallStateMonitor(this) { state ->
            deliverAsync(MachineEvent.CallStateChanged(state, now()))
        }

        DiagnosticLogger.log("Monitoring service started")

        if (!callMonitor.start()) {
            DiagnosticLogger.log(
                "Call-state monitoring unavailable — READ_PHONE_STATE not granted",
            )
        }

        // A restart mid-call must not leave the machine believing the line is idle,
        // so the current call state is seeded before anything else can move the
        // machine. currentState() is read on the dispatcher, so it picks up a
        // RINGING that arrived while this was queued rather than overwriting it
        // with a stale IDLE.
        scope.launch {
            deliver(MachineEvent.CallStateChanged(callMonitor.currentState(), now()))
        }

        // Queued after the call-state seeding above, so ordering on the
        // single-threaded dispatcher is deterministic: state first, then the
        // enabled flag.
        scope.launch {
            settings.autoAnswerEnabled.collect { enabled ->
                deliver(MachineEvent.EnabledChanged(enabled, now()))
                if (!enabled) {
                    DiagnosticLogger.log("Auto-answer switched off — stopping the service")
                    stopSelf()
                }
            }
        }
    }

    /**
     * Records the process's own state at the instant a ring arms the gesture.
     *
     * The reported bug is "it only works while the app is on screen", and the two
     * candidate causes are indistinguishable in the existing log: either the
     * platform is withholding sensors from a backgrounded process, or the gesture
     * ran fine and the answer itself was refused. `importance` settles it —
     * FOREGROUND (100) means the Activity is up, FOREGROUND_SERVICE (125) means the
     * service alone is holding the process, and anything higher means the app is
     * genuinely backgrounded and sensor delivery is not guaranteed.
     */
    private fun logRingContext() {
        val importance = try {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            when (info.importance) {
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND(100)"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FGS(125)"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE(200)"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE(230)"
                else -> "OTHER(${info.importance})"
            }
        } catch (t: Throwable) {
            "unknown"
        }
        val screenOn = try {
            getSystemService(PowerManager::class.java)?.isInteractive == true
        } catch (t: Throwable) {
            false
        }
        DiagnosticLogger.log("Ring context — process=$importance, screenOn=$screenOn")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 destroys a foreground service that has not called startForeground()
        // essentially immediately, so this is the very first thing that happens —
        // before any other work, and regardless of why we were started.
        if (!promoteToForeground()) return START_NOT_STICKY
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // First, so a machine effect already in flight on machineDispatcher cannot
        // register the accelerometer behind the teardown below.
        destroyed = true

        _isRunning.value = false

        // Hardware before scope, deliberately. scope.cancel() cannot preempt a
        // coroutine that is already running, and the machine's effect handling has
        // no suspension point, so cancelling first leaves a window in which a
        // RegisterSensors can complete *after* sensors.stop() has run.
        if (::sensors.isInitialized) sensors.stop()
        if (::callMonitor.isInitialized) callMonitor.stop()

        // Cancel the timer jobs explicitly before killing the scope so nothing is
        // left holding a delay.
        timers.values.forEach { it.cancel() }
        timers.clear()
        scope.cancel()

        // Idempotent, and the belt to `destroyed`'s braces: SensorController.start
        // and .stop are both synchronized, so whichever order a racing pair resolved
        // in, this final stop unregisters the listener and its HandlerThread. The
        // process outlives the service as a cached one, so a leak here is a live
        // 50 Hz accelerometer registration until the process is killed.
        if (::sensors.isInitialized) sensors.stop()

        DiagnosticLogger.log("Monitoring service stopped")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- events

    /**
     * Wall-clock milliseconds. Every timestamp the machine compares — motion samples,
     * proximity edges, timer firings — has to come from this same base, and the
     * sensor layer stamps its events with the wall clock too.
     */
    private fun now(): Long = System.currentTimeMillis()

    /** Hands an event to the machine. Callable only from [machineDispatcher]. */
    private fun deliver(event: MachineEvent) {
        machine.onEvent(event)
        refreshNotification()
    }

    /** Hops onto [machineDispatcher] from any thread. Never blocks the caller. */
    private fun deliverAsync(event: MachineEvent) {
        scope.launch { deliver(event) }
    }

    // --------------------------------------------------------------- effects

    /**
     * Runs on [machineDispatcher], synchronously inside [AutoAnswerStateMachine.onEvent].
     * Nothing here may block: long work is launched and its result posted back as a
     * new event.
     */
    private fun handleEffect(effect: MachineEffect) {
        when (effect) {
            // Guarded: onDestroy sets `destroyed` before it stops the hardware, and
            // this handler is called synchronously from the machine with no
            // suspension point, so an event mid-flight at teardown would otherwise
            // re-register the accelerometer after it had been stopped.
            MachineEffect.RegisterSensors -> if (!destroyed) {
                logRingContext()
                sensors.start()
            }

            MachineEffect.UnregisterSensors -> sensors.stop()

            MachineEffect.AnswerCall -> scope.launch {
                // The telecom accept and the root fallback are both binder / process
                // calls and can take a moment; keep them off the machine's thread,
                // then report the outcome back on it.
                val success = withContext(Dispatchers.IO) { answerer.answer() }
                deliver(MachineEvent.AnswerAttemptFinished(success, now()))
            }

            is MachineEffect.StartTimer -> startTimer(effect.id, effect.delayMs)

            is MachineEffect.CancelTimer -> timers.remove(effect.id)?.cancel()

            is MachineEffect.Log -> DiagnosticLogger.log(effect.message)
        }
    }

    private fun startTimer(id: TimerId, delayMs: Long) {
        // Restarting a timer must never leave the previous one armed.
        timers.remove(id)?.cancel()
        // Launched from the machine's own thread, so the coroutine cannot begin (and
        // therefore cannot remove itself from the map) before the assignment below.
        timers[id] = scope.launch {
            delay(delayMs)
            timers.remove(id)
            deliver(MachineEvent.TimerFired(id, now()))
        }
    }

    // ---------------------------------------------------------- notification

    /**
     * Called from `onStartCommand`, i.e. on the main thread — which is precisely
     * why it must not read [machine]. The machine is confined to [machineDispatcher]
     * and its state field carries no memory barrier, so a main-thread read is both a
     * confinement violation and liable to see a stale value: a service (re)started
     * while a call is already ringing would post "watching for incoming calls" and,
     * by stamping [notifiedState] to match, suppress the correction until the next
     * genuine state change.
     *
     * So: post a fixed, always-safe IDLE text here, then let [refreshNotification]
     * put the real text up from the machine's own thread.
     *
     * Returns false when the platform refused the promotion.
     */
    private fun promoteToForeground(): Boolean = try {
        @Suppress("InlinedApi")
        startForeground(
            ServiceNotifications.NOTIFICATION_ID,
            ServiceNotifications.build(this, textFor(AutoAnswerState.IDLE)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // Forget what was just posted so the refresh below always re-posts,
        // even when the machine really is IDLE. notifiedState is touched
        // nowhere but machineDispatcher.
        scope.launch {
            notifiedState = null
            refreshNotification()
        }
        true
    } catch (t: Throwable) {
        // Refused (background start restrictions, notifications blocked at the
        // OS level). Staying alive as a background service would only get us
        // killed silently, so stop cleanly and say why.
        DiagnosticLogger.log("Foreground start refused (${t.javaClass.simpleName}) — stopping")
        stopSelf()
        false
    }

    /** Called on [machineDispatcher] after every event; only posts on a real change. */
    private fun refreshNotification() {
        val state = machineStateOrIdle()
        if (state == notifiedState) return
        notifiedState = state
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(
                ServiceNotifications.NOTIFICATION_ID,
                ServiceNotifications.build(this, textFor(state)),
            )
        } catch (t: Throwable) {
            DiagnosticLogger.log("Notification update failed (${t.javaClass.simpleName})")
        }
    }

    private fun machineStateOrIdle(): AutoAnswerState =
        if (::machine.isInitialized) machine.state else AutoAnswerState.IDLE

    private fun textFor(state: AutoAnswerState): String = when (state) {
        AutoAnswerState.IDLE -> "Watching for incoming calls"
        AutoAnswerState.RINGING,
        AutoAnswerState.PICKUP_DETECTED,
        AutoAnswerState.NEAR_CANDIDATE,
        -> "Incoming call — raise the phone to your ear"
        AutoAnswerState.ANSWERING -> "Answering the call"
        AutoAnswerState.CALL_ACTIVE -> "Call in progress"
    }

    companion object {
        private val _isRunning = MutableStateFlow(false)

        /** True between [onCreate] and [onDestroy]. Observed by the UI. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}
