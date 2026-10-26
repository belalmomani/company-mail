package com.shegrowsdev.mail.ui.accounts

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.data.ApprovedPreset
import com.shegrowsdev.mail.ui.common.Notice
import com.shegrowsdev.mail.ui.common.attempt
import com.shegrowsdev.mail.ui.common.containerViewModel
import com.shegrowsdev.mail.ui.common.toNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class AccountSetupViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val displayName: String = "",
        val email: String = "",
        val password: String = "",
        val imapHost: String = "",
        val imapPort: String = "993",
        val smtpHost: String = "",
        val smtpPort: String = "465",
        val testing: Boolean = false,
        val notice: Notice? = null,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun edit(transform: (UiState) -> UiState) {
        _state.update { transform(it).copy(notice = null) }
    }

    /** Fills the settings given in the project specification for the approved test account only. */
    fun applyApprovedPreset() {
        _state.update {
            it.copy(
                email = ApprovedPreset.EMAIL,
                imapHost = ApprovedPreset.IMAP_HOST,
                imapPort = ApprovedPreset.IMAP_PORT.toString(),
                smtpHost = ApprovedPreset.SMTP_HOST,
                smtpPort = ApprovedPreset.SMTP_PORT.toString(),
                notice = null,
            )
        }
    }

    fun submit() {
        val s = _state.value
        if (s.testing) return

        val email = s.email.trim()
        val imapPort = s.imapPort.trim().toIntOrNull()
        val smtpPort = s.smtpPort.trim().toIntOrNull()

        val problem: Int? = when {
            email.isBlank() || s.password.isEmpty() ||
                s.imapHost.isBlank() || s.smtpHost.isBlank() -> R.string.setup_missing_fields
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> R.string.setup_invalid_email
            imapPort == null || imapPort !in 1..65535 ||
                smtpPort == null || smtpPort !in 1..65535 -> R.string.setup_invalid_port
            container.accountStore.emailExists(email) -> R.string.setup_duplicate
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(notice = Notice(problem)) }
            return
        }

        val account = Account(
            id = UUID.randomUUID().toString(),
            displayName = s.displayName.trim(),
            email = email,
            imapHost = s.imapHost.trim(),
            imapPort = imapPort!!,
            smtpHost = s.smtpHost.trim(),
            smtpPort = smtpPort!!,
        )
        val password = s.password

        _state.update { it.copy(testing = true, notice = null) }
        viewModelScope.launch {
            // A real login to both servers must succeed before the account is saved.
            val result = attempt {
                container.repository.testConnection(account, password)
                container.accountStore.add(account, password)
            }
            result
                .onSuccess { _state.update { it.copy(testing = false, password = "", done = true) } }
                .onFailure { e -> _state.update { it.copy(testing = false, notice = e.toNotice()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSetupScreen(
    canGoBack: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val vm = containerViewModel { AccountSetupViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) { if (state.done) onDone() }

    val ltrField = TextStyle(textDirection = TextDirection.Ltr)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = vm::applyApprovedPreset,
                enabled = !state.testing,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.setup_use_preset)) }

            OutlinedTextField(
                value = state.displayName,
                onValueChange = { v -> vm.edit { it.copy(displayName = v) } },
                label = { Text(stringResource(R.string.setup_display_name)) },
                singleLine = true,
                enabled = !state.testing,
                textStyle = TextStyle(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.email,
                onValueChange = { v -> vm.edit { it.copy(email = v) } },
                label = { Text(stringResource(R.string.setup_email)) },
                singleLine = true,
                enabled = !state.testing,
                textStyle = ltrField,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = { v -> vm.edit { it.copy(password = v) } },
                label = { Text(stringResource(R.string.setup_password)) },
                singleLine = true,
                enabled = !state.testing,
                textStyle = ltrField,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            Text(stringResource(R.string.setup_incoming), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.imapHost,
                    onValueChange = { v -> vm.edit { it.copy(imapHost = v) } },
                    label = { Text(stringResource(R.string.setup_imap_host)) },
                    singleLine = true,
                    enabled = !state.testing,
                    textStyle = ltrField,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = state.imapPort,
                    onValueChange = { v -> vm.edit { it.copy(imapPort = v.filter(Char::isDigit)) } },
                    label = { Text(stringResource(R.string.setup_port)) },
                    singleLine = true,
                    enabled = !state.testing,
                    textStyle = ltrField,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Text(stringResource(R.string.setup_outgoing), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.smtpHost,
                    onValueChange = { v -> vm.edit { it.copy(smtpHost = v) } },
                    label = { Text(stringResource(R.string.setup_smtp_host)) },
                    singleLine = true,
                    enabled = !state.testing,
                    textStyle = ltrField,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = state.smtpPort,
                    onValueChange = { v -> vm.edit { it.copy(smtpPort = v.filter(Char::isDigit)) } },
                    label = { Text(stringResource(R.string.setup_port)) },
                    singleLine = true,
                    enabled = !state.testing,
                    textStyle = ltrField,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                stringResource(R.string.setup_security_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.notice?.let { n ->
                Text(
                    text = stringResource(n.textRes) + (n.detail?.let { "\n$it" } ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = vm::submit,
                enabled = !state.testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(0.dp))
                    Text(
                        stringResource(R.string.setup_testing),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Text(stringResource(R.string.setup_test_and_save))
                }
            }
        }
    }
}
