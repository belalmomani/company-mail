package com.shegrowsdev.mail.ui.colleagues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.data.CompanyDirectory
import com.shegrowsdev.mail.ui.common.ltr
import com.shegrowsdev.mail.ui.common.rememberContainer

/** Company addresses visible to everyone. Listing an address gives no access to that person's mailbox. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColleaguesScreen(onBack: () -> Unit, onWrite: () -> Unit) {
    val container = rememberContainer()
    val accounts by container.accountStore.accounts.collectAsStateWithLifecycle()
    val activeId by container.accountStore.activeId.collectAsStateWithLifecycle()
    val me = (accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull())?.email?.lowercase()
    val onDevice = accounts.map { it.email.lowercase() }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.colleagues_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.colleagues_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompanyDirectory.withAccounts(accounts).forEach { email ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(CompanyDirectory.nameOf(email), style = MaterialTheme.typography.titleSmall)
                            Text(email.ltr(), style = MaterialTheme.typography.bodySmall)
                            if (email.lowercase() == me) {
                                Text(
                                    stringResource(R.string.colleagues_you),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (email.lowercase() !in onDevice) {
                                Text(
                                    stringResource(R.string.colleagues_private),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (email.lowercase() != me) {
                            TextButton(onClick = {
                                container.setComposeRecipient(email)
                                onWrite()
                            }) { Text(stringResource(R.string.colleagues_write)) }
                        }
                    }
                }
            }
        }
    }
}
