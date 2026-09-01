package dev.killua.iptv.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Translate
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.killua.iptv.core.preferences.PlaybackGestureOptions
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.SubtitleBackground
import dev.killua.iptv.domain.model.SubtitleStyle
import dev.killua.iptv.domain.model.SubtitleTextSize
import dev.killua.iptv.domain.model.ThemeMode
import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.model.languageDisplayName
import dev.killua.iptv.domain.support.CryptoAddress
import dev.killua.iptv.domain.support.Donations
import dev.killua.iptv.domain.userdata.UserDataImportPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsRoute(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val pip by viewModel.pictureInPictureEnabled.collectAsStateWithLifecycle()
    val autoPlayNext by viewModel.autoPlayNextEpisode.collectAsStateWithLifecycle()
    val updateCheck by viewModel.updateCheckEnabled.collectAsStateWithLifecycle()
    val doubleTapSeekSeconds by viewModel.doubleTapSeekSeconds.collectAsStateWithLifecycle()
    val holdPlaybackSpeed by viewModel.holdPlaybackSpeed.collectAsStateWithLifecycle()
    val trackLanguages by viewModel.trackLanguages.collectAsStateWithLifecycle()
    val subtitleStyle by viewModel.subtitleStyle.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The system picker creates the document, so no storage permission is involved and the viewer
    // chooses where it lands - a folder their own cloud already syncs, if they want one.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportUserData { document ->
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(document.toByteArray())
                    } ?: error("the picked document could not be opened")
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val document = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            // Bounded on purpose: the viewer can pick any document, and reading an
                            // arbitrary one into memory unbounded is how an app dies. Read in a
                            // plain loop rather than with readNBytes, which is a Java 9 API this
                            // project cannot assume on its minimum Android version.
                            val collected = ByteArrayOutputStream()
                            val chunk = ByteArray(64 * 1024)
                            var oversized = false
                            while (true) {
                                val read = stream.read(chunk)
                                if (read <= 0) break
                                if (collected.size() + read > MAX_IMPORT_BYTES) {
                                    oversized = true
                                    break
                                }
                                collected.write(chunk, 0, read)
                            }
                            if (oversized) null else collected.toString(Charsets.UTF_8.name())
                        }
                    }
                }.getOrNull()
                if (document == null) viewModel.reportImportUnreadable() else viewModel.prepareUserDataImport(document)
            }
        }
    }
    SettingsScreen(
        state = state,
        themeMode = theme,
        pictureInPictureEnabled = pip,
        autoPlayNextEpisode = autoPlayNext,
        updateCheckEnabled = updateCheck,
        doubleTapSeekSeconds = doubleTapSeekSeconds,
        holdPlaybackSpeed = holdPlaybackSpeed,
        trackLanguages = trackLanguages,
        subtitleStyle = subtitleStyle,
        onClearTrackLanguages = viewModel::clearTrackLanguages,
        onExportUserData = { exportLauncher.launch(suggestedExportFileName()) },
        onImportUserData = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        onConfirmImport = viewModel::confirmUserDataImport,
        onCancelImport = viewModel::cancelUserDataImport,
        onSubtitleTextSizeChange = viewModel::setSubtitleTextSize,
        onSubtitleBackgroundChange = viewModel::setSubtitleBackground,
        onThemeChange = viewModel::setTheme,
        onPipChange = viewModel::setPictureInPicture,
        onUpdateCheckChange = viewModel::setUpdateCheckEnabled,
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
    updateCheckEnabled: Boolean,
    autoPlayNextEpisode: Boolean,
    doubleTapSeekSeconds: Int,
    holdPlaybackSpeed: Float,
    trackLanguages: TrackLanguagePreferences,
    subtitleStyle: SubtitleStyle,
    onClearTrackLanguages: () -> Unit,
    onExportUserData: () -> Unit,
    onImportUserData: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onSubtitleTextSizeChange: (SubtitleTextSize) -> Unit,
    onSubtitleBackgroundChange: (SubtitleBackground) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onPipChange: (Boolean) -> Unit,
    onUpdateCheckChange: (Boolean) -> Unit,
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
    var showSubtitleSizeOptions by remember { mutableStateOf(false) }
    var showSubtitleBackgroundOptions by remember { mutableStateOf(false) }

    state.pendingImport?.let { plan ->
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text("Import this file?") },
            text = { Text(importSummary(plan)) },
            confirmButton = { TextButton(onClick = onConfirmImport) { Text("Import") } },
            dismissButton = { TextButton(onClick = onCancelImport) { Text("Cancel") } },
        )
    }
    if (showSubtitleSizeOptions) {
        ChoiceDialog(
            title = "Subtitle size",
            options = SubtitleTextSize.entries,
            selected = subtitleStyle.textSize,
            label = SubtitleTextSize::label,
            onSelect = {
                onSubtitleTextSizeChange(it)
                showSubtitleSizeOptions = false
            },
            onDismiss = { showSubtitleSizeOptions = false },
        )
    }
    if (showSubtitleBackgroundOptions) {
        ChoiceDialog(
            title = "Subtitle background",
            options = SubtitleBackground.entries,
            selected = subtitleStyle.background,
            label = SubtitleBackground::label,
            onSelect = {
                onSubtitleBackgroundChange(it)
                showSubtitleBackgroundOptions = false
            },
            onDismiss = { showSubtitleBackgroundOptions = false },
        )
    }
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
            item {
                ListItem(
                    headlineContent = { Text("Export your data") },
                    supportingContent = {
                        Text(
                            "Save watch progress, favourites, your list and recent channels to a " +
                                "file. No password or server address is written into it.",
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.FileDownload, null) },
                    trailingContent = {
                        if (state.isExporting) CircularProgressIndicator(Modifier.padding(8.dp))
                    },
                    modifier = Modifier.clickable(enabled = !state.isExporting, onClick = onExportUserData),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Import data") },
                    supportingContent = {
                        Text(
                            "Read a file exported from this or another device. Entries are merged, " +
                                "the newer one wins, and nothing is ever removed.",
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.FileUpload, null) },
                    trailingContent = {
                        if (state.isImporting) CircularProgressIndicator(Modifier.padding(8.dp))
                    },
                    modifier = Modifier.clickable(enabled = !state.isImporting, onClick = onImportUserData),
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
                    headlineContent = { Text("Audio and subtitle language") },
                    supportingContent = { Text(trackLanguageSummary(trackLanguages)) },
                    leadingContent = { Icon(Icons.Outlined.Translate, null) },
                    trailingContent = {
                        if (!trackLanguages.isEmpty) {
                            TextButton(onClick = onClearTrackLanguages) { Text("Clear") }
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Subtitle size") },
                    supportingContent = { Text(subtitleSizeSummary(subtitleStyle.textSize)) },
                    leadingContent = { Icon(Icons.Outlined.FormatSize, null) },
                    modifier = Modifier.clickable { showSubtitleSizeOptions = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Subtitle background") },
                    supportingContent = { Text(subtitleBackgroundSummary(subtitleStyle.background)) },
                    leadingContent = { Icon(Icons.Outlined.ClosedCaption, null) },
                    modifier = Modifier.clickable { showSubtitleBackgroundOptions = true },
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
            item { SectionTitle("Updates") }
            item {
                ListItem(
                    headlineContent = { Text("Check for updates") },
                    supportingContent = {
                        // The reason sits next to the switch, not only in a document nobody opens.
                        // This is the app's only automatic request to anyone but your provider, so
                        // what it costs should be readable at the moment you decide about it.
                        Text(
                            "Ask GitHub once a day whether a newer version exists. The request " +
                                "sends nothing about you, your account, or what you watch — but " +
                                "GitHub sees your IP address, as it would for any web page. " +
                                "Turned off, the app never contacts it.",
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.SystemUpdate, null) },
                    trailingContent = {
                        Switch(checked = updateCheckEnabled, onCheckedChange = onUpdateCheckChange)
                    },
                    modifier = Modifier.clickable { onUpdateCheckChange(!updateCheckEnabled) },
                )
            }
            item { SectionTitle("Support") }
            item { SupportSection() }
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

/**
 * Names what an import would actually change, rather than how much the file holds.
 *
 * A file can be large and change nothing, so counting its contents would overstate what is about to
 * happen. Only rows that would move are listed.
 */
private fun importSummary(plan: UserDataImportPlan.Ready): String {
    val parts = buildList {
        if (plan.progress.isNotEmpty()) add("${plan.progress.size} watch positions")
        val favourites = plan.movieFavorites.size + plan.seriesFavorites.size
        if (favourites > 0) add("$favourites favourites")
        if (plan.watchlist.isNotEmpty()) add("${plan.watchlist.size} saved items")
        if (plan.recentChannels.isNotEmpty()) add("${plan.recentChannels.size} recent channels")
    }
    return parts.joinToString(", ") +
        ". Existing entries are only replaced when the file has a newer one, and nothing is removed."
}

/** Dated, so successive exports sit beside each other rather than overwriting one another. */
private fun suggestedExportFileName(): String =
    "killua-iptv-data-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".json"

private fun formatPlaybackSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

/**
 * Describes what the player will pick on its own next time.
 *
 * Deliberately says where the choice is made, because nothing on this screen makes it: the row
 * reports and clears, and the track menu in the player is what fills it.
 */
private fun trackLanguageSummary(languages: TrackLanguagePreferences): String {
    if (languages.isEmpty) {
        return "Not set. Choose a track in the player and it is used for what you watch next."
    }
    // Read into a local first: `subtitleLanguage` now lives in :shared, and Kotlin will not smart
    // cast a public property from another module.
    val subtitleLanguage = languages.subtitleLanguage
    val parts = buildList {
        languages.audioLanguage?.let { add("Audio: ${languageDisplayName(it)}") }
        when {
            languages.subtitlesDisabled -> add("Subtitles: off")
            subtitleLanguage != null -> add("Subtitles: ${languageDisplayName(subtitleLanguage)}")
        }
    }
    return parts.joinToString(" · ") + ". Used when a stream carries it."
}

/**
 * Both subtitle rows name the choice and then what it means, because neither is self-evident and
 * there is no preview on this screen to fall back on.
 *
 * A preview is deliberately absent: the chosen size is a fraction of the **player's** height, so a
 * sample drawn in a settings list would be a different size than the real thing and would mislead
 * about the one property being set.
 */
private fun subtitleSizeSummary(size: SubtitleTextSize): String = when (size) {
    SubtitleTextSize.System -> "${size.label}. Follows Android's caption size."
    else -> "${size.label}. A fixed share of the picture height."
}

private fun subtitleBackgroundSummary(background: SubtitleBackground): String = when (background) {
    SubtitleBackground.System -> "${background.label}. Follows Android's caption style."
    SubtitleBackground.None -> "${background.label}. Nothing behind the text."
    SubtitleBackground.Shadow -> "${background.label}. A soft edge behind the letters."
    SubtitleBackground.Outline -> "${background.label}. A hard edge around the letters."
    SubtitleBackground.Box -> "${background.label}. A dark box behind the text."
}

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

/**
 * The one place the app asks for anything, and it asks quietly.
 *
 * Deliberately a row like any other rather than a banner: this is a client for an account the
 * viewer already pays someone else for, so a donation prompt that pushes would be asking for money
 * twice. The wording says what the money is for - the work on the app - because an IPTV client
 * that is vague about that is an IPTV client that reads as selling access to something. It is not.
 *
 * Nothing here takes a payment. It opens a page, or hands over an address, and stops.
 */
@Composable
private fun SupportSection() {
    val context = LocalContext.current
    // The failure this is written for is a television. A Fire TV Stick frequently has no browser
    // installed at all, and there ACTION_VIEW throws rather than quietly doing nothing - so the
    // fallback is to put the address on screen where it can be read off and typed elsewhere.
    var showAddress by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("Support development") },
            supportingContent = {
                Text("Entirely optional. It supports work on the app and unlocks nothing.")
            },
            leadingContent = { Icon(Icons.Outlined.FavoriteBorder, null) },
            trailingContent = { Icon(Icons.Outlined.OpenInNew, null) },
            modifier = Modifier.clickable {
                val opened = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Donations.KO_FI_URL.toUri())
                            // Its own task, so leaving the browser comes back here rather than
                            // into this app's back stack - and so a non-Activity context cannot
                            // turn this into a crash.
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
                if (!opened) showAddress = true
            },
        )
        if (showAddress) {
            ListItem(
                headlineContent = { Text(Donations.KO_FI_LABEL) },
                supportingContent = { Text("No browser on this device. Open it elsewhere.") },
                leadingContent = { Icon(Icons.Outlined.ContentCopy, null) },
                modifier = Modifier.clickable {
                    copyToClipboard(context, label = "Ko-fi", text = Donations.KO_FI_URL)
                },
            )
        }
        // Only the addresses that passed Donations' rule, which is why there is no check here: an
        // address that is a placeholder, damaged, or a token contract never arrives in this list.
        Donations.coins.forEach { coin -> CryptoRow(coin) }
    }
}

/** One coin, copied rather than typed, because nobody types a wallet address correctly. */
@Composable
private fun CryptoRow(coin: CryptoAddress) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text("${coin.coin} (${coin.ticker})") },
        supportingContent = {
            Column {
                Text(
                    if (copied) "Address copied" else coin.address,
                    // The address is long and this is a phone. Cutting it in the middle would hide
                    // the end, which is the half a person checks after pasting.
                    maxLines = 2,
                )
                // The network matters as much as the address: sending on a chain this account
                // cannot be reached on loses the money just as completely as a wrong address.
                coin.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        leadingContent = { Icon(Icons.Outlined.ContentCopy, null) },
        modifier = Modifier.clickable {
            copyToClipboard(context, label = "${coin.coin} address", text = coin.address)
            copied = true
        },
    )
}

/**
 * The platform clipboard rather than Compose's, which is deprecated in this Compose version.
 *
 * Failure is swallowed on purpose: a clipboard that refuses is a device-policy decision, not
 * something to interrupt a settings screen over.
 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
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

/** 32 MB. An export of a six-figure library is a small fraction of this. */
private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
