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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image as SkiaImage
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.RemoteAccount
import dev.killua.iptv.domain.model.XtreamCredentials
import dev.killua.iptv.domain.update.UpdateStatus
import dev.killua.iptv.domain.userdata.UserDataExportCodec
import kotlinx.coroutines.launch

/**
 * The desktop client, first vertical slice: sign in, browse Live by category, play a channel.
 *
 * What it deliberately does not do yet matters as much as what it does. There is **no local
 * database**: browsing by category never asks the provider for more than one category at a time, so
 * the six-figure listing that forced streaming and batching on Android is never requested, and the
 * persistence question is postponed rather than answered badly.
 *
 * There is also **no credential storage**. Signing in again each launch is honest for now; Windows
 * has no Android Keystore, and doing it properly means DPAPI. Nothing is written to disk here.
 */
@OptIn(FlowPreview::class)
fun main() = application {
    println("Killua IPTV desktop starting")
    // Maximized from the first frame rather than after the preferences arrive: a window that
    // opens small and jumps a moment later looks like something went wrong, and the default this
    // client wants is the large one anyway.
    val windowState = rememberWindowState(
        width = DesktopPreferences.DEFAULT_WIDTH.dp,
        height = DesktopPreferences.DEFAULT_HEIGHT.dp,
        placement = WindowPlacement.Maximized,
    )
    /**
     * Whether the picture has a screen to itself.
     *
     * A plain boolean, and **not** `WindowPlacement.Fullscreen`. That placement is exclusive
     * full-screen mode underneath, which Windows minimizes the moment the window loses focus and
     * which this client then could not get out of; [FullscreenPlayerWindow] carries the measurements
     * and the reasoning. Fullscreen is now a second, undecorated window, so this state says whether
     * that window exists rather than what shape this one is in.
     */
    var fullscreen by remember { mutableStateOf(false) }

    fun leaveFullscreen(): Boolean {
        if (!fullscreen) return false
        fullscreen = false
        return true
    }
    val client = remember { XtreamDesktopClient() }
    val player = remember {
        VlcVideoPlayer().also {
            // The one startup fact worth printing. "No video and no error" otherwise looks like a
            // rendering bug when it is really a missing native library.
            println(if (it.isAvailable) "libvlc: found" else "libvlc: NOT found - playback disabled")
        }
    }
    var session by remember { mutableStateOf<DesktopSession?>(null) }
    val keys = remember { ScreenKeys() }
    val appIcon = remember { loadAppIcon() }
    val preferenceStore = remember { PreferenceStore() }
    var preferences by remember { mutableStateOf(DesktopPreferences()) }
    var preferencesLoaded by remember { mutableStateOf(false) }
    /**
     * Whether a newer release exists, asked once per launch and at most once a day.
     *
     * Held here rather than inside the overlay so that dismissing it lasts for this run: the
     * overlay is drawn from this value, and *Not yet* clears it.
     */
    val updateChecker = remember { DesktopUpdateChecker(DesktopUpdateChecker.installedVersion()) }
    val updateInstaller = remember { DesktopUpdateInstaller() }
    /** The window's own scope, so a download outlives the frame that started it. */
    val updateScope = rememberCoroutineScope()
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Unknown) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0f) }
    var updateProblem by remember { mutableStateOf<String?>(null) }
    /** A check the viewer asked for, which is a different thing from the one at launch. */
    var updateChecking by remember { mutableStateOf(false) }
    var updateCheckMessage by remember { mutableStateOf<String?>(null) }
    /**
     * The whole library, read once per sign-in and held for as long as this window is open.
     *
     * Owned here rather than by the browsing screen so that skipping the progress panel does not
     * cancel the read: the panel is a view of something happening above it, not the thing itself.
     * Signing out replaces it with an empty one, because a library belongs to the account that was
     * asked for it.
     */
    var library by remember { mutableStateOf(LibraryIndex()) }
    var syncState by remember { mutableStateOf(LibrarySyncState()) }
    var syncPanelVisible by remember { mutableStateOf(false) }
    var syncAttempt by remember { mutableStateOf(0) }
    /** True for a read the viewer asked for, which is the one read that ignores what is kept. */
    var readingAgain by remember { mutableStateOf(false) }
    val libraryCache = remember { LibraryCache() }

    LaunchedEffect(Unit) {
        val stored = preferenceStore.load()
        preferences = stored
        player.restoreAudio(stored.volume, stored.muted)
        if (stored.windowMaximized) {
            windowState.placement = WindowPlacement.Maximized
        } else {
            windowState.placement = WindowPlacement.Floating
            windowState.size = DpSize(stored.safeWidth.dp, stored.safeHeight.dp)
        }
        preferencesLoaded = true
        // Once, at launch. A library of six figures of posters would otherwise fill a disk quietly
        // over months, which is the kind of bug that gets found by something else failing.
        ArtworkLoader.prune()

        // After the stored preferences, never before: the switch that decides whether this asks at
        // all lives in them, and asking first would make the switch a thing that takes effect
        // tomorrow. The checker itself refuses to ask more often than daily.
        val outcome = updateChecker.check(preferences)
        outcome.checkedAtMillis?.let { preferences = preferences.copy(updateCheckedAtMillis = it) }
        updateStatus = outcome.status
    }

    /** Everything worth remembering, read at the instant it is asked for. */
    fun currentPreferences() = preferences.copy(
        volume = player.volume,
        muted = player.isMuted,
        windowWidth = windowState.size.width.value.toInt(),
        windowHeight = windowState.size.height.value.toInt(),
        // The main window is never put into full-screen any more, so this is simply what it is: a
        // film cannot teach the client that this viewer likes their window full-screen, because a
        // film no longer touches this window at all.
        windowMaximized = windowState.placement == WindowPlacement.Maximized,
    )

    // Written from one place, and only after the file has been read: saving before that would put
    // the defaults over whatever was there. Debounced because dragging a volume slider or a window
    // edge produces a value every frame, and none of the ones in between is worth a file.
    LaunchedEffect(preferencesLoaded) {
        if (!preferencesLoaded) return@LaunchedEffect
        snapshotFlow { currentPreferences() }
            .distinctUntilChanged()
            .debounce(800)
            .collect { preferenceStore.save(it) }
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    /**
     * What a key press does, for **both** windows.
     *
     * Declared here rather than inline in the window, because fullscreen is now a second window and
     * the two must not answer keys differently — a client where space pauses in one window and types
     * a space in the other would be worse than one with no keys at all.
     *
     * The `when` is exhaustive over [Shortcut] on purpose: offering a viewer a key and giving it
     * nothing to do is then a compile error rather than a disappointment. Which key means what comes
     * from the viewer's own bindings over the defaults.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (session == null || event.type != KeyEventType.KeyDown) return false
        // A rebinding in progress swallows everything: the press is the answer to a question the
        // settings screen asked, not a command. Escape is not bindable, so it ends the capture.
        keys.capture?.let { capture ->
            capture(event)
            return true
        }
        return when (
            preferences.shortcutBindings.forPress(event, keys.playerOnScreen)
        ) {
            null -> false
            Shortcut.PlayPause -> { player.togglePause(); true }
            Shortcut.Back -> { player.skip(-preferences.safeSkipBack * 1_000L); true }
            Shortcut.Forward -> { player.skip(preferences.safeSkipForward * 1_000L); true }
            Shortcut.PreviousChannel -> { keys.step?.invoke(-1); true }
            Shortcut.NextChannel -> { keys.step?.invoke(1); true }
            Shortcut.Louder -> { player.adjustVolume(VOLUME_STEP); true }
            Shortcut.Quieter -> { player.adjustVolume(-VOLUME_STEP); true }
            Shortcut.Mute -> { player.toggleMute(); true }
            Shortcut.Fullscreen -> { fullscreen = !fullscreen; true }
            Shortcut.Fill -> { player.toggleFill(); true }
            // Topmost first, and consumed only when it actually closed something: escape with
            // nothing open belongs to whatever has focus.
            Shortcut.Escape -> when {
                keys.helpVisible -> { keys.helpVisible = false; true }
                // Returns false when there was no fullscreen to leave, which is what lets escape
                // reach whatever has focus instead.
                else -> leaveFullscreen()
            }
            Shortcut.Search -> {
                keys.focusSearch?.invoke()
                keys.focusSearch != null
            }
            Shortcut.Help -> { keys.helpVisible = !keys.helpVisible; true }
        }
    }

    /**
     * Reads the whole library for whoever has just signed in.
     *
     * Keyed on the session, so signing out cancels a read in flight rather than letting it finish
     * into a window that belongs to somebody else, and on the attempt so that *Read again* is one
     * increment rather than its own machinery.
     *
     * The index is handed over after every step, which is what lets the panel be skipped: Live works
     * while films are still arriving.
     */
    LaunchedEffect(session, syncAttempt) {
        val active = session ?: return@LaunchedEffect
        library = LibraryIndex()
        syncState = LibrarySyncState()

        // What was kept last time, where the viewer has asked for it to be kept and has not just
        // asked for it to be read again. This is the whole point of the file: the second launch of
        // an evening should not be minutes of waiting.
        val fingerprint = DesktopUserData.fingerprintOf(
            active.credentials.serverUrl,
            active.credentials.username,
        )
        /*
         * A playlist is read fresh, every launch, and the cache stays shut for it.
         *
         * Not an oversight and not a performance decision. `LibraryCache` deliberately drops
         * `direct_source` before writing, because for an Xtream channel that address carries the
         * account - and for a playlist channel that address is the *only* way to play it. A cached
         * playlist would restore a library of channels that cannot be started. Reading it again
         * costs one request, which is what the format is, so there is nothing to trade away.
         */
        val cacheUsable = active.playlistUrl == null && preferences.libraryCacheEnabled
        val reader: LibraryReader = active.playlistUrl
            ?.let { PlaylistLibraryReader(it) }
            ?: client
        val kept = if (readingAgain || !cacheUsable) {
            null
        } else {
            libraryCache.load(fingerprint, preferences.libraryCacheMaxAgeMillis)
        }
        readingAgain = false
        if (kept != null) {
            library = kept.asIndex()
            syncState = LibrarySyncState(
                expected = library.loaded.ifEmpty { LibraryKind.entries.toSet() },
                counts = LibraryKind.entries.associateWith { library.countOf(it) },
                done = library.loaded,
                fromDisk = true,
                finished = true,
            )
            syncPanelVisible = false
            return@LaunchedEffect
        }

        syncPanelVisible = true
        val index = loadLibrary(
            client = reader,
            credentials = active.credentials,
            onState = { syncState = it },
            onIndex = { library = it },
            // Reading the library is background work; watching is not. See `loadLibrary`.
            isPlaying = { player.hasMedia },
        )
        library = index
        // Kept only when it is worth keeping: a run that failed a listing would otherwise write a
        // half library and hand it back on the next launch as if it were the whole thing.
        if (cacheUsable && !syncState.hasFailure && !index.isEmpty) {
            libraryCache.save(
                fingerprint = fingerprint,
                index = index,
                credentials = SecretsToStrip(
                    username = active.credentials.username,
                    password = active.credentials.password,
                ),
            )
        }
        // A clean run puts itself away: someone who has just watched three counts finish does not
        // want to confirm that they finished. A failed one stays, because it has something to say.
        if (!syncState.hasFailure) syncPanelVisible = false
    }

    Window(
        onCloseRequest = {
            // Both of these are the same argument. The debounce above keeps preferences off the disk
            // while a slider moves, and the state file is written by coroutines nobody waits for —
            // the position between checkpoints, and every mark the moment it is set. At closing
            // time each of those is the only thing standing between a change and being forgotten.
            // Blocking briefly on two small files is what the ordinary way an evening ends is worth.
            runBlocking {
                keys.flushToDisk?.invoke()
                if (preferencesLoaded) preferenceStore.save(currentPreferences())
            }
            exitApplication()
        },
        state = windowState,
        // Drawn once and handed to the window, the taskbar and alt-tab. Without it the client wears
        // the Java coffee cup, which says nothing about what it is and everything about how it was
        // built.
        icon = appIcon,
        // The taskbar entry says what is on, which is what someone alt-tabbing is looking for.
        title = keys.nowPlaying?.let { "Killua IPTV — $it" } ?: "Killua IPTV",
        // Handled at the window rather than on a focused control, so the keys work wherever the
        // pointer last was. Which key means what, and which of them wait for something to be
        // playing, is [Shortcut] — one list, shared with the screen that shows it to a viewer.
        //
        // The `when` below is exhaustive over that list on purpose: offering a viewer a key and
        // giving it nothing to do is then a compile error rather than a disappointment.
        onPreviewKeyEvent = { event -> handleKey(event) },
    ) {
        KilluaDesktopTheme {
            Box(Modifier.fillMaxSize()) {
                val active = session
                if (active == null) {
                    SignInScreen(
                        client = client,
                        preferences = preferences,
                        onPreferencesChange = { preferences = it },
                    ) { session = it }
                } else if (syncPanelVisible) {
                    LibrarySyncScreen(
                        state = syncState,
                        accountLabel = active.account.label,
                        onRetry = { syncAttempt++ },
                        onContinue = { syncPanelVisible = false },
                    )
                } else {
                    BrowseScreen(
                        client = client,
                        player = player,
                        session = active,
                        keys = keys,
                        preferences = preferences,
                        onPreferencesChange = { preferences = it },
                        library = library,
                        librarySyncing = !syncState.finished,
                        onReloadLibrary = {
                            readingAgain = true
                            syncAttempt++
                            syncPanelVisible = true
                        },
                        libraryCache = libraryCache,
                        fullscreen = fullscreen,
                        onFullscreenChange = { fullscreen = it },
                        // Read from this window, so fullscreen lands on the screen the client is
                        // already on rather than on whichever one the system calls first.
                        screenBounds = { window.graphicsConfiguration.bounds },
                        onKeyEvent = { handleKey(it) },
                        onSignOut = {
                            session = null
                            fullscreen = false
                            library = LibraryIndex()
                            syncState = LibrarySyncState()
                        },
                        onExportUserData = { export, report ->
                            // The system dialog picks the destination, so this never decides where a
                            // viewer's file goes and never needs a permission to put it there.
                            val target = chooseFile(window, save = true, suggested = suggestedExportName())
                            if (target != null) {
                                val written = runCatching {
                                    target.writeText(UserDataExportCodec.encode(export))
                                }.isSuccess
                                // A silent failure here is a viewer who thinks their evening is backed
                                // up. Said either way.
                                report(written)
                            }
                        },
                        onImportUserData = { accept ->
                            chooseFile(window, save = false)?.let { file -> accept(file.readTextBounded()) }
                        },
                        checkingForUpdate = updateChecking,
                        updateCheckMessage = updateCheckMessage,
                        onCheckForUpdate = {
                            updateChecking = true
                            updateCheckMessage = null
                            updateScope.launch {
                                // Forced, because this one is a person asking rather than a launch.
                                // The daily interval still governs the automatic check.
                                val outcome = updateChecker.check(preferences, force = true)
                                outcome.checkedAtMillis?.let {
                                    preferences = preferences.copy(updateCheckedAtMillis = it)
                                }
                                updateStatus = outcome.status
                                updateChecking = false
                                updateCheckMessage = when (val status = outcome.status) {
                                    is UpdateStatus.Available ->
                                        "Version ${status.release.version} is available"
                                    UpdateStatus.UpToDate -> "You have the newest version"
                                    // Also covers being switched off, which cannot be reached from
                                    // here because the button is hidden then.
                                    UpdateStatus.Unknown -> "Could not check just now"
                                }
                            }
                        },
                    )
                }
                // Above the browsing screen, and above the picture when the picture is in this
                // window. When it is not, the fullscreen window draws the same panel over itself —
                // the case this exists for is fullscreen playback, where there is no Settings to
                // walk to.
                if (keys.helpVisible && !fullscreen) {
                    KeyboardHelpOverlay(
                        bindings = preferences.shortcutBindings,
                        skipBackSeconds = preferences.safeSkipBack,
                        skipForwardSeconds = preferences.safeSkipForward,
                        onClose = { keys.helpVisible = false },
                    )
                }
                // Last, so it draws above everything else - but never over a picture that is
                // already playing full-screen, where an unasked-for dialog is simply an intrusion.
                // Not over the sign-in screen, and not over a picture that has the whole screen.
                // Someone typing a password, or watching something, is not the person to interrupt
                // with a version number - the same rule the phone follows.
                (updateStatus as? UpdateStatus.Available)
                    ?.takeIf { !fullscreen && active != null }
                    ?.let { available ->
                    UpdateOverlay(
                        available = available,
                        busy = updateBusy,
                        progress = updateProgress,
                        problem = updateProblem,
                        onInstall = {
                            val asset = available.release.windowsInstaller
                            if (asset == null) {
                                updateProblem = "That release has no Windows installer."
                            } else {
                                updateBusy = true
                                updateProblem = null
                                updateScope.launch {
                                    val result = updateInstaller.install(
                                        asset = asset,
                                        signatureAsset = available.release.windowsInstallerSignature,
                                    ) { updateProgress = it }
                                    updateBusy = false
                                    when (result) {
                                        is DesktopUpdateInstaller.Result.Started -> {
                                            // Windows has the installer and is waiting for these
                                            // files to be free. Everything unsaved goes to disk
                                            // first - the same two writes the close button makes,
                                            // for the same reason.
                                            runBlocking {
                                                keys.flushToDisk?.invoke()
                                                if (preferencesLoaded) {
                                                    preferenceStore.save(currentPreferences())
                                                }
                                            }
                                            exitApplication()
                                        }
                                        is DesktopUpdateInstaller.Result.Failed ->
                                            updateProblem = result.reason
                                    }
                                }
                            }
                        },
                        onDismiss = { updateStatus = UpdateStatus.Unknown },
                    )
                }
            }
        }
    }
}

/** Everything the client keeps about the signed-in account. In memory only, never persisted. */
class DesktopSession(
    val credentials: XtreamCredentials,
    val account: Account,
    /**
     * The playlist this session reads, or null when it is an Xtream account.
     *
     * It is kept on the session rather than derived from the credentials because it decides two
     * things at once: which [LibraryReader] the library comes from, and that the library cache stays
     * shut. See the load effect for why the cache cannot serve a playlist.
     */
    val playlistUrl: String? = null,
)

/**
 * The three ways in.
 *
 * The first two are the same account reached differently - a link spares the transcription - and
 * the third is not an account at all.
 *
 * [Link] used to be labelled *Playlist link*, and that was a trap the owner walked into on
 * 22 August 2026: they pasted an `.m3u` address into it and got told the link could not be read, of
 * a link that was perfectly good. It means the `get.php` line a **provider** issues, so it says so
 * now, and the name *Playlist* is left for the thing that actually is one.
 */
private enum class SignInMode(val label: String) {
    Details("Server details"),
    Link("Provider link"),
    Playlist("Playlist file"),
}

@Composable
private fun SignInScreen(
    client: XtreamDesktopClient,
    preferences: DesktopPreferences,
    onPreferencesChange: (DesktopPreferences) -> Unit,
    onSignedIn: (DesktopSession) -> Unit,
) {
    var mode by remember { mutableStateOf(SignInMode.Details) }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var linkShown by remember { mutableStateOf(false) }
    var playlist by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A session that signed in successfully over plain HTTP, held back until the viewer has seen
    // what that means. Previously the warning was set and the screen left in the same breath, so the
    // one message on this screen that is actually about their credentials was never once read.
    var cleartext by remember { mutableStateOf<DesktopSession?>(null) }
    val scope = rememberCoroutineScope()

    val complete = when (mode) {
        SignInMode.Details -> server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        SignInMode.Link -> link.isNotBlank()
        SignInMode.Playlist -> playlist.isNotBlank()
    }
    val firstField = remember { FocusRequester() }
    // Off by default and never remembered: a revealed password is a decision for one moment, not a
    // setting. It exists because a mistyped password costs a round trip to the provider to discover.
    var passwordShown by remember { mutableStateOf(false) }
    val passwordField = remember { FocusRequester() }

    /**
     * Whether to keep the account, and whether there is anything to keep it with.
     *
     * Off unless the viewer asks, and only offered where something can actually seal the bytes —
     * see [CredentialVault]. It starts ticked when a stored account is already there, because the
     * box is then describing what is true rather than proposing something.
     */
    val vault = remember { CredentialVault() }
    var staySignedIn by remember { mutableStateOf(vault.hasStored) }
    /** True while the form is signing in with something it read rather than something typed. */
    var resuming by remember { mutableStateOf(vault.hasStored) }

    // The caret starts in the first field. Three fields and a window that opens with the caret
    // nowhere is a form that has to be aimed at before it can be typed into, every launch, for a
    // client that signs in every launch by design.
    // Keyed on the mode as well as on the launch: switching to the other way in leaves the caret
    // in a field that is no longer on screen, and a form you have to aim at is the thing this was
    // added to avoid. Only one of the two first fields is ever composed, so they share a requester.
    LaunchedEffect(mode) { runCatching { firstField.requestFocus() } }

    /**
     * Finishes a successful sign-in, deciding what this account is called.
     *
     * A typed name wins and is remembered against the account's fingerprint; an empty field means
     * "keep whatever I called it last time", which is what makes the field worth having at all —
     * nobody should retype their own name for a provider every launch. With neither, the label falls
     * back to the username, exactly as it did before.
     */
    fun finish(result: SignInResult.Ok) {
        val fingerprint = DesktopUserData.fingerprintOf(
            result.credentials.serverUrl,
            result.credentials.username,
        )
        val chosen = playlistName.trim().takeIf { it.isNotBlank() }
        if (chosen != null && chosen != preferences.playlistNames[fingerprint]) {
            onPreferencesChange(
                preferences.copy(playlistNames = preferences.playlistNames + (fingerprint to chosen)),
            )
        }
        val session = DesktopSession(
            credentials = result.credentials,
            account = result.account.toAccount(
                result.credentials,
                displayName = chosen ?: preferences.playlistNames[fingerprint],
            ),
        )
        // Only ever after the provider has said yes, and only what was actually used to get in.
        if (staySignedIn) {
            vault.save(
                when (mode) {
                    SignInMode.Details -> StoredSignIn(
                        server = server,
                        username = username,
                        password = password,
                    )
                    SignInMode.Link -> StoredSignIn(link = link)
                    // Unreachable: the box is not offered for a playlist. See the form below.
                    SignInMode.Playlist -> StoredSignIn()
                },
            )
        } else {
            vault.forget()
        }
        if (result.isCleartext) cleartext = session else onSignedIn(session)
    }

    /**
     * The playlist way in, which is not a sign-in and does not pretend to be one.
     *
     * There is no account, no password and nothing that can answer yes, so what stands in for
     * authentication is [PlaylistLibraryReader.probe]: the address is opened once and its first few
     * hundred bytes are read. That turns the two failures a viewer can actually cause - a typo and a
     * page that is not a playlist - into a message on this screen instead of a library that arrives
     * empty for reasons nobody can see.
     *
     * The account is synthetic. It carries no expiry and no connection limit because a file has
     * neither, and its identity is the fingerprint of the address, so the same playlist finds the
     * same watch history next time.
     */
    suspend fun submitPlaylist() {
        val reader = PlaylistLibraryReader(playlist.trim())
        when (val outcome = reader.probe()) {
            is PlaylistProbe.Refused ->
                error = "That address cannot be opened: ${outcome.reasonName}."
            is PlaylistProbe.Unreachable ->
                error = "That address could not be reached."
            PlaylistProbe.NotAPlaylist ->
                error = "That address answered, but what came back is not a playlist."
            is PlaylistProbe.Ok -> {
                val credentials = XtreamCredentials(
                    accountId = "desktop",
                    serverUrl = outcome.url,
                    username = "",
                    password = "",
                )
                val fingerprint = DesktopUserData.fingerprintOf(outcome.url, "")
                val chosen = playlistName.trim().takeIf { it.isNotBlank() }
                if (chosen != null && chosen != preferences.playlistNames[fingerprint]) {
                    onPreferencesChange(
                        preferences.copy(
                            playlistNames = preferences.playlistNames + (fingerprint to chosen),
                        ),
                    )
                }
                val session = DesktopSession(
                    credentials = credentials,
                    account = Account(
                        id = credentials.accountId,
                        username = "Playlist",
                        displayName = chosen ?: preferences.playlistNames[fingerprint],
                        serverUrl = outcome.url,
                        status = AccountStatus.Active,
                        expiresAtEpochSeconds = null,
                        activeConnections = null,
                        maximumConnections = null,
                        serverTimezone = null,
                        allowedOutputFormats = emptySet(),
                        lastValidatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                    playlistUrl = outcome.url,
                )
                // A playlist over plain HTTP still puts the whole evening's viewing in the clear,
                // even with no password in it, so it meets the same warning an HTTP account does.
                if (outcome.url.startsWith("http://")) cleartext = session else onSignedIn(session)
            }
        }
    }

    fun submit() {
        if (busy || !complete) return
        busy = true
        error = null
        scope.launch {
            if (mode == SignInMode.Playlist) {
                submitPlaylist()
                busy = false
                return@launch
            }
            val outcome = when (mode) {
                SignInMode.Details -> client.signIn(server, username, password)
                SignInMode.Link -> client.signInWithLink(link)
                SignInMode.Playlist -> throw IllegalStateException("handled above")
            }
            when (val result = outcome) {
                is SignInResult.Ok -> finish(result)
                // The caret goes to the field the answer implicates. A refusal is about the
                // credentials and an unreachable host is about the address, and in both cases the
                // viewer's next keystroke belongs somewhere specific rather than wherever the
                // pointer left it.
                is SignInResult.BadServer -> {
                    error = "That server address is not usable."
                    runCatching { firstField.requestFocus() }
                }
                // The reason is a name from the parser and the link is a credential, so neither is
                // put on screen. What the viewer needs is which half of the line is wrong.
                is SignInResult.BadLink -> {
                    error = "That playlist link could not be read. It should be the whole line " +
                        "your provider gave you, ending in get.php or player_api.php with a " +
                        "username and password in it."
                }
                is SignInResult.Unreachable -> {
                    error = "The server could not be reached."
                    runCatching { firstField.requestFocus() }
                }
                SignInResult.Rejected -> {
                    // The provider has said these are wrong, so a stored copy of them is wrong too
                    // and retrying it every launch would be a client that fails silently for ever.
                    // An unreachable server is left alone: that is the network, not the account.
                    vault.forget()
                    staySignedIn = false
                    error = "The provider did not accept those details."
                    // The text is left alone: the mistake may have been the username, and clearing
                    // a password nobody has said is wrong makes the retry longer than the mistake.
                    runCatching { passwordField.requestFocus() }
                }
                is SignInResult.Refused -> {
                    vault.forget()
                    staySignedIn = false
                    // The same wording browsing uses, because it is the same answer: the account
                    // rather than the request, and no guess about which of its reasons.
                    error = providerRefusedMessage(result.code)
                    runCatching { passwordField.requestFocus() }
                }
            }
            busy = false
            resuming = false
        }
    }

    /**
     * Signs in with what was kept, once, at launch.
     *
     * Declared after [submit] because a local function has to exist before it is named, and placed
     * here rather than in the window above so that everything about signing in — the two ways in,
     * the failure wording, the caret — stays in one screen.
     */
    LaunchedEffect(Unit) {
        val stored = vault.load() ?: run {
            resuming = false
            return@LaunchedEffect
        }
        if (stored.isLink) {
            mode = SignInMode.Link
            link = stored.link
        } else {
            server = stored.server
            username = stored.username
            password = stored.password
        }
        staySignedIn = true
        submit()
    }

    Box(Modifier.fillMaxSize().brandBackdrop()) {
        Column(
            modifier = Modifier.align(Alignment.Center).width(460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlowText("Killua IPTV", style = MaterialTheme.typography.displaySmall, glowRadius = 36f)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your television, beautifully organized.",
                style = MaterialTheme.typography.bodyLarge,
                color = InkMuted,
            )
            Spacer(Modifier.height(32.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(NightRaised.copy(alpha = 0.88f))
                    .border(1.dp, Violet.copy(alpha = 0.28f), RoundedCornerShape(20.dp))
                    // Enter submits, from any of the three fields. A sign-in form where the return
                    // key does nothing is a form that has to be aimed at.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            submit()
                            true
                        } else {
                            false
                        }
                    }
                    .padding(28.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    SignInMode.entries.forEach { entry ->
                        ModeTab(entry.label, selected = mode == entry) {
                            mode = entry
                            error = null
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))

                when (mode) {
                    SignInMode.Details -> {
                        BrandField(
                            value = server,
                            onValueChange = { server = it; error = null },
                            label = "Server",
                            focusRequester = firstField,
                        )
                        Spacer(Modifier.height(12.dp))
                        BrandField(username, { username = it; error = null }, "Username")
                        Spacer(Modifier.height(12.dp))
                        BrandField(
                            value = password,
                            onValueChange = { password = it; error = null },
                            label = "Password",
                            masked = !passwordShown,
                            focusRequester = passwordField,
                            trailing = {
                                Icon(
                                    imageVector = if (passwordShown) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = if (passwordShown) {
                                        "Hide the password"
                                    } else {
                                        "Show the password"
                                    },
                                    tint = InkMuted,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .clickable { passwordShown = !passwordShown }
                                        .padding(5.dp),
                                )
                            },
                        )
                    }
                    // Masked by default like the password, because that is what it is: the whole
                    // line carries the username and password in its query. The eye is there because
                    // a pasted link is worth checking once, and unlike a password it is too long to
                    // recognise by its shape.
                    SignInMode.Link -> BrandField(
                        value = link,
                        onValueChange = { link = it; error = null },
                        label = "Provider link",
                        masked = !linkShown,
                        focusRequester = firstField,
                        trailing = {
                            Icon(
                                imageVector = if (linkShown) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (linkShown) {
                                    "Hide the link"
                                } else {
                                    "Show the link"
                                },
                                tint = InkMuted,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .clickable { linkShown = !linkShown }
                                    .padding(5.dp),
                            )
                        },
                    )

                    // Not masked, unlike the provider link above. A public playlist address is not
                    // a secret and hiding it would make it harder to check for a typo, which is the
                    // only thing that goes wrong here. A provider's own playlist address *is* a
                    // credential, and the honest place for that is the link field beside this one.
                    SignInMode.Playlist -> BrandField(
                        value = playlist,
                        onValueChange = { playlist = it; error = null },
                        label = "Playlist address",
                        focusRequester = firstField,
                    )
                }

                Spacer(Modifier.height(12.dp))
                // Optional, and on both ways in. A provider's username is not a name for a
                // television, and someone with two accounts should be able to tell them apart at a
                // glance rather than by remembering which login they used.
                BrandField(playlistName, { playlistName = it }, "Playlist name (optional)")

                // Only where something can seal it. On a Mac the honest answer is to say nothing
                // and ask for the password, rather than to offer a box that writes it in the clear.
                if (vault.isSupported && mode != SignInMode.Playlist) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .focusRing(RoundedCornerShape(10.dp))
                            .clickable { staySignedIn = !staySignedIn }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = staySignedIn,
                            onCheckedChange = { staySignedIn = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = VioletBright,
                                uncheckedColor = InkMuted,
                                checkmarkColor = Night,
                            ),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Stay signed in on this computer",
                            color = Ink,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "Your details are sealed with Windows' own protection and can only be " +
                            "read back by this Windows account — not by anyone else on this " +
                            "machine, and not on another one. Leave it off and nothing is written: " +
                            "you type it again next launch. Either way your watch history, list " +
                            "and marks stay where they are — they do not depend on this.",
                        color = InkMuted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }

                Spacer(Modifier.height(22.dp))

                Button(
                    enabled = !busy && complete,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VioletBright,
                        contentColor = Night,
                    ),
                    onClick = { submit() },
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Night,
                        )
                        if (resuming) {
                            Spacer(Modifier.width(12.dp))
                            Text("Signing you back in…", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text("Sign in", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            cleartext?.let { session ->
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(NightRaised)
                        .border(1.dp, Violet.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(18.dp),
                ) {
                    Text(
                        "This account uses HTTP",
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Your username and password travel unencrypted, and so does everything " +
                            "you watch. Anyone between this machine and the provider can read " +
                            "both. The provider offered no HTTPS, so this is their choice rather " +
                            "than one this client can fix.",
                        color = InkMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Button(
                            onClick = { onSignedIn(session) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VioletBright,
                                contentColor = Night,
                            ),
                        ) {
                            Text("Continue anyway", fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { cleartext = null }) {
                            Text("Back", color = InkMuted)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                when (mode) {
                    SignInMode.Details ->
                        "The server is the address your provider gave you, with its port if it " +
                            "has one — for example http://provider.example:8080."
                    SignInMode.Link ->
                        "Paste the whole playlist line your provider gave you, the one ending in " +
                            "get.php or player_api.php. The address, username and password are " +
                            "read out of it; nothing is downloaded and nothing is written down."
                    SignInMode.Playlist ->
                        "The address of an .m3u playlist. There is no account and no password — " +
                            "a playlist carries channels only, so films and series stay away " +
                            "while one is open."
                },
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Your username and password are never written to disk, so you sign in again next " +
                    "time. What you have marked and how far you have watched is saved locally.",
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
                textAlign = TextAlign.Center,
            )

            // Said on the way in rather than buried in Settings, because it is the one thing about
            // this program someone should know before they use it rather than after.
            Spacer(Modifier.height(20.dp))
            Text(
                "Killua IPTV is a player and nothing else. It carries no channels, films or " +
                    "accounts of its own, has no directory of providers, and unlocks nothing. " +
                    "Use it only with an account you are entitled to use — what you watch through " +
                    "it is between you and whoever sold you that account.",
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Killua IPTV · developed by MyNameIsKillua",
                style = MaterialTheme.typography.labelSmall,
                color = InkMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One of the two ways in, as a tab.
 *
 * Chips rather than a dropdown: there are two, both are one word away from obvious, and a menu that
 * has to be opened to find out what the alternatives are is a menu that hides the alternative from
 * everyone who did not already know it was there.
 */
@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) VioletBright else InkMuted,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Violet.copy(alpha = 0.22f) else Color.Transparent)
            .focusRing(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun BrandField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    masked: Boolean = false,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        trailingIcon = trailing,
        visualTransformation =
            if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VioletBright,
            unfocusedBorderColor = Violet.copy(alpha = 0.3f),
            focusedLabelColor = VioletBright,
            unfocusedLabelColor = InkMuted,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            cursorColor = VioletBright,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    )
}

/**
 * The provider's answer, as the account model the shared code expects.
 *
 * `XtreamStreamUrlFactory.selectFormat` needs the advertised output formats, which is the only
 * reason this mapping exists. The id is fixed because this client holds one account and writes
 * nothing.
 */
private fun RemoteAccount.toAccount(
    credentials: XtreamCredentials,
    displayName: String? = null,
) = Account(
    id = credentials.accountId,
    username = username ?: credentials.username,
    displayName = displayName,
    serverUrl = credentials.serverUrl,
    status = status,
    expiresAtEpochSeconds = expiresAtEpochSeconds,
    activeConnections = activeConnections,
    maximumConnections = maximumConnections,
    serverTimezone = serverTimezone,
    allowedOutputFormats = allowedOutputFormats,
    lastValidatedAtEpochMillis = System.currentTimeMillis(),
)

/**
 * The platform's own file dialog.
 *
 * `java.awt.FileDialog` rather than Swing's chooser because on Windows it is the actual Explorer
 * dialog, which is what someone expects when an application asks them for a file.
 */
private fun chooseFile(
    parent: java.awt.Frame,
    save: Boolean,
    suggested: String? = null,
): java.io.File? {
    val mode = if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD
    val dialog = java.awt.FileDialog(parent, if (save) "Export your data" else "Import your data", mode)
    suggested?.let { dialog.file = it }
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return java.io.File(directory, name)
}

/**
 * Reads a file the viewer picked, refusing anything absurd.
 *
 * The same ceiling the phone's import uses. An export of a large library is a few hundred kilobytes;
 * anything past thirty-two megabytes is a file somebody chose by mistake, and reading it whole into
 * memory to discover that would be the client's fault rather than theirs.
 */
private fun java.io.File.readTextBounded(): String? = runCatching {
    if (length() > MAX_IMPORT_BYTES) null else readText()
}.getOrNull()

private const val MAX_IMPORT_BYTES = 32L * 1024L * 1024L

/** Dated, so successive exports sit beside each other rather than replacing one another. */
private fun suggestedExportName(): String =
    "killua-iptv-data-" + java.time.LocalDate.now() + ".json"

/**
 * The application icon, decoded the same way provider artwork is.
 *
 * Skia rather than Compose's resource loader, which is deprecated in favour of a resources library
 * this module has no other use for. Null if it is somehow missing: an icon is not worth failing to
 * start over.
 */
private fun loadAppIcon(): Painter? = runCatching {
    val bytes = VlcVideoPlayer::class.java.getResourceAsStream("/icon.png")!!.use { it.readBytes() }
    BitmapPainter(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

/** Five points a press: fine enough to land where you meant, coarse enough to get there. */
private const val VOLUME_STEP = 5
