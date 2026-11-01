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
import com.shegrowsdev.mail.ui.Routes
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
                NewMailChecker.check(applicationContext, container, account.id)
            } catch (e: Exception) {
                // One failing account (offline, wrong password, ...) must not block the others.
                // The next periodic run retries; the marker is only advanced on success.
            }
        }
        notifyFollowUps(container)
        return Result.success()
    }

    /** Reminders for follow-ups that are approaching or overdue; each stage is shown at most once per follow-up. */
    private suspend fun notifyFollowUps(container: com.shegrowsdev.mail.AppContainer) {
        if (!container.adminSettings.remindersEnabled.value) return
        try {
            val allowed = container.accountStore.accounts.value.filter { it.notificationsEnabled }.map { it.id }.toSet()
            val due = container.admin
                .followUpsToNotify(System.currentTimeMillis(), container.adminSettings.leadMillis)
                .filter { (item, _) -> item.followUp.accountId in allowed }
            for ((stage, group) in due.groupBy({ it.second }, { it.first })) {
                val posted = NotificationHelper.notifyFollowUps(applicationContext, stage, group, Routes.FOLLOWUPS)
                if (posted) container.admin.markNotified(group.map { it.followUp.id }, stage)
            }
        } catch (e: Exception) {
            // Reminders are retried on the next run; nothing was marked as notified.
        }
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
