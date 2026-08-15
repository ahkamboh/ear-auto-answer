package com.example.earautoanswer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.earautoanswer.diagnostics.DiagnosticLogger

/**
 * The in-memory technical log, newest last. Nothing here identifies a call — the
 * logger's contract forbids it — so the list is safe to read over someone's shoulder.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val entries by DiagnosticLogger.entries.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Tail behaviour: a new line always brings the view to the bottom.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader(
            title = "Diagnostics",
            onBack = onBack,
            trailing = {
                Row {
                    // Screenshotting a scrolling monospace list is a miserable way to
                    // report a bug, and a truncated log is worse than none — the line
                    // that matters is usually the one that got cropped. Sharing the
                    // whole buffer as plain text costs nothing and is still safe: the
                    // logger's contract forbids anything call-identifying from ever
                    // reaching it, so there is nothing here to redact.
                    TextButton(
                        onClick = {
                            val text = entries.joinToString("\n") { DiagnosticLogger.format(it) }
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Ear Auto Answer diagnostics")
                                .putExtra(Intent.EXTRA_TEXT, text)
                            try {
                                context.startActivity(
                                    Intent.createChooser(send, "Share diagnostics")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } catch (t: Throwable) {
                                // No share target on the device; the list is still readable.
                            }
                        },
                        enabled = entries.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("Share")
                    }
                    TextButton(
                        onClick = { DiagnosticLogger.clear() },
                        enabled = entries.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("Clear")
                    }
                }
            },
        )

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No entries yet.\nSwitch auto-answer on and place a test call.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(entries) { entry ->
                    Text(
                        text = DiagnosticLogger.format(entry),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}
