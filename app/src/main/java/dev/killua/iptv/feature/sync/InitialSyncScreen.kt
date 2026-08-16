package dev.killua.iptv.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun InitialSyncRoute(
    viewModel: InitialSyncViewModel,
    onFinished: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onFinished()
    }
    InitialSyncScreen(
        state = state,
        onRetry = viewModel::start,
        onSkip = viewModel::skip,
    )
}

@Composable
fun InitialSyncScreen(
    state: InitialSyncUiState,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    // A Surface rather than a plain Box, so text inherits a readable content colour. A Box with
    // only a background modifier leaves LocalContentColor at its default and renders the
    // headline black on black.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Preparing your library",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This runs once. A large provider can take a few minutes — " +
                    "afterwards everything is browsed from this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            SyncStepRow(
                label = "Live channels",
                step = SyncStep.Channels,
                state = state,
                icon = { Icon(Icons.Outlined.LiveTv, null, Modifier.size(20.dp)) },
            )
            Spacer(Modifier.height(14.dp))
            SyncStepRow(
                label = "Movies",
                step = SyncStep.Movies,
                state = state,
                icon = { Icon(Icons.Outlined.Movie, null, Modifier.size(20.dp)) },
            )
            Spacer(Modifier.height(14.dp))
            SyncStepRow(
                label = "Series",
                step = SyncStep.Series,
                state = state,
                icon = { Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(20.dp)) },
            )

            if (state.isRunning) {
                Spacer(Modifier.height(24.dp))
                // The provider never reports a total, so the bar cannot be a percentage.
                // The counts above are the honest progress signal.
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }

            state.errorMessage?.let { error ->
                Spacer(Modifier.height(24.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(error, Modifier.padding(14.dp), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onRetry) { Text("Try again") }
                    TextButton(onClick = onSkip) {
                        Text(
                            if (state.canContinueWithCache) {
                                "Use cached library"
                            } else {
                                "Continue anyway"
                            },
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * One library in the sync list.
 *
 * Active and done are derived from the step order rather than passed in per row, so adding a
 * library cannot leave an earlier row stuck without its tick.
 */
@Composable
private fun SyncStepRow(
    label: String,
    step: SyncStep,
    state: InitialSyncUiState,
    icon: @Composable () -> Unit,
) {
    val count = state.countOf(step)
    val isActive = state.isActive(step)
    val isDone = state.isDone(step)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                val detail = when {
                    state.wasAlreadyCached(step) -> "Already on this device"
                    count > 0 -> "%,d loaded".format(count)
                    else -> null
                }
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                isDone -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                isActive -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}
