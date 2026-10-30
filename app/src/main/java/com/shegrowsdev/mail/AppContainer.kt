package com.shegrowsdev.mail

import android.content.Context
import com.shegrowsdev.mail.admin.AdminDb
import com.shegrowsdev.mail.admin.AdminRepository
import com.shegrowsdev.mail.admin.AdminSettings
import com.shegrowsdev.mail.admin.Viewer
import com.shegrowsdev.mail.data.AccountStore
import com.shegrowsdev.mail.mail.MailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Minimal manual dependency container (no DI framework needed at this size). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val accountStore = AccountStore(appContext)
    val repository = MailRepository(accountStore)
    val admin = AdminRepository(AdminDb(appContext))
    val adminSettings = AdminSettings(appContext)

    /** The active account as the viewer of administrative data (null when no account exists). */
    fun viewer(): Viewer? = accountStore.active()?.let { Viewer.of(it) }

    /** Domains of the accounts configured here; senders outside them count as external. */
    fun companyDomains(): Set<String> =
        accountStore.accounts.value.map { it.email.substringAfterLast('@').lowercase() }.toSet()

    private val _pendingRoute = MutableStateFlow<String?>(null)

    /** A screen requested from outside the UI (a reminder notification); consumed once by the navigation host. */
    val pendingRoute: StateFlow<String?> = _pendingRoute.asStateFlow()

    fun requestRoute(route: String?) {
        _pendingRoute.value = route
    }
}
