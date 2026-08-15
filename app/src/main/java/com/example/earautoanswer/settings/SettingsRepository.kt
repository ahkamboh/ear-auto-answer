package com.example.earautoanswer.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The persisted settings: one master on/off switch, and nothing else.
 *
 * SharedPreferences rather than DataStore — a single boolean does not justify a
 * dependency, and the boot receiver needs a synchronous read.
 *
 * Safety default: auto-answer OFF. An unreadable or missing value means off, so a
 * fresh install (or a corrupt preferences file) never answers a call by itself.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _autoAnswerEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    /** Hot state, safe to collect from the UI and from the service. */
    val autoAnswerEnabled: StateFlow<Boolean> = _autoAnswerEnabled.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) {
                _autoAnswerEnabled.value = prefs.getBoolean(KEY_ENABLED, false)
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Synchronous read, for the boot receiver and other one-shot callers. */
    fun isAutoAnswerEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setAutoAnswerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _autoAnswerEnabled.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "ear_auto_answer_settings"
        private const val KEY_ENABLED = "auto_answer_enabled"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
