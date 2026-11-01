package com.shegrowsdev.mail.ui.admin

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.admin.MailRef
import com.shegrowsdev.mail.admin.TaskStatus
import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.ui.common.formatFullDateTime
import com.shegrowsdev.mail.ui.common.ltr
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Tomorrow at 09:00 in the device time zone: the default for a new due time. */
fun defaultDueMillis(): Long =
    LocalDate.now().plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * Finds the message behind a [MailRef] (it may have been moved), remembers its new place and makes its
 * account the active one, so the message screen opens it. Throws MailFailure when it cannot be found.
 */
suspend fun resolveAndActivate(container: AppContainer, ref: MailRef): Pair<FolderRole, Long> {
    val (role, uid) = container.repository.locate(ref.accountId, ref.messageId, ref.folderRole, ref.uid)
    if (role != ref.folderRole || uid != ref.uid) container.admin.updateLocation(ref.id, role, uid)
    container.accountStore.setActive(ref.accountId)
    return role to uid
}

@StringRes
fun TaskStatus.labelRes(): Int = when (this) {
    TaskStatus.OPEN -> R.string.task_status_open
    TaskStatus.IN_PROGRESS -> R.string.task_status_in_progress
    TaskStatus.DONE -> R.string.task_status_done
}

/** Date picker followed by a time picker. [onPicked] receives epoch milliseconds in the device time zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickDateTimeDialog(initialMillis: Long, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember(initialMillis) { Instant.ofEpochMilli(initialMillis).atZone(zone) }
    var step by remember { mutableStateOf(0) }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(context)
    )
    if (step == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { step = 1 }, enabled = dateState.selectedDateMillis != null) {
                    Text(stringResource(R.string.action_next))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
        ) { DatePicker(state = dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val dayUtc = dateState.selectedDateMillis
                    if (dayUtc != null) {
                        val day = Instant.ofEpochMilli(dayUtc).atZone(ZoneOffset.UTC).toLocalDate()
                        onPicked(day.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant().toEpochMilli())
                    }
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.action_back)) } },
            text = { Box(Modifier.fillMaxWidth()) { TimePicker(state = timeState) } }
        )
    }
}

/** Create-task dialog: title, responsible employee (an account on this device) and an optional due time. */
@Composable
fun TaskDialog(
    accounts: List<Account>,
    defaultTitle: String,
    defaultAssignee: String,
    onConfirm: (title: String, assigneeEmail: String, dueAt: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(defaultTitle) }
    var assignee by remember { mutableStateOf(defaultAssignee) }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_create_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.task_title)) },
                    textStyle = TextStyle(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.task_assignee), style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(assignee.ltr(), maxLines = 1)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        com.shegrowsdev.mail.data.CompanyDirectory.withAccounts(accounts).forEach { email ->
                            DropdownMenuItem(
                                text = { Text(email.ltr()) },
                                onClick = { assignee = email; menu = false }
                            )
                        }
                    }
                }
                Text(stringResource(R.string.task_due), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = true }) {
                        Text(dueAt?.let { formatFullDateTime(context, it).ltr() } ?: stringResource(R.string.task_due_none))
                    }
                    if (dueAt != null) {
                        TextButton(onClick = { dueAt = null }) { Text(stringResource(R.string.action_clear)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.trim(), assignee, dueAt) },
                enabled = title.isNotBlank() && assignee.isNotBlank()
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
    if (picking) {
        PickDateTimeDialog(
            initialMillis = dueAt ?: defaultDueMillis(),
            onPicked = { dueAt = it; picking = false },
            onDismiss = { picking = false }
        )
    }
}

/** Create-reminder dialog: a due time (required) and an optional note. */
@Composable
fun FollowUpDialog(
    onConfirm: (dueAt: Long, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var dueAt by remember { mutableStateOf(defaultDueMillis()) }
    var note by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.followup_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.followup_due), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(formatFullDateTime(context, dueAt).ltr())
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.followup_note)) },
                    textStyle = TextStyle(textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dueAt, note.trim()) }) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
    if (picking) {
        PickDateTimeDialog(
            initialMillis = dueAt,
            onPicked = { dueAt = it; picking = false },
            onDismiss = { picking = false }
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
