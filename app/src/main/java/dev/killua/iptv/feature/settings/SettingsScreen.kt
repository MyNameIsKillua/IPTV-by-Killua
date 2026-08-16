package dev.killua.iptv.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.killua.iptv.core.preferences.PlaybackGestureOptions
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.ThemeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsRoute(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val pip by viewModel.pictureInPictureEnabled.collectAsStateWithLifecycle()
    val autoPlayNext by viewModel.autoPlayNextEpisode.collectAsStateWithLifecycle()
    val doubleTapSeekSeconds by viewModel.doubleTapSeekSeconds.collectAsStateWithLifecycle()
    val holdPlaybackSpeed by viewModel.holdPlaybackSpeed.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        themeMode = theme,
        pictureInPictureEnabled = pip,
        autoPlayNextEpisode = autoPlayNext,
        doubleTapSeekSeconds = doubleTapSeekSeconds,
        holdPlaybackSpeed = holdPlaybackSpeed,
        onThemeChange = viewModel::setTheme,
        onPipChange = viewModel::setPictureInPicture,
        onAutoPlayNextChange = viewModel::setAutoPlayNextEpisode,
        onDoubleTapSeekChange = viewModel::setDoubleTapSeekSeconds,
        onHoldPlaybackSpeedChange = viewModel::setHoldPlaybackSpeed,
        onClearArtworkCache = viewModel::clearArtworkCache,
        onRefresh = viewModel::refreshLibrary,
        onReconnect = viewModel::reconnect,
        onRename = viewModel::rename,
        onLogout = viewModel::logout,
    )
}

/**
 * Replaces the whole screen while signing out. Clearing a large cached library takes seconds, and
 * leaving the settings list visible and scrollable made the action look like it had done nothing.
 */
@Composable
private fun LogoutOverlay() {
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
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Signing out", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Removing credentials and the cached library from this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    themeMode: ThemeMode,
    pictureInPictureEnabled: Boolean,
    autoPlayNextEpisode: Boolean,
    doubleTapSeekSeconds: Int,
    holdPlaybackSpeed: Float,
    onThemeChange: (ThemeMode) -> Unit,
    onPipChange: (Boolean) -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit,
    onDoubleTapSeekChange: (Int) -> Unit,
    onHoldPlaybackSpeedChange: (Float) -> Unit,
    onClearArtworkCache: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onRename: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var showSeekOptions by remember { mutableStateOf(false) }
    var showSpeedOptions by remember { mutableStateOf(false) }

    if (showSeekOptions) {
        ChoiceDialog(
            title = "Double-tap skip",
            options = PlaybackGestureOptions.seekSeconds,
            selected = doubleTapSeekSeconds,
            label = ::formatSeekInterval,
            onSelect = {
                onDoubleTapSeekChange(it)
                showSeekOptions = false
            },
            onDismiss = { showSeekOptions = false },
        )
    }
    if (showSpeedOptions) {
        ChoiceDialog(
            title = "Press-and-hold speed",
            options = PlaybackGestureOptions.holdSpeeds,
            selected = holdPlaybackSpeed,
            label = ::formatPlaybackSpeed,
            onSelect = {
                onHoldPlaybackSpeedChange(it)
                showSpeedOptions = false
            },
            onDismiss = { showSpeedOptions = false },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("Encrypted credentials and this account's cached live library will be removed from the device.") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }

    if (state.isLoggingOut) {
        LogoutOverlay()
        return
    }

    if (renaming) {
        var draft by remember { mutableStateOf(state.account.displayName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Playlist name") },
            text = {
                Column {
                    Text(
                        "Shown on the home screen. Leave it empty to go back to your provider " +
                            "user name.",
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        singleLine = true,
                        label = { Text("Name") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renaming = false
                        onRename(draft)
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionTitle("Account") }
            item { AccountCard(state.account) }
            item {
                ListItem(
                    headlineContent = { Text("Playlist name") },
                    supportingContent = {
                        Text(
                            state.account.displayName?.takeIf { it.isNotBlank() }
                                ?: "Not set — your provider user name is shown instead",
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Label, null) },
                    modifier = Modifier.clickable { renaming = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Reconnect account") },
                    supportingContent = { Text("Validate the saved credentials with your provider") },
                    leadingContent = { Icon(Icons.Default.CloudSync, null) },
                    trailingContent = {
                        if (state.isReconnecting) CircularProgressIndicator(Modifier.padding(8.dp))
                    },
                    modifier = Modifier.clickable(enabled = !state.isReconnecting, onClick = onReconnect),
                )
            }
            item { SectionTitle("Library") }
            item {
                ListItem(
                    headlineContent = { Text("Refresh IPTV library") },
                    supportingContent = { Text("Update live categories and channels without deleting recents") },
                    leadingContent = { Icon(Icons.Default.Refresh, null) },
                    trailingContent = {
                        if (state.isRefreshing) CircularProgressIndicator(Modifier.padding(8.dp))
                    },
                    modifier = Modifier.clickable(enabled = !state.isRefreshing, onClick = onRefresh),
                )
            }
            item { SectionTitle("Playback") }
            item {
                ListItem(
                    headlineContent = { Text("Picture-in-Picture") },
                    supportingContent = { Text("Continue video in a floating window when you leave the app") },
                    leadingContent = { Icon(Icons.Outlined.PictureInPicture, null) },
                    trailingContent = {
                        Switch(checked = pictureInPictureEnabled, onCheckedChange = onPipChange)
                    },
                    modifier = Modifier.clickable { onPipChange(!pictureInPictureEnabled) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Autoplay next episode") },
                    supportingContent = {
                        Text("Start the next episode of a series when one finishes")
                    },
                    leadingContent = { Icon(Icons.Outlined.SkipNext, null) },
                    trailingContent = {
                        Switch(checked = autoPlayNextEpisode, onCheckedChange = onAutoPlayNextChange)
                    },
                    modifier = Modifier.clickable { onAutoPlayNextChange(!autoPlayNextEpisode) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Double-tap skip") },
                    supportingContent = {
                        Text("Skip ${formatSeekInterval(doubleTapSeekSeconds)} backward or forward on seekable video")
                    },
                    leadingContent = { Icon(Icons.Default.FastForward, null) },
                    modifier = Modifier.clickable { showSeekOptions = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Press-and-hold speed") },
                    supportingContent = {
                        Text("Temporarily play at ${formatPlaybackSpeed(holdPlaybackSpeed)} while holding the video")
                    },
                    leadingContent = { Icon(Icons.Default.Speed, null) },
                    modifier = Modifier.clickable { showSpeedOptions = true },
                )
            }
            item { SectionTitle("Storage") }
            item {
                ListItem(
                    headlineContent = { Text("Clear channel artwork") },
                    supportingContent = {
                        Text("Remove cached channel logos. The disk cache is limited to 128 MB.")
                    },
                    leadingContent = { Icon(Icons.Default.DeleteSweep, null) },
                    trailingContent = {
                        if (state.isClearingArtworkCache) CircularProgressIndicator(Modifier.padding(8.dp))
                    },
                    modifier = Modifier.clickable(
                        enabled = !state.isClearingArtworkCache,
                        onClick = onClearArtworkCache,
                    ),
                )
            }
            item { SectionTitle("Appearance") }
            items(ThemeMode.entries.size) { index ->
                val mode = ThemeMode.entries[index]
                ListItem(
                    headlineContent = { Text(mode.name) },
                    leadingContent = { Icon(Icons.Outlined.Palette, null) },
                    trailingContent = { RadioButton(selected = themeMode == mode, onClick = null) },
                    modifier = Modifier.selectable(
                        selected = themeMode == mode,
                        onClick = { onThemeChange(mode) },
                    ),
                )
            }
            state.message?.let { item { MessageCard(it, MaterialTheme.colorScheme.primary) } }
            state.errorMessage?.let { item { MessageCard(it, MaterialTheme.colorScheme.error) } }
            item { SectionTitle("Privacy") }
            item {
                ListItem(
                    headlineContent = { Text("Private by design") },
                    supportingContent = { Text("No analytics, ads, telemetry, or third-party account service") },
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                )
            }
            item {
                Button(
                    onClick = { confirmLogout = true },
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                ) {
                    Icon(Icons.Default.Logout, null)
                    Text("  Log out")
                }
            }
            item {
                Text(
                    text = "Developed by MyNameIsKillua",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                onClick = { onSelect(option) },
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatSeekInterval(seconds: Int): String =
    if (seconds == 60) "1 minute" else "$seconds seconds"

private fun formatPlaybackSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

@Composable
private fun AccountCard(account: Account) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountCircle, null)
                Text("  ${account.username}", style = MaterialTheme.typography.titleLarge)
            }
            Text(account.serverUrl, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Text(
                listOfNotNull(
                    account.status.name,
                    account.activeConnections?.let { active ->
                        account.maximumConnections?.let { max -> "$active / $max connections" } ?: "$active active"
                    },
                    account.expiresAtEpochSeconds?.let { "Expires ${formatEpochSeconds(it)}" },
                ).joinToString(" · "),
                modifier = Modifier.padding(top = 8.dp),
            )
            account.lastLiveSyncAtEpochMillis?.let {
                Text(
                    "Library updated ${formatEpochMillis(it)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
private fun MessageCard(message: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = MaterialTheme.shapes.medium,
    ) { Text(message, modifier = Modifier.padding(14.dp)) }
}

private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm")

private fun formatEpochSeconds(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(dateFormatter)

private fun formatEpochMillis(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)
