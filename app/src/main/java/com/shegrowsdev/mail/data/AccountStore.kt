package com.shegrowsdev.mail.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Position of the last inbox message already notified, per account (avoids duplicate notifications). */
data class NotifyMarker(val validity: Long, val lastUid: Long)

/**
 * Local store for account settings (SharedPreferences, app-private, excluded from backup) and
 * Keystore-encrypted passwords. Account metadata contains no secrets.
 */
class AccountStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _accounts = MutableStateFlow(readAccounts())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun get(id: String): Account? = _accounts.value.firstOrNull { it.id == id }

    fun active(): Account? = _activeId.value?.let { get(it) } ?: _accounts.value.firstOrNull()

    fun emailExists(email: String): Boolean =
        _accounts.value.any { it.email.equals(email.trim(), ignoreCase = true) }

    /** Encrypts first; if the Keystore fails nothing is written. */
    fun add(account: Account, password: String) = synchronized(lock) {
        val encrypted = CredentialCipher.encrypt(password)
        val list = _accounts.value + account
        prefs.edit()
            .putString(KEY_ACCOUNTS, toJson(list))
            .putString(pwKey(account.id), encrypted)
            .putString(KEY_ACTIVE, account.id)
            .apply()
        _accounts.value = list
        _activeId.value = account.id
    }

    fun update(account: Account) = synchronized(lock) {
        val list = _accounts.value.map { if (it.id == account.id) account else it }
        prefs.edit().putString(KEY_ACCOUNTS, toJson(list)).apply()
        _accounts.value = list
    }

    fun remove(id: String) = synchronized(lock) {
        val list = _accounts.value.filterNot { it.id == id }
        val newActive = if (_activeId.value == id) list.firstOrNull()?.id else _activeId.value
        prefs.edit()
            .putString(KEY_ACCOUNTS, toJson(list))
            .remove(pwKey(id))
            .remove(KEY_NOTIFY_VALIDITY + id)
            .remove(KEY_NOTIFY_UID + id)
            .putString(KEY_ACTIVE, newActive)
            .apply()
        _accounts.value = list
        _activeId.value = newActive
    }

    fun setActive(id: String) = synchronized(lock) {
        if (get(id) == null) return@synchronized
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeId.value = id
    }

    fun password(id: String): String? =
        prefs.getString(pwKey(id), null)?.let { CredentialCipher.decrypt(it) }

    fun notifyMarker(id: String): NotifyMarker? {
        if (!prefs.contains(KEY_NOTIFY_UID + id)) return null
        return NotifyMarker(
            validity = prefs.getLong(KEY_NOTIFY_VALIDITY + id, -1L),
            lastUid = prefs.getLong(KEY_NOTIFY_UID + id, 0L)
        )
    }

    fun setNotifyMarker(id: String, validity: Long, lastUid: Long) {
        prefs.edit()
            .putLong(KEY_NOTIFY_VALIDITY + id, validity)
            .putLong(KEY_NOTIFY_UID + id, lastUid)
            .apply()
    }

    fun notificationPermissionAsked(): Boolean = prefs.getBoolean(KEY_PERM_ASKED, false)

    fun markNotificationPermissionAsked() {
        prefs.edit().putBoolean(KEY_PERM_ASKED, true).apply()
    }

    private fun readAccounts(): List<Account> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Account(
                    id = o.getString("id"),
                    displayName = o.optString("displayName"),
                    email = o.getString("email"),
                    imapHost = o.getString("imapHost"),
                    imapPort = o.getInt("imapPort"),
                    smtpHost = o.getString("smtpHost"),
                    smtpPort = o.getInt("smtpPort"),
                    signatureAr = o.optString("signatureAr"),
                    signatureEn = o.optString("signatureEn"),
                    notificationsEnabled = o.optBoolean("notificationsEnabled", true),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toJson(list: List<Account>): String {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("displayName", a.displayName)
                    .put("email", a.email)
                    .put("imapHost", a.imapHost)
                    .put("imapPort", a.imapPort)
                    .put("smtpHost", a.smtpHost)
                    .put("smtpPort", a.smtpPort)
                    .put("signatureAr", a.signatureAr)
                    .put("signatureEn", a.signatureEn)
                    .put("notificationsEnabled", a.notificationsEnabled)
            )
        }
        return arr.toString()
    }

    private fun pwKey(id: String) = "pw_$id"

    private companion object {
        const val PREFS = "company_mail_accounts"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_ACTIVE = "active_account"
        const val KEY_NOTIFY_VALIDITY = "nv_"
        const val KEY_NOTIFY_UID = "nu_"
        const val KEY_PERM_ASKED = "notif_permission_asked"
    }
}
