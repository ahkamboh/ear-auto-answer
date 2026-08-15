package com.example.earautoanswer.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory, bounded, non-persistent technical log shared by the service and the
 * diagnostics screen.
 *
 * Privacy rule, enforced by convention and by review: nothing that identifies a
 * call may be passed to [log]. No phone numbers, no contact names, no content.
 * Only state transitions, sensor decisions and answer outcomes.
 */
object DiagnosticLogger {

    private const val MAX_ENTRIES = 300

    data class Entry(val timestampMs: Long, val message: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** logcat tag, so the same stream is readable over adb without opening the app. */
    private const val TAG = "EarAutoAnswer"

    fun log(message: String) {
        val entry = Entry(System.currentTimeMillis(), message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        // Mirrored to logcat deliberately. The in-memory buffer is only readable by
        // opening the app, which is useless for diagnosing a failure that happens
        // *because* the app is not open. Safe by the same contract that governs this
        // class: nothing call-identifying is ever passed to log().
        android.util.Log.i(TAG, message)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** "15:20:03  Proximity -> NEAR" */
    fun format(entry: Entry): String {
        val totalSeconds = entry.timestampMs / 1000
        val secondOfDay = ((totalSeconds % 86_400) + 86_400) % 86_400
        val hours = secondOfDay / 3600
        val minutes = (secondOfDay % 3600) / 60
        val seconds = secondOfDay % 60
        return "%02d:%02d:%02d  %s".format(hours, minutes, seconds, entry.message)
    }
}
