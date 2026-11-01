package com.shegrowsdev.mail.ui.mailbox

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.ui.common.BrandLogo
import com.shegrowsdev.mail.data.AccountRole
import com.shegrowsdev.mail.mail.ConversationRow
import com.shegrowsdev.mail.mail.Conversations
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.SortMode
import com.shegrowsdev.mail.mail.MessageSummary
import com.shegrowsdev.mail.ui.common.Notice
import com.shegrowsdev.mail.ui.common.attempt
import com.shegrowsdev.mail.ui.common.containerViewModel
import com.shegrowsdev.mail.ui.common.formatDateTime
import com.shegrowsdev.mail.ui.common.labelRes
import com.shegrowsdev.mail.ui.common.ltr
import com.shegrowsdev.mail.ui.common.rememberContainer
import com.shegrowsdev.mail.ui.common.toNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val navigableRoles = listOf(
    FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS, FolderRole.SPAM, FolderRole.ARCHIVE, FolderRole.TRASH
)

class MailboxViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val role: FolderRole = FolderRole.INBOX,
        val items: List<MessageSummary> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val error: Notice? = null,
        val sort: SortMode = SortMode.NEWEST_FIRST,
        /** Messages with the same subject are shown as one row (applies to the loaded messages). */
        val grouped: Boolean = false,
        val searchActive: Boolean = false,
        val query: String = "",
        val searching: Boolean = false,
        /** Null until a search has run; then the matches (possibly empty). */
        val searchResults: List<MessageSummary>? = null,
        val searchPartial: Boolean = false,
    ) {
        /** A fresh list for another folder/account that keeps the user's view options. */
        fun reset(role: FolderRole = this.role) = UiState(role = role, sort = sort, grouped = grouped)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var accountId: String? = null
    private var page = 0
    private var job: Job? = null
    private var searchJob: Job? = null
    private var resumedBefore = false

    init {
        viewModelScope.launch {
            combine(container.accountStore.accounts, container.accountStore.activeId) { list, active ->
                (list.firstOrNull { it.id == active } ?: list.firstOrNull())?.id
            }.collect { id ->
                if (id != accountId) {
                    accountId = id
                    if (id == null) {
                        job?.cancel()
                        _state.update { it.reset() }
                    } else {
                        load(clear = true)
                    }
                }
            }
        }
    }

    fun selectRole(role: FolderRole) {
        if (role == _state.value.role && !_state.value.searchActive) return
        searchJob?.cancel()
        _state.update { it.reset(role) }
        load(clear = true)
    }

    fun setSort(mode: SortMode) = _state.update { it.copy(sort = mode) }
    fun setGrouped(on: Boolean) = _state.update { it.copy(grouped = on) }

    // ------------------------------------------------------------ search

    fun openSearch() = _state.update { it.copy(searchActive = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchActive = false, query = "", searching = false, searchResults = null, searchPartial = false) }
    }

    fun onQuery(q: String) = _state.update { it.copy(query = q) }

    fun submitSearch() {
        val id = accountId ?: return
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        val role = _state.value.role
        searchJob?.cancel()
        _state.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch {
            attempt { container.repository.search(id, role, q) }
                .onSuccess { r ->
                    _state.update { it.copy(searching = false, searchResults = r.items, searchPartial = r.partialScan) }
                }
                .onFailure { e -> _state.update { it.copy(searching = false, error = e.toNotice()) } }
        }
    }

    /** Coming back from another screen (e.g. after deleting/archiving or reading): bring the list up to date. */
    fun onResume() {
        if (!resumedBefore) {
            resumedBefore = true
            return
        }
        if (accountId == null) return
        if (_state.value.searchActive) submitSearch() else load(clear = false)
    }

    fun refresh() = load(clear = false)

    private fun load(clear: Boolean) {
        val id = accountId ?: return
        job?.cancel()
        page = 0
        _state.update { (if (clear) it.reset() else it).copy(loading = true, error = null) }
        val role = _state.value.role
        job = viewModelScope.launch {
            attempt { container.repository.page(id, role, 0) }
                .onSuccess { p ->
                    _state.update { it.copy(items = p.items, hasMore = p.hasMore, loading = false) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toNotice()) } }
        }
    }

    fun loadMore() {
        val s = _state.value
        val id = accountId ?: return
        if (s.loading || s.loadingMore || !s.hasMore) return
        val role = s.role
        val nextPage = page + 1
        _state.update { it.copy(loadingMore = true, error = null) }
        job = viewModelScope.launch {
            attempt { container.repository.page(id, role, nextPage) }
                .onSuccess { p ->
                    page = nextPage
                    // New mail may have shifted the paging window; never show a message twice.
                    _state.update {
                        it.copy(
                            items = (it.items + p.items).distinctBy { m -> m.uid },
                            hasMore = p.hasMore,
                            loadingMore = false
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, error = e.toNotice()) } }
        }
    }
}

private fun FolderRole.icon(): ImageVector = when (this) {
    FolderRole.INBOX -> Icons.Filled.Inbox
    FolderRole.SENT -> Icons.AutoMirrored.Filled.Send
    FolderRole.DRAFTS -> Icons.Filled.Drafts
    FolderRole.SPAM -> Icons.Filled.Report
    FolderRole.ARCHIVE -> Icons.Filled.Archive
    FolderRole.TRASH -> Icons.Filled.Delete
    else -> Icons.Filled.Inbox
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(
    onOpenMessage: (FolderRole, Long) -> Unit,
    onOpenDraft: (Long) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onAddAccount: () -> Unit,
    onUnified: () -> Unit,
    onTasks: () -> Unit,
    onRegister: () -> Unit,
    onFollowUps: () -> Unit,
    onDashboard: () -> Unit,
    onBackup: () -> Unit,
    onColleagues: () -> Unit,
) {
    val container = rememberContainer()
    val vm = containerViewModel { MailboxViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val accounts by container.accountStore.accounts.collectAsStateWithLifecycle()
    val activeId by container.accountStore.activeId.collectAsStateWithLifecycle()
    val activeAccount = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var viewMenuOpen by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    val source = if (state.searchActive) state.searchResults.orEmpty() else state.items
    val rows: List<ConversationRow> = remember(source, state.sort, state.grouped) {
        val sorted = Conversations.sort(source, state.sort)
        if (state.grouped) Conversations.group(sorted) else Conversations.single(sorted)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BrandLogo(height = 120.dp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                    Text(
                        stringResource(R.string.account_switch),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                    accounts.forEach { acc ->
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    if (acc.displayName.isNotBlank()) {
                                        Text(acc.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(
                                        acc.email.ltr(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            icon = { Icon(Icons.Filled.AccountCircle, null) },
                            selected = acc.id == activeAccount?.id,
                            onClick = {
                                container.accountStore.setActive(acc.id)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.account_add)) },
                        icon = { Icon(Icons.Filled.Add, null) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onAddAccount()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    navigableRoles.forEach { role ->
                        NavigationDrawerItem(
                            label = { Text(stringResource(role.labelRes())) },
                            icon = { Icon(role.icon(), null) },
                            selected = state.role == role,
                            onClick = {
                                vm.selectRole(role)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    listOf(
                        Triple(R.string.colleagues_title, Icons.Filled.Groups, onColleagues),
                        Triple(R.string.unified_title, Icons.Filled.AllInbox, onUnified),
                        Triple(R.string.tasks_title, Icons.AutoMirrored.Filled.Assignment, onTasks),
                        Triple(R.string.register_title, Icons.AutoMirrored.Filled.MenuBook, onRegister),
                        Triple(R.string.followups_title, Icons.Filled.Alarm, onFollowUps),
                        Triple(R.string.dash_title, Icons.Filled.Dashboard, onDashboard),
                    ).plus(
                        // Backup and restore cover every employee's data: administrators only.
                        if (activeAccount?.role == AccountRole.ADMIN) {
                            listOf(Triple(R.string.backup_title, Icons.Filled.Backup, onBackup))
                        } else emptyList()
                    ).forEach { (label, icon, action) ->
                        NavigationDrawerItem(
                            label = { Text(stringResource(label)) },
                            icon = { Icon(icon, null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                action()
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.settings_title)) },
                        icon = { Icon(Icons.Filled.Settings, null) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onSettings()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (state.searchActive) {
                    TopAppBar(
                        title = {
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = vm::onQuery,
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.search_hint)) },
                                textStyle = TextStyle(textDirection = TextDirection.Content),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { vm.submitSearch() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = vm::closeSearch) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                            }
                        },
                        actions = {
                            IconButton(onClick = vm::submitSearch, enabled = state.query.isNotBlank() && !state.searching) {
                                Icon(Icons.Filled.Search, stringResource(R.string.action_search))
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(stringResource(state.role.labelRes()))
                                activeAccount?.let {
                                    Text(
                                        it.email.ltr(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, stringResource(R.string.action_menu))
                            }
                        },
                        actions = {
                            IconButton(onClick = vm::openSearch) {
                                Icon(Icons.Filled.Search, stringResource(R.string.action_search))
                            }
                            IconButton(onClick = vm::refresh, enabled = !state.loading) {
                                Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh))
                            }
                            Box {
                                IconButton(onClick = { viewMenuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, stringResource(R.string.view_options))
                                }
                                DropdownMenu(expanded = viewMenuOpen, onDismissRequest = { viewMenuOpen = false }) {
                                    listOf(
                                        SortMode.NEWEST_FIRST to R.string.sort_newest,
                                        SortMode.OLDEST_FIRST to R.string.sort_oldest,
                                        SortMode.UNREAD_FIRST to R.string.sort_unread_first,
                                    ).forEach { (mode, label) ->
                                        DropdownMenuItem(
                                            text = { Text((if (state.sort == mode) "✓ " else "") + stringResource(label)) },
                                            onClick = { vm.setSort(mode); viewMenuOpen = false }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text((if (state.grouped) "✓ " else "") + stringResource(R.string.view_group_conversations))
                                        },
                                        onClick = { vm.setGrouped(!state.grouped); viewMenuOpen = false }
                                    )
                                }
                            }
                        }
                    )
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onCompose,
                    icon = { Icon(Icons.Filled.Edit, null) },
                    text = { Text(stringResource(R.string.compose_title_new)) }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    rows.isEmpty() && (state.loading || state.searching) ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    rows.isEmpty() && state.error != null -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val n = state.error!!
                        Text(
                            stringResource(n.textRes) + (n.detail?.let { "\n$it" } ?: ""),
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = vm::refresh) { Text(stringResource(R.string.action_retry)) }
                    }

                    rows.isEmpty() -> Text(
                        stringResource(
                            when {
                                !state.searchActive -> R.string.mailbox_empty
                                state.searchResults == null -> R.string.search_hint
                                else -> R.string.search_no_results
                            }
                        ),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        if (state.searchActive && state.searchPartial) {
                            item {
                                Text(
                                    stringResource(R.string.search_partial_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                        state.error?.let { n ->
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(n.textRes) + (n.detail?.let { "\n$it" } ?: ""),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = vm::refresh) {
                                        Text(stringResource(R.string.action_retry))
                                    }
                                }
                            }
                        }
                        items(rows, key = { it.latest.uid }) { row ->
                            val m = row.latest
                            MessageRow(
                                summary = m,
                                role = state.role,
                                dateText = formatDateTime(context, m.dateMillis),
                                count = row.count,
                                unreadCount = row.unread,
                                onClick = {
                                    if (state.role == FolderRole.DRAFTS) onOpenDraft(m.uid)
                                    else onOpenMessage(state.role, m.uid)
                                }
                            )
                            HorizontalDivider()
                        }
                        if (state.hasMore && !state.searchActive) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    if (state.loadingMore) {
                                        CircularProgressIndicator()
                                    } else {
                                        TextButton(onClick = vm::loadMore) {
                                            Text(stringResource(R.string.mailbox_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    summary: MessageSummary,
    role: FolderRole,
    dateText: String,
    count: Int,
    unreadCount: Int,
    onClick: () -> Unit,
) {
    val unread = unreadCount > 0 && role != FolderRole.SENT && role != FolderRole.DRAFTS
    val weight = if (unread) FontWeight.Bold else FontWeight.Normal
    val contentDirection = TextStyle(textDirection = TextDirection.Content)

    val party = if (role == FolderRole.SENT || role == FolderRole.DRAFTS) {
        summary.to.joinToString(", ") { it.display }
            .ifBlank { stringResource(R.string.mailbox_no_recipient) }
    } else {
        summary.from?.display ?: stringResource(R.string.mailbox_unknown_sender)
    }

    val partyText = if (count > 1) "$party ($count)" else party

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                partyText,
                modifier = Modifier.weight(1f),
                fontWeight = weight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.merge(contentDirection)
            )
            Text(
                dateText,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(
            summary.subject.ifBlank { stringResource(R.string.mailbox_no_subject) },
            fontWeight = weight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.merge(contentDirection)
        )
    }
}
