package com.shegrowsdev.mail.backup

import android.content.Context
import com.shegrowsdev.mail.data.CredentialCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Outcome of the last automatic backup, shown on the backup screen. */
data class BackupStatus(val at: Long, val ok: Boolean)

/**
 * Automatic-backup switch and its passphrase. The passphrase is kept only encrypted with the Android
 * Keystore key (like mail passwords), so the background job can encrypt backups without asking; it is never
 * written to a file in clear text, to a log or to a backup. Backups themselves can be opened on another
 * device only with the same passphrase, so the administrator must keep it.
 */
class BackupSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("company_mail_backup", Context.MODE_PRIVATE)

    private val _autoEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO, false))
    val autoEnabled: StateFlow<Boolean> = _autoEnabled.asStateFlow()

    private val _lastAuto = MutableStateFlow(readStatus())
    val lastAuto: StateFlow<BackupStatus?> = _lastAuto.asStateFlow()

    private val _hasPassphrase = MutableStateFlow(prefs.contains(KEY_PASS))
    val hasPassphrase: StateFlow<Boolean> = _hasPassphrase.asStateFlow()

    fun setAutoEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
        _autoEnabled.value = on
    }

    /** Stores the passphrase for the automatic job (Keystore-encrypted). */
    fun savePassphrase(passphrase: CharArray) {
        prefs.edit().putString(KEY_PASS, CredentialCipher.encrypt(String(passphrase))).apply()
        _hasPassphrase.value = true
    }

    fun clearPassphrase() {
        prefs.edit().remove(KEY_PASS).apply()
        _hasPassphrase.value = false
    }

    /** Null when none is stored or the Keystore key can no longer decrypt it. */
    fun passphrase(): CharArray? =
        prefs.getString(KEY_PASS, null)?.let { CredentialCipher.decrypt(it) }?.toCharArray()

    fun recordAuto(status: BackupStatus) {
        prefs.edit().putLong(KEY_LAST_AT, status.at).putBoolean(KEY_LAST_OK, status.ok).apply()
        _lastAuto.value = status
    }

    private fun readStatus(): BackupStatus? =
        if (prefs.contains(KEY_LAST_AT)) BackupStatus(prefs.getLong(KEY_LAST_AT, 0), prefs.getBoolean(KEY_LAST_OK, false)) else null

    companion object {
        private const val KEY_AUTO = "auto"
        private const val KEY_PASS = "passphrase"
        private const val KEY_LAST_AT = "last_at"
        private const val KEY_LAST_OK = "last_ok"
    }
}
