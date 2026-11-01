package com.shegrowsdev.mail.notify

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.MailApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Instant new-mail notifications: one IMAP IDLE connection per account that has notifications on. The mail
 * server tells the app the moment a message arrives. Android requires a visible (silent, minimised) notification
 * while this runs. The 15-minute background check stays as a safety net.
 */
class MailIdleService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchers = HashMap<String, Job>()
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.ensureChannel(this)
        ServiceCompat.startForeground(
            this, NotificationHelper.ID_INSTANT, NotificationHelper.idleNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        if (observer == null) observer = scope.launch { observe(container()) }
        return START_STICKY
    }

    private fun container(): AppContainer = (application as MailApplication).container

    private suspend fun observe(container: AppContainer) {
        combine(container.accountStore.accounts, container.adminSettings.instantEnabled) { accounts, on ->
            if (on) accounts.filter { it.notificationsEnabled }.map { it.id }.toSet() else emptySet()
        }.distinctUntilChanged().collect { ids ->
            if (ids.isEmpty()) {
                stopSelf()
                return@collect
            }
            synchronized(watchers) {
                watchers.keys.filter { it !in ids }.forEach { watchers.remove(it)?.cancel() }
                ids.filter { it !in watchers }.forEach { id -> watchers[id] = scope.launch { watch(container, id) } }
            }
        }
    }

    /** Keeps one account's connection alive: reconnects with a growing (capped) delay after any failure. */
    private suspend fun watch(container: AppContainer, id: String) = coroutineScope {
        val trigger = Channel<Unit>(Channel.CONFLATED)
        launch {
            for (ignored in trigger) {
                try {
                    NewMailChecker.check(applicationContext, container, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Offline or a server hiccup: the next event or the periodic check tries again.
                }
            }
        }
        var attempt = 0
        while (isActive) {
            val started = SystemClock.elapsedRealtime()
            try {
                trigger.trySend(Unit) // catch up on anything that arrived while disconnected
                val supported = container.repository.watchInbox(id) { trigger.trySend(Unit) }
                if (!supported) {
                    // This server cannot push: leave the account to the periodic check and stop trying.
                    return@coroutineScope
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // connection lost, wrong password, server down: retry below
            }
            attempt = if (SystemClock.elapsedRealtime() - started >= IdlePolicy.HEALTHY_AFTER_MS) 0 else attempt + 1
            delay(IdlePolicy.nextDelay(attempt - 1))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Starts or stops the service to match the settings. Safe to call at any time. */
object InstantMail {
    fun sync(context: Context) {
        val container = (context.applicationContext as MailApplication).container
        val want = container.adminSettings.instantEnabled.value &&
            container.accountStore.accounts.value.any { it.notificationsEnabled }
        val intent = Intent(context, MailIdleService::class.java)
        if (!want) {
            context.stopService(intent)
            return
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Android refuses to start it from the background; it starts the next time the app is opened
            // (or after the phone restarts). The periodic check keeps working meanwhile.
        }
    }
}

/** Restarts instant notifications after the phone boots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        InstantMail.sync(context)
    }
}
