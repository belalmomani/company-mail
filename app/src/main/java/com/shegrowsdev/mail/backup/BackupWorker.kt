package com.shegrowsdev.mail.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shegrowsdev.mail.MailApplication
import com.shegrowsdev.mail.data.AccountRole
import com.shegrowsdev.mail.notify.NotificationHelper
import java.util.concurrent.TimeUnit

/** Daily automatic backup. A failure is recorded and notified, never silently ignored. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MailApplication).container
        val settings = container.backupSettings
        if (!settings.autoEnabled.value) return Result.success()
        // Only while an administrator account exists on this device.
        if (container.accountStore.accounts.value.none { it.role == AccountRole.ADMIN }) return Result.success()

        val passphrase = settings.passphrase()
        val now = System.currentTimeMillis()
        if (passphrase == null) {
            settings.recordAuto(BackupStatus(now, ok = false))
            NotificationHelper.notifyBackupFailed(applicationContext)
            return Result.success()
        }
        return try {
            container.backup.create(BackupKind.AUTO, passphrase, actor = null, system = true)
            settings.recordAuto(BackupStatus(now, ok = true))
            Result.success()
        } catch (e: Exception) {
            settings.recordAuto(BackupStatus(now, ok = false))
            NotificationHelper.notifyBackupFailed(applicationContext)
            Result.success() // the next daily run tries again; failure is already recorded and shown
        } finally {
            passphrase.fill('\u0000')
        }
    }
}

object BackupScheduler {
    private const val WORK_NAME = "admin_backup"

    fun update(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
