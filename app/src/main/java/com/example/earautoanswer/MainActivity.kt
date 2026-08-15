package com.example.earautoanswer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.earautoanswer.ui.DiagnosticsScreen
import com.example.earautoanswer.ui.MainScreen
import com.example.earautoanswer.ui.SensorTestScreen
import com.example.earautoanswer.ui.theme.EarAutoAnswerTheme

/** Three screens, one activity, no navigation library. */
private enum class Screen {
    MAIN,
    SENSOR_TEST,
    DIAGNOSTICS,
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EarAutoAnswerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    EarAutoAnswerApp()
                }
            }
        }
    }
}

@Composable
private fun EarAutoAnswerApp() {
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }

    // System back returns to the main screen instead of leaving the app.
    BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }

    when (screen) {
        Screen.MAIN -> MainScreen(
            onOpenSensorTest = { screen = Screen.SENSOR_TEST },
            onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
        )

        Screen.SENSOR_TEST -> SensorTestScreen(onBack = { screen = Screen.MAIN })

        Screen.DIAGNOSTICS -> DiagnosticsScreen(onBack = { screen = Screen.MAIN })
    }
}
