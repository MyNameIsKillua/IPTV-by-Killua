package dev.killua.iptv.ui.update

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.killua.iptv.AppContainer
import dev.killua.iptv.core.update.UpdateInstaller
import dev.killua.iptv.domain.update.UpdateStatus
import kotlinx.coroutines.launch

/**
 * Tells the viewer, once, that a newer release exists - and says why it knows.
 *
 * A dialog rather than a bar along the top, which is what the owner asked for and is also the
 * right call on a television: a strip has no focus of its own and a D-pad has no way to reach it,
 * while a dialog takes focus and its buttons are reachable with the ring.
 *
 * It appears at most once a day, only when something newer actually exists, and never blocks
 * anything - *Not yet* dismisses it and the app carries on. The paragraph at the bottom is not
 * decoration: this is the one moment the app can explain a network request the viewer did not ask
 * for, in the place where they are already reading.
 */
@Composable
fun UpdatePrompt(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Read rather than owned: the launch check and *Check now* in settings both write here, and
    // this is the one thing that draws the answer.
    val status by container.updateStatus.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var problem by remember { mutableStateOf<String?>(null) }
    var permissionIntent by remember { mutableStateOf<Intent?>(null) }

    // Once per composition of the authenticated app, and the checker itself refuses to ask more
    // often than daily - so rotating the device or coming back from the player asks nothing.
    LaunchedEffect(Unit) { container.checkForUpdate() }

    val available = status as? UpdateStatus.Available ?: return

    // Dismissing clears the shared answer rather than setting a flag here. A flag would survive
    // *Check now*, so asking again would find the update and show nothing.
    fun dismiss() {
        container.updateStatus.value = UpdateStatus.Unknown
    }

    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("Update available") },
        text = {
            Column {
                Text(
                    "${available.installed} → ${available.release.version}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer()
                if (busy) {
                    Text("Downloading…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                problem?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer()
                }
                if (permissionIntent != null) {
                    Text(
                        "Android needs your permission for this app to install an update. That " +
                            "is granted once, in system settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer()
                }
                // The honest paragraph. It says what was sent, what was not, and where the switch
                // is - because an app that contacts a server on its own owes the viewer that much,
                // and this is the only moment they are looking.
                Text(
                    "Killua IPTV asks GitHub once a day whether a newer version exists. The " +
                        "request sends nothing about you, your account, or what you watch — but " +
                        "it does show GitHub your IP address, like opening any web page would. " +
                        "You can turn this off under Settings → Updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            val intent = permissionIntent
            if (intent != null) {
                TextButton(onClick = { openSettings(context, intent) }) { Text("Open settings") }
            } else {
                TextButton(
                    enabled = !busy && available.release.androidPackage != null,
                    onClick = {
                        val asset = available.release.androidPackage ?: return@TextButton
                        busy = true
                        problem = null
                        scope.launch {
                            when (val result = container.updateInstaller.install(asset) { progress = it }) {
                                is UpdateInstaller.Result.Handed -> busy = false
                                is UpdateInstaller.Result.PermissionNeeded -> {
                                    busy = false
                                    permissionIntent = result.intent
                                }
                                is UpdateInstaller.Result.Failed -> {
                                    busy = false
                                    problem = result.reason
                                }
                            }
                        }
                    },
                ) { Text("Download and install") }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { dismiss() }) { Text("Not yet") }
        },
    )
}

@Composable
private fun Spacer() {
    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
}

/** Swallowed because a device without that settings screen is not worth crashing over. */
private fun openSettings(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
}
