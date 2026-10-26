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
import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.mail.MessageSummary

object NotificationHelper {
    const val CHANNEL_ID = "new_mail"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
