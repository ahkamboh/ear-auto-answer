# Ear Auto Answer — implementation contract

The normative description of how this app is built. `README.md` explains what it
does and why; this document is the part you need before changing it.

Scope: **SIM (telephony) calls only.** App-to-app / VoIP answering was implemented
and then removed — see "Why VoIP/WhatsApp calls are not supported" in `README.md`
for the platform reason. Nothing in this document describes it.

## Project facts

| Fact | Value |
|---|---|
| Package | `com.example.earautoanswer` |
| Source root | `app/src/main/java/com/example/earautoanswer/` |
| Test root | `app/src/test/java/com/example/earautoanswer/` |
| Kotlin | 2.4.10 · JVM target 17 |
| AGP / Gradle | 8.11.1 / 8.14 |
| compileSdk / targetSdk / minSdk | 36 / 34 / 33 |
| UI | Jetpack Compose, Material 3 (BOM) |
| Build | `./gradlew :app:assembleDebug` |
| Unit tests | `./gradlew :app:testDebugUnitTest` |
| Release | `./gradlew :app:assembleRelease` |

JDK 17+ is pinned via `org.gradle.java.home` in `gradle.properties`, so `./gradlew`
works from any shell without `JAVA_HOME`. Change that line on a machine where the
JDK lives elsewhere.

## Hard rules

1. **No new runtime dependencies.** Everything needed is already in
   `app/build.gradle.kts`: AndroidX core/lifecycle/activity, Compose, coroutines.
   Nothing else, and nothing that touches the network.
2. `core/` and `sensors/MotionAnalyzer.kt` must contain **no `android.*` imports** —
   they are unit-tested on the JVM.
3. **No `System.currentTimeMillis()` inside the state machine.** Time always arrives
   on the event. This is what makes the answer decision reproducible in tests.
4. **Never log a phone number, contact name, or anything call-identifying.**
5. Sensors are registered only on a `RegisterSensors` effect and unregistered on
   `UnregisterSensors`. **No polling loops anywhere** in the app.
6. Every tunable threshold and duration lives in `core/AutoAnswerConfig.kt`. Nothing
   else may hard-code one.
7. Comment the privileged (root) code paths, and never widen `RootExecutor` beyond
   its fixed command enum.
8. **No new `<uses-permission>` without a matching entry in the README's permission
   table.** The six-permission list is a promise the app makes.
9. Any doubt in the gesture pipeline resolves *backwards* (toward RINGING/IDLE),
   never forwards into ANSWERING. A missed call is a nuisance; a false answer is a
   privacy failure.

## Module map

| Path | Contains |
|---|---|
| `core/` | `AutoAnswerConfig`, `AutoAnswerTypes`, `AutoAnswerStateMachine` — pure Kotlin |
| `sensors/` | `MotionAnalyzer` (pure), `SensorController`, `LiveSensorMonitor` |
| `call/` | `PhoneCallState`, `CallAnswerer`, `CallStateMonitor`, `TelecomCallAnswerer`, `RootCallAnswerer`, `ChainedCallAnswerer` |
| `root/` | `RootExecutor` — fixed-command privileged executor |
| `service/` | `CallMonitorService`, `ServiceController`, `ServiceNotifications` |
| `boot/` | `BootReceiver`, `PhoneStateWakeReceiver` |
| `settings/` | `SettingsRepository` |
| `diagnostics/` | `DiagnosticLogger` |
| `ui/` | `MainScreen`, `SensorTestScreen`, `DiagnosticsScreen`, `theme/` |

---

## A · `AutoAnswerStateMachine`

```kotlin
package com.example.earautoanswer.core

class AutoAnswerStateMachine(
    private val config: AutoAnswerConfig = AutoAnswerConfig.DEFAULT,
    initiallyEnabled: Boolean = true,
    private val emit: (MachineEffect) -> Unit,
) {
    val state: AutoAnswerState        // current state, read-only
    fun onEvent(event: MachineEvent)  // the only mutator
    fun snapshot(): Snapshot

    data class Snapshot(
        val state: AutoAnswerState,
        val enabled: Boolean,
        val proximityNear: Boolean?,      // null = no reading yet
        val lastMotion: MotionFeatures?,
        val sawFarSinceRingStart: Boolean,
        val answerAttempts: Int,
    )
}
```

Not thread-safe by design; the host confines it to one dispatcher.

States: `IDLE → RINGING → PICKUP_DETECTED → NEAR_CANDIDATE → ANSWERING → CALL_ACTIVE`.

Events (`MachineEvent`, each carrying `atMs`): `CallStateChanged`,
`ProximityChanged`, `MotionSampled`, `TimerFired`, `AnswerAttemptFinished`,
`EnabledChanged`.

Effects (`MachineEffect`): `RegisterSensors`, `UnregisterSensors`, `AnswerCall`,
`StartTimer(id, delayMs)`, `CancelTimer(id)`, `Log(message)`.

Timers (`TimerId`): `NEAR_DEBOUNCE`, `GESTURE_WINDOW`, `ANSWER_RETRY`.

### Internal memory

`enabled`, `ringStartedAtMs: Long?`, `lastPickupAtMs: Long?`,
`lastMotion: MotionFeatures?`, `proximityNear: Boolean?`,
`sawFarSinceRingStart: Boolean`, `answerAttempts: Int`, `answerAborted: Boolean`.

"Reset gesture memory" = `lastPickupAtMs = null`, `proximityNear = null`,
`lastMotion = null`, `sawFarSinceRingStart = false`, `answerAttempts = 0`,
`answerAborted = false`.

### Transition table — normative

**`EnabledChanged(false)`** — record `enabled = false`. If state != IDLE: emit
`CancelTimer` for all three timers, `UnregisterSensors`,
`Log("Auto-answer disabled — candidate cancelled")`, reset gesture memory,
`state = IDLE`.

**`EnabledChanged(true)`** — record `enabled = true`. No state change even if a call
is currently ringing (**do not arm mid-ring** — the pocket guard has to have watched
the call from its beginning). `Log("Auto-answer enabled")`.

**`CallStateChanged(RINGING)`**
- `!enabled` → `Log("Ringing ignored — auto-answer is off")`, stay IDLE.
- `state != IDLE` → duplicate notification, ignore silently.
- otherwise → reset gesture memory, `ringStartedAtMs = atMs`, `state = RINGING`,
  emit `RegisterSensors`, `Log("Call state -> RINGING")`, `Log("Sensors registered")`.

**`CallStateChanged(ACTIVE)`** — if `state != IDLE`: emit all three `CancelTimer` +
`UnregisterSensors`. `Log("Call state -> ACTIVE")`. `state = CALL_ACTIVE`.
(Covers the user answering manually, an outgoing call, and our own answer landing.)

**`CallStateChanged(IDLE)`** — if `state == IDLE` ignore. Otherwise emit all three
`CancelTimer` + `UnregisterSensors`, `Log("Call state -> IDLE")`, reset gesture
memory, `state = IDLE`.

**`ProximityChanged(near, atMs)`** — always record `proximityNear = near` first.
- `near == false`: if state is RINGING / PICKUP_DETECTED / NEAR_CANDIDATE set
  `sawFarSinceRingStart = true`. If state == NEAR_CANDIDATE: emit
  `CancelTimer(NEAR_DEBOUNCE)`, `Log("Proximity -> FAR — candidate cancelled")`,
  then `state =` PICKUP_DETECTED if a pickup is still inside the gesture window
  (`lastPickupAtMs != null && atMs - lastPickupAtMs <= config.maxGestureWindowMs`)
  else RINGING.
- `near == true`: return immediately if `answerAborted`, or if state is not
  RINGING / PICKUP_DETECTED. Then `Log("Proximity -> NEAR")` and check, in order:
  1. `config.requireFarBeforeNear && !sawFarSinceRingStart` →
     `Log("NEAR rejected — sensor covered since before the call")`, return.
     *(The pocket/bag guard. Necessary but not sufficient: proximity history cannot
     tell "lying uncovered, then raised to an ear" apart from "pulled out of a
     pocket, looked at, pushed back in" — both are a FAR followed by a NEAR after a
     movement impulse, and the sensor layer only reports transitions, so the FAR may
     legitimately be many seconds old in either case. The stillness gate under
     `TimerFired(NEAR_DEBOUNCE)` is what separates them.)*
  2. `lastPickupAtMs == null || atMs - lastPickupAtMs > config.pickupWindowMs` →
     `Log("NEAR rejected — no recent pickup")`, return.
  - passed → `state = NEAR_CANDIDATE`, emit
    `StartTimer(NEAR_DEBOUNCE, config.proximityDebounceMs)`,
    `Log("NEAR debounce started")`.

**`MotionSampled(features, atMs)`** — record `lastMotion = features`. Return if
`answerAborted` or state is not RINGING / PICKUP_DETECTED. If
`features.movementEnergy >= config.pickupMovementThreshold`:
- state == RINGING → `state = PICKUP_DETECTED`, `lastPickupAtMs = atMs`, emit
  `StartTimer(GESTURE_WINDOW, config.maxGestureWindowMs)`,
  `Log("Motion detected — pickup")`.
- state == PICKUP_DETECTED → refresh `lastPickupAtMs = atMs` only. **Do not restart
  the gesture window** — it is a hard deadline, otherwise a phone jostling in a
  pocket keeps a candidate alive indefinitely.

**`TimerFired(GESTURE_WINDOW)`** — only when state is PICKUP_DETECTED or
NEAR_CANDIDATE: emit `CancelTimer(NEAR_DEBOUNCE)`, `Log("Gesture window expired")`,
`lastPickupAtMs = null`, `state = RINGING`. Otherwise ignore.

**`TimerFired(NEAR_DEBOUNCE)`** — ignore unless state == NEAR_CANDIDATE. Validate in
order, each failure logging `"<reason> — candidate cancelled"`:

1. `proximityNear == true` else `"Proximity no longer NEAR"`
2. `lastPickupAtMs != null && atMs - lastPickupAtMs <= config.pickupWindowMs` else
   `"Pickup too old"`
3. `lastMotion != null` else `"No motion data"`
4. `lastMotion.screenTiltDegrees < config.faceDownTiltDegrees` else
   `"Phone is face-down (tilt Xdeg)"`
5. `lastMotion.screenTiltDegrees in config.earTiltMinDegrees..config.earTiltMaxDegrees`
   else `"Orientation not plausible (tilt Xdeg, want A-B)"`
6. `lastMotion.recentMovementEnergy <= config.maxSettleMovementEnergy` else
   `"Phone is still moving (energy X, want <= Y)"`

   *(The stillness gate, and the single most load-bearing rule in the file. Rules
   1–5 are **all** satisfied by a handset standing in a trouser pocket, so without
   this one the app answers from a pocket whenever the user takes the phone out and
   puts it back during the same ring.*

   *It reads `recentMovementEnergy`, **not** `movementEnergy`. The latter spans
   `motionWindowMs` (800 ms) while validation runs only `proximityDebounceMs`
   (500 ms) after the phone reaches the ear, so it is guaranteed to still contain
   the tail of the lift — a gate built on it is unpassable and rejects every genuine
   gesture on real hardware. Any change here must preserve
   `settleWindowMs < proximityDebounceMs`.)*
7. `lastMotion.pitchDegrees >= config.minEarPitchDegrees` else
   `"Earpiece is pointing down (pitch Xdeg)"`
   *(Rejects only the physically impossible for an ear — an earpiece aimed steeply
   downward, which is how a phone dropped head-first into a pocket or bag sits.)*

Measured values are formatted to one decimal place, locale-independently, so the
diagnostics log alone is enough to retune a threshold.

On failure: `state =` PICKUP_DETECTED if the pickup is still inside the gesture
window, else RINGING. On success: `Log("NEAR debounce passed")`,
`Log("Gesture validation passed")`, emit `CancelTimer(GESTURE_WINDOW)`,
`answerAttempts = 1`, `state = ANSWERING`, emit `AnswerCall`,
`Log("Attempting answer (1)")`.

**`TimerFired(ANSWER_RETRY)`** — ignore unless state == ANSWERING.
`answerAttempts++`, emit `AnswerCall`, `Log("Attempting answer (${answerAttempts})")`.

**`AnswerAttemptFinished(success)`** — ignore unless state == ANSWERING.
- success → `Log("Answer successful")`, emit all three `CancelTimer` +
  `UnregisterSensors`, `state = CALL_ACTIVE`.
- failure and `answerAttempts <= config.answerRetryCount` → emit
  `StartTimer(ANSWER_RETRY, config.answerRetryDelayMs)`,
  `Log("Answer failed — retrying once")`.
- failure otherwise → `Log("Answer failed — giving up")`, `answerAborted = true`,
  emit all three `CancelTimer` + `UnregisterSensors`, `state = RINGING`.
  `answerAborted` is latched for the rest of the call and blocks all further gesture
  progress, so the answer path — including the privileged one — can never be
  hammered by continued motion.

---

## B · sensors

```kotlin
package com.example.earautoanswer.sensors

// Pure Kotlin. No android.* imports. Unit-tested.
class MotionAnalyzer(private val config: AutoAnswerConfig = AutoAnswerConfig.DEFAULT) {
    /** Feed a raw accelerometer sample (m/s^2, device axes). Returns derived features. */
    fun onSample(x: Float, y: Float, z: Float, atMs: Long): MotionFeatures
    fun reset()
}
```

- `movementEnergy` = RMS of `(magnitude - 9.81f)` over samples inside
  `config.motionWindowMs`, evicting older ones. Near zero when still.
- `recentMovementEnergy` = the same RMS over the much shorter
  `config.settleWindowMs`. `movementEnergy` answers "did this phone move recently?"
  and drives pickup detection; this one answers "is it moving *now*?" and drives the
  settle gate. `MotionFeatures` defaults it to `movementEnergy` so any producer that
  does not distinguish the two gets the conservative old behaviour.
- `screenTiltDegrees` = angle between the low-pass-filtered gravity vector and the
  device `+Z` axis, i.e. `acos(gz / |g|)` in degrees. 0 = face-up flat, 180 =
  face-down.
- `pitchDegrees` = `asin(gy / |g|)` in degrees, sign such that holding the phone
  upright (earpiece up) gives a positive value near +90.
- Use a simple exponential low-pass (`alpha ≈ 0.2`) for the gravity estimate so tilt
  is not corrupted by the pickup impulse itself.
- Guard against `|g|` near zero and clamp `acos`/`asin` inputs to `[-1, 1]`.

```kotlin
class SensorController(
    context: Context,
    private val config: AutoAnswerConfig = AutoAnswerConfig.DEFAULT,
    private val onEvent: (MachineEvent) -> Unit,  // only ProximityChanged / MotionSampled
) {
    val hasProximity: Boolean
    val hasAccelerometer: Boolean
    val proximityMaxRangeCm: Float
    val isRunning: Boolean
    fun start()   // idempotent
    fun stop()    // idempotent
}
```

- Register on a private `HandlerThread` so callbacks never run on the main thread.
- Proximity: `SENSOR_DELAY_NORMAL`; NEAR means `value < maxRange` **and**
  `value < 5f`, which is the correct test for a binary-style proximity sensor.
- Accelerometer: `SENSOR_DELAY_GAME`, but emit at most one `MotionSampled` every
  100 ms — the machine does not need 50 Hz.
- Emit a `ProximityChanged` for the first reading received after `start()`; the
  machine depends on learning the current NEAR/FAR value.
- Sensor callbacks stay lightweight: compute features, hand the event off, return.
- `stop()` must unregister everything and quit the handler thread safely.
- Per-run callback counters (accelerometer and proximity) are kept and surfaced in
  diagnostics: "the gesture never armed" and "the sensor never delivered" are
  otherwise indistinguishable in the log.
- **`MotionAnalyzer` is confined to the handler thread.** `start()` and `stop()` run
  on the caller's thread, and `quitSafely()` deliberately lets an already-dispatched
  callback finish, so neither may touch the analyzer: a `reset()` racing an
  `onSample()` mutates the same non-thread-safe deque from two threads and throws on
  a looper thread, which kills the process. Give each start/stop cycle its own
  analyzer (and its own listener + dedup state) instead of resetting a shared one.
  Applies identically to `LiveSensorMonitor`.

```kotlin
// Drives the sensor test screen only. Independent of the answer pipeline.
class LiveSensorMonitor(context: Context) {
    data class Reading(
        val proximityCm: Float? = null,
        val proximityNear: Boolean? = null,
        val accel: List<Float>? = null,
        val gyro: List<Float>? = null,
        val features: MotionFeatures? = null,
        val hasGyroscope: Boolean = false,
        val proximityMaxRangeCm: Float = 0f,
    )
    val readings: StateFlow<Reading>
    fun start()
    fun stop()
}
```

`List<Float>` rather than `FloatArray`: a `data class` with array members generates
a broken `equals`, which a `StateFlow` relies on for deduplication.

---

## C · call layer and root

```kotlin
package com.example.earautoanswer.call

enum class PhoneCallState { IDLE, RINGING, ACTIVE }

interface CallAnswerer {
    val name: String
    suspend fun isAvailable(): Boolean
    suspend fun answer(): Boolean
}

class CallStateMonitor(context: Context, private val onState: (PhoneCallState) -> Unit) {
    fun start(): Boolean   // false if READ_PHONE_STATE is not granted
    fun stop()
    fun currentState(): PhoneCallState
}
```

Uses `TelephonyManager.registerTelephonyCallback(executor, TelephonyCallback.CallStateListener)`
(API 31+; minSdk is 33, so there is no legacy `PhoneStateListener` branch). Maps
`CALL_STATE_RINGING → RINGING`, `CALL_STATE_OFFHOOK → ACTIVE`,
`CALL_STATE_IDLE → IDLE`. Deduplicates: `onState` fires only when the mapped value
actually changes. Never reads or stores a number. Registration is wrapped in
try/catch for `SecurityException`.

```kotlin
class TelecomCallAnswerer(context: Context) : CallAnswerer   // name = "telecom"
class RootCallAnswerer : CallAnswerer                        // name = "root"
class ChainedCallAnswerer(private val answerers: List<CallAnswerer>) : CallAnswerer
```

`TelecomCallAnswerer.isAvailable()` = `ANSWER_PHONE_CALLS` granted and
`TelecomManager` non-null. `answer()` calls `telecomManager.acceptRingingCall()`
inside try/catch and returns true when it does not throw. This is the preferred path
and works without root.

`ChainedCallAnswerer.answer()` tries each *available* answerer in order, logging
`"Answer via <name> -> success/failure"` through `DiagnosticLogger`, and returns true
on the first success. `name = "chain"`.

```kotlin
package com.example.earautoanswer.root

object RootExecutor {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /** Only these fixed commands may ever run. No arbitrary strings, ever. */
    enum class Command(internal val shell: String) { … }

    suspend fun isRootAvailable(): Boolean   // cached after first probe
    suspend fun run(command: Command, timeoutMs: Long = 3_000L): Result
}
```

Spawns `su` via `ProcessBuilder`, writes the fixed command to stdin, closes it,
drains both streams on their own threads, enforces the timeout, and always destroys
the process in a `finally`. Runs on `Dispatchers.IO`. Never throws — returns a
non-zero `Result` instead. `PROBE` succeeds when stdout trims to `"0"`.

**The command enum is the security boundary.** Do not add a parameterised or
string-taking overload.

---

## D · service, notification, boot

```kotlin
package com.example.earautoanswer.service

class CallMonitorService : Service() {
    companion object {
        val isRunning: StateFlow<Boolean>   // observed by the UI
    }
}

object ServiceController {
    fun start(context: Context)
    fun stop(context: Context)
}

object ServiceNotifications {
    const val CHANNEL_ID = "ear_auto_answer_status"
    const val NOTIFICATION_ID = 1001
    fun ensureChannel(context: Context)
    fun build(context: Context, text: String): Notification
}
```

`CallMonitorService` is the wiring hub and the only place Android and the machine
meet. It owns:

- a `SupervisorJob` + **single-threaded dispatcher**
  (`Dispatchers.Default.limitedParallelism(1)`; `newSingleThreadContext` is not in
  the main coroutines artifact) on which **all** machine events are delivered. The
  machine is not thread-safe, and its inputs arrive on at least three different
  threads — sensor callbacks on a `HandlerThread`, telephony callbacks on a binder
  pool thread, settings changes on whichever thread wrote the preference — so every
  one of them hops onto this dispatcher first. This confinement is not optional.
- one `AutoAnswerStateMachine`, created in `onCreate`, whose `emit` lambda performs
  effects: `RegisterSensors`/`UnregisterSensors` → `SensorController`;
  `StartTimer`/`CancelTimer` → a concurrent `Map<TimerId, Job>` of `delay()`-based
  coroutines that post `TimerFired`; `AnswerCall` → run the answerer on
  `Dispatchers.IO` and post `AnswerAttemptFinished` back on the machine dispatcher;
  `Log` → `DiagnosticLogger.log`.
- a `CallStateMonitor` started for the service's whole lifetime (it costs nothing),
  whose callback posts `MachineEvent.CallStateChanged` onto the machine dispatcher.
- a `SensorController` started only on demand, i.e. only while a call rings.
- a flat `ChainedCallAnswerer(listOf(TelecomCallAnswerer(this), RootCallAnswerer()))`.
- a collector on `SettingsRepository.autoAnswerEnabled` that posts `EnabledChanged`
  and stops the service when it turns off. Queued *after* the call-state seeding so
  ordering on the single-threaded dispatcher is deterministic: state first, then the
  enabled flag.

Normative details:

- **`startForeground` is the first thing `onStartCommand` does**, with type
  `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` and a **fixed** IDLE text. `onStartCommand`
  is a main-thread callback and the machine is confined to the service dispatcher,
  so reading `machine.state` there is both a confinement violation and a stale read:
  a service restarted while a call is already ringing would post "watching for
  incoming calls" and, by stamping the notified state to match, suppress the
  correction until the next genuine state change. Correct the text afterwards from
  the dispatcher. Returns `START_STICKY`; `START_NOT_STICKY` only if the platform
  refused the promotion, in which case the service stops itself and says why.
- **On `onCreate`, seed the machine** by posting the monitor's `currentState()` from
  the dispatcher, so a restart mid-call is consistent and a RINGING that arrived
  while the seed was queued is not overwritten by a stale IDLE.
- **A `destroyed` flag is set at the very top of `onDestroy`**, before anything is
  torn down. `scope.cancel()` cannot preempt a coroutine that is already running and
  the machine's effect handling has no suspension point in it, so a `RegisterSensors`
  in flight when teardown begins would otherwise re-register the accelerometer after
  the controller had been stopped and leak a 50 Hz registration for the life of the
  process. `RegisterSensors` is guarded on it.
- **`onDestroy` stops hardware before cancelling the scope**, cancels and clears the
  timer map, and calls `sensors.stop()` a second time at the end as a belt to the
  flag's braces.
- **`logRingContext()`** records the process importance and screen state at the
  instant a ring arms the gesture (`FOREGROUND(100)` / `FGS(125)` / …). "The gesture
  never ran" and "the answer was refused" are otherwise indistinguishable in a log.

Notification: low importance, ongoing, no sound; tapping opens `MainActivity`. Text
tracks the machine state and is re-posted only on a real change.

`BootReceiver` handles `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`: if
`SettingsRepository.get(context).isAutoAnswerEnabled()` then
`ServiceController.start(context)`. It never turns anything on by itself.

`PhoneStateWakeReceiver` is a safety net, not a wake-on-call design: it claims no
role and no extra permission, and only starts the monitor service if a call rings
while the service is missing. `ACTION_PHONE_STATE_CHANGED` is exempt from the
implicit-broadcast ban, so it is delivered even to a cached process.

---

## E · UI

Compose, Material 3, one activity, **three** screens switched by simple state (no
navigation library): `MAIN`, `SENSOR_TEST`, `DIAGNOSTICS`. System back returns to
main rather than leaving the app.

`MainScreen` — the master `Switch` bound to `SettingsRepository`, plus a status list:
phone-state permission, answer permission, notifications permission, proximity
sensor present, accelerometer present, monitor service running, root available.
Anything missing renders as a clear warning row with a reason, **never a silent
pass**. Buttons: "Test Sensors", "Diagnostics".

Turning the switch ON must first request, in order, `READ_PHONE_STATE`,
`ANSWER_PHONE_CALLS`, `POST_NOTIFICATIONS` via
`rememberLauncherForActivityResult(RequestMultiplePermissions())`, and only persist
`true` + `ServiceController.start()` once phone-state **and** answer permissions are
granted. If the user denies either, leave the setting OFF and show why. The
persistence happens in the launcher callback, never at the switch.

Permission state is re-read on every activity resume — the user can revoke a
permission from system settings while the screen is backgrounded.

`SensorTestScreen` — live values from `LiveSensorMonitor`: proximity NEAR/FAR + cm,
accel x/y/z, gyro x/y/z if present, and the derived features each labelled with the
threshold that judges it — `movementEnergy`, **`settleEnergy`
(`recentMovementEnergy`, shown against `maxSettleMovementEnergy`)**,
`screenTiltDegrees`, `pitchDegrees` — plus current call state and root status. The
`settleEnergy` row is what the calibration procedure in `README.md` is written
against; it is not optional.

Start and stop the monitor — and the screen's own `CallStateMonitor` — from the
hosting activity's ON_START/ON_STOP, with a `DisposableEffect` for final cleanup. A
`DisposableEffect` alone is not enough: backgrounding the app does not leave the
composition, so a 50 Hz accelerometer feed started from one keeps running, and the
foreground service keeps the process important enough that idle-app sensor
throttling will not mask it.

`DiagnosticsScreen` — `DiagnosticLogger.entries` rendered newest-last in a
scrollable monospace list, with **Share** and **Clear** buttons. Share hands the
formatted log to `Intent.createChooser` — it is how a log gets off the device, and
there is no other route because the app has no network access.

`DiagnosticLogger` mirrors every entry to logcat as well as to its in-memory
`StateFlow`, so a crash or a process death does not take the reasoning with it.

`ui/theme/Theme.kt` — `EarAutoAnswerTheme` using `dynamicDarkColorScheme` /
`dynamicLightColorScheme` when available (API 31+), else the default schemes.

---

## F · tests

JVM unit tests, JUnit 4, no Robolectric, no Android imports. The machine is driven
by hand with a fake clock (increasing `atMs`) and effects are collected into a list.
38 tests total: 26 in `AutoAnswerStateMachineTest`, 12 in `MotionAnalyzerTest`.

`AutoAnswerStateMachineTest` covers at least:

1. happy path: RINGING → motion → FAR → NEAR → debounce timer → `AnswerCall` emitted
2. the settle gate reads `recentMovementEnergy`: a hot long window with a quiet short
   window **must** answer, and a hot short window must not
3. NEAR with no motion at all → no `AnswerCall`
4. motion but proximity stays FAR → no `AnswerCall`
5. NEAR flips FAR before the debounce fires → `CancelTimer(NEAR_DEBOUNCE)`, no answer
6. pocket: proximity NEAR from the very first reading (never FAR) → no answer even
   with plenty of motion
7. face-down orientation at validation time → no answer
8. tilt outside the ear range → no answer
9. gesture window expiry returns the machine to RINGING, and is **not** restarted by
   further motion
10. call ends mid-candidate → `UnregisterSensors`, state IDLE, no answer
11. `EnabledChanged(false)` mid-candidate → immediate cancel, no answer
12. `EnabledChanged(true)` mid-ring does not arm
13. duplicate `CallStateChanged(RINGING)` → only one `RegisterSensors`
14. answer failure → exactly one retry, then abort; no third `AnswerCall`
15. after abort, further NEAR/motion events produce no new `AnswerCall`
16. answer success → `UnregisterSensors` and state CALL_ACTIVE
17. outgoing call (`ACTIVE` straight from IDLE) never answers
18. phone taken out of a pocket mid-ring and pushed back in → no answer (every
    proximity and tilt gate passes; only the stillness gate catches it)
19. still moving when the debounce fires → no answer
20. earpiece pointing steeply down at validation → no answer
21. a cancelled candidate with a stale pickup falls back to RINGING, not
    PICKUP_DETECTED

Because real hardware keeps sampling throughout the debounce, the last
`MotionSampled` before validation is always a post-lift one: **tests that expect an
answer must feed a settled sample**, not leave the pickup impulse as `lastMotion`.
The fixture's `MotionFeatures.settled()` models this correctly — long window still
hot, short window quiet. Modelling it as fully quiet is what let an earlier settle
gate pass its tests and then reject every real gesture on a handset.

`MotionAnalyzerTest`: still phone → `movementEnergy` near zero; flat face-up →
`screenTiltDegrees` near 0; flat face-down → near 180; held vertical → near 90; a
shake sequence exceeds `pickupMovementThreshold`; `recentMovementEnergy` decays
faster than `movementEnergy` after an impulse.

Tests use a shrunk `AutoAnswerConfig` so the intent of each reads off the numbers
directly; changing a production default must not require touching them.

---

## Design decisions worth not re-litigating

**The FAR-before-NEAR guard is deliberately *not* time-limited.** Requiring the last
FAR to be recent was considered and rejected: the proximity layer only reports
transitions, so a phone ringing uncovered on a table emits exactly one FAR, at ring
start, and the NEAR arrives whenever the user gets to it. Any window short enough to
block a pocket re-insertion also blocks the ordinary case of answering after a few
seconds. Stillness, not proximity history, is what separates the two.

**The two-window motion design is load-bearing.** `movementEnergy` (long window,
"did it move?") and `recentMovementEnergy` (short window, "has it stopped?") answer
opposite questions and must not be collapsed into one. See rule 6 above.

**`maxSettleMovementEnergy` and `minEarPitchDegrees` are calibration values, not
constants.** They were added because the original guard set could be satisfied in
full by a phone being pushed back into a pocket during a ring — which is a normal way
of ignoring a call — so the app could answer from a pocket despite that being its
headline promise. Check both against the sensor test screen on any new handset.

**VoIP answering is out of scope and is not coming back without a platform change.**
A VoIP app's answer action is an activity `PendingIntent`, and Android blocks a
backgrounded app from triggering another app's activity start (BAL). Evidenced on a
Pixel 9 / Android 17 by `Background activity launch blocked! …
realCallingUidHasVisibleActivity: false`. It works only while this app is on screen,
which is not a feature. The implementation — notification listener, package
allowlist, answer-action resolver, multi-source call merging, source arbiter,
`SYSTEM_ALERT_WINDOW` and the `<queries>` block — was removed in v2.0 rather than
shipped half-working. Do not re-add any of it without solving BAL first.
