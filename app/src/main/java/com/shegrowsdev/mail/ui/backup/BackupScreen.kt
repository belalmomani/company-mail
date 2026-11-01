package com.shegrowsdev.mail.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.Permissions
import com.shegrowsdev.mail.backup.BackupCrypto
import com.shegrowsdev.mail.backup.BackupInfo
import com.shegrowsdev.mail.backup.BackupKind
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.MailFailure
import com.shegrowsdev.mail.ui.common.Notice
import com.shegrowsdev.mail.ui.common.attempt
import com.shegrowsdev.mail.ui.common.containerViewModel
import com.shegrowsdev.mail.ui.common.formatFullDateTime
import com.shegrowsdev.mail.ui.common.ltr
import com.shegrowsdev.mail.ui.common.rememberContainer
import com.shegrowsdev.mail.ui.common.toNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a restore reads from: a backup kept on this device, or a file the administrator picked. */
sealed interface RestoreSource {
    data class Stored(val info: BackupInfo) : RestoreSource
    class Imported(val bytes: ByteArray) : RestoreSource
}

class BackupViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val items: List<BackupInfo> = emptyList(),
        val busy: Boolean = false,
        val notice: Notice? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun success(@StringRes res: Int) = Notice(res, null, isError = false)

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun refresh() {
        val viewer = container.viewer()
        if (!Permissions.canManageBackups(viewer)) return
        viewModelScope.launch {
            val r = attempt { withContext(Dispatchers.IO) { container.backup.list(viewer) } }
            _state.update { s -> r.fold({ s.copy(items = it) }, { s.copy(notice = it.toNotice()) }) }
        }
    }

    /** Runs one backup operation off the main thread, with a busy flag and a visible result either way. */
    private fun run(done: Int, block: () -> Unit) {
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val r = attempt { withContext(Dispatchers.IO) { block() } }
            val notice = r.fold({ success(done) }, { it.toNotice() })
            val items = attempt {
                withContext(Dispatchers.IO) { container.backup.list(container.viewer()) }
            }.getOrNull()
            _state.update { it.copy(busy = false, notice = notice, items = items ?: it.items) }
        }
    }

    fun backupNow(passphrase: CharArray) {
        val viewer = container.viewer()
        run(R.string.backup_done) {
            try {
                container.backup.create(BackupKind.MANUAL, passphrase, viewer)
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun enableAuto(passphrase: CharArray) {
        val viewer = container.viewer()
        run(R.string.backup_auto_enabled) {
            try {
                if (!Permissions.canManageBackups(viewer)) throw MailFailure(FailureKind.NOT_PERMITTED)
                if (passphrase.size < BackupCrypto.MIN_PASSPHRASE_LENGTH) throw MailFailure(FailureKind.BACKUP_NO_PASSPHRASE)
                container.backupSettings.savePassphrase(passphrase)
                container.backupSettings.setAutoEnabled(true)
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun disableAuto() {
        val viewer = container.viewer()
        run(R.string.backup_auto_disabled) {
            if (!Permissions.canManageBackups(viewer)) throw MailFailure(FailureKind.NOT_PERMITTED)
            container.backupSettings.setAutoEnabled(false)
            container.backupSettings.clearPassphrase()
        }
    }

    fun restore(source: RestoreSource, passphrase: CharArray) {
        val viewer = container.viewer()
        run(R.string.backup_restored) {
            try {
                val bytes = when (source) {
                    is RestoreSource.Stored -> container.backup.read(viewer, source.info)
                    is RestoreSource.Imported -> source.bytes
                }
                container.backup.restore(viewer, bytes, passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun delete(info: BackupInfo) {
        val viewer = container.viewer()
        run(R.string.backup_deleted) { container.backup.delete(viewer, info) }
    }

    fun export(info: BackupInfo, target: Uri) {
        val viewer = container.viewer()
        run(R.string.backup_exported) {
            val bytes = container.backup.read(viewer, info)
            val out = container.appContext.contentResolver.openOutputStream(target)
                ?: throw MailFailure(FailureKind.BACKUP_WRITE)
            try {
                out.use { it.write(bytes) }
            } catch (e: java.io.IOException) {
                throw MailFailure(FailureKind.BACKUP_WRITE, cause = e)
            }
        }
    }

    /** Reads a picked file (bounded size) and hands its bytes back for the restore dialog. */
    fun readImport(uri: Uri, onRead: (ByteArray) -> Unit) {
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val r = attempt {
                withContext(Dispatchers.IO) {
                    val stream = container.appContext.contentResolver.openInputStream(uri)
                        ?: throw MailFailure(FailureKind.BACKUP_BAD_FILE)
                    stream.use {
                        // Bounded read (InputStream.readNBytes needs Android 13+, the app supports 8.0+).
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = it.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_IMPORT_BYTES) throw MailFailure(FailureKind.BACKUP_BAD_FILE)
                        }
                        out.toByteArray()
                    }
                }
            }
            _state.update { s -> s.copy(busy = false, notice = r.exceptionOrNull()?.toNotice()) }
            r.getOrNull()?.let(onRead)
        }
    }

    companion object {
        const val MAX_IMPORT_BYTES = 64 * 1024 * 1024
    }
}

@StringRes
private fun BackupKind.labelRes(): Int = when (this) {
    BackupKind.AUTO -> R.string.backup_kind_auto
    BackupKind.MANUAL -> R.string.backup_kind_manual
    BackupKind.SAFETY -> R.string.backup_kind_safety
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val container = rememberContainer()
    val vm = containerViewModel { BackupViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val autoOn by container.backupSettings.autoEnabled.collectAsStateWithLifecycle()
    val lastAuto by container.backupSettings.lastAuto.collectAsStateWithLifecycle()
    val accounts by container.accountStore.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val allowed = Permissions.canManageBackups(container.viewer()) && accounts.isNotEmpty()

    // The passphrase lives only in memory while this screen is open (never in saved state).
    var passphrase by remember { mutableStateOf("") }
    var restoreSource by remember { mutableStateOf<RestoreSource?>(null) }
    var restorePass by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf<BackupInfo?>(null) }
    var deleting by remember { mutableStateOf<BackupInfo?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val info = exporting
        exporting = null
        if (uri != null && info != null) vm.export(info, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readImport(uri) { bytes -> restoreSource = RestoreSource.Imported(bytes) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!allowed) {
                Text(stringResource(R.string.backup_admin_only))
                return@Column
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.backup_scope_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.backup_scope_body), style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.notice?.let { n ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(n.textRes) + (n.detail?.let { " (${it.ltr()})" } ?: ""),
                            color = if (n.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.action_ok)) }
                    }
                }
            }

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.backup_passphrase)) },
                supportingText = { Text(stringResource(R.string.backup_passphrase_hint, BackupCrypto.MIN_PASSPHRASE_LENGTH)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.backupNow(passphrase.toCharArray()) }, enabled = !state.busy) {
                    Text(stringResource(R.string.backup_now))
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                    Text(stringResource(R.string.backup_import))
                }
                if (state.busy) CircularProgressIndicator(Modifier.padding(start = 8.dp))
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_auto), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.backup_auto_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoOn,
                    enabled = !state.busy,
                    onCheckedChange = { on -> if (on) vm.enableAuto(passphrase.toCharArray()) else vm.disableAuto() }
                )
            }
            lastAuto?.let {
                Text(
                    stringResource(
                        if (it.ok) R.string.backup_last_ok else R.string.backup_last_failed,
                        formatFullDateTime(context, it.at).ltr()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider()
            Text(stringResource(R.string.backup_list_title), style = MaterialTheme.typography.titleSmall)
            if (state.items.isEmpty()) {
                Text(stringResource(R.string.backup_none), style = MaterialTheme.typography.bodyMedium)
            }
            state.items.forEach { info ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(formatFullDateTime(context, info.createdAt).ltr(), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(info.kind.labelRes()) + " · " + "${info.sizeBytes / 1024 + 1} KB".ltr(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { restorePass = ""; restoreSource = RestoreSource.Stored(info) }, enabled = !state.busy) {
                                Text(stringResource(R.string.backup_restore))
                            }
                            TextButton(
                                onClick = {
                                    exporting = info
                                    exportLauncher.launch(info.name)
                                },
                                enabled = !state.busy
                            ) { Text(stringResource(R.string.backup_export)) }
                            TextButton(onClick = { deleting = info }, enabled = !state.busy) {
                                Text(stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
        }
    }

    restoreSource?.let { source ->
        AlertDialog(
            onDismissRequest = { restoreSource = null; restorePass = "" },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_restore_warning))
                    OutlinedTextField(
                        value = restorePass,
                        onValueChange = { restorePass = it },
                        label = { Text(stringResource(R.string.backup_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.restore(source, restorePass.toCharArray())
                        restoreSource = null
                        restorePass = ""
                    },
                    enabled = restorePass.length >= BackupCrypto.MIN_PASSPHRASE_LENGTH
                ) { Text(stringResource(R.string.backup_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreSource = null; restorePass = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    deleting?.let { info ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.backup_delete_title)) },
            text = { Text(stringResource(R.string.backup_delete_body, formatFullDateTime(context, info.createdAt).ltr())) },
            confirmButton = {
                TextButton(onClick = { vm.delete(info); deleting = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
