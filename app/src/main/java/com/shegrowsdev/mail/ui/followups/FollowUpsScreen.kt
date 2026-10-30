package com.shegrowsdev.mail.ui.followups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.FollowUpSection
import com.shegrowsdev.mail.admin.FollowUpState
import com.shegrowsdev.mail.admin.FollowUpWithRef
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.ui.admin.PickDateTimeDialog
import com.shegrowsdev.mail.ui.admin.SectionLabel
import com.shegrowsdev.mail.ui.admin.resolveAndActivate
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

class FollowUpsViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val overdue: List<FollowUpWithRef> = emptyList(),
        val upcoming: List<FollowUpWithRef> = emptyList(),
        val closed: List<FollowUpWithRef> = emptyList(),
        val loading: Boolean = true,
        val busy: Boolean = false,
        val now: Long = System.currentTimeMillis(),
        val notice: Notice? = null,
        val openTarget: Pair<FolderRole, Long>? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        val viewer = container.viewer()
        if (viewer == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            attempt {
                Triple(
                    container.admin.listFollowUps(viewer, FollowUpSection.OVERDUE, now),
                    container.admin.listFollowUps(viewer, FollowUpSection.UPCOMING, now),
                    container.admin.listFollowUps(viewer, FollowUpSection.CLOSED, now),
                )
            }
                .onSuccess { (o, u, c) ->
                    _state.update { it.copy(overdue = o, upcoming = u, closed = c, loading = false, now = now) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, notice = e.toNotice()) } }
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            attempt { block() }
                .onSuccess { _state.update { it.copy(busy = false) }; reload() }
                .onFailure { e -> _state.update { it.copy(busy = false, notice = e.toNotice()) } }
        }
    }

    fun complete(item: FollowUpWithRef) {
        val viewer = container.viewer() ?: return
        run { container.admin.closeFollowUp(viewer, item.followUp.id, FollowUpState.DONE) }
    }

    fun cancel(item: FollowUpWithRef) {
        val viewer = container.viewer() ?: return
        run { container.admin.closeFollowUp(viewer, item.followUp.id, FollowUpState.CANCELLED) }
    }

    fun reschedule(item: FollowUpWithRef, dueAt: Long) {
        val viewer = container.viewer() ?: return
        run { container.admin.updateFollowUp(viewer, item.followUp.id, dueAt, null) }
    }

    fun openMessage(item: FollowUpWithRef) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            attempt { resolveAndActivate(container, item.ref) }
                .onSuccess { target -> _state.update { it.copy(busy = false, openTarget = target) } }
                .onFailure { e -> _state.update { it.copy(busy = false, notice = e.toNotice()) } }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun consumeOpen() = _state.update { it.copy(openTarget = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpsScreen(onBack: () -> Unit, onOpenMessage: (FolderRole, Long) -> Unit) {
    val vm = containerViewModel { FollowUpsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var rescheduling by remember { mutableStateOf<FollowUpWithRef?>(null) }
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
                title = { Text(stringResource(R.string.followups_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val empty = state.overdue.isEmpty() && state.upcoming.isEmpty() && state.closed.isEmpty()
            when {
                state.loading && empty -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                empty -> Text(
                    stringResource(R.string.followups_empty),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (state.overdue.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.followups_overdue) + " (" + state.overdue.size + ")") }
                        items(state.overdue, key = { "o" + it.followUp.id }) { f ->
                            FollowUpRow(f, overdue = true, pending = true, busy = state.busy, vm = vm, onReschedule = { rescheduling = f })
                        }
                    }
                    if (state.upcoming.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.followups_upcoming) + " (" + state.upcoming.size + ")") }
                        items(state.upcoming, key = { "u" + it.followUp.id }) { f ->
                            FollowUpRow(f, overdue = false, pending = true, busy = state.busy, vm = vm, onReschedule = { rescheduling = f })
                        }
                    }
                    if (state.closed.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.followups_closed)) }
                        items(state.closed, key = { "c" + it.followUp.id }) { f ->
                            FollowUpRow(f, overdue = false, pending = false, busy = state.busy, vm = vm, onReschedule = {})
                        }
                    }
                }
            }
        }
    }

    rescheduling?.let { f ->
        PickDateTimeDialog(
            initialMillis = maxOf(f.followUp.dueAt, System.currentTimeMillis()),
            onPicked = { vm.reschedule(f, it); rescheduling = null },
            onDismiss = { rescheduling = null }
        )
    }
}

@Composable
private fun FollowUpRow(
    item: FollowUpWithRef,
    overdue: Boolean,
    pending: Boolean,
    busy: Boolean,
    vm: FollowUpsViewModel,
    onReschedule: () -> Unit,
) {
    val context = LocalContext.current
    val content = TextStyle(textDirection = TextDirection.Content)
    val f = item.followUp
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            item.ref.subject.ifBlank { stringResource(R.string.mailbox_no_subject) },
            style = MaterialTheme.typography.bodyLarge.merge(content),
            maxLines = 2
        )
        Text(
            item.ref.counterpart.ltr() + " · " + item.ref.accountEmail.ltr(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (f.note.isNotBlank()) Text(f.note, style = MaterialTheme.typography.bodyMedium.merge(content))
        Text(
            (if (overdue) stringResource(R.string.tasks_overdue_prefix) + " " else "") + formatFullDateTime(context, f.dueAt).ltr() +
                if (!pending) " · " + stringResource(
                    if (f.state == FollowUpState.DONE) R.string.followup_state_done else R.string.followup_state_cancelled
                ) else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { vm.openMessage(item) }, enabled = !busy) { Text(stringResource(R.string.admin_open_message)) }
            if (pending) {
                TextButton(onClick = { vm.complete(item) }, enabled = !busy) { Text(stringResource(R.string.followup_done)) }
                TextButton(onClick = onReschedule, enabled = !busy) { Text(stringResource(R.string.followup_reschedule)) }
                TextButton(onClick = { vm.cancel(item) }, enabled = !busy) { Text(stringResource(R.string.followup_dismiss)) }
            }
        }
    }
    HorizontalDivider()
}
