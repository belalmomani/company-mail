package com.shegrowsdev.mail

import android.app.Application
import com.shegrowsdev.mail.notify.NotificationHelper
import com.shegrowsdev.mail.notify.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MailApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.ensureChannel(this)

        // Background checking runs only while at least one account has notifications enabled.
        appScope.launch {
            container.accountStore.accounts
                .map { list -> list.any { it.notificationsEnabled } }
                .collect { enabled -> SyncScheduler.update(this@MailApplication, enabled) }
        }
    }
}
