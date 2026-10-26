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
import com.shegrowsdev.mail.ui.compose.ComposeScreen
import com.shegrowsdev.mail.ui.mailbox.MailboxScreen
import com.shegrowsdev.mail.ui.message.MessageScreen
import com.shegrowsdev.mail.ui.settings.SettingsScreen
import com.shegrowsdev.mail.ui.theme.CompanyMailTheme

private object Routes {
    const val SETUP = "setup"
    const val MAILBOX = "mailbox"
    const val SETTINGS = "settings"
    const val MESSAGE = "message/{role}/{uid}"
    const val COMPOSE = "compose/{mode}/{role}/{uid}"

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
                )
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
