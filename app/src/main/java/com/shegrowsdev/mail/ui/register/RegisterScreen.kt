package com.shegrowsdev.mail.ui.register

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.Direction
import com.shegrowsdev.mail.admin.RegisterRow
import com.shegrowsdev.mail.admin.SerialFormat
import com.shegrowsdev.mail.admin.TaskWithRef
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.ui.admin.labelRes
import com.shegrowsdev.mail.ui.admin.resolveAndActivate
import com.shegrowsdev.mail.ui.common.Notice
import com.shegrowsdev.mail.ui.common.attempt
import com.shegrowsdev.mail.ui.common.containerViewModel
import com.shegrowsdev.mail.ui.common.formatFullDateTime
import com.shegrowsdev.mail.ui.common.ltr
import com.shegrowsdev.mail.ui.common.toNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RegisterViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val query: String = "",
        val direction: Direction? = null,
        val rows: List<RegisterRow> = emptyList(),
        val loading: Boolean = true,
        val error: Notice? = null,
        val selected: RegisterRow? = null,
        val relatedTasks: List<TaskWithRef> = emptyList(),
        val busy: Boolean = false,
        val notice: Notice? = null,
        val openTarget: Pair<FolderRole, Long>? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        reload()
    }

    fun onQuery(q: String) {
        _state.update { it.copy(query = q) }
        reload()
    }

    fun setDirection(d: Direction?) {
        _state.update { it.copy(direction = d) }
        reload()
    }

    fun reload() {
        val viewer = container.viewer()
        if (viewer == null) {
            _state.update { it.copy(loading = false, rows = emptyList()) }
            return
        }
        job?.cancel()
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            attempt { container.admin.listRegister(viewer, s.query, s.direction) }
                .onSuccess { rows -> _state.update { it.copy(rows = rows, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toNotice()) } }
        }
    }

    fun select(row: RegisterRow?) {
        _state.update { it.copy(selected = row, relatedTasks = emptyList()) }
        val viewer = container.viewer() ?: return
        if (row == null) return
        viewModelScope.launch {
            attempt { container.admin.tasksInTransaction(viewer, row.ref.transactionId) }
                .onSuccess { tasks -> _state.update { if (it.selected?.entry?.id == row.entry.id) it.copy(relatedTasks = tasks) else it } }
        }
    }

    fun openMessage(row: RegisterRow) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            attempt { resolveAndActivate(container, row.ref) }
                .onSuccess { target -> _state.update { it.copy(busy = false, openTarget = target, selected = null) } }
                .onFailure { e -> _state.update { it.copy(busy = false, notice = e.toNotice()) } }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun consumeOpen() = _state.update { it.copy(openTarget = null) }
}

private fun directionLabel(context: Context, d: Direction): String =
    context.getString(if (d == Direction.INCOMING) R.string.register_incoming else R.string.register_outgoing)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(onBack: () -> Unit, onOpenMessage: (FolderRole, Long) -> Unit) {
    val vm = containerViewModel { RegisterViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }

    LaunchedEffect(state.notice) {
        state.notice?.let { n ->
            snackbar.showSnackbar(context.getString(n.textRes) + (n.detail?.let { "\n$it" } ?: ""), duration = SnackbarDuration.Long)
            vm.consumeNotice()
        }
    }
    LaunchedEffect(state.openTarget) {
        state.openTarget?.let { (role, uid) ->
            vm.consumeOpen()
            onOpenMessage(role, uid)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.register_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQuery,
                singleLine = true,
                label = { Text(stringResource(R.string.register_search_hint)) },
                textStyle = TextStyle(textDirection = TextDirection.Content),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    null to R.string.register_all,
                    Direction.INCOMING to R.string.register_incoming,
                    Direction.OUTGOING to R.string.register_outgoing,
                ).forEach { (d, label) ->
                    FilterChip(
                        selected = state.direction == d,
                        onClick = { vm.setDirection(d) },
                        label = { Text(stringResource(label), maxLines = 1) }
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                val error = state.error
                when {
                    state.loading && state.rows.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(error.textRes), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::reload) { Text(stringResource(R.string.action_retry)) }
                    }
                    state.rows.isEmpty() -> Text(
                        stringResource(R.string.register_empty),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.rows, key = { it.entry.id }) { row ->
                            RegisterRowItem(row, context) { vm.select(row) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    state.selected?.let { row ->
        RegisterDetailDialog(
            row = row,
            tasks = state.relatedTasks,
            busy = state.busy,
            onOpenMessage = { vm.openMessage(row) },
            onDismiss = { vm.select(null) }
        )
    }
}

@Composable
private fun RegisterRowItem(row: RegisterRow, context: Context, onClick: () -> Unit) {
    val e = row.entry
    val content = TextStyle(textDirection = TextDirection.Content)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#" + SerialFormat.format(e.seq) + " · " + directionLabel(context, e.direction),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(formatFullDateTime(context, e.entryDate).ltr(), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            e.subject.ifBlank { stringResource(R.string.mailbox_no_subject) },
            style = MaterialTheme.typography.bodyLarge.merge(content),
            maxLines = 2
        )
        Text(
            e.party.ltr() + " → " + e.responsibleEmail.ltr(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        val extras = buildList {
            if (row.ref.attachmentNames.isNotEmpty()) add(stringResource(R.string.register_attachments_count, row.ref.attachmentNames.size))
            if (row.relatedTaskCount > 0) add(stringResource(R.string.register_tasks_count, row.relatedTaskCount))
        }
        if (extras.isNotEmpty()) {
            Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RegisterDetailDialog(
    row: RegisterRow,
    tasks: List<TaskWithRef>,
    busy: Boolean,
    onOpenMessage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val e = row.entry
    val content = TextStyle(textDirection = TextDirection.Content)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("#" + SerialFormat.format(e.seq)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(directionLabel(context, e.direction) + " · " + formatFullDateTime(context, e.entryDate).ltr())
                Text(e.subject.ifBlank { stringResource(R.string.mailbox_no_subject) }, style = MaterialTheme.typography.titleSmall.merge(content))
                Text(stringResource(R.string.register_party) + ": " + e.party.ltr())
                Text(stringResource(R.string.register_responsible) + ": " + e.responsibleEmail.ltr())
                Text(stringResource(R.string.register_mailbox) + ": " + row.ref.accountEmail.ltr())
                if (row.ref.attachmentNames.isNotEmpty()) {
                    Text(stringResource(R.string.message_attachments), style = MaterialTheme.typography.titleSmall)
                    row.ref.attachmentNames.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall.merge(content)) }
                }
                Text(stringResource(R.string.register_related_tasks), style = MaterialTheme.typography.titleSmall)
                if (tasks.isEmpty()) {
                    Text(stringResource(R.string.register_no_tasks), style = MaterialTheme.typography.bodySmall)
                } else {
                    tasks.forEach { t ->
                        Text(
                            "• " + t.task.title + " (" + stringResource(t.task.status.labelRes()) + ")",
                            style = MaterialTheme.typography.bodySmall.merge(content)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenMessage, enabled = !busy) { Text(stringResource(R.string.admin_open_message)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}
