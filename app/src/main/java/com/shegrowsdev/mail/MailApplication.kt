package com.shegrowsdev.mail

import android.app.Application
import com.shegrowsdev.mail.backup.BackupScheduler
import com.shegrowsdev.mail.data.AttachmentFiles
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
        // Attachment file names written with RFC 2047 encoding (common with Outlook) are decoded for display.
        System.setProperty("mail.mime.decodefilename", "true")
        container = AppContainer(this)
        NotificationHelper.ensureChannel(this)
        appScope.launch { AttachmentFiles.cleanOldFiles(this@MailApplication) }

        // Background checking runs only while at least one account has notifications enabled.
        appScope.launch {
            container.accountStore.accounts
                .map { list -> list.any { it.notificationsEnabled } }
                .collect { enabled -> SyncScheduler.update(this@MailApplication, enabled) }
        }

        // Start/stop instant notifications when the switch or the accounts change (only works while the app is visible).
        appScope.launch {
            kotlinx.coroutines.flow.combine(
                container.adminSettings.instantEnabled,
                container.accountStore.accounts.map { list -> list.any { it.notificationsEnabled } }
            ) { a, b -> a && b }.collect { com.shegrowsdev.mail.notify.InstantMail.sync(this@MailApplication) }
        }

        // Daily encrypted backup runs only while switched on by an administrator.
        appScope.launch {
            container.backupSettings.autoEnabled.collect { on -> BackupScheduler.update(this@MailApplication, on) }
        }
    }
}
