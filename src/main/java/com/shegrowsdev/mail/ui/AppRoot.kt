package com.shegrowsdev.mail.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.ui.accounts.AccountSetupScreen
import com.shegrowsdev.mail.ui.compose.ComposeMode
import com.shegrowsdev.mail.ui.backup.BackupScreen
import com.shegrowsdev.mail.ui.colleagues.ColleaguesScreen
import com.shegrowsdev.mail.ui.dashboard.DashboardScreen
import com.shegrowsdev.mail.ui.followups.FollowUpsScreen
import com.shegrowsdev.mail.ui.register.RegisterScreen
import com.shegrowsdev.mail.ui.tasks.TaskDetailScreen
import com.shegrowsdev.mail.ui.tasks.TasksScreen
import com.shegrowsdev.mail.ui.unified.UnifiedInboxScreen
import com.shegrowsdev.mail.ui.compose.ComposeScreen
import com.shegrowsdev.mail.ui.mailbox.MailboxScreen
import com.shegrowsdev.mail.ui.message.MessageScreen
import com.shegrowsdev.mail.ui.settings.SettingsScreen
import com.shegrowsdev.mail.ui.theme.CompanyMailTheme

internal object Routes {
    const val SETUP = "setup"
    const val MAILBOX = "mailbox"
    const val SETTINGS = "settings"
    const val UNIFIED = "unified"
    const val TASKS = "tasks"
    const val TASK = "task/{id}"
    const val REGISTER = "register"
    const val FOLLOWUPS = "followups"
    const val DASHBOARD = "dashboard"
    const val BACKUP = "backup"
    const val COLLEAGUES = "colleagues"
    const val MESSAGE = "message/{role}/{uid}"
    const val COMPOSE = "compose/{mode}/{role}/{uid}"

    fun task(id: Long) = "task/$id"
    fun message(role: FolderRole, uid: Long) = "message/${role.name}/$uid"
    fun compose(mode: ComposeMode, role: FolderRole, uid: Long) = "compose/${mode.name}/${role.name}/$uid"
}

@Composable
fun AppRoot(container: AppContainer) {
    CompanyMailTheme {
        val nav = rememberNavController()
        val accounts by container.accountStore.accounts.collectAsStateWithLifecycle()
        val startDestination = remember {
            if (container.accountStore.accounts.value.isEmpty()) Routes.SETUP else Routes.MAILBOX
        }

        RequestNotificationPermissionOnce(container, hasAccounts = accounts.isNotEmpty())

        // A screen requested from a reminder notification; only valid once an account exists.
        val pendingRoute by container.pendingRoute.collectAsStateWithLifecycle()
        LaunchedEffect(pendingRoute, accounts.isNotEmpty()) {
            val route = pendingRoute
            if (route != null && accounts.isNotEmpty()) {
                container.requestRoute(null)
                nav.navigate(route) { launchSingleTop = true }
            }
        }

        NavHost(navController = nav, startDestination = startDestination) {

            composable(Routes.SETUP) {
                AccountSetupScreen(
                    canGoBack = nav.previousBackStackEntry != null,
                    onBack = { nav.popBackStack() },
                    onDone = {
                        nav.navigate(Routes.MAILBOX) {
                            popUpTo(nav.graph.id) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.MAILBOX) {
                MailboxScreen(
                    onOpenMessage = { role, uid -> nav.navigate(Routes.message(role, uid)) },
                    onOpenDraft = { uid ->
                        nav.navigate(Routes.compose(ComposeMode.DRAFT, FolderRole.DRAFTS, uid))
                    },
                    onCompose = {
                        nav.navigate(Routes.compose(ComposeMode.NEW, FolderRole.INBOX, -1L))
                    },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onAddAccount = { nav.navigate(Routes.SETUP) },
                    onUnified = { nav.navigate(Routes.UNIFIED) },
                    onTasks = { nav.navigate(Routes.TASKS) },
                    onRegister = { nav.navigate(Routes.REGISTER) },
                    onFollowUps = { nav.navigate(Routes.FOLLOWUPS) },
                    onDashboard = { nav.navigate(Routes.DASHBOARD) },
                    onBackup = { nav.navigate(Routes.BACKUP) },
                    onColleagues = { nav.navigate(Routes.COLLEAGUES) },
                )
            }

            composable(Routes.UNIFIED) {
                UnifiedInboxScreen(
                    onBack = { nav.popBackStack() },
                    onOpenMessage = { role, uid -> nav.navigate(Routes.message(role, uid)) },
                )
            }

            composable(Routes.TASKS) {
                TasksScreen(onBack = { nav.popBackStack() }, onOpenTask = { id -> nav.navigate(Routes.task(id)) })
            }

            composable(
                Routes.TASK,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                TaskDetailScreen(
                    taskId = id,
                    onBack = { nav.popBackStack() },
                    onOpenMessage = { role, uid -> nav.navigate(Routes.message(role, uid)) },
                )
            }

            composable(Routes.REGISTER) {
                RegisterScreen(
                    onBack = { nav.popBackStack() },
                    onOpenMessage = { role, uid -> nav.navigate(Routes.message(role, uid)) },
                )
            }

            composable(Routes.FOLLOWUPS) {
                FollowUpsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenMessage = { role, uid -> nav.navigate(Routes.message(role, uid)) },
                )
            }

            composable(Routes.DASHBOARD) {
                DashboardScreen(onBack = { nav.popBackStack() })
            }

            composable(Routes.COLLEAGUES) {
                ColleaguesScreen(
                    onBack = { nav.popBackStack() },
                    onWrite = { nav.navigate(Routes.compose(ComposeMode.NEW, FolderRole.INBOX, -1L)) },
                )
            }

            composable(Routes.BACKUP) {
                BackupScreen(onBack = { nav.popBackStack() })
            }

            composable(
                Routes.MESSAGE,
                arguments = listOf(
                    navArgument("role") { type = NavType.StringType },
                    navArgument("uid") { type = NavType.LongType },
                )
            ) { entry ->
                // The account is fixed when the screen opens; the graph itself captures no changing state.
                val accountId = remember { container.accountStore.active()?.id } ?: return@composable
                val role = FolderRole.valueOf(entry.arguments?.getString("role") ?: FolderRole.INBOX.name)
                val uid = entry.arguments?.getLong("uid") ?: return@composable
                MessageScreen(
                    accountId = accountId,
                    role = role,
                    uid = uid,
                    onBack = { nav.popBackStack() },
                    onReply = { nav.navigate(Routes.compose(ComposeMode.REPLY, role, uid)) },
                    onReplyAll = { nav.navigate(Routes.compose(ComposeMode.REPLY_ALL, role, uid)) },
                    onForward = { nav.navigate(Routes.compose(ComposeMode.FORWARD, role, uid)) },
                )
            }

            composable(
                Routes.COMPOSE,
                arguments = listOf(
                    navArgument("mode") { type = NavType.StringType },
                    navArgument("role") { type = NavType.StringType },
                    navArgument("uid") { type = NavType.LongType },
                )
            ) { entry ->
                val accountId = remember { container.accountStore.active()?.id } ?: return@composable
                val mode =ComposeMode.valueOf(entry.arguments?.getString("mode") ?: ComposeMode.NEW.name)
                val role = FolderRole.valueOf(entry.arguments?.getString("role") ?: FolderRole.INBOX.name)
                val uid = entry.arguments?.getLong("uid") ?: -1L
                ComposeScreen(
                    accountId = accountId,
                    mode = mode,
                    role = role,
                    uid = uid,
                    onClose = { nav.popBackStack() },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onAddAccount = { nav.navigate(Routes.SETUP) },
                    onNoAccountsLeft = {
                        nav.navigate(Routes.SETUP) {
                            popUpTo(nav.graph.id) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

/** Asks for the Android 13+ notification permission once, after the first account exists. */
@Composable
private fun RequestNotificationPermissionOnce(container: AppContainer, hasAccounts: Boolean) {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(hasAccounts) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (hasAccounts && !granted && !container.accountStore.notificationPermissionAsked()) {
            container.accountStore.markNotificationPermissionAsked()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
