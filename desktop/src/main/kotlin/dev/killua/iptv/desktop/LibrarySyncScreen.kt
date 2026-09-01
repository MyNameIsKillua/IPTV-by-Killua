package dev.killua.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The wait between signing in and having a library, said out loud.
 *
 * The phone has had one of these since its fourth alpha, for a reason that applies twice over here:
 * a provider with six figures of titles takes minutes, and a client that spends those minutes on an
 * empty screen has not told the viewer whether it is working, broken, or finished. Counts that climb
 * are the cheapest possible proof that something is happening.
 *
 * **Skipping is always offered**, and it is not a courtesy. Everything this screen fetches is an
 * improvement on browsing one category at a time, not a precondition for it — so someone who only
 * wants to watch the news should never be held here, and a provider that refuses one listing must
 * not be able to hold anyone at all.
 */
@Composable
fun LibrarySyncScreen(
    state: LibrarySyncState,
    accountLabel: String,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Night), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(NightRaised)
                .border(1.dp, Violet.copy(alpha = 0.2f), RoundedCornerShape(22.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlowText("Killua IPTV", style = MaterialTheme.typography.headlineSmall, glowRadius = 26f)
            Spacer(Modifier.height(8.dp))
            Text(
                "Reading the library for $accountLabel",
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))

            state.expected.forEach { kind ->
                SyncRow(kind, state)
                Spacer(Modifier.height(10.dp))
            }

            if (state.paused) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Paused while you are watching. Most accounts allow only one or two " +
                        "connections at a time, and the picture comes first.",
                    color = InkMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            state.failed.values.firstOrNull()?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "You can still browse those by category, the way this client always could.",
                    color = InkMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.finished && state.hasFailure) {
                    SyncButton("Try again", prominent = true, onClick = onRetry)
                }
                SyncButton(
                    label = if (state.finished) "Continue" else "Skip and browse now",
                    prominent = state.finished && !state.hasFailure,
                    onClick = onContinue,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Nothing here is written to disk. The library is held for as long as this window " +
                    "is open and read again next time.",
                color = InkMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SyncRow(kind: LibraryKind, state: LibrarySyncState) {
    val failure = state.failed[kind]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                failure != null -> Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )

                state.isDone(kind) -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = VioletBright,
                    modifier = Modifier.size(18.dp),
                )

                state.isActive(kind) -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = VioletBright,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            kind.label,
            color = if (state.isActive(kind)) Ink else InkMuted,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (state.isActive(kind)) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        Text(
            when {
                failure != null -> "not read"
                state.countOf(kind) > 0 -> "%,d".format(state.countOf(kind))
                state.isActive(kind) -> "…"
                else -> ""
            },
            color = InkMuted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SyncButton(label: String, prominent: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (prominent) VioletBright else Violet.copy(alpha = 0.2f))
            .focusRing(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(
            label,
            color = if (prominent) Night else VioletBright,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
