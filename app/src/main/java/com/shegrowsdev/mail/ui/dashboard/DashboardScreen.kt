package com.shegrowsdev.mail.ui.dashboard

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.Durations
import com.shegrowsdev.mail.admin.Period
import com.shegrowsdev.mail.admin.PeriodPreset
import com.shegrowsdev.mail.admin.Periods
import com.shegrowsdev.mail.admin.Permissions
import com.shegrowsdev.mail.admin.ResponseGroup
import com.shegrowsdev.mail.admin.ResponseSummary
import com.shegrowsdev.mail.admin.Stats
import com.shegrowsdev.mail.admin.TransactionCounts
import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.ui.common.Notice
import com.shegrowsdev.mail.ui.common.attempt
import com.shegrowsdev.mail.ui.common.containerViewModel
import com.shegrowsdev.mail.ui.common.ltr
import com.shegrowsdev.mail.ui.common.toNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Real numbers only: nothing on this screen is a placeholder. A figure that could not be read says so. */
data class DashboardData(
    val period: Period,
    val incoming: Int?,
    val outgoing: Int?,
    /** Accounts whose server counts could not be read (the totals then leave them out). */
    val failedAccounts: List<String>,
    val transactions: TransactionCounts,
    val responses: ResponseSummary,
    val computedAt: Long,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val preset: PeriodPreset = PeriodPreset.LAST_7_DAYS,
        val customFirst: LocalDate? = null,
        val customLast: LocalDate? = null,
        /** Null = every account the viewer may see. */
        val accountId: String? = null,
        val loading: Boolean = true,
        val data: DashboardData? = null,
        val error: Notice? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null
    private val zone: ZoneId = ZoneId.systemDefault()

    init {
        reload()
    }

    fun setPreset(p: PeriodPreset) {
        _state.update { it.copy(preset = p) }
        if (p != PeriodPreset.CUSTOM) reload()
    }

    fun setCustom(first: LocalDate, last: LocalDate) {
        _state.update { it.copy(preset = PeriodPreset.CUSTOM, customFirst = first, customLast = last) }
        reload()
    }

    fun setAccount(id: String?) {
        _state.update { it.copy(accountId = id) }
        reload()
    }

    private fun currentPeriod(s: UiState): Period {
        val today = LocalDate.now(zone)
        return if (s.preset == PeriodPreset.CUSTOM && s.customFirst != null && s.customLast != null) {
            Periods.of(s.customFirst, s.customLast, zone)
        } else Periods.preset(s.preset, today, zone)
    }

    fun reload() {
        val viewer = container.viewer()
        if (viewer == null) {
            _state.update { it.copy(loading = false, data = null) }
            return
        }
        job?.cancel()
        val s = _state.value
        // An employee can only ever see their own account, whatever was selected.
        val filter = if (viewer.isAdmin) s.accountId else viewer.accountId
        val period = currentPeriod(s)
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val result = attempt {
                val scope: List<Account> = Permissions.visibleAccounts(viewer, container.accountStore.accounts.value)
                    .filter { filter == null || it.id == filter }
                var incoming = 0
                var outgoing = 0
                var readIncoming = 0
                val failed = mutableListOf<String>()
                for (a in scope) {
                    val inc = attempt {
                        container.repository.countInPeriod(a.id, FolderRole.INBOX, period.fromMillis, period.toExclusiveMillis)
                    }
                    val out = attempt {
                        container.repository.countInPeriod(a.id, FolderRole.SENT, period.fromMillis, period.toExclusiveMillis)
                    }
                    if (inc.isSuccess && out.isSuccess) {
                        incoming += inc.getOrThrow(); outgoing += out.getOrThrow(); readIncoming++
                    } else failed += a.email
                }
                val now = System.currentTimeMillis()
                val refs = container.admin.statRefs(viewer, filter)
                val samples = Stats.responseSamples(refs, period.fromMillis, period.toExclusiveMillis)
                DashboardData(
                    period = period,
                    incoming = if (readIncoming > 0) incoming else null,
                    outgoing = if (readIncoming > 0) outgoing else null,
                    failedAccounts = failed,
                    transactions = Stats.transactionCounts(container.admin.openItems(viewer, filter), now),
                    responses = Stats.summarize(samples),
                    computedAt = now,
                )
            }
            result
                .onSuccess { d -> _state.update { it.copy(loading = false, data = d) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toNotice()) } }
        }
    }
}

private fun formatDuration(context: Context, millis: Long): String {
    val p = Durations.parts(millis)
    return when {
        p.days > 0 -> context.getString(R.string.dash_duration_dhm, p.days, p.hours, p.minutes)
        p.hours > 0 -> context.getString(R.string.dash_duration_hm, p.hours, p.minutes)
        else -> context.getString(R.string.dash_duration_m, p.minutes)
    }
}

private fun PeriodPreset.labelRes(): Int = when (this) {
    PeriodPreset.TODAY -> R.string.dash_period_today
    PeriodPreset.LAST_7_DAYS -> R.string.dash_period_7
    PeriodPreset.LAST_30_DAYS -> R.string.dash_period_30
    PeriodPreset.THIS_MONTH -> R.string.dash_period_this_month
    PeriodPreset.LAST_MONTH -> R.string.dash_period_last_month
    PeriodPreset.CUSTOM -> R.string.dash_period_custom
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onBack: () -> Unit) {
    val vm = containerViewModel { DashboardViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val container = com.shegrowsdev.mail.ui.common.rememberContainer()
    val accounts by container.accountStore.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val viewer = container.viewer()
    var accountMenu by remember { mutableStateOf(false) }
    var pickingRange by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dash_title)) },
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
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (viewer == null) {
                Text(stringResource(R.string.dash_no_account))
                return@Column
            }
            Text(
                stringResource(if (viewer.isAdmin) R.string.dash_scope_admin else R.string.dash_scope_employee),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PeriodPreset.entries.forEach { p ->
                    FilterChip(
                        selected = state.preset == p,
                        onClick = { if (p == PeriodPreset.CUSTOM) pickingRange = true else vm.setPreset(p) },
                        label = { Text(stringResource(p.labelRes())) }
                    )
                }
            }

            if (viewer.isAdmin && accounts.size > 1) {
                Box {
                    OutlinedButton(onClick = { accountMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            accounts.firstOrNull { it.id == state.accountId }?.email?.ltr()
                                ?: stringResource(R.string.dash_all_accounts),
                            maxLines = 1
                        )
                    }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dash_all_accounts)) },
                            onClick = { accountMenu = false; vm.setAccount(null) }
                        )
                        accounts.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.email.ltr()) },
                                onClick = { accountMenu = false; vm.setAccount(a.id) }
                            )
                        }
                    }
                }
            }

            val data = state.data
            if (data != null) {
                val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                Text(
                    stringResource(
                        R.string.dash_period_range,
                        data.period.firstDay.format(fmt).ltr(),
                        data.period.lastDay.format(fmt).ltr()
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            when {
                state.loading && data == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> {
                    Text(
                        stringResource(state.error!!.textRes) + (state.error!!.detail?.let { "\n$it" } ?: ""),
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = vm::reload) { Text(stringResource(R.string.action_retry)) }
                }
                data != null -> {
                    if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    if (data.failedAccounts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.dash_partial, data.failedAccounts.joinToString(", ") { it.ltr() }),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val na = stringResource(R.string.dash_unavailable)
                    StatCard(
                        stringResource(R.string.dash_incoming),
                        data.incoming?.toString() ?: na,
                        stringResource(R.string.dash_incoming_note)
                    )
                    StatCard(
                        stringResource(R.string.dash_outgoing),
                        data.outgoing?.toString() ?: na,
                        stringResource(R.string.dash_outgoing_note)
                    )
                    StatCard(
                        stringResource(R.string.dash_open_tx),
                        data.transactions.open.toString(),
                        stringResource(R.string.dash_tx_note)
                    )
                    StatCard(
                        stringResource(R.string.dash_overdue_tx),
                        data.transactions.overdue.toString(),
                        stringResource(R.string.dash_overdue_note)
                    )
                    ResponseCard(context, data.responses)
                    Text(
                        stringResource(R.string.dash_definitions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (pickingRange) {
        val rangeState = rememberDateRangePickerState()
        AlertDialog(
            onDismissRequest = { pickingRange = false },
            confirmButton = {
                TextButton(
                    enabled = rangeState.selectedStartDateMillis != null && rangeState.selectedEndDateMillis != null,
                    onClick = {
                        val a = rangeState.selectedStartDateMillis
                        val b = rangeState.selectedEndDateMillis
                        if (a != null && b != null) {
                            vm.setCustom(
                                Instant.ofEpochMilli(a).atZone(ZoneOffset.UTC).toLocalDate(),
                                Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate()
                            )
                        }
                        pickingRange = false
                    }
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingRange = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { DateRangePicker(state = rangeState, modifier = Modifier.height(480.dp)) }
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, note: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResponseCard(context: Context, r: ResponseSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.dash_response), style = MaterialTheme.typography.labelLarge)
            ResponseLine(context, stringResource(R.string.dash_response_all), r.overall, big = true)
            ResponseLine(context, stringResource(R.string.dash_response_external), r.external)
            ResponseLine(context, stringResource(R.string.dash_response_internal), r.internal)
            Text(
                stringResource(R.string.dash_response_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResponseLine(context: Context, label: String, g: ResponseGroup, big: Boolean = false) {
    val avg = g.averageMillis
    val value = if (avg == null) stringResource(R.string.dash_no_replies) else formatDuration(context, avg)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label + " (" + g.count + ")")
        Text(
            value,
            style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            color = if (big) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
