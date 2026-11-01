package com.shegrowsdev.mail.admin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reminder preferences (device-wide). Stored in app-private SharedPreferences. */
class AdminSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("company_mail_admin", Context.MODE_PRIVATE)

    private val _remindersEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val remindersEnabled: StateFlow<Boolean> = _remindersEnabled.asStateFlow()

    private val _leadMinutes = MutableStateFlow(prefs.getInt(KEY_LEAD, DEFAULT_LEAD_MINUTES))
    val leadMinutes: StateFlow<Int> = _leadMinutes.asStateFlow()

    private val _instantEnabled = MutableStateFlow(prefs.getBoolean(KEY_INSTANT, true))

    /** Instant new-mail notifications (a persistent connection to the mail server). On by default. */
    val instantEnabled: StateFlow<Boolean> = _instantEnabled.asStateFlow()

    fun setInstantEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_INSTANT, on).apply()
        _instantEnabled.value = on
    }

    fun setRemindersEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _remindersEnabled.value = on
    }

    fun setLeadMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_LEAD, minutes).apply()
        _leadMinutes.value = minutes
    }

    val leadMillis: Long get() = _leadMinutes.value * 60_000L

    companion object {
        private const val KEY_ENABLED = "reminders_enabled"
        private const val KEY_INSTANT = "instant_enabled"
        private const val KEY_LEAD = "reminder_lead_minutes"
        const val DEFAULT_LEAD_MINUTES = 60
        /** Choices offered in Settings: 15 min, 1 h, 4 h, 1 day. */
        val LEAD_CHOICES = listOf(15, 60, 240, 1440)
    }
}
