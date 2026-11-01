package com.shegrowsdev.mail.notify

import android.content.Context
import com.shegrowsdev.mail.AppContainer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that looks for new Inbox mail and notifies. The periodic worker and the instant (IDLE)
 * service both call it; the lock makes sure two checks never run at once, so one message never produces
 * two notifications.
 */
object NewMailChecker {
    private val lock = Mutex()

    suspend fun check(context: Context, container: AppContainer, accountId: String) = lock.withLock {
        val account = container.accountStore.get(accountId) ?: return@withLock
        if (!account.notificationsEnabled) return@withLock
        val fresh = container.repository.checkNewInbox(accountId)
        NotificationHelper.notifyNewMail(context, account, fresh)
    }
}
