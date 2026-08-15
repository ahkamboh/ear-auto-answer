package com.example.earautoanswer.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.earautoanswer.root.RootExecutor
import com.example.earautoanswer.service.CallMonitorService
import com.example.earautoanswer.service.ServiceController
import com.example.earautoanswer.settings.SettingsRepository
import com.example.earautoanswer.ui.theme.StatusPalette

/**
 * Requested in this exact order: the two that gate the feature first, the
 * cosmetic one last, so a user who bails out of the dialog stack has already
 * answered the questions that matter.
 */
private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.ANSWER_PHONE_CALLS,
    Manifest.permission.POST_NOTIFICATIONS,
)

@Composable
fun MainScreen(
    onOpenSensorTest: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context) { SettingsRepository.get(context) }

    val enabled by settings.autoAnswerEnabled.collectAsState()
    val serviceRunning by CallMonitorService.isRunning.collectAsState()

    // Permission grants are read on every resume: the user can revoke them from
    // system settings while this screen is in the background.
    var permissionEpoch by remember { mutableIntStateOf(0) }
    ObserveResume { permissionEpoch++ }

    val phoneStateGranted = remember(permissionEpoch) {
        context.isPermissionGranted(Manifest.permission.READ_PHONE_STATE)
    }
    val answerGranted = remember(permissionEpoch) {
        context.isPermissionGranted(Manifest.permission.ANSWER_PHONE_CALLS)
    }
    val notificationsGranted = remember(permissionEpoch) {
        context.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
    }

    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val hasProximity = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
    }
    val hasAccelerometer = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    // null until the probe has actually run — an unknown root state is never
    // allowed to render as a pass.
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        rootAvailable = RootExecutor.isRootAvailable()
    }

    var notice by rememberSaveable { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionEpoch++
        // The contract omits keys it short-circuited, so fall back to a live read.
        val phoneOk = result[Manifest.permission.READ_PHONE_STATE]
            ?: context.isPermissionGranted(Manifest.permission.READ_PHONE_STATE)
        val answerOk = result[Manifest.permission.ANSWER_PHONE_CALLS]
            ?: context.isPermissionGranted(Manifest.permission.ANSWER_PHONE_CALLS)
        val notifyOk = result[Manifest.permission.POST_NOTIFICATIONS]
            ?: context.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)

        if (phoneOk && answerOk) {
            settings.setAutoAnswerEnabled(true)
            ServiceController.start(context)
            notice = if (notifyOk) {
                null
            } else {
                "Auto-answer is on. Notifications are blocked, so the ongoing status " +
                    "notification will not be visible — Android may stop the monitor " +
                    "sooner as a result. Allow notifications to make it reliable."
            }
        } else {
            // Nothing is persisted and nothing is started unless both gating
            // permissions came back granted.
            settings.setAutoAnswerEnabled(false)
            notice = buildString {
                append("Auto-answer stayed off. It needs ")
                val missing = buildList {
                    if (!phoneOk) add("Phone (to know a call is ringing)")
                    if (!answerOk) add("Answer calls (to pick it up)")
                }
                append(missing.joinToString(" and "))
                append(". Grant these and try the switch again.")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Ear Auto Answer",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Answers an incoming call when you pick the phone up and raise it to " +
                "your ear. It never answers from a pocket or a bag.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-answer",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (enabled) "On — watching for calls" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { wantsOn ->
                        notice = null
                        if (wantsOn) {
                            if (phoneStateGranted && answerGranted && notificationsGranted) {
                                settings.setAutoAnswerEnabled(true)
                                ServiceController.start(context)
                            } else {
                                // Persisting happens in the launcher callback, never here.
                                permissionLauncher.launch(REQUIRED_PERMISSIONS)
                            }
                        } else {
                            settings.setAutoAnswerEnabled(false)
                            ServiceController.stop(context)
                        }
                    },
                )
            }
        }

        val missingCritical = !phoneStateGranted || !answerGranted

        notice?.let { message ->
            NoticeCard(
                message = message,
                level = if (missingCritical) StatusLevel.BAD else StatusLevel.WARN,
                actionLabel = if (missingCritical) "Open app permissions" else null,
                onAction = { context.openAppSettings() },
            )
        }

        if (enabled && missingCritical) {
            NoticeCard(
                message = "Auto-answer is switched on but a required permission has been " +
                    "revoked, so it cannot answer anything.",
                level = StatusLevel.BAD,
                actionLabel = "Grant permissions",
                onAction = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
            )
        }

        Text(
            text = "STATUS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                StatusRow(
                    label = "Phone state permission",
                    value = if (phoneStateGranted) "Granted" else "Denied",
                    level = if (phoneStateGranted) StatusLevel.OK else StatusLevel.BAD,
                    detail = if (phoneStateGranted) null else "Required — without it no ring is seen.",
                )
                RowDivider()
                StatusRow(
                    label = "Answer calls permission",
                    value = if (answerGranted) "Granted" else "Denied",
                    level = if (answerGranted) StatusLevel.OK else StatusLevel.BAD,
                    detail = if (answerGranted) null else "Required — without it the call cannot be picked up.",
                )
                RowDivider()
                StatusRow(
                    label = "Notifications permission",
                    value = if (notificationsGranted) "Granted" else "Denied",
                    level = if (notificationsGranted) StatusLevel.OK else StatusLevel.WARN,
                    detail = if (notificationsGranted) {
                        null
                    } else {
                        "The monitor still runs, but its status notification is hidden."
                    },
                )
                RowDivider()
                StatusRow(
                    label = "Proximity sensor",
                    value = if (hasProximity) "Present" else "Missing",
                    level = if (hasProximity) StatusLevel.OK else StatusLevel.BAD,
                    detail = if (hasProximity) null else "This device cannot detect the ear.",
                )
                RowDivider()
                StatusRow(
                    label = "Accelerometer",
                    value = if (hasAccelerometer) "Present" else "Missing",
                    level = if (hasAccelerometer) StatusLevel.OK else StatusLevel.BAD,
                    detail = if (hasAccelerometer) null else "This device cannot detect the pickup.",
                )
                RowDivider()
                StatusRow(
                    label = "Monitor service",
                    value = if (serviceRunning) "Running" else "Stopped",
                    level = when {
                        serviceRunning -> StatusLevel.OK
                        !enabled -> StatusLevel.UNKNOWN
                        else -> StatusLevel.WARN
                    },
                    detail = when {
                        serviceRunning -> null
                        !enabled -> "Starts when auto-answer is switched on."
                        else -> "Switched on but not running — toggle it off and on again."
                    },
                )
                RowDivider()
                StatusRow(
                    label = "Root access",
                    value = when (rootAvailable) {
                        null -> "Checking…"
                        true -> "Available"
                        false -> "Not available"
                    },
                    level = when (rootAvailable) {
                        null -> StatusLevel.UNKNOWN
                        true -> StatusLevel.OK
                        false -> StatusLevel.WARN
                    },
                    detail = when (rootAvailable) {
                        null -> "Probing for su…"
                        true -> "Used only as a fallback if the telecom answer fails."
                        false -> "Optional. Answering uses the standard telecom API."
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onOpenSensorTest,
                modifier = Modifier.weight(1f),
            ) {
                Text("Test Sensors")
            }
            OutlinedButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.weight(1f),
            ) {
                Text("Diagnostics")
            }
        }

        Text(
            text = "Nothing leaves the device. No phone number, contact name or message is " +
                "ever read or logged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

// ---------------------------------------------------------------------------
// Shared pieces, used by every screen in this package.
// ---------------------------------------------------------------------------

internal enum class StatusLevel { OK, WARN, BAD, UNKNOWN }

internal val StatusLevel.color: Color
    get() = when (this) {
        StatusLevel.OK -> StatusPalette.ok
        StatusLevel.WARN -> StatusPalette.warn
        StatusLevel.BAD -> StatusPalette.bad
        StatusLevel.UNKNOWN -> StatusPalette.unknown
    }

@Composable
internal fun StatusRow(
    label: String,
    value: String,
    level: StatusLevel,
    detail: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(9.dp)
                .background(level.color, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = level.color,
        )
    }
}

/** Label on the left, fixed-width value on the right. Used by the sensor screen. */
@Composable
internal fun ValueRow(
    label: String,
    value: String,
    valueColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
internal fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text("Back")
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
internal fun NoticeCard(
    message: String,
    level: StatusLevel,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .size(9.dp)
                        .background(level.color, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (actionLabel != null) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * Re-reads permission state whenever the activity resumes, without depending on
 * `LocalLifecycleOwner` — the hosting activity is found by unwrapping the
 * context, which is stable across Compose versions.
 */
@Composable
internal fun ObserveResume(onResume: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    DisposableEffect(activity) {
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) onResume()
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }
}

internal fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Compose hands out a themed wrapper, not the activity — unwrap to reach it. */
internal fun Context.findComponentActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}

internal fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
