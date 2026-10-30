package com.shegrowsdev.mail.ui.unified

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.Permissions
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.MessageSummary
import com.shegrowsdev.mail.ui.common.Notice
import com.shegrowsdev.mail.ui.common.attempt
import com.shegrowsdev.mail.ui.common.containerViewModel
import com.shegrowsdev.mail.ui.common.formatDateTime
import com.shegrowsdev.mail.ui.common.ltr
import com.shegrowsdev.mail.ui.common.rememberContainer
import com.shegrowsdev.mail.ui.common.toNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One inbox message together with the account it belongs to. */
data class UnifiedItem(val accountId: String, val accountEmail: String, val message: MessageSummary)

/**
 * Newest inbox messages of every account the current viewer may see: an administrator sees all accounts
 * on this device, an employee only their own (so for an employee this is just their inbox).
 */
class UnifiedViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val items: List<UnifiedItem> = emptyList(),
        /** Accounts that could not be read (offline, wrong password, ...). */
        val failed: List<String> = emptyList(),
        val error: Notice? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        reload()
    }

    fun reload() {
        val viewer = container.viewer()
        if (viewer == null) {
            _state.update { UiState(loading = false) }
            return
        }
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val accounts = Permissions.visibleAccounts(viewer, container.accountStore.accounts.value)
            val all = mutableListOf<UnifiedItem>()
            val failed = mutableListOf<String>()
            var firstError: Notice? = null
            for (a in accounts) {
                attempt { container.repository.page(a.id, FolderRole.INBOX, 0) }
                    .onSuccess { p -> p.items.forEach { all += UnifiedItem(a.id, a.email, it) } }
                    .onFailure { e ->
                        failed += a.email
                        if (firstError == null) firstError = e.toNotice()
                    }
            }
            val sorted = all.sortedByDescending { it.message.dateMillis ?: Long.MIN_VALUE }
            _state.update {
                UiState(
                    loading = false,
                    items = sorted,
                    failed = failed,
                    error = if (sorted.isEmpty()) firstError else null
                )
            }
        }
    }

    /** Opening a message of another account first makes that account the active one. */
    fun activate(accountId: String) = container.accountStore.setActive(accountId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedInboxScreen(onBack: () -> Unit, onOpenMessage: (FolderRole, Long) -> Unit) {
    val vm = containerViewModel { UnifiedViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val container = rememberContainer()
    val context = LocalContext.current
    val isAdmin = container.viewer()?.isAdmin == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.unified_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::reload) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(if (isAdmin) R.string.unified_scope_admin else R.string.unified_scope_employee),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (state.failed.isNotEmpty()) {
                Text(
                    stringResource(R.string.unified_failed, state.failed.joinToString(", ") { it.ltr() }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            Box(Modifier.fillMaxSize()) {
                val error = state.error
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> Text(
                        stringResource(error.textRes) + (error.detail?.let { "\n$it" } ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                    state.items.isEmpty() -> Text(
                        stringResource(R.string.mailbox_empty),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items, key = { it.accountId + "/" + it.message.uid }) { item ->
                            val m = item.message
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.activate(item.accountId)
                                        onOpenMessage(FolderRole.INBOX, m.uid)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    item.accountEmail.ltr(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    (m.from?.display ?: stringResource(R.string.mailbox_unknown_sender)),
                                    fontWeight = if (m.seen) FontWeight.Normal else FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    m.subject.ifBlank { stringResource(R.string.mailbox_no_subject) },
                                    fontWeight = if (m.seen) FontWeight.Normal else FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    formatDateTime(context, m.dateMillis).ltr(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
