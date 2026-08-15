package com.example.earautoanswer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.earautoanswer.call.CallStateMonitor
import com.example.earautoanswer.call.PhoneCallState
import com.example.earautoanswer.core.AutoAnswerConfig
import com.example.earautoanswer.root.RootExecutor
import com.example.earautoanswer.sensors.LiveSensorMonitor
import com.example.earautoanswer.ui.theme.StatusPalette
import java.util.Locale

/**
 * Live sensor readout, used to calibrate [AutoAnswerConfig] on a real handset.
 * Completely independent of the answer pipeline — nothing here can answer a call.
 */
@Composable
fun SensorTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config = remember { AutoAnswerConfig.DEFAULT }

    val activity = remember(context) { context.findComponentActivity() }

    val monitor = remember(context) { LiveSensorMonitor(context) }
    // Tied to the activity lifecycle, not to the composition. Backgrounding this
    // screen does not leave the composition, so a plain DisposableEffect would
    // keep the accelerometer at SENSOR_DELAY_GAME and the gyroscope registered for
    // as long as the app sat in the background — and the foreground service keeps
    // the process important enough that the platform's idle-app sensor throttling
    // would not step in to hide it.
    LifecycleBoundEffect(
        activity = activity,
        key = monitor,
        onStart = { monitor.start() },
        onStop = { monitor.stop() },
    )
    val reading by monitor.readings.collectAsState()

    // The call state is read for display only; the monitor deduplicates and never
    // exposes a number.
    var callState by remember { mutableStateOf<PhoneCallState?>(null) }
    var callStateReadable by remember { mutableStateOf(true) }
    val callMonitor = remember(context) { CallStateMonitor(context) { state -> callState = state } }
    // Same reasoning: telephony callbacks must not keep firing into a screen the
    // user has navigated away from.
    LifecycleBoundEffect(
        activity = activity,
        key = callMonitor,
        onStart = {
            val started = callMonitor.start()
            callStateReadable = started
            if (started) callState = callMonitor.currentState()
        },
        onStop = { callMonitor.stop() },
    )

    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        rootAvailable = RootExecutor.isRootAvailable()
    }

    val features = reading.features
    val accel = formatAxes(reading.accel)
    val gyro = formatAxes(reading.gyro)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(title = "Sensor Test", onBack = onBack)

        Text(
            text = "Live values, refreshed straight from the sensors. Use this to check " +
                "the thresholds behave on this handset.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        SectionCard(title = "PROXIMITY") {
            val near = reading.proximityNear
            ValueRow(
                label = "State",
                value = when (near) {
                    null -> "no reading"
                    true -> "NEAR"
                    false -> "FAR"
                },
                valueColor = when (near) {
                    null -> StatusPalette.unknown
                    true -> StatusPalette.ok
                    false -> null
                },
            )
            ValueRow(label = "Distance", value = formatNumber(reading.proximityCm, suffix = " cm"))
            ValueRow(
                label = "Sensor max range",
                value = formatNumber(reading.proximityMaxRangeCm, suffix = " cm"),
            )
        }

        SectionCard(title = "ACCELEROMETER  (m/s²)") {
            ValueRow(label = "x", value = accel.getOrElse(0) { NO_VALUE })
            ValueRow(label = "y", value = accel.getOrElse(1) { NO_VALUE })
            ValueRow(label = "z", value = accel.getOrElse(2) { NO_VALUE })
        }

        SectionCard(title = "GYROSCOPE  (rad/s)") {
            if (!reading.hasGyroscope) {
                ValueRow(
                    label = "Sensor",
                    value = "not present",
                    valueColor = StatusPalette.warn,
                )
            } else {
                ValueRow(label = "x", value = gyro.getOrElse(0) { NO_VALUE })
                ValueRow(label = "y", value = gyro.getOrElse(1) { NO_VALUE })
                ValueRow(label = "z", value = gyro.getOrElse(2) { NO_VALUE })
            }
        }

        SectionCard(title = "DERIVED") {
            // movementEnergy is the RMS deviation of |accel| from 1g over the recent
            // window: ~0 when the phone is still, well above the threshold while it
            // is being lifted.
            val energy = features?.movementEnergy
            ValueRow(
                label = "movementEnergy  (pickup ≥ ${formatNumber(config.pickupMovementThreshold)})",
                value = formatNumber(energy),
                valueColor = when {
                    energy == null -> StatusPalette.unknown
                    energy >= config.pickupMovementThreshold -> StatusPalette.ok
                    else -> null
                },
            )

            // recentMovementEnergy is the same figure over the much shorter settle
            // window. This is the one the settle gate reads: hold the phone at your
            // ear and it must fall to green (at or below the threshold) within about
            // half a second, otherwise no gesture will ever validate.
            val settleEnergy = features?.recentMovementEnergy
            ValueRow(
                label = "settleEnergy  (ear ≤ ${formatNumber(config.maxSettleMovementEnergy)})",
                value = formatNumber(settleEnergy),
                valueColor = when {
                    settleEnergy == null -> StatusPalette.unknown
                    settleEnergy <= config.maxSettleMovementEnergy -> StatusPalette.ok
                    else -> StatusPalette.bad
                },
            )

            // screenTiltDegrees is the angle between the screen normal and world-up:
            // 0 = flat face-up, 90 = screen vertical, 180 = flat face-down. A handset
            // held at an ear lands inside the ear range; past faceDownTiltDegrees the
            // phone is face-down and must never answer.
            val tilt = features?.screenTiltDegrees
            val tiltInEarRange = tilt != null &&
                tilt >= config.earTiltMinDegrees &&
                tilt <= config.earTiltMaxDegrees &&
                tilt < config.faceDownTiltDegrees
            ValueRow(
                label = "screenTilt  (ear ${formatNumber(config.earTiltMinDegrees, 0)}–" +
                    "${formatNumber(config.earTiltMaxDegrees, 0)}°)",
                value = formatNumber(tilt, suffix = "°"),
                valueColor = when {
                    tilt == null -> StatusPalette.unknown
                    tiltInEarRange -> StatusPalette.ok
                    tilt >= config.faceDownTiltDegrees -> StatusPalette.bad
                    else -> null
                },
            )

            // pitchDegrees is the elevation of the +Y axis (the earpiece end) above
            // horizontal: +90 held upright, -90 inverted.
            ValueRow(
                label = "pitch",
                value = formatNumber(features?.pitchDegrees, suffix = "°"),
                valueColor = if (features == null) StatusPalette.unknown else null,
            )
        }

        SectionCard(title = "CONTEXT") {
            ValueRow(
                label = "Call state",
                value = when {
                    !callStateReadable -> "permission denied"
                    callState == null -> "no reading"
                    else -> callState.toString()
                },
                valueColor = when {
                    !callStateReadable -> StatusPalette.bad
                    callState == null -> StatusPalette.unknown
                    callState == PhoneCallState.RINGING -> StatusPalette.ok
                    else -> null
                },
            )
            ValueRow(
                label = "Root access",
                value = when (rootAvailable) {
                    null -> "checking…"
                    true -> "available"
                    false -> "not available"
                },
                valueColor = when (rootAvailable) {
                    null -> StatusPalette.unknown
                    true -> StatusPalette.ok
                    false -> StatusPalette.warn
                },
            )
        }
    }
}

/**
 * Runs [onStart] / [onStop] on the hosting activity's ON_START / ON_STOP, so a
 * subscription lives exactly as long as the screen is actually visible.
 *
 * Compose's own `DisposableEffect` is the wrong tool on its own here: a
 * composition survives the activity being stopped, so anything expensive started
 * from one keeps running while the app sits in the background.
 */
@Composable
private fun LifecycleBoundEffect(
    activity: ComponentActivity?,
    key: Any?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnStop by rememberUpdatedState(onStop)

    DisposableEffect(activity, key) {
        val lifecycle = activity?.lifecycle
        if (lifecycle == null) {
            // No activity to observe (a preview, or an unusual host): fall back to
            // the composition's lifetime rather than never starting at all.
            currentOnStart()
            onDispose { currentOnStop() }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> currentOnStart()
                    Lifecycle.Event.ON_STOP -> currentOnStop()
                    else -> Unit
                }
            }
            // addObserver replays the events already passed, so a lifecycle that is
            // STARTED by the time this runs gets its ON_START immediately.
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                // Leaving the screen while still resumed never produces an
                // ON_STOP, so stop here too. Both callbacks are idempotent.
                currentOnStop()
            }
        }
    }
}

private const val NO_VALUE = "—"

private fun formatNumber(value: Float?, decimals: Int = 2, suffix: String = ""): String =
    if (value == null) NO_VALUE else String.format(Locale.US, "%.${decimals}f%s", value, suffix)

/**
 * Renders the raw axis triple. Accepts either representation of the axes so this
 * screen stays decoupled from how [LiveSensorMonitor.Reading] chooses to carry them.
 */
private fun formatAxes(values: Any?): List<String> = when (values) {
    null -> emptyList()
    is FloatArray -> values.map { formatAxis(it) }
    is DoubleArray -> values.map { formatAxis(it.toFloat()) }
    is List<*> -> values.map { element ->
        (element as? Number)?.let { formatAxis(it.toFloat()) } ?: NO_VALUE
    }

    else -> emptyList()
}

private fun formatAxis(value: Float): String = String.format(Locale.US, "%+.3f", value)
