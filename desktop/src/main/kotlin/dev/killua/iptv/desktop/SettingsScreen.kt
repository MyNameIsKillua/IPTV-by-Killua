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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.account.ExpiryWarning
import dev.killua.iptv.domain.account.expiryWarningFor
import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.model.languageDisplayName
import dev.killua.iptv.domain.support.CryptoAddress
import dev.killua.iptv.domain.support.Donations
import dev.killua.iptv.domain.userdata.UserDataExport
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Account, storage and data, in that order.
 *
 * Everything here is either a fact about this installation or an action on the viewer's own data.
 * There are no playback preferences yet, because the desktop player has nothing worth remembering
 * that is not already remembered.
 *
 * **The server address and username are deliberately not shown.** They are the account, they are
 * secrets, and a settings screen that prints them is a settings screen that ends up in a screenshot.
 * What is shown instead is what the viewer needs to recognise which account this is.
 */
@Composable
fun SettingsScreen(
    session: DesktopSession,
    userData: UserDataExport?,
    vlcAvailable: Boolean,
    artworkBytes: Long,
    keptFiles: List<String>,
    message: String?,
    languages: TrackLanguagePreferences,
    onForgetLanguages: () -> Unit,
    onClearArtwork: () -> Unit,
    dataDirectory: File,
    /** Whether this machine can seal an account at all, and whether one is sealed. */
    credentialsSupported: Boolean,
    credentialsStored: Boolean,
    onForgetCredentials: () -> Unit,
    library: LibraryIndex,
    librarySyncing: Boolean,
    onReloadLibrary: () -> Unit,
    libraryCacheBytes: Long,
    onForgetLibraryCache: () -> Unit,
    preferences: DesktopPreferences,
    onPreferencesChange: (DesktopPreferences) -> Unit,
    /** The shortcut waiting for a key, if any. Its row says so rather than showing a binding. */
    capturingShortcut: Shortcut?,
    onCaptureKey: (Shortcut?) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    /** True while a check the viewer asked for is in flight. */
    checkingForUpdate: Boolean,
    /** What the last asked-for check said, or null before one was asked for. */
    updateCheckMessage: String?,
    onCheckForUpdate: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        GlowText("Settings", style = MaterialTheme.typography.headlineSmall, glowRadius = 24f)
        Spacer(Modifier.height(24.dp))

        SettingsCard("Account") {
            val account = session.account
            InfoRow("Playlist", account.label)
            InfoRow(
                "Status",
                when (account.status) {
                    AccountStatus.Active -> "Active"
                    AccountStatus.Expired -> "Expired"
                    AccountStatus.Disabled -> "Disabled by the provider"
                    AccountStatus.Unknown -> "Unknown"
                },
            )
            account.expiresAtEpochSeconds?.takeIf { it > 0L }?.let { expiry ->
                val date = DATE.format(Instant.ofEpochSecond(expiry).atZone(ZoneId.systemDefault()))
                // A date on its own does not say whether it is next week. The days are what someone
                // reads this row for.
                val remaining = when (
                    val warning = expiryWarningFor(expiry, System.currentTimeMillis() / 1000L)
                ) {
                    ExpiryWarning.Expired -> " · expired"
                    is ExpiryWarning.Soon -> when (warning.days) {
                        0 -> " · today"
                        1 -> " · tomorrow"
                        else -> " · in ${warning.days} days"
                    }
                    null -> ""
                }
                InfoRow("Expires", date + remaining)
            }
            val connections = listOfNotNull(account.activeConnections, account.maximumConnections)
            if (connections.size == 2) {
                InfoRow("Connections", "${connections[0]} of ${connections[1]} in use")
            }
            if (account.allowedOutputFormats.isNotEmpty()) {
                InfoRow("Formats", account.allowedOutputFormats.sorted().joinToString(", "))
            }
            if (credentialsSupported) {
                InfoRow("Stay signed in", if (credentialsStored) "Yes, on this computer" else "No")
            }
            Spacer(Modifier.height(12.dp))
            Row {
                ActionButton("Sign out", onClick = onSignOut)
                if (credentialsStored) {
                    Spacer(Modifier.width(10.dp))
                    ActionButton("Forget the saved sign-in", onClick = onForgetCredentials)
                }
            }
            Spacer(Modifier.height(6.dp))
            Hint(
                if (credentialsStored) {
                    "Your sign-in is sealed with Windows' own protection: only this Windows " +
                        "account can read it back, not anyone else on this machine and not on " +
                        "another one. Signing out forgets it, and so does the button beside it. " +
                        "Your watch progress and marks are kept separately and stay either way."
                } else {
                    "Nothing about this account is written to disk, so signing out only forgets " +
                        "what is in memory. Your watch progress and marks stay — they are kept " +
                        "against a one-way fingerprint of the account rather than against a " +
                        "password, so signing in again brings them back."
                },
            )
        }

        Spacer(Modifier.height(18.dp))

        SettingsCard("Library") {
            LibraryKind.entries.forEach { kind ->
                InfoRow(
                    kind.label,
                    when {
                        !library.has(kind) && librarySyncing -> "reading…"
                        !library.has(kind) -> "not read — browse by category"
                        kind in library.truncated ->
                            "%,d".format(library.countOf(kind)) + " (as many as are held)"

                        else -> "%,d".format(library.countOf(kind))
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            ActionButton(
                if (librarySyncing) "Reading…" else "Read the library again",
                onClick = onReloadLibrary,
            )
            Spacer(Modifier.height(6.dp))
            Hint(
                "The whole listing is read once per sign-in and held in memory, which is what " +
                    "makes search and browsing without a category possible.",
            )

            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .focusRing(RoundedCornerShape(10.dp))
                    .clickable {
                        onPreferencesChange(
                            preferences.copy(libraryCacheEnabled = !preferences.libraryCacheEnabled),
                        )
                    }
                    .padding(vertical = 2.dp),
            ) {
                Checkbox(
                    checked = preferences.libraryCacheEnabled,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(libraryCacheEnabled = it))
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = VioletBright,
                        uncheckedColor = InkMuted,
                        checkmarkColor = Night,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Keep the library on this computer",
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (preferences.libraryCacheEnabled) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Read it again after",
                        color = InkMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(140.dp),
                    )
                    SortMenu(
                        options = DesktopPreferences.CACHE_HOUR_CHOICES.map {
                            it.toString() to cacheAgeLabel(it)
                        },
                        selected = preferences.libraryCacheHours.toString(),
                        onSelect = { chosen ->
                            chosen.toIntOrNull()?.let {
                                onPreferencesChange(preferences.copy(libraryCacheHours = it))
                            }
                        },
                    )
                }
                InfoRow("Kept on disk", megabytesOf(libraryCacheBytes))
                if (libraryCacheBytes > 0L) {
                    Spacer(Modifier.height(8.dp))
                    ActionButton("Forget what is kept", onClick = onForgetLibraryCache)
                }
            }

            Spacer(Modifier.height(6.dp))
            Hint(
                "With this on, the listing is written here and used at the next launch instead of " +
                    "being read again — the difference between opening the client and waiting for " +
                    "it. Turn it off if you would rather nothing were written down, or if your " +
                    "provider changes its listing so often that a day-old copy would mislead you; " +
                    "either way *Read the library again* is always there.",
            )
            Spacer(Modifier.height(6.dp))
            Hint(
                "What is written is the listing — names, artwork addresses and the provider's own " +
                    "ids — and never an account: no password, no user name, no server address. A " +
                    "channel's direct source is dropped outright, because that is the one field a " +
                    "provider fills with a full signed-in URL, and any artwork address carrying " +
                    "your user name or password is dropped with it. It is kept per account, and a " +
                    "file that cannot be read is deleted rather than repaired.",
            )
        }

        Spacer(Modifier.height(18.dp))

        SettingsCard("Your data") {
            val records = userData?.recordCount ?: 0
            InfoRow("Stored entries", "$records")
            // Split out because nothing here is ever pruned: one number says whether anything is
            // stored, five say which of them is the one growing.
            userData?.recordCounts?.filterValues { it > 0 }?.forEach { (kind, count) ->
                InfoRow("    $kind", "$count")
            }
            InfoRow("Location", dataDirectory.path)
            Spacer(Modifier.height(12.dp))
            Row {
                ActionButton("Export to a file…", onClick = onExport)
                Spacer(Modifier.width(10.dp))
                ActionButton("Import from a file…", onClick = onImport)
            }
            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = VioletBright, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            Hint(
                "The file holds watch progress, favourites and your list — and no password, " +
                    "username or server address. It is the same format the phone reads and writes, " +
                    "so the two can be carried between each other. Importing merges: the newer " +
                    "entry wins and nothing is ever removed.",
            )
            Spacer(Modifier.height(6.dp))
            Hint(
                "Two smaller files sit beside it: the names of what you have marked, and where " +
                    "you left the window. Both are local and can be deleted at any time.",
            )

            // Only when there are any, because a row reading "none" about something that has never
            // happened teaches a viewer to worry about it.
            if (keptFiles.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                InfoRow("Kept from a failed read", "${keptFiles.size}")
                keptFiles.forEach { InfoRow("", it) }
                Spacer(Modifier.height(6.dp))
                // No button to remove them. Each one is the only remaining copy of a history this
                // client could not read, and a delete button beside that is a way to finish the
                // loss rather than a convenience.
                Hint(
                    "A file here could not be read at launch, so it was kept rather than " +
                        "overwritten and this account started empty. To try it again, close the " +
                        "client, delete user-data.json and rename the kept file to it. Nothing " +
                        "removes these but you.",
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        SettingsCard("Artwork") {
            InfoRow("Kept on disk", megabytesOf(artworkBytes))
            Spacer(Modifier.height(12.dp))
            ActionButton("Clear cached artwork", onClick = onClearArtwork)
            Spacer(Modifier.height(6.dp))
            Hint(
                "Posters and logos are kept so that browsing does not re-download them every " +
                    "launch. They are capped at 200 MB, the oldest go first, and clearing costs " +
                    "nothing but a few seconds of fetching.",
            )
        }

        Spacer(Modifier.height(18.dp))

        SettingsCard("Playback") {
            InfoRow("Engine", if (vlcAvailable) "libvlc, found" else "libvlc NOT found")
            if (!vlcAvailable) {
                Spacer(Modifier.height(6.dp))
                Hint(
                    "This client plays through VLC and does not bundle it. Install VLC and " +
                        "restart to enable playback.",
                )
            }

            Spacer(Modifier.height(14.dp))
            // The one playback preference that is about a habit rather than a stream. Ten and
            // thirty suit a film; a match, a lecture or a set of adverts want something else, and
            // the viewer is the only one who knows which of those they are watching.
            SkipRow(
                label = "Skip back",
                seconds = preferences.safeSkipBack,
                onChange = { onPreferencesChange(preferences.copy(skipBackSeconds = it)) },
            )
            SkipRow(
                label = "Skip forward",
                seconds = preferences.safeSkipForward,
                onChange = { onPreferencesChange(preferences.copy(skipForwardSeconds = it)) },
            )
            Spacer(Modifier.height(6.dp))
            Hint("Used by the arrow keys and by the two round buttons under the picture.")

            Spacer(Modifier.height(14.dp))
            InfoRow("Audio", languages.audioLanguage?.let(::languageDisplayName) ?: FOLLOWS_STREAM)
            val subtitleLanguage = languages.subtitleLanguage
            InfoRow(
                "Subtitles",
                when {
                    languages.subtitlesDisabled -> "Off"
                    // Read into a local first: Kotlin does not smart cast a property from another
                    // module, and this one lives in `:shared`.
                    subtitleLanguage != null -> languageDisplayName(subtitleLanguage)
                    else -> FOLLOWS_STREAM
                },
            )
            if (!languages.isEmpty) {
                Spacer(Modifier.height(12.dp))
                ActionButton("Forget these", onClick = onForgetLanguages)
            }
            Spacer(Modifier.height(6.dp))
            Hint(
                "Picking a track in the player teaches this, and the next title with that " +
                    "language starts in it. Only what you choose by hand is learned — never what " +
                    "the player picked on its own.",
            )
        }

        Spacer(Modifier.height(18.dp))

        SettingsCard("Keyboard") {
            // The same list the window dispatches over, so this cannot offer a key that does
            // nothing. It replaced a paragraph of prose that had no way of staying true — and now
            // that each row is a button, it is also where the keys are changed.
            ShortcutTable(
                bindings = preferences.shortcutBindings,
                skipBackSeconds = preferences.safeSkipBack,
                skipForwardSeconds = preferences.safeSkipForward,
                onRebind = onCaptureKey,
                capturing = capturingShortcut,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                if (capturingShortcut != null) {
                    ActionButton("Cancel", onClick = { onCaptureKey(null) })
                    Spacer(Modifier.width(10.dp))
                }
                if (preferences.keys.isNotEmpty()) {
                    ActionButton(
                        "Put the keys back",
                        onClick = { onPreferencesChange(preferences.withDefaultKeys()) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Hint(
                "Click a key to change it, then press the one you want. Escape cancels. Taking a " +
                    "key from another shortcut leaves that one unset until you give it one — two " +
                    "shortcuts on one key would mean one of them silently stops working. Escape " +
                    "itself cannot be changed: it is the way out of this.",
            )
            Spacer(Modifier.height(6.dp))
            Hint("F1 shows this over whatever is playing, which is where fullscreen leaves you.")
        }

        Spacer(Modifier.height(18.dp))

        SettingsCard("Updates") {
            // The client never said what it was, which made every bug report start with a question
            // and made an update impossible to confirm by looking. Read from the same generated
            // resource the update check compares against, so this row and that decision can never
            // disagree about what is running.
            InfoRow("Version", DesktopUpdateChecker.installedVersion())
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = preferences.updateCheckEnabled,
                    onCheckedChange = {
                        onPreferencesChange(preferences.copy(updateCheckEnabled = it))
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = VioletBright,
                        uncheckedColor = InkMuted,
                        checkmarkColor = Night,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Check for updates",
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The daily interval is right for something the client does on its own and wrong
                // for someone who heard a fix exists. Without this, the answer to "is there a new
                // version?" was "ask again tomorrow".
                if (preferences.updateCheckEnabled && !checkingForUpdate) {
                    ActionButton("Check now", onClick = onCheckForUpdate)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    when {
                        checkingForUpdate -> "Asking…"
                        updateCheckMessage != null -> updateCheckMessage
                        !preferences.updateCheckEnabled -> "Turned off"
                        else -> ""
                    },
                    color = InkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(10.dp))
            // The reason sits beside the switch rather than only in a document nobody opens. This
            // is the one request this client makes to anyone but the viewer's own provider, so
            // what it costs belongs where the decision about it is made.
            Hint(
                "Asks GitHub once a day whether a newer version exists. The request sends nothing " +
                    "about you, your account, or what you watch — but GitHub sees your IP " +
                    "address, as it would for any web page. Turned off, this client never " +
                    "contacts it.",
            )
        }

        Spacer(Modifier.height(18.dp))

        SupportCard()

        Spacer(Modifier.height(24.dp))
        Text(
            "Killua IPTV desktop · developed by MyNameIsKillua",
            color = InkMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * The one card that asks for anything, placed last and written to be skippable.
 *
 * It is a card like the others rather than something that stands out, because this is a client for
 * an account the viewer already pays a provider for - a donation prompt with any push in it would
 * be asking the same person for money twice. The text says the money is for the work on the app,
 * since an IPTV client that is vague about what a payment buys reads as selling access to content.
 * It does not, and cannot.
 */
@Composable
private fun SupportCard() {
    // What the last click did, shown in place of a dialog. Null until something has been clicked.
    var note by remember { mutableStateOf<String?>(null) }

    SettingsCard("Support") {
        Hint(
            "Entirely optional, and it unlocks nothing: it supports work on the app itself, not " +
                "access to anything you watch with it.",
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton("Open Ko-fi") {
                note = if (openInBrowser(Donations.KO_FI_URL)) {
                    "Opened in your browser."
                } else {
                    // Not every machine has a browser this can reach - a locked-down Windows
                    // install, or a Linux desktop without xdg-open. Copying is the way out.
                    copyToClipboard(Donations.KO_FI_URL)
                    "No browser available. The link is on your clipboard."
                }
            }
            Spacer(Modifier.width(10.dp))
            ActionButton("Copy link") {
                copyToClipboard(Donations.KO_FI_URL)
                note = "Link copied."
            }
        }
        Spacer(Modifier.height(8.dp))
        InfoRow("Ko-fi", Donations.KO_FI_LABEL)

        // Only the addresses that passed Donations' rule, which is why there is no check here: an
        // address that is a placeholder, damaged, or a token contract never arrives in this list.
        if (Donations.hasCoins) {
            Spacer(Modifier.height(16.dp))
            Hint("Or directly, if you would rather:")
            Spacer(Modifier.height(6.dp))
            Donations.coins.forEach { coin ->
                CoinRow(coin) {
                    copyToClipboard(coin.address)
                    note = "${coin.ticker} address copied. Check the network before you send."
                }
            }
        }

        note?.let {
            Spacer(Modifier.height(12.dp))
            Hint(it)
        }
    }
}

/** One coin, with the address behind a button, because nobody retypes one of these correctly. */
@Composable
private fun CoinRow(coin: CryptoAddress, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${coin.coin} (${coin.ticker})",
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(140.dp),
            )
            ActionButton("Copy address", onClick = onCopy)
        }
        // The network matters as much as the address: sending on a chain this account cannot be
        // reached on loses the money just as completely as a wrong address does.
        coin.note?.let {
            Spacer(Modifier.height(4.dp))
            Row {
                Spacer(Modifier.width(140.dp))
                Hint(it)
            }
        }
    }
}

/**
 * Opens a link in whatever the system considers a browser, and reports whether it managed to.
 *
 * Every step of this is optional on some machine: AWT desktop integration can be absent, BROWSE
 * can be unsupported, and the call can throw on a system with nothing registered for http. The
 * answer to all three is the same false, so the caller can offer the clipboard instead of failing.
 */
internal fun openInBrowser(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
    desktop.browse(URI(url))
    true
}.getOrDefault(false)

/** Failure is swallowed: a clipboard the system refuses is not worth interrupting a screen over. */
private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/**
 * One skip setting, as a row with a picker.
 *
 * The choices are a fixed list rather than a number field. Every value anyone actually wants is in
 * it, and a field would need validating, explaining and defending against someone typing zero.
 */
@Composable
private fun SkipRow(label: String, seconds: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = InkMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(140.dp),
        )
        SortMenu(
            // The stored value is always offered, even when it is not one of the choices: a file
            // edited by hand should not silently become something else the moment this opens.
            options = (DesktopPreferences.SKIP_CHOICES + seconds).distinct().sorted()
                .map { it.toString() to secondsPhrase(it) },
            selected = seconds.toString(),
            onSelect = { chosen -> chosen.toIntOrNull()?.let(onChange) },
        )
    }
}

/** How a kept library's age reads in a menu, including the answer that is not a duration. */
private fun cacheAgeLabel(hours: Int): String = when (hours) {
    0 -> "Only when I ask"
    1 -> "1 hour"
    24 -> "1 day"
    72 -> "3 days"
    168 -> "1 week"
    else -> "$hours hours"
}

/** One decimal place, which is as much as anyone reads off a cache size. */
private fun megabytesOf(bytes: Long): String =
    if (bytes <= 0L) "nothing yet" else "%.1f MB".format(bytes / (1024.0 * 1024.0))

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(NightRaised)
            .border(1.dp, Violet.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
            .padding(22.dp),
    ) {
        Text(
            title.uppercase(),
            color = VioletBright,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** What no preference looks like, which is a different state from having chosen. */
private const val FOLLOWS_STREAM = "Whatever the stream offers"

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = InkMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(140.dp))
        Text(value, color = Ink, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = InkMuted, style = MaterialTheme.typography.labelMedium)
}

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

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
