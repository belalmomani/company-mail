package com.shegrowsdev.mail.ui.message

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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.shegrowsdev.mail.mail.FolderRole
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MessageViewModel(
    private val container: AppContainer,
    private val accountId: String,
    private val role: FolderRole,
    private val uid: Long,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val detail: MessageDetail? = null,
        val error: Notice? = null,
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
                .onSuccess { d -> _state.update { UiState(loading = false, detail = d) } }
                .onFailure { e -> _state.update { UiState(loading = false, error = e.toNotice()) } }
        }
    }
}

private const val MAX_RENDERED_LINES = 4000

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    if (detail.bodyFromHtml) InfoText(stringResource(R.string.message_html_notice))
                    if (detail.hasAttachments) InfoText(stringResource(R.string.message_attachments_notice))

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
