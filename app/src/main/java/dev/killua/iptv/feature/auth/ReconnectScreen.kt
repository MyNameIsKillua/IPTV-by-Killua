package dev.killua.iptv.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ReconnectRoute(viewModel: ReconnectViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session = viewModel.session
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Icon(Icons.Default.LockReset, null, Modifier.padding(18.dp))
        }
        Text(
            "Reconnect your account",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            session.reason.userMessage(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        session.account?.let { account ->
            Text(
                "${account.username} · ${account.serverUrl}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        state.errorMessage?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::logout, modifier = Modifier.weight(1f)) {
                Text("Use another account")
            }
            Button(
                onClick = viewModel::reconnect,
                enabled = !state.isReconnecting,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isReconnecting) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("Reconnect")
                }
            }
        }
    }
}
