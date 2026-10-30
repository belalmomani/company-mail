package com.shegrowsdev.mail.ui.message

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.MessageLinks
import com.shegrowsdev.mail.admin.RefDrafts
import com.shegrowsdev.mail.admin.SerialFormat
import com.shegrowsdev.mail.data.AttachmentFiles
import com.shegrowsdev.mail.ui.admin.FollowUpDialog
import com.shegrowsdev.mail.ui.admin.TaskDialog
import com.shegrowsdev.mail.mail.AttachmentInfo
import com.shegrowsdev.mail.mail.AttachmentPolicy
import com.shegrowsdev.mail.mail.DeleteOutcome
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.MailFailure
import com.shegrowsdev.mail.mail.MailAddress
import com.shegrowsdev.mail.mail.MessageDetail
import com.shegrowsdev.mail.ui.common.Notice
import com.shegrowsdev.mail.ui.common.attempt
import com.shegrowsdev.mail.ui.common.containerViewModel
import com.shegrowsdev.mail.ui.common.formatFullDateTime
import com.shegrowsdev.mail.ui.common.ltr
import com.shegrowsdev.mail.ui.common.toNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MessageViewModel(
    private val container: AppContainer,
    private val accountId: String,
    private val role: FolderRole,
    private val uid: Long,
) : ViewModel() {

    /** A downloaded file waiting to be opened or saved by the screen (which owns the Activity). */
    data class PendingFile(val file: File, val name: String, val mime: String, val launched: Boolean = false)

    data class UiState(
        val loading: Boolean = true,
        val detail: MessageDetail? = null,
        val error: Notice? = null,
        val busyAttachment: Int? = null,
        val actionBusy: Boolean = false,
        val notice: Notice? = null,
        /** Non-null while the delete dialog is open; true = the message will be removed for good. */
        val confirmDeletePermanent: Boolean? = null,
        val pendingOpen: PendingFile? = null,
        val pendingSave: PendingFile? = null,
        @StringRes val finishedWith: Int? = null,
        val links: MessageLinks? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            attempt { container.repository.message(accountId, role, uid, markSeen = true) }
                .onSuccess { d ->
                    _state.update { UiState(loading = false, detail = d) }
                    refreshLinks(d)
                }
                .onFailure { e -> _state.update { UiState(loading = false, error = e.toNotice()) } }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    // ---------------------------------------------------------- attachments

    /** Downloads the attachment to the app cache, then asks the screen to open it or to save it. */
    fun fetchAttachment(info: AttachmentInfo, forSave: Boolean) {
        if (_state.value.busyAttachment != null) return
        _state.update { it.copy(busyAttachment = info.index, notice = null) }
        val dest = AttachmentFiles.receivedFile(container.appContext, accountId, uid, info.index, info.name)
        viewModelScope.launch {
            attempt {
                if (!dest.isFile) container.repository.downloadAttachment(accountId, role, uid, info.index, dest)
                if (!forSave) {
                    // Only open what really is the type it claims to be.
                    val head = withContext(Dispatchers.IO) { AttachmentFiles.readHead(dest) }
                    if (!AttachmentPolicy.contentMatches(info.name, head)) {
                        dest.delete()
                        throw MailFailure(FailureKind.ATTACH_CONTENT, detail = info.name)
                    }
                }
                dest
            }
                .onSuccess { file ->
                    val mime = AttachmentPolicy.mimeForName(info.name) ?: "application/octet-stream"
                    val pending = PendingFile(file, info.name, mime)
                    _state.update {
                        if (forSave) it.copy(busyAttachment = null, pendingSave = pending)
                        else it.copy(busyAttachment = null, pendingOpen = pending)
                    }
                }
                .onFailure { e -> _state.update { it.copy(busyAttachment = null, notice = e.toNotice()) } }
        }
    }

    fun consumeOpen(opened: Boolean) = _state.update {
        it.copy(
            pendingOpen = null,
            notice = if (opened) it.notice else Notice(R.string.err_no_app_to_open)
        )
    }

    fun markSaveLaunched() = _state.update { it.copy(pendingSave = it.pendingSave?.copy(launched = true)) }

    fun finishSave(target: Uri?) {
        val pending = _state.value.pendingSave ?: return
        _state.update { it.copy(pendingSave = null) }
        if (target == null) return // the user cancelled the file dialog
        viewModelScope.launch {
            attempt {
                withContext(Dispatchers.IO) {
                    AttachmentFiles.copyTo(container.appContext.contentResolver, pending.file, target)
                }
            }
                .onSuccess { _state.update { it.copy(notice = Notice(R.string.message_attachment_saved, isError = false)) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(notice = Notice(R.string.err_attach_save, pending.name.takeIf { n -> n.isNotBlank() }))
                    }
                }
        }
    }

    // ---------------------------------------------------------- tasks / register / follow-ups

    private fun draftOf(d: MessageDetail) = RefDrafts.fromDetail(
        accountId = accountId,
        accountEmail = container.accountStore.get(accountId)?.email.orEmpty(),
        role = role,
        detail = d,
        companyDomains = container.companyDomains(),
    )

    private val direction get() = RefDrafts.directionOf(role)

    private suspend fun loadLinks(d: MessageDetail): MessageLinks? {
        val viewer = container.viewer() ?: return null
        return attempt { container.admin.linksFor(viewer, accountId, d.messageId, direction) }.getOrNull()
    }

    private fun refreshLinks(d: MessageDetail) {
        viewModelScope.launch {
            val links = loadLinks(d)
            _state.update { it.copy(links = links) }
        }
    }

    private fun runAdmin(@StringRes okText: Int, block: suspend (viewer: com.shegrowsdev.mail.admin.Viewer, d: MessageDetail) -> Unit) {
        val d = _state.value.detail ?: return
        val viewer = container.viewer() ?: return
        viewModelScope.launch {
            attempt { block(viewer, d) }
                .onSuccess {
                    val links = loadLinks(d)
                    _state.update { it.copy(notice = Notice(okText, isError = false), links = links) }
                }
                .onFailure { e -> _state.update { it.copy(notice = e.toNotice()) } }
        }
    }

    fun createTask(title: String, assignee: String, dueAt: Long?) =
        runAdmin(R.string.task_saved) { v, d -> container.admin.createTask(v, draftOf(d), title, assignee, dueAt) }

    fun registerMessage() = runAdmin(R.string.register_done) { v, d ->
        container.admin.register(v, draftOf(d), v.email)
    }

    fun createFollowUp(dueAt: Long, note: String) =
        runAdmin(R.string.followup_saved) { v, d -> container.admin.createFollowUp(v, draftOf(d), dueAt, note) }

    // ---------------------------------------------------------- archive / delete

    fun archive() {
        if (_state.value.actionBusy) return
        _state.update { it.copy(actionBusy = true, notice = null) }
        viewModelScope.launch {
            attempt { container.repository.archive(accountId, role, uid) }
                .onSuccess { _state.update { it.copy(actionBusy = false, finishedWith = R.string.message_archived) } }
                .onFailure { e -> _state.update { it.copy(actionBusy = false, notice = e.toNotice()) } }
        }
    }

    /** Opens the confirmation; the wording depends on whether the message goes to Trash or is erased. */
    fun requestDelete() {
        if (_state.value.actionBusy) return
        if (role == FolderRole.TRASH) {
            _state.update { it.copy(confirmDeletePermanent = true) }
            return
        }
        _state.update { it.copy(actionBusy = true, notice = null) }
        viewModelScope.launch {
            attempt { container.repository.hasFolder(accountId, FolderRole.TRASH) }
                .onSuccess { has -> _state.update { it.copy(actionBusy = false, confirmDeletePermanent = !has) } }
                .onFailure { e -> _state.update { it.copy(actionBusy = false, notice = e.toNotice()) } }
        }
    }

    fun dismissDelete() = _state.update { it.copy(confirmDeletePermanent = null) }

    fun confirmDelete() {
        if (_state.value.actionBusy) return
        _state.update { it.copy(confirmDeletePermanent = null, actionBusy = true) }
        viewModelScope.launch {
            attempt { container.repository.delete(accountId, role, uid) }
                .onSuccess { outcome ->
                    _state.update {
                        it.copy(
                            actionBusy = false,
                            finishedWith = if (outcome == DeleteOutcome.MOVED_TO_TRASH) R.string.message_moved_to_trash
                            else R.string.message_deleted
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(actionBusy = false, notice = e.toNotice()) } }
        }
    }
}

private const val MAX_RENDERED_LINES = 4000

private enum class AdminDialog { TASK, FOLLOW_UP }

/** Addresses without a display name are isolated LTR so they stay intact inside Arabic text. */
private fun MailAddress.isolated(): String = if (name.isNullOrBlank()) email.ltr() else name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    accountId: String,
    role: FolderRole,
    uid: Long,
    onBack: () -> Unit,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
) {
    val vm = containerViewModel { MessageViewModel(it, accountId, role, uid) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val contentDirection = TextStyle(textDirection = TextDirection.Content)
    val snackbar = remember { SnackbarHostState() }
    val container = com.shegrowsdev.mail.ui.common.rememberContainer()
    var adminMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<AdminDialog?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> vm.finishSave(uri) }

    LaunchedEffect(state.pendingOpen) {
        state.pendingOpen?.let { p -> vm.consumeOpen(AttachmentFiles.open(context, p.file, p.mime)) }
    }
    LaunchedEffect(state.pendingSave) {
        val p = state.pendingSave
        if (p != null && !p.launched) {
            vm.markSaveLaunched()
            saveLauncher.launch(p.name)
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { n ->
            snackbar.showSnackbar(
                context.getString(n.textRes) + (n.detail?.let { "\n$it" } ?: ""),
                duration = SnackbarDuration.Long
            )
            vm.consumeNotice()
        }
    }
    LaunchedEffect(state.finishedWith) {
        state.finishedWith?.let { res ->
            Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.detail != null) {
                        Box {
                            IconButton(onClick = { adminMenu = true }, enabled = container.viewer() != null) {
                                Icon(Icons.AutoMirrored.Filled.Assignment, stringResource(R.string.admin_actions))
                            }
                            DropdownMenu(expanded = adminMenu, onDismissRequest = { adminMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.admin_make_task)) },
                                    onClick = { adminMenu = false; dialog = AdminDialog.TASK }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.admin_register)) },
                                    onClick = { adminMenu = false; vm.registerMessage() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.admin_follow_up)) },
                                    onClick = { adminMenu = false; dialog = AdminDialog.FOLLOW_UP }
                                )
                            }
                        }
                        if (role != FolderRole.ARCHIVE) {
                            IconButton(onClick = vm::archive, enabled = !state.actionBusy) {
                                Icon(Icons.Filled.Archive, stringResource(R.string.action_archive))
                            }
                        }
                        IconButton(onClick = vm::requestDelete, enabled = !state.actionBusy) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.detail != null) {
                Column {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onReply, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.Reply, null)
                            Text(stringResource(R.string.message_reply), modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                        }
                        OutlinedButton(onClick = onReplyAll, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.Reply, null)
                            Text(stringResource(R.string.message_reply_all), modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                        }
                        OutlinedButton(onClick = onForward, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                            Text(stringResource(R.string.message_forward), modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val detail = state.detail
            val error = state.error
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(error.textRes) + (error.detail?.let { "\n$it" } ?: ""),
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = vm::load) { Text(stringResource(R.string.action_retry)) }
                }

                detail != null -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        detail.subject.ifBlank { stringResource(R.string.mailbox_no_subject) },
                        style = MaterialTheme.typography.headlineSmall.merge(contentDirection)
                    )
                    HeaderLine(
                        stringResource(R.string.message_from),
                        detail.from?.let { listOf(it) }.orEmpty()
                    )
                    HeaderLine(stringResource(R.string.message_to), detail.to)
                    if (detail.cc.isNotEmpty()) HeaderLine(stringResource(R.string.message_cc), detail.cc)
                    if (detail.bcc.isNotEmpty()) HeaderLine(stringResource(R.string.message_bcc), detail.bcc)
                    if (detail.dateMillis != null) {
                        Text(
                            stringResource(R.string.message_date) + ": " +
                                formatFullDateTime(context, detail.dateMillis).ltr(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.links?.takeIf { it.any }?.let { links ->
                        val parts = buildList {
                            links.registerSeq?.let { add(stringResource(R.string.admin_links_register, SerialFormat.format(it))) }
                            if (links.tasks > 0) add(stringResource(R.string.admin_links_tasks, links.tasks))
                            if (links.pendingFollowUps > 0) add(stringResource(R.string.admin_links_follow_ups, links.pendingFollowUps))
                        }
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    if (detail.bodyFromHtml) InfoText(stringResource(R.string.message_html_notice))
                    if (detail.attachments.isNotEmpty()) {
                        Text(
                            stringResource(R.string.message_attachments) + " (" + detail.attachments.size + ")",
                            style = MaterialTheme.typography.titleSmall
                        )
                        detail.attachments.forEach { a ->
                            AttachmentRow(
                                info = a,
                                busy = state.busyAttachment == a.index,
                                anyBusy = state.busyAttachment != null,
                                onOpen = { vm.fetchAttachment(a, forSave = false) },
                                onSave = { vm.fetchAttachment(a, forSave = true) },
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }

                    // Each line picks its own direction, so mixed Arabic/English paragraphs read correctly.
                    SelectionContainer {
                        Column {
                            val lines = detail.bodyText.lines()
                            lines.take(MAX_RENDERED_LINES).forEach { line ->
                                Text(
                                    text = line.ifEmpty { " " },
                                    style = MaterialTheme.typography.bodyMedium.merge(contentDirection)
                                )
                            }
                            if (lines.size > MAX_RENDERED_LINES) {
                                Text(
                                    stringResource(R.string.message_truncated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    when (dialog) {
        AdminDialog.TASK -> TaskDialog(
            accounts = com.shegrowsdev.mail.admin.Permissions.visibleAccounts(
                container.viewer() ?: com.shegrowsdev.mail.admin.Viewer("", "", com.shegrowsdev.mail.data.AccountRole.EMPLOYEE),
                container.accountStore.accounts.value
            ),
            defaultTitle = state.detail?.subject.orEmpty(),
            defaultAssignee = container.viewer()?.email.orEmpty(),
            onConfirm = { title, assignee, due -> dialog = null; vm.createTask(title, assignee, due) },
            onDismiss = { dialog = null }
        )
        AdminDialog.FOLLOW_UP -> FollowUpDialog(
            onConfirm = { due, note -> dialog = null; vm.createFollowUp(due, note) },
            onDismiss = { dialog = null }
        )
        null -> Unit
    }
    state.confirmDeletePermanent?.let { permanent ->
        DeleteDialog(permanent, onConfirm = vm::confirmDelete, onDismiss = vm::dismissDelete)
    }
}


@Composable
private fun AttachmentRow(
    info: AttachmentInfo,
    busy: Boolean,
    anyBusy: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                info.name,
                style = MaterialTheme.typography.bodyMedium.merge(TextStyle(textDirection = TextDirection.Content)),
                maxLines = 2
            )
            val size = AttachmentPolicy.formatSize(info.sizeBytes)
            Text(
                listOf(info.mimeType, size).filter { it.isNotEmpty() }.joinToString(" · ").ltr(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.padding(8.dp))
        } else {
            if (info.openable) {
                TextButton(onClick = onOpen, enabled = !anyBusy) { Text(stringResource(R.string.action_open)) }
            }
            TextButton(onClick = onSave, enabled = !anyBusy) { Text(stringResource(R.string.action_save_to_device)) }
        }
    }
}

@Composable
private fun DeleteDialog(permanent: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_delete)) },
        text = {
            Text(stringResource(if (permanent) R.string.message_delete_permanent else R.string.message_delete_to_trash))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun HeaderLine(label: String, addresses: List<MailAddress>) {
    if (addresses.isEmpty()) return
    Text(
        text = "$label: " + addresses.joinToString(", ") { it.isolated() },
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
