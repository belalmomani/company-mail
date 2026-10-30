package com.shegrowsdev.mail.ui.tasks

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.TaskEvent
import com.shegrowsdev.mail.admin.TaskFilter
import com.shegrowsdev.mail.admin.TaskPatch
import com.shegrowsdev.mail.admin.TaskStatus
import com.shegrowsdev.mail.admin.TaskWithRef
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.ui.admin.PickDateTimeDialog
import com.shegrowsdev.mail.ui.admin.defaultDueMillis
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

// ------------------------------------------------------------------ list

class TasksViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val filter: TaskFilter = TaskFilter.OPEN,
        val items: List<TaskWithRef> = emptyList(),
        val loading: Boolean = true,
        val error: Notice? = null,
        val now: Long = System.currentTimeMillis(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        reload()
    }

    fun setFilter(filter: TaskFilter) {
        _state.update { it.copy(filter = filter) }
        reload()
    }

    fun reload() {
        val viewer = container.viewer()
        if (viewer == null) {
            _state.update { it.copy(loading = false, items = emptyList()) }
            return
        }
        job?.cancel()
        val filter = _state.value.filter
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val now = System.currentTimeMillis()
            attempt { container.admin.listTasks(viewer, filter, now) }
                .onSuccess { list -> _state.update { it.copy(items = list, loading = false, now = now) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toNotice()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(onBack: () -> Unit, onOpenTask: (Long) -> Unit) {
    val vm = containerViewModel { TasksViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    TaskFilter.OPEN to R.string.tasks_filter_open,
                    TaskFilter.OVERDUE to R.string.tasks_filter_overdue,
                    TaskFilter.DONE to R.string.tasks_filter_done,
                    TaskFilter.ALL to R.string.tasks_filter_all,
                ).forEach { (f, label) ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { vm.setFilter(f) },
                        label = { Text(stringResource(label), maxLines = 1) }
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                val error = state.error
                when {
                    state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(error.textRes), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::reload) { Text(stringResource(R.string.action_retry)) }
                    }
                    state.items.isEmpty() -> Text(
                        stringResource(R.string.tasks_empty),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items, key = { it.task.id }) { item ->
                            TaskRow(item, state.now, context) { onOpenTask(item.task.id) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(item: TaskWithRef, now: Long, context: Context, onClick: () -> Unit) {
    val task = item.task
    val overdue = task.isOverdue(now)
    val content = TextStyle(textDirection = TextDirection.Content)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.merge(content),
                maxLines = 2
            )
            Text(
                stringResource(task.status.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(
            item.ref.subject.ifBlank { stringResource(R.string.mailbox_no_subject) },
            style = MaterialTheme.typography.bodySmall.merge(content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Row {
            Text(
                task.assigneeEmail.ltr(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            val due = task.dueAt
            if (due != null) {
                Text(
                    (if (overdue) stringResource(R.string.tasks_overdue_prefix) + " " else "") +
                        formatFullDateTime(context, due).ltr(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ------------------------------------------------------------------ detail

class TaskDetailViewModel(private val container: AppContainer, private val taskId: Long) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val item: TaskWithRef? = null,
        val events: List<TaskEvent> = emptyList(),
        val title: String = "",
        val assignee: String = "",
        val dueAt: Long? = null,
        val status: TaskStatus = TaskStatus.OPEN,
        val busy: Boolean = false,
        val notice: Notice? = null,
        val openTarget: Pair<FolderRole, Long>? = null,
    ) {
        val dirty: Boolean
            get() {
                val i = item ?: return false
                return title.trim() != i.task.title || !assignee.equals(i.task.assigneeEmail, ignoreCase = true) ||
                    dueAt != i.task.dueAt || status != i.task.status
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val viewer = container.viewer()
        if (viewer == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        viewModelScope.launch {
            attempt {
                val item = container.admin.task(viewer, taskId)
                val events = if (item != null) container.admin.taskEvents(viewer, taskId) else emptyList<TaskEvent>()
                item to events
            }
                .onSuccess { (item, events) ->
                    _state.update {
                        it.copy(
                            loading = false, item = item, events = events,
                            title = item?.task?.title.orEmpty(),
                            assignee = item?.task?.assigneeEmail.orEmpty(),
                            dueAt = item?.task?.dueAt,
                            status = item?.task?.status ?: TaskStatus.OPEN,
                            notice = if (item == null) Notice(R.string.err_not_permitted) else null
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, notice = e.toNotice()) } }
        }
    }

    fun onTitle(v: String) = _state.update { it.copy(title = v) }
    fun onAssignee(v: String) = _state.update { it.copy(assignee = v) }
    fun onDue(v: Long?) = _state.update { it.copy(dueAt = v) }
    fun onStatus(v: TaskStatus) = _state.update { it.copy(status = v) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun consumeOpen() = _state.update { it.copy(openTarget = null) }

    fun save() {
        val viewer = container.viewer() ?: return
        val s = _state.value
        if (s.busy || !s.dirty || s.title.isBlank()) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            attempt {
                container.admin.updateTask(
                    viewer, taskId,
                    TaskPatch(
                        title = s.title, assigneeEmail = s.assignee,
                        setDue = true, dueAt = s.dueAt, status = s.status
                    )
                )
                container.admin.taskEvents(viewer, taskId) to container.admin.task(viewer, taskId)
            }
                .onSuccess { (events, item) ->
                    _state.update { it.copy(busy = false, events = events, item = item ?: it.item, notice = Notice(R.string.task_saved, isError = false)) }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, notice = e.toNotice()) } }
        }
    }

    fun openMessage() {
        val ref = _state.value.item?.ref ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            attempt { resolveAndActivate(container, ref) }
                .onSuccess { target -> _state.update { it.copy(busy = false, openTarget = target) } }
                .onFailure { e -> _state.update { it.copy(busy = false, notice = e.toNotice()) } }
        }
    }
}

private fun dueText(context: Context, raw: String?): String =
    raw?.toLongOrNull()?.let { formatFullDateTime(context, it).ltr() } ?: context.getString(R.string.task_due_none)

private fun statusText(context: Context, raw: String?): String =
    runCatching { context.getString(TaskStatus.valueOf(raw.orEmpty()).labelRes()) }.getOrDefault(raw.orEmpty())

private fun eventText(context: Context, e: TaskEvent): String = when (e.kind) {
    "CREATED" -> context.getString(R.string.task_event_created, e.newValue.orEmpty())
    "TITLE" -> context.getString(R.string.task_event_title, e.oldValue.orEmpty(), e.newValue.orEmpty())
    "ASSIGNEE" -> context.getString(R.string.task_event_assignee, e.oldValue.orEmpty().ltr(), e.newValue.orEmpty().ltr())
    "DUE" -> context.getString(R.string.task_event_due, dueText(context, e.oldValue), dueText(context, e.newValue))
    "STATUS" -> context.getString(R.string.task_event_status, statusText(context, e.oldValue), statusText(context, e.newValue))
    else -> e.kind
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskId: Long,
    onBack: () -> Unit,
    onOpenMessage: (FolderRole, Long) -> Unit,
) {
    val vm = containerViewModel { TaskDetailViewModel(it, taskId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val container = com.shegrowsdev.mail.ui.common.rememberContainer()
    val accounts by container.accountStore.accounts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf(false) }
    var assigneeMenu by remember { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.task_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val item = state.item
            if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (item != null) {
                val content = TextStyle(textDirection = TextDirection.Content)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // the message this task belongs to (the link never changes)
                    Text(stringResource(R.string.task_linked_message), style = MaterialTheme.typography.titleSmall)
                    Text(
                        item.ref.subject.ifBlank { stringResource(R.string.mailbox_no_subject) },
                        style = MaterialTheme.typography.bodyLarge.merge(content)
                    )
                    Text(
                        item.ref.counterpart.ltr() + " · " + item.ref.accountEmail.ltr(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = vm::openMessage, enabled = !state.busy) {
                        Text(stringResource(R.string.admin_open_message))
                    }
                    HorizontalDivider()

                    OutlinedTextField(
                        value = state.title,
                        onValueChange = vm::onTitle,
                        label = { Text(stringResource(R.string.task_title)) },
                        textStyle = content,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.task_assignee), style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { assigneeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(state.assignee.ltr(), maxLines = 1)
                        }
                        DropdownMenu(expanded = assigneeMenu, onDismissRequest = { assigneeMenu = false }) {
                            accounts.forEach { a ->
                                DropdownMenuItem(
                                    text = { Text(a.email.ltr()) },
                                    onClick = { vm.onAssignee(a.email); assigneeMenu = false }
                                )
                            }
                        }
                    }
                    Text(stringResource(R.string.task_due), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picking = true }) {
                            Text(state.dueAt?.let { formatFullDateTime(context, it).ltr() } ?: stringResource(R.string.task_due_none))
                        }
                        if (state.dueAt != null) {
                            TextButton(onClick = { vm.onDue(null) }) { Text(stringResource(R.string.action_clear)) }
                        }
                    }
                    Text(stringResource(R.string.task_status), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskStatus.values().forEach { st ->
                            FilterChip(
                                selected = state.status == st,
                                onClick = { vm.onStatus(st) },
                                label = { Text(stringResource(st.labelRes()), maxLines = 1) }
                            )
                        }
                    }
                    Button(
                        onClick = vm::save,
                        enabled = state.dirty && state.title.isNotBlank() && !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.action_save)) }

                    HorizontalDivider()
                    Text(stringResource(R.string.task_history), style = MaterialTheme.typography.titleSmall)
                    state.events.forEach { e ->
                        Column {
                            Text(eventText(context, e), style = MaterialTheme.typography.bodyMedium.merge(content))
                            Text(
                                formatFullDateTime(context, e.at).ltr() + " · " + e.actor.ltr(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    if (picking) {
        PickDateTimeDialog(
            initialMillis = state.dueAt ?: defaultDueMillis(),
            onPicked = { vm.onDue(it); picking = false },
            onDismiss = { picking = false }
        )
    }
}
