package com.shegrowsdev.mail.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.ui.common.ltr
import com.shegrowsdev.mail.ui.common.rememberContainer

private fun currentLanguage(): String? {
    val locales = AppCompatDelegate.getApplicationLocales()
    return if (locales.isEmpty) null else locales[0]?.language
}

private fun applyLanguage(tag: String?) {
    AppCompatDelegate.setApplicationLocales(
        if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onNoAccountsLeft: () -> Unit,
) {
    val container = rememberContainer()
    val accounts by container.accountStore.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var language by remember { mutableStateOf(currentLanguage()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the system remembers the answer; nothing else to do */ }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- language
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            Column {
                LanguageOption(stringResource(R.string.settings_language_system), language == null) {
                    language = null
                    applyLanguage(null)
                }
                LanguageOption(stringResource(R.string.lang_arabic), language == "ar") {
                    language = "ar"
                    applyLanguage("ar")
                }
                LanguageOption(stringResource(R.string.lang_english), language == "en") {
                    language = "en"
                    applyLanguage("en")
                }
            }

            HorizontalDivider()

            // ---- accounts
            Text(stringResource(R.string.settings_accounts), style = MaterialTheme.typography.titleMedium)
            accounts.forEach { account ->
                AccountCard(
                    account = account,
                    onSave = { container.accountStore.update(it) },
                    onNotificationsChanged = { enabled ->
                        container.accountStore.update(account.copy(notificationsEnabled = enabled))
                        if (enabled) ensureNotificationPermission()
                    },
                    onRemove = {
                        container.accountStore.remove(account.id)
                        if (container.accountStore.accounts.value.isEmpty()) onNoAccountsLeft()
                    }
                )
            }
            Button(onClick = onAddAccount, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_add))
            }
        }
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AccountCard(
    account: Account,
    onSave: (Account) -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var signatureAr by remember(account.id) { mutableStateOf(account.signatureAr) }
    var signatureEn by remember(account.id) { mutableStateOf(account.signatureEn) }
    var confirmRemove by remember { mutableStateOf(false) }
    val changed = signatureAr != account.signatureAr || signatureEn != account.signatureEn

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (account.displayName.isNotBlank()) {
                Text(account.displayName, style = MaterialTheme.typography.titleSmall)
            }
            Text(account.email.ltr(), style = MaterialTheme.typography.bodyMedium)

            Text(stringResource(R.string.settings_signature), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_signature_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = signatureAr,
                onValueChange = { signatureAr = it },
                label = { Text(stringResource(R.string.settings_signature_ar)) },
                minLines = 3,
                textStyle = TextStyle(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = signatureEn,
                onValueChange = { signatureEn = it },
                label = { Text(stringResource(R.string.settings_signature_en)) },
                minLines = 3,
                textStyle = TextStyle(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSave(account.copy(signatureAr = signatureAr, signatureEn = signatureEn)) },
                enabled = changed
            ) { Text(stringResource(R.string.action_save)) }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_notifications))
                    Text(
                        stringResource(R.string.settings_notifications_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = account.notificationsEnabled, onCheckedChange = onNotificationsChanged)
            }

            TextButton(onClick = { confirmRemove = true }) {
                Text(stringResource(R.string.settings_remove_account), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.settings_remove_account)) },
            text = { Text(stringResource(R.string.settings_remove_confirm, account.email.ltr())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
