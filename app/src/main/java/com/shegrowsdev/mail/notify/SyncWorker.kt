package com.shegrowsdev.mail.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shegrowsdev.mail.MailApplication
import java.util.concurrent.TimeUnit

/**
 * Periodic new-mail check (WorkManager minimum interval: 15 minutes). This is polling,
 * not instant push; instant delivery would need a long-lived IMAP IDLE connection.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MailApplication).container
        for (account in container.accountStore.accounts.value.filter { it.notificationsEnabled }) {
            try {
                val fresh = container.repository.checkNewInbox(account.id)
                NotificationHelper.notifyNewMail(applicationContext, account, fresh)
            } catch (e: Exception) {
                // One failing account (offline, wrong password, ...) must not block the others.
                // The next periodic run retries; the marker is only advanced on success.
            }
        }
        return Result.success()
    }
}

object SyncScheduler {
    private const val WORK_NAME = "mail_sync"

    fun update(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
