# Ear Auto Answer

Answers an incoming SIM call when you pick your phone up and raise it to your ear.

Nothing else triggers it. In particular it does not answer from a pocket, from a
bag, or when you pick the phone up just to look at who is calling. Getting that
part right — refusing to answer — is most of what this app is.

Android only. Kotlin + Jetpack Compose, MIT licensed, no third-party runtime
dependencies, no analytics, no network code of any kind. `minSdk 33`,
`targetSdk 34`, `compileSdk 36`.

---

## How the gesture works

While — and *only* while — a call is ringing, a foreground service registers the
accelerometer and the proximity sensor. Between calls, no sensor is registered at
all.

Three signals have to agree, and each of them answers a different question.

### 1. Motion = intent

A raise-to-ear starts with the phone being picked up. The accelerometer's RMS
deviation from 1 g over an 800 ms window (`movementEnergy`) has to cross
`pickupMovementThreshold`. A phone lying on a table reads about `0.02`; a lift
peaks well above `2`.

Without this, proximity alone would be enough — and proximity alone is exactly
what a pocket looks like.

### 2. Proximity = confirmation

After a pickup, the proximity sensor going NEAR is read as "something is right in
front of the earpiece". It has to hold NEAR for `proximityDebounceMs` (500 ms), and
it only counts if the sensor was seen **FAR** at some point after this call started
ringing.

That FAR-before-NEAR rule is the pocket guard: a sensor that has been covered
continuously since before the phone rang carries no information about you
approaching it, so a NEAR from that state is never trusted.

### 3. Stillness = it's an ear, not a pocket

This is the gate that actually does the work, and the one that took the longest to
get right.

Everything above is *also* true of a phone being pushed back into a pocket during a
ring — which is one of the normal ways people ignore a call. Pulling it out is a
pickup, the fabric is a NEAR, and a handset standing in a trouser pocket sits
squarely inside the ear tilt range. Proximity history cannot separate the two:
both are a FAR followed by a NEAR after a movement impulse.

What separates them is that **a phone held at an ear has stopped moving**, while a
phone in a pocket is still carrying the insertion impulse and the wearer's own
motion. So at validation time the app requires `recentMovementEnergy` — the energy
over a short 300 ms window — to be at or below `maxSettleMovementEnergy`.

> The short window matters. The obvious implementation reads the 800 ms
> `movementEnergy`, but validation happens only 500 ms after the phone arrives at
> the ear, so that figure is *guaranteed* to still contain the deceleration of the
> lift. A stillness gate built on it passes its unit tests and then rejects every
> real gesture on a handset. The settle window has to close inside the debounce.

### Plus two sanity checks

- **Screen tilt** must be inside `earTiltMinDegrees..earTiltMaxDegrees` (40°–140°,
  where 0° is flat face-up and 180° is flat face-down) and below
  `faceDownTiltDegrees`.
- **Earpiece pitch** must be at or above `minEarPitchDegrees` (−45°). This rejects
  only the physically impossible for an ear — an earpiece aimed steeply *downward*,
  which is exactly how a phone dropped head-first into a pocket sits. It is kept
  deliberately generous so answering while lying on your side still works.

The whole sequence must complete inside `maxGestureWindowMs` (3 s) or the candidate
is discarded and the machine falls back to plain RINGING.

### The state machine

```
IDLE → RINGING → PICKUP_DETECTED → NEAR_CANDIDATE → ANSWERING → CALL_ACTIVE
```

Any doubt at any point falls *backwards* toward RINGING (or IDLE once the call is
gone), never forwards into ANSWERING.

`core/AutoAnswerStateMachine.kt` owns no clock, no sensors and no Android types —
every instant arrives on the event, every side effect leaves through a callback. So
the entire answer decision is reproducible on the JVM, and it is covered by 26 unit
tests that need no device.

### Answering

Two answerers, tried in order, first success wins:

1. **Telecom (public API, preferred).** `TelecomManager.acceptRingingCall()`, gated
   on the runtime `ANSWER_PHONE_CALLS` permission. No root, no privileged access,
   no accessibility service. This is the path that runs in practice on a stock,
   unrooted handset.
2. **Root (optional fallback).** `su -c 'input keyevent 5'` — KEYCODE_CALL, the
   hardware answer key. This exists only for the cases where path 1 cannot work:
   the user declined `ANSWER_PHONE_CALLS`, or an OEM build refuses
   `acceptRingingCall()`. On an unrooted device it reports itself unavailable and
   is skipped.

   Root is **not required** to install or use the app. `root/RootExecutor.kt`
   accepts a fixed enum of commands, never an arbitrary string.

One answer attempt plus exactly one retry per call. After that the call is
abandoned for good, so a failing answer path can never be hammered.

---

## Permissions

Six, and no more.

| Permission | Why it is needed |
|---|---|
| `READ_PHONE_STATE` | To know a call is ringing at all, via `TelephonyCallback.CallStateListener` (RINGING / OFFHOOK / IDLE). No phone number is ever read, stored or logged. |
| `ANSWER_PHONE_CALLS` | The answer itself: `TelecomManager.acceptRingingCall()`. |
| `FOREGROUND_SERVICE` | The monitor runs as a foreground service so Android does not kill it while you are being called. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ requires an FGS *type*. `phoneCall` is gated behind the default-dialer role, which this app deliberately does not take, so `specialUse` is the correct declaration: the service exists to keep sensors sampling while a call rings. |
| `POST_NOTIFICATIONS` | To show the ongoing status notification. Without it the service still runs, but Android may stop it sooner. |
| `RECEIVE_BOOT_COMPLETED` | To restart monitoring after a reboot or an app update — but only if you had left auto-answer switched on. It never turns anything on by itself. |

Verify on the built APK yourself:

```sh
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | grep uses-permission
```

You should see exactly those six, plus
`<yourpackage>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX injects
into every app that registers a runtime receiver.

**What is deliberately absent:**

- No `SYSTEM_ALERT_WINDOW` ("display over other apps"). The app draws nothing over
  anything.
- No `BIND_NOTIFICATION_LISTENER_SERVICE` / notification access. The app cannot see
  your notifications.
- No `INTERNET`. Nothing can leave the device because there is nothing to leave
  through.
- No `CallScreeningService`, no `ROLE_CALL_SCREENING`, no default-dialer role. The
  app does not take over calling or caller ID.
- No `<queries>` block. The app does not enumerate what else you have installed.
- No `READ_CONTACTS`, no call log.

Sensors need no permission. Root is not a permission and is never requested.

---

## Why VoIP / WhatsApp calls are not supported

An earlier version tried, and it is worth being honest about why it was removed
rather than leaving a half-working feature in a public repo.

Apps like WhatsApp and Telegram register **self-managed** `ConnectionService`s. The
platform hard-blocks `TelecomManager.acceptRingingCall()` for a self-managed call —
silently, since the method returns `void`, so the standard answerer reports success
for a call it did not answer. Root `input keyevent` does not help either: the
framework routes it into the same blocked call.

The only remaining route is to fire the call notification's **own answer action**.
That is where it dies, and the reason is structural:

> **A VoIP app's answer action is an *activity* `PendingIntent`**, and Android
> blocks a backgrounded app from triggering another app's activity start
> (background activity launch, "BAL").

Captured on a Pixel 9 / Android 17, app backgrounded, answering a real WhatsApp
call:

```
E/ActivityTaskManager: Background activity launch blocked! goo.gle/android-bal
  intent: com.whatsapp/.calling.ui.VoipActivityV2 (ACCEPT_CALL)
  realCallingPackage: <this app>
  realCallerStartMode: MODE_BACKGROUND_ACTIVITY_START_ALLOWED
  balAllowedByPiSender: BSP.ALLOW_BAL
  resultIfPiSenderAllowsBal: BAL_BLOCK
  realCallingUidHasVisibleActivity: false
```

Firing the PendingIntent makes WhatsApp start `VoipActivityV2`, and the platform
judges that start against **this** app's background-launch privilege, not
WhatsApp's. A foreground service does not confer that privilege on Android 14+, and
a sender cannot delegate a privilege it does not hold — which is why passing
`MODE_BACKGROUND_ACTIVITY_START_ALLOWED` is not enough on its own.
`realCallingUidHasVisibleActivity: false` is the whole verdict in one line.

With the app **on screen**, the identical send succeeds. That is precisely the
problem: a raise-to-ear feature that only works while you are already staring at
the app is not a feature. Holding `SYSTEM_ALERT_WINDOW` is on Android's documented
BAL exemption list and did move the needle in testing, but shipping an
overlay-permission request for a capability that still cannot be guaranteed was not
a trade worth making.

So the feature — the notification listener, the package allowlist, the answer-action
resolver, the multi-source call merging and the source arbiter — was deleted
outright, along with `SYSTEM_ALERT_WINDOW` and the `<queries>` block. SIM calls need
none of it: `acceptRingingCall()` is not an activity start.

If you want to revisit it, the honest starting point is that you need a
platform-granted BAL exemption, not a cleverer PendingIntent.

---

## Build

Requires JDK 17+ and the Android SDK. Nothing else — no keys, no accounts.

```sh
git clone <this repo>
cd ear-auto-answer

./gradlew :app:testDebugUnitTest     # JVM unit tests, no device needed
./gradlew :app:assembleDebug         # debug APK
./gradlew :app:assembleRelease       # release APK
```

`gradle.properties` pins `org.gradle.java.home` to the JDK bundled with Android
Studio, so `./gradlew` works from any shell without `JAVA_HOME`. **On a fresh
clone, change or delete that line** if your JDK lives elsewhere.

Release signing reads an optional `keystore.properties` at the project root:

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

If that file is absent the release build still succeeds — it just produces an
unsigned APK. Neither the keystore nor the properties file is committed.

Minification is deliberately off. The gain is small for an app this size, and R8
rules are one more thing that can silently break a service you only find out about
during a real phone call.

## Install

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to the phone and open it (you will have to allow installing from
that source once).

Then, on the phone:

1. Open **Ear Auto Answer** and turn the main switch **on**.
2. Grant the three runtime permissions when asked — Phone, Answer calls,
   Notifications. If Phone or Answer calls is denied, the switch stays off and the
   app tells you why; nothing is silently half-enabled.
3. The **STATUS** card should read all-green: both permissions granted, proximity
   sensor present, accelerometer present, monitor service running.

One foreground service then runs for as long as the switch is on. Sensors are still
registered only while a call is actually ringing.

Some OEM builds (Xiaomi, Oppo, Vivo, Samsung to a lesser degree) aggressively kill
background services. If the monitor keeps stopping, exclude the app from battery
optimisation in system settings.

---

## Calibrating the motion thresholds

Every tunable number lives in `core/AutoAnswerConfig.kt` and nowhere else — nothing
else in the project may hard-code a threshold or a duration. Defaults were tuned on
a Pixel; a different handset may want different ones.

Open the app → **Test Sensors**. It shows live proximity (NEAR/FAR + cm), raw
accelerometer and gyroscope axes, and — importantly — the exact derived features the
state machine compares against, each labelled with the threshold it is judged by.

Then, in order of how much they matter:

**1. `maxSettleMovementEnergy`** (default `1.5`) — the one most worth checking.
Watch the `settleEnergy` row (`recentMovementEnergy`).

| Do this | Expect | Meaning |
|---|---|---|
| Hold the phone at your ear, wait a second | well under 1 | must **pass** |
| Same, while walking | somewhat higher | must still **pass** |
| Phone in a trouser pocket, walking | clearly higher | must **fail** |

Set the threshold between the second and the third. Too low and calls taken while
walking get missed; too high and the pocket case gets through. This single number is
the difference between the app's headline promise holding and not.

**2. `pickupMovementThreshold`** (default `2.0`) — watch `movementEnergy`. Lay the
phone on a table and read it (expect ~`0.02`). Lift it to your ear and watch the
peak (expect well above `2`). Set the threshold roughly midway.

**3. `earTiltMinDegrees` / `earTiltMaxDegrees` / `faceDownTiltDegrees`**
(defaults `40` / `140` / `150`) — hold the phone at your ear in every posture you
actually use: standing, sitting, lying down, left ear, right ear. Note the
`screenTilt` range across all of them and widen the min/max to cover it with a
little margin.

**4. `minEarPitchDegrees`** (default `-45`) — read `pitch` at the ear (strongly
positive) and with the phone dropped head-first into a pocket (strongly negative).
Anywhere comfortably between the two is fine.

**5. Timings** — `proximityDebounceMs` (500 ms) is how long the phone must stay at
your ear before it answers; raising it makes the app more cautious and slower.
`settleWindowMs` (300 ms) **must stay comfortably shorter than
`proximityDebounceMs`** — see the note in the gesture section above; getting this
wrong makes the stillness gate unpassable and no gesture will ever answer.

Edit `AutoAnswerConfig.kt`, rebuild, reinstall. The unit tests use their own shrunk
config and are unaffected.

**Use the Diagnostics screen.** It shows the machine's own reasoning for every ring,
including the exact rule that rejected a candidate *and the measured value that
failed it* — e.g. `Phone is still moving (energy 2.3, want <= 1.5)`. That is almost
always faster than guessing. It has a Share button for pulling a log off the device.

---

## Manual test checklist

Run these against real incoming calls with auto-answer on. **The first group is the
important one** — a false positive (answering when you did not mean to) is far worse
than a miss.

### Must NOT answer

- [ ] Phone in a trouser pocket for the whole ring.
- [ ] Phone in a bag or backpack for the whole ring.
- [ ] Phone taken out of a pocket, looked at, and pushed back in while it is still
      ringing. *(Historically the worst case: every proximity and tilt gate passes.
      Only the stillness gate catches it.)*
- [ ] Phone in a pocket while walking, then while running.
- [ ] Phone lying face-down on a table, tapped or nudged.
- [ ] Phone lying face-up on a table with a hand waved over it.
- [ ] Phone picked up and put down again without reaching your ear.
- [ ] Phone picked up and held in front of your face to read the caller's name.
- [ ] Phone picked up, then covered by resting it against your chest or a cushion.
- [ ] Phone handed to someone else while ringing.
- [ ] Phone already sitting face-down when the call arrives, then the table is
      bumped.
- [ ] Auto-answer switched off mid-ring — the candidate is cancelled immediately.

### Must answer

- [ ] Phone on a table, picked up and raised to your ear — answers within about a
      second of arriving at the ear.
- [ ] Same, pulled from a pocket and raised to the ear in one continuous movement.
- [ ] Same, while walking.
- [ ] Same, lying on your side in bed.
- [ ] Both ears.

### Lifecycle and teardown

- [ ] After any answer: sensors unregistered (check Diagnostics) and the
      notification reads "Call in progress".
- [ ] Call answered manually, or rejected — sensors unregistered, state back to
      IDLE.
- [ ] Outgoing call placed normally — the machine goes straight to CALL_ACTIVE and
      no gesture is ever evaluated.
- [ ] Reboot with auto-answer on — the service comes back by itself.
- [ ] Revoke the Phone permission in system settings while the service is running —
      Diagnostics reports "Call-state monitoring unavailable". Grant it back and the
      next start recovers.
- [ ] Two calls back to back — the second one is armed from a clean slate.

---

## Known limitations

**A phone that is already at your ear when the call arrives will not auto-answer.**
The pocket guard requires proximity to be seen FAR *after* the call starts ringing,
and a sensor covered since before the call is deliberately never trusted — that is
the same condition as sitting in a pocket, and the sensor cannot tell them apart.
Move the phone away from your ear and bring it back, or answer manually.

By design, and not bugs:

- **Auto-answer never arms mid-ring.** Turning the switch on while a call is already
  ringing does nothing until the next call, because the pocket guard needs to have
  watched the call from its very beginning.
- **One attempt plus one retry**, then the call is abandoned for good.
- **SIM calls only.** See the VoIP section above.
- **Requires both a proximity sensor and an accelerometer.** Both are declared
  `required="true"` in the manifest.

## Privacy

Nothing leaves the device, and there is no code that could send it anywhere. No
phone number, contact name, or message is ever read or logged — the call layer maps
telephony state to `IDLE | RINGING | ACTIVE` and discards everything else. The
diagnostics log holds only the state machine's own reasoning, lives in memory, and
is shared only if you press Share yourself.

## Project layout

```
core/          the state machine, its types and every tunable value (pure Kotlin, unit-tested)
sensors/       accelerometer/proximity registration and the derived-feature analyzer
call/          telephony state monitoring and the answerers
root/          the fixed-command privileged executor (optional path)
service/       the wiring hub: foreground service, notifications, start/stop
boot/          restore-after-reboot and wake-on-ring receivers
ui/            Compose screens: main, sensor test, diagnostics
diagnostics/   the in-memory log
```

`CONTRACT.md` documents the exact transition table and the interfaces between these
pieces. If you are changing the state machine, read it first.

## Licence

MIT. See `LICENSE`.
