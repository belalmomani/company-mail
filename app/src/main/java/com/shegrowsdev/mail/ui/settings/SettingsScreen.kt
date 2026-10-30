package com.shegrowsdev.mail.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.AdminSettings
import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.data.AccountRole
import com.shegrowsdev.mail.data.AccountStore
import com.shegrowsdev.mail.data.LogoImporter
import com.shegrowsdev.mail.ui.common.attempt
import com.shegrowsdev.mail.ui.common.messageRes
import com.shegrowsdev.mail.ui.common.asFailure
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

            // ---- follow-up reminders (device-wide)
            val remindersOn by container.adminSettings.remindersEnabled.collectAsStateWithLifecycle()
            val leadMinutes by container.adminSettings.leadMinutes.collectAsStateWithLifecycle()
            Text(stringResource(R.string.settings_reminders), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_reminders_enable))
                    Text(
                        stringResource(R.string.settings_reminders_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = remindersOn,
                    onCheckedChange = {
                        container.adminSettings.setRemindersEnabled(it)
                        if (it) ensureNotificationPermission()
                    }
                )
            }
            if (remindersOn) {
                Text(stringResource(R.string.settings_reminders_lead), style = MaterialTheme.typography.labelLarge)
                AdminSettings.LEAD_CHOICES.forEach { minutes ->
                    LanguageOption(leadLabel(minutes), leadMinutes == minutes) {
                        container.adminSettings.setLeadMinutes(minutes)
                    }
                }
            }
            HorizontalDivider()

            // ---- accounts
            val activeViewer = container.viewer()
            val anyAdmin = accounts.any { it.role == AccountRole.ADMIN }
            // The first administrator can be named freely; after that only an administrator may change roles.
            val canEditRoles = !anyAdmin || activeViewer?.isAdmin == true
            Text(stringResource(R.string.settings_accounts), style = MaterialTheme.typography.titleMedium)
            accounts.forEach { account ->
                AccountCard(
                    account = account,
                    roleEditable = canEditRoles,
                    onRoleChange = { container.accountStore.setRole(account.id, if (it) AccountRole.ADMIN else AccountRole.EMPLOYEE) },
                    store = container.accountStore,
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
private fun leadLabel(minutes: Int): String = when {
    minutes % 1440 == 0 -> stringResource(R.string.settings_lead_days, minutes / 1440)
    minutes % 60 == 0 -> stringResource(R.string.settings_lead_hours, minutes / 60)
    else -> stringResource(R.string.settings_lead_minutes, minutes)
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
    roleEditable: Boolean,
    onRoleChange: (Boolean) -> Unit,
    store: AccountStore,
    onSave: (Account) -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var nameAr by remember(account.id) { mutableStateOf(account.nameAr) }
    var titleAr by remember(account.id) { mutableStateOf(account.titleAr) }
    var contactAr by remember(account.id) { mutableStateOf(account.signatureAr) }
    var nameEn by remember(account.id) { mutableStateOf(account.nameEn) }
    var titleEn by remember(account.id) { mutableStateOf(account.titleEn) }
    var contactEn by remember(account.id) { mutableStateOf(account.signatureEn) }
    var confirmRemove by remember { mutableStateOf(false) }
    var logoVersion by remember(account.id) { mutableStateOf(0) }
    val hasLogo = remember(account.id, logoVersion) { store.hasLogo(account.id) }
    val logoBitmap = remember(account.id, logoVersion) {
        if (hasLogo) BitmapFactory.decodeFile(store.logoFile(account.id).path)?.asImageBitmap() else null
    }
    val changed = nameAr != account.nameAr || titleAr != account.titleAr || contactAr != account.signatureAr ||
        nameEn != account.nameEn || titleEn != account.titleEn || contactEn != account.signatureEn

    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attempt { LogoImporter.importLogo(context.contentResolver, uri, store.logoFile(account.id)) }
                .onSuccess {
                    logoVersion++
                    onSave(account.copy(includeLogo = true))
                }
                .onFailure { e ->
                    Toast.makeText(context, context.getString(e.asFailure().kind.messageRes()), Toast.LENGTH_LONG).show()
                }
        }
    }

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
            val content = TextStyle(textDirection = TextDirection.Content)
            Text(stringResource(R.string.settings_signature_ar), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = nameAr, onValueChange = { nameAr = it },
                label = { Text(stringResource(R.string.settings_sig_name)) },
                singleLine = true, textStyle = content, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = titleAr, onValueChange = { titleAr = it },
                label = { Text(stringResource(R.string.settings_sig_title)) },
                singleLine = true, textStyle = content, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = contactAr, onValueChange = { contactAr = it },
                label = { Text(stringResource(R.string.settings_sig_contact)) },
                minLines = 2, textStyle = content, modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.settings_signature_en), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = nameEn, onValueChange = { nameEn = it },
                label = { Text(stringResource(R.string.settings_sig_name)) },
                singleLine = true, textStyle = content, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = titleEn, onValueChange = { titleEn = it },
                label = { Text(stringResource(R.string.settings_sig_title)) },
                singleLine = true, textStyle = content, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = contactEn, onValueChange = { contactEn = it },
                label = { Text(stringResource(R.string.settings_sig_contact)) },
                minLines = 2, textStyle = content, modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    onSave(
                        account.copy(
                            nameAr = nameAr.trim(), titleAr = titleAr.trim(), signatureAr = contactAr.trim(),
                            nameEn = nameEn.trim(), titleEn = titleEn.trim(), signatureEn = contactEn.trim()
                        )
                    )
                },
                enabled = changed
            ) { Text(stringResource(R.string.action_save)) }

            // ---- logo
            Text(stringResource(R.string.settings_logo), style = MaterialTheme.typography.titleSmall)
            if (logoBitmap != null) {
                Image(
                    bitmap = logoBitmap,
                    contentDescription = stringResource(R.string.settings_logo),
                    modifier = Modifier.height(56.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_logo_include), modifier = Modifier.weight(1f))
                    Switch(
                        checked = account.includeLogo,
                        onCheckedChange = { onSave(account.copy(includeLogo = it)) }
                    )
                }
            } else {
                Text(
                    stringResource(R.string.settings_logo_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickLogo.launch("image/*") }) {
                    Text(stringResource(R.string.settings_logo_choose))
                }
                if (hasLogo) {
                    TextButton(onClick = {
                        runCatching { store.logoFile(account.id).delete() }
                        logoVersion++
                        onSave(account.copy(includeLogo = false))
                    }) { Text(stringResource(R.string.settings_logo_remove)) }
                }
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_role_admin))
                    Text(
                        stringResource(if (roleEditable) R.string.settings_role_desc else R.string.settings_role_locked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = account.role == AccountRole.ADMIN,
                    onCheckedChange = onRoleChange,
                    enabled = roleEditable
                )
            }

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
