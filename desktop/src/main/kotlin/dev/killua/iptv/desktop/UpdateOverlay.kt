package dev.killua.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.killua.iptv.domain.update.UpdateStatus

/**
 * Says that a newer release exists, and installs it without sending anyone to a browser.
 *
 * Over the app rather than beside it, because it appears once a day at most and is gone the moment
 * it is answered. The scrim swallows clicks so nothing behind it is hit by accident, and *Not yet*
 * is a real answer rather than a delay - the next launch inside twenty-four hours asks nothing.
 *
 * The paragraph at the bottom is the point of the whole thing. This is the only request this client
 * makes to anyone but the viewer's own provider, and this is the one moment the viewer is looking
 * at a screen where saying so costs nothing.
 */
@Composable
fun UpdateOverlay(
    available: UpdateStatus.Available,
    busy: Boolean,
    progress: Float,
    problem: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Catches the click rather than passing it through, which is the whole job of a scrim.
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(enabled = false, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NightRaised)
                .border(1.dp, Violet.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
                .padding(28.dp),
        ) {
            Text(
                "UPDATE AVAILABLE",
                color = VioletBright,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "${available.installed} → ${available.release.version}",
                color = Ink,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))

            if (busy) {
                Text(
                    "Downloading…",
                    color = InkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = VioletBright,
                    trackColor = Violet.copy(alpha = 0.25f),
                )
                Spacer(Modifier.height(16.dp))
            }

            problem?.let {
                Text(it, color = Ink, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
            }

            // Said before it happens, because two of the three are surprising: the client closes
            // itself, and Windows asks for administrator rights. Someone who is not expecting
            // either reads them as something having gone wrong.
            Text(
                "Killua IPTV downloads the installer, closes itself so the files can be replaced, " +
                    "and lets Windows do the rest. Windows will ask for administrator rights. " +
                    "Nothing has to be uninstalled first, and your library, marks and watch " +
                    "progress are untouched.",
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!busy) {
                    ActionButton("Download and install", onClick = onInstall)
                    Spacer(Modifier.width(10.dp))
                    ActionButton("Not yet", onClick = onDismiss)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Killua IPTV asks GitHub once a day whether a newer version exists. The request " +
                    "sends nothing about you, your account, or what you watch — but it does show " +
                    "GitHub your IP address, as opening any web page would. You can turn this off " +
                    "under Settings → Updates.",
                color = InkMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** The same button the settings screen uses; duplicated here only because that one is private. */
@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Violet.copy(alpha = 0.2f))
            .focusRing(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, color = VioletBright, style = MaterialTheme.typography.labelLarge)
    }
}
