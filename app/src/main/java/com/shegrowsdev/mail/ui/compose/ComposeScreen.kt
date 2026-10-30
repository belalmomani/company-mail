package com.shegrowsdev.mail.ui.compose

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.Direction
import com.shegrowsdev.mail.admin.RefDraft
import com.shegrowsdev.mail.admin.RefDrafts
import com.shegrowsdev.mail.admin.ThreadLinker
import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.data.AttachmentFiles
import com.shegrowsdev.mail.mail.AttachmentPolicy
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.MailFailure
import com.shegrowsdev.mail.mail.OutgoingAttachment
import com.shegrowsdev.mail.mail.OutgoingSignature
import com.shegrowsdev.mail.mail.SignatureParts
import com.shegrowsdev.mail.mail.MessageDetail
import com.shegrowsdev.mail.mail.OutgoingMessage
import com.shegrowsdev.mail.mail.ReplyBuilder
import com.shegrowsdev.mail.ui.common.Notice
import com.shegrowsdev.mail.ui.common.attempt
import com.shegrowsdev.mail.ui.common.containerViewModel
import com.shegrowsdev.mail.ui.common.toNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress

enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD, DRAFT }

/** Localized fragments used when quoting/forwarding; resolved by the screen in the UI language. */
data class ComposeTexts(
    val wrote: String,
    val forwardedHeader: String,
    val from: String,
    val date: String,
    val subject: String,
    val to: String,
)

class ComposeViewModel(
    private val container: AppContainer,
    private val accountId: String,
    private val mode: ComposeMode,
    private val sourceRole: FolderRole,
    private val sourceUid: Long,
    private val texts: ComposeTexts,
    private val languageTag: String,
    private val locale: Locale,
) : ViewModel() {

    data class UiState(
        val to: String = "",
        val cc: String = "",
        val bcc: String = "",
        val subject: String = "",
        val body: TextFieldValue = TextFieldValue(""),
        val showCcBcc: Boolean = false,
        val loadingOriginal: Boolean = false,
        val busy: Boolean = false,
        val dirty: Boolean = false,
        val notice: Notice? = null,
        @StringRes val finishedWith: Int? = null,
        val attachments: List<OutgoingAttachment> = emptyList(),
        /** True while files are being copied/downloaded; sending is blocked so nothing is silently left out. */
        val attaching: Boolean = false,
        val includeSignature: Boolean = true,
        /** Also enter the sent message in the official incoming/outgoing register. */
        val registerInLog: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val account: Account? = container.accountStore.get(accountId)
    private var messageId: String = ReplyBuilder.newMessageId(account?.email.orEmpty())
    private var inReplyTo: String? = null
    private var references: String? = null
    private var replacesUid: Long? = if (mode == ComposeMode.DRAFT) sourceUid else null

    /** The message being answered (replies only); recorded so response times and follow-ups can be tied to it. */
    private var originalDraft: RefDraft? = null

    private val sessionId = UUID.randomUUID().toString()
    private val workDir: File = AttachmentFiles.composeDir(container.appContext, sessionId)

    /** The signature that would be added when sending (null when none is set up). */
    val signatureParts: SignatureParts = account?.signaturePartsFor(languageTag) ?: SignatureParts("", "", "")
    val hasLogo: Boolean = account != null && account.includeLogo && container.accountStore.hasLogo(account.id)
    val signatureAvailable: Boolean = !signatureParts.isEmpty || hasLogo

    init {
        if (mode != ComposeMode.NEW) loadOriginal()
    }

    override fun onCleared() {
        // The working copies are only needed until the message is sent, saved or discarded.
        runCatching { workDir.deleteRecursively() }
    }

    // ------------------------------------------------------------ editing

    fun onTo(v: String) = _state.update { it.copy(to = v, dirty = true) }
    fun onCc(v: String) = _state.update { it.copy(cc = v, dirty = true) }
    fun onBcc(v: String) = _state.update { it.copy(bcc = v, dirty = true) }
    fun onSubject(v: String) = _state.update { it.copy(subject = v, dirty = true) }
    fun onBody(v: TextFieldValue) =
        _state.update { it.copy(body = v, dirty = it.dirty || v.text != it.body.text) }

    fun showCcBcc() = _state.update { it.copy(showCcBcc = true) }
    fun onIncludeSignature(v: Boolean) = _state.update { it.copy(includeSignature = v, dirty = true) }
    fun onRegisterInLog(v: Boolean) = _state.update { it.copy(registerInLog = v) }

    // ------------------------------------------------------------ attachments

    /** Adds files chosen with the system picker. Each file is checked; the first problem is reported. */
    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.attaching) return
        _state.update { it.copy(attaching = true, notice = null) }
        viewModelScope.launch {
            var firstProblem: Notice? = null
            val added = mutableListOf<OutgoingAttachment>()
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val current = _state.value.attachments + added
                    try {
                        added += AttachmentFiles.importFromUri(
                            container.appContext.contentResolver, uri, workDir,
                            current.size, current.sumOf { a -> a.sizeBytes }
                        )
                    } catch (e: Exception) {
                        if (firstProblem == null) firstProblem = e.toNotice()
                    }
                }
            }
            _state.update {
                it.copy(
                    attachments = it.attachments + added,
                    attaching = false,
                    dirty = it.dirty || added.isNotEmpty(),
                    notice = firstProblem
                )
            }
        }
    }

    fun removeAttachment(a: OutgoingAttachment) {
        runCatching { a.file.delete() }
        _state.update { it.copy(attachments = it.attachments - a, dirty = true) }
    }

    /** Re-creates the attachments of a draft or forwarded message from the server copy. */
    private suspend fun importReceivedAttachments(d: MessageDetail) {
        if (d.attachments.isEmpty()) return
        _state.update { it.copy(attaching = true) }
        val kept = mutableListOf<OutgoingAttachment>()
        var skipped = 0
        var firstProblem: Notice? = null
        for (info in d.attachments) {
            val total = kept.sumOf { a -> a.sizeBytes }
            if (!info.openable || kept.size >= AttachmentPolicy.MAX_COUNT) {
                skipped++
                continue
            }
            val dest = File(File(workDir, "r${info.index}"), info.name)
            try {
                container.repository.downloadAttachment(accountId, sourceRole, sourceUid, info.index, dest)
                val size = dest.length()
                val head = withContext(Dispatchers.IO) { AttachmentFiles.readHead(dest) }
                when {
                    !AttachmentPolicy.contentMatches(info.name, head) -> {
                        dest.delete(); skipped++
                    }
                    size > AttachmentPolicy.MAX_FILE_BYTES || total + size > AttachmentPolicy.MAX_TOTAL_BYTES -> {
                        dest.delete(); skipped++
                    }
                    else -> kept += OutgoingAttachment(dest, info.name, AttachmentPolicy.mimeForName(info.name)!!, size)
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                skipped++
                if (firstProblem == null) firstProblem = e.toNotice()
            }
        }
        _state.update {
            it.copy(
                attachments = it.attachments + kept,
                attaching = false,
                notice = firstProblem ?: if (skipped > 0) Notice(R.string.compose_attachments_skipped, skipped.toString()) else null
            )
        }
    }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    // ------------------------------------------------------------ original message

    private fun dateText(millis: Long?): String =
        if (millis == null) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .format(Date(millis))

    private fun loadOriginal() {
        _state.update { it.copy(loadingOriginal = true) }
        viewModelScope.launch {
            attempt {
                container.repository.message(accountId, sourceRole, sourceUid, markSeen = false)
            }
                .onSuccess { d ->
                    applyOriginal(d)
                    if (mode == ComposeMode.DRAFT || mode == ComposeMode.FORWARD) importReceivedAttachments(d)
                }
                .onFailure { e -> _state.update { it.copy(loadingOriginal = false, notice = e.toNotice()) } }
        }
    }

    private fun applyOriginal(d: MessageDetail) {
        val me = account?.email.orEmpty()
        when (mode) {
            ComposeMode.REPLY, ComposeMode.REPLY_ALL -> {
                originalDraft = RefDrafts.fromDetail(accountId, me, sourceRole, d, container.companyDomains())
                val to = d.replyTo.ifEmpty { listOfNotNull(d.from) }
                val cc = if (mode == ComposeMode.REPLY_ALL) ReplyBuilder.replyAllCc(d, me, to) else emptyList()
                inReplyTo = d.messageId
                references = ReplyBuilder.references(d)
                val header = texts.wrote.format(dateText(d.dateMillis), d.from?.display.orEmpty())
                val body = "\n\n" + header + "\n" + ReplyBuilder.quote(d.bodyText)
                _state.update {
                    it.copy(
                        to = to.joinToString(", ") { a -> a.formatted() },
                        cc = cc.joinToString(", ") { a -> a.formatted() },
                        showCcBcc = cc.isNotEmpty(),
                        subject = ReplyBuilder.replySubject(d.subject),
                        body = TextFieldValue(body, TextRange(0)),
                        loadingOriginal = false,
                        dirty = false,
                    )
                }
            }

            ComposeMode.FORWARD -> {
                val forwarded = buildString {
                    appendLine(texts.forwardedHeader)
                    appendLine("${texts.from}: ${d.from?.formatted().orEmpty()}")
                    appendLine("${texts.date}: ${dateText(d.dateMillis)}")
                    appendLine("${texts.subject}: ${d.subject}")
                    appendLine("${texts.to}: ${d.to.joinToString(", ") { a -> a.formatted() }}")
                    appendLine()
                    append(d.bodyText)
                }
                _state.update {
                    it.copy(
                        subject = ReplyBuilder.forwardSubject(d.subject),
                        body = TextFieldValue("\n\n" + forwarded, TextRange(0)),
                        loadingOriginal = false,
                        dirty = false,
                    )
                }
            }

            ComposeMode.DRAFT -> {
                d.messageId?.let { messageId = it }
                inReplyTo = d.inReplyTo
                references = d.references
                _state.update {
                    it.copy(
                        to = d.to.joinToString(", ") { a -> a.formatted() },
                        cc = d.cc.joinToString(", ") { a -> a.formatted() },
                        bcc = d.bcc.joinToString(", ") { a -> a.formatted() },
                        showCcBcc = d.cc.isNotEmpty() || d.bcc.isNotEmpty(),
                        subject = d.subject,
                        body = TextFieldValue(d.bodyText, TextRange(0)),
                        loadingOriginal = false,
                        dirty = false,
                    )
                }
            }

            ComposeMode.NEW -> _state.update { it.copy(loadingOriginal = false) }
        }
    }

    // ------------------------------------------------------------ send / draft

    private class AddressProblem(val ref: String?) : Exception()

    private fun parseAddresses(input: String, strict: Boolean): List<String> {
        val cleaned = input
            .replace(';', ',').replace('؛', ',').replace('،', ',').replace('\n', ',')
            .trim().trim(',')
        if (cleaned.isBlank()) return emptyList()
        return try {
            InternetAddress.parse(cleaned, strict).map { a ->
                if (strict) a.validate()
                a.toString()
            }
        } catch (e: AddressException) {
            throw AddressProblem(e.ref)
        }
    }

    private fun buildOutgoing(strict: Boolean, withSignature: Boolean): OutgoingMessage {
        val s = _state.value
        val signature = if (withSignature && s.includeSignature && signatureAvailable) {
            OutgoingSignature(
                parts = signatureParts,
                rtl = languageTag.startsWith("ar"),
                logo = if (hasLogo) container.accountStore.logoFile(accountId) else null
            )
        } else null
        return OutgoingMessage(
            messageId = messageId,
            to = parseAddresses(s.to, strict),
            cc = parseAddresses(s.cc, strict),
            bcc = parseAddresses(s.bcc, strict),
            subject = s.subject.trim(),
            body = s.body.text,
            inReplyTo = inReplyTo,
            references = references,
            replacesDraftUid = replacesUid,
            attachments = s.attachments,
            signature = signature,
        )
    }

    fun send() {
        if (_state.value.busy || _state.value.loadingOriginal || _state.value.attaching) return
        val out = try {
            buildOutgoing(strict = true, withSignature = true)
        } catch (p: AddressProblem) {
            _state.update { it.copy(notice = Notice(R.string.err_invalid_recipient, p.ref)) }
            return
        }
        if (out.to.isEmpty() && out.cc.isEmpty() && out.bcc.isEmpty()) {
            _state.update { it.copy(notice = Notice(R.string.err_no_recipient)) }
            return
        }
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            attempt { container.repository.send(accountId, out) }
                .onSuccess { outcome ->
                    recordSent(out)
                    _state.update {
                        it.copy(
                            busy = false,
                            dirty = false,
                            finishedWith = if (outcome.copySavedToSent) R.string.compose_sent_ok
                            else R.string.compose_sent_no_copy
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, notice = e.toNotice()) } }
        }
    }

    /**
     * Best effort, after the message was really delivered: remembers it (and the message it answers) for
     * the statistics, closes the follow-ups of the answered message and, if asked, enters it in the register.
     * A failure here never turns a delivered message into an error.
     */
    private suspend fun recordSent(out: OutgoingMessage) {
        val viewer = container.viewer() ?: return
        if (viewer.accountId != accountId) return
        val counterpart = (out.to + out.cc + out.bcc).firstOrNull()
            ?.let { runCatching { InternetAddress(it).address }.getOrNull() ?: it }.orEmpty()
        val sent = RefDraft(
            accountId = accountId,
            accountEmail = account?.email.orEmpty(),
            messageId = out.messageId,
            inReplyTo = out.inReplyTo,
            references = out.references,
            folderRole = FolderRole.SENT,
            uid = -1L,
            subject = out.subject,
            counterpart = counterpart,
            direction = Direction.OUTGOING,
            dateMillis = System.currentTimeMillis(),
            attachmentNames = out.attachments.map { it.name },
            external = ThreadLinker.isExternal(counterpart, container.companyDomains()),
        )
        attempt {
            container.admin.recordSent(
                viewer = viewer,
                original = originalDraft,
                sent = sent,
                register = _state.value.registerInLog,
                responsibleEmail = viewer.email,
            )
        }
    }

    fun saveDraft(closeAfter: Boolean) {
        if (_state.value.busy || _state.value.loadingOriginal || _state.value.attaching) return
        val out = try {
            buildOutgoing(strict = false, withSignature = false)
        } catch (p: AddressProblem) {
            _state.update { it.copy(notice = Notice(R.string.err_invalid_recipient, p.ref)) }
            return
        }
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            attempt { container.repository.saveDraft(accountId, out) }
                .onSuccess {
                    replacesUid = null // the original draft copy was replaced; later saves match by Message-ID
                    _state.update {
                        it.copy(
                            busy = false,
                            dirty = false,
                            finishedWith = if (closeAfter) R.string.compose_draft_saved else null,
                            notice = if (closeAfter) null else Notice(R.string.compose_draft_saved, isError = false)
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, notice = e.toNotice()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    accountId: String,
    mode: ComposeMode,
    role: FolderRole,
    uid: Long,
    onClose: () -> Unit,
) {
    val texts = ComposeTexts(
        wrote = stringResource(R.string.compose_wrote),
        forwardedHeader = stringResource(R.string.compose_forwarded_header),
        from = stringResource(R.string.message_from),
        date = stringResource(R.string.message_date),
        subject = stringResource(R.string.compose_subject),
        to = stringResource(R.string.message_to),
    )
    val locale = LocalConfiguration.current.locales[0]
    val vm = containerViewModel {
        ComposeViewModel(it, accountId, mode, role, uid, texts, locale.language, locale)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val container = com.shegrowsdev.mail.ui.common.rememberContainer()
    val snackbar = remember { SnackbarHostState() }
    var showDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(state.finishedWith) {
        state.finishedWith?.let { res ->
            Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
            onClose()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { n ->
            val text = context.getString(n.textRes) + (n.detail?.let { "\n$it" } ?: "")
            snackbar.showSnackbar(text, duration = SnackbarDuration.Long)
            vm.consumeNotice()
        }
    }

    val requestBack: () -> Unit = {
        if (!state.busy) {
            if (state.dirty) {
                showDiscard = true
            } else {
                onClose()
            }
        }
    }
    BackHandler(enabled = state.dirty || state.busy) { requestBack() }

    val addressStyle = TextStyle(textDirection = TextDirection.Ltr)
    val contentStyle = TextStyle(textDirection = TextDirection.Content)
    val editable = !state.busy && !state.loadingOriginal && !state.attaching

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addAttachments(uris)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (mode) {
                                ComposeMode.REPLY, ComposeMode.REPLY_ALL -> R.string.message_reply
                                ComposeMode.FORWARD -> R.string.message_forward
                                else -> R.string.compose_title_new
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { pickFiles.launch(AttachmentPolicy.pickerMimeTypes) },
                        enabled = editable
                    ) {
                        Icon(Icons.Filled.AttachFile, stringResource(R.string.compose_attach))
                    }
                    IconButton(onClick = { vm.saveDraft(closeAfter = false) }, enabled = editable) {
                        Icon(Icons.Filled.Save, stringResource(R.string.compose_save_draft))
                    }
                    IconButton(onClick = vm::send, enabled = editable) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.compose_send))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (state.busy || state.loadingOriginal || state.attaching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.to,
                    onValueChange = vm::onTo,
                    label = { Text(stringResource(R.string.compose_to)) },
                    enabled = editable,
                    textStyle = addressStyle,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    supportingText = { Text(stringResource(R.string.compose_recipients_hint)) },
                    trailingIcon = {
                        if (!state.showCcBcc) {
                            TextButton(onClick = vm::showCcBcc) {
                                Text(stringResource(R.string.compose_show_cc_bcc))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.showCcBcc) {
                    OutlinedTextField(
                        value = state.cc,
                        onValueChange = vm::onCc,
                        label = { Text(stringResource(R.string.compose_cc)) },
                        enabled = editable,
                        textStyle = addressStyle,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.bcc,
                        onValueChange = vm::onBcc,
                        label = { Text(stringResource(R.string.compose_bcc)) },
                        enabled = editable,
                        textStyle = addressStyle,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = state.subject,
                    onValueChange = vm::onSubject,
                    label = { Text(stringResource(R.string.compose_subject)) },
                    enabled = editable,
                    singleLine = true,
                    textStyle = contentStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.body,
                    onValueChange = vm::onBody,
                    label = { Text(stringResource(R.string.compose_body)) },
                    enabled = editable,
                    textStyle = contentStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp)
                )

                // ---- attachments
                OutlinedButton(
                    onClick = { pickFiles.launch(AttachmentPolicy.pickerMimeTypes) },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AttachFile, null)
                    Text(stringResource(R.string.compose_attach), modifier = Modifier.padding(start = 8.dp))
                }
                Text(
                    stringResource(
                        R.string.compose_attach_hint,
                        AttachmentPolicy.formatSize(AttachmentPolicy.MAX_FILE_BYTES),
                        AttachmentPolicy.formatSize(AttachmentPolicy.MAX_TOTAL_BYTES)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.attachments.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = contentStyle.merge(MaterialTheme.typography.bodyMedium), maxLines = 2)
                            Text(
                                AttachmentPolicy.formatSize(a.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.removeAttachment(a) }, enabled = editable) {
                            Icon(Icons.Filled.Close, stringResource(R.string.compose_remove_attachment))
                        }
                    }
                }

                // ---- optional entry in the official register
                if (container.viewer() != null) {
                    HorizontalDivider()
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.compose_register_in_log), Modifier.weight(1f))
                        Switch(
                            checked = state.registerInLog,
                            onCheckedChange = vm::onRegisterInLog,
                            enabled = editable
                        )
                    }
                }

                // ---- signature (added when sending; the text above stays exactly what you typed)
                if (vm.signatureAvailable) {
                    HorizontalDivider()
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.compose_include_signature))
                            val preview = vm.signatureParts.lines().joinToString("\n")
                            if (preview.isNotBlank()) {
                                Text(
                                    preview,
                                    style = contentStyle.merge(MaterialTheme.typography.bodySmall),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (vm.hasLogo) {
                                Text(
                                    stringResource(R.string.compose_signature_logo),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = state.includeSignature,
                            onCheckedChange = vm::onIncludeSignature,
                            enabled = editable
                        )
                    }
                }
            }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.compose_discard_title)) },
            text = { Text(stringResource(R.string.compose_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    vm.saveDraft(closeAfter = true)
                }) { Text(stringResource(R.string.compose_save_draft)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showDiscard = false
                        onClose()
                    }) { Text(stringResource(R.string.compose_discard)) }
                    TextButton(onClick = { showDiscard = false }) {
                        Text(stringResource(R.string.compose_keep_editing))
                    }
                }
            }
        )
    }
}
