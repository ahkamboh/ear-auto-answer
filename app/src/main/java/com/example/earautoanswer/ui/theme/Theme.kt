package com.example.earautoanswer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Fixed semantic colours for the status list.
 *
 * These deliberately sit outside the Material colour scheme: with dynamic colour
 * the wallpaper decides what `primary` looks like, and "the answer permission is
 * missing" must never be rendered in a hue that happens to read as success.
 */
object StatusPalette {
    /** Checked and present. */
    val ok = Color(0xFF4CAF50)

    /** Present-but-degraded, or optional and absent. Not fatal. */
    val warn = Color(0xFFFFB300)

    /** Required and absent — auto-answer cannot work. */
    val bad = Color(0xFFE53935)

    /** Not probed yet. Never rendered as a pass. */
    val unknown = Color(0xFF9E9E9E)
}

@Composable
fun EarAutoAnswerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
