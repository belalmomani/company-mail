package com.shegrowsdev.mail.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shegrowsdev.mail.MainActivity
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.FollowUpWithRef
import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.mail.MessageSummary

object NotificationHelper {
    const val CHANNEL_ID = "new_mail"
    const val CHANNEL_FOLLOW_UPS = "follow_ups"
    private const val ID_FOLLOW_APPROACHING = 9001
    private const val ID_FOLLOW_OVERDUE = 9002
    const val CHANNEL_BACKUP = "backup"
    private const val ID_BACKUP_FAILED = 9003

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_desc) }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FOLLOW_UPS,
                context.getString(R.string.notif_followup_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_followup_channel_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BACKUP,
                context.getString(R.string.notif_backup_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_backup_channel_desc) }
        )
    }

    /** Tells the administrator that the automatic backup did not complete. Contains no data from the backup. */
    fun notifyBackupFailed(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context,
            9010,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, "backup")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(context.getString(R.string.notif_backup_failed_title))
            .setContentText(context.getString(R.string.notif_backup_failed_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_BACKUP_FAILED, notification)
        } catch (e: SecurityException) {
            // Without the permission the failure is still shown on the backup screen.
        }
    }

    /**
     * One notification per stage (approaching / overdue). Returns true only when it was really posted, so
     * the caller marks the reminders as notified only then and a missing permission never loses a reminder.
     */
    fun notifyFollowUps(context: Context, stage: Int, items: List<FollowUpWithRef>, route: String): Boolean {
        if (items.isEmpty()) return true
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        val title = context.getString(R.string.notif_followup_title)
        val countText = context.getString(
            if (stage >= 2) R.string.notif_followup_overdue else R.string.notif_followup_approaching,
            items.size
        )
        val open = PendingIntent.getActivity(
            context,
            9000 + stage,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Subjects are private: the lock screen only shows the generic count.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_FOLLOW_UPS)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(title)
            .setContentText(countText)
            .build()
        val style = NotificationCompat.InboxStyle()
        items.take(5).forEach { f ->
            style.addLine(f.ref.subject.ifBlank { context.getString(R.string.mailbox_no_subject) })
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_FOLLOW_UPS)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(title)
            .setContentText(countText)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            NotificationManagerCompat.from(context)
                .notify(if (stage >= 2) ID_FOLLOW_OVERDUE else ID_FOLLOW_APPROACHING, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun notifyNewMail(context: Context, account: Account, fresh: List<MessageSummary>) {
        if (fresh.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val title = context.getString(R.string.notif_new_mail_title)
        val countText = context.getString(R.string.notif_new_count, fresh.size)
        val first = fresh.first()
        val firstLine = "${first.from?.display ?: context.getString(R.string.mailbox_unknown_sender)}: " +
            first.subject.ifBlank { context.getString(R.string.mailbox_no_subject) }

        val open = PendingIntent.getActivity(
            context,
            account.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ACCOUNT_ID, account.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // On the lock screen only a generic text is shown; sender and subject stay private.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(title)
            .setContentText(countText)
            .build()

        val style = NotificationCompat.InboxStyle().setSummaryText(account.email)
        fresh.take(5).forEach { m ->
            style.addLine(
                "${m.from?.display ?: context.getString(R.string.mailbox_unknown_sender)}: " +
                    m.subject.ifBlank { context.getString(R.string.mailbox_no_subject) }
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(if (fresh.size == 1) title else countText)
            .setContentText(if (fresh.size == 1) firstLine else account.email)
            .setStyle(if (fresh.size == 1) NotificationCompat.BigTextStyle().bigText(firstLine) else style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(account.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call; nothing to do.
        }
    }
}
