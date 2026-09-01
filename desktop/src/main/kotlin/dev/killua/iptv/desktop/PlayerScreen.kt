package dev.killua.iptv.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.killua.iptv.domain.epg.EpgSelection
import dev.killua.iptv.domain.model.EpgEntry
import kotlinx.coroutines.delay

/**
 * The player, with the whole content area to itself.
 *
 * Controls sit on the picture rather than beside it, which is what the rendering approach was chosen
 * for in the first place: the video is ordinary Compose content, so an overlay is just Compose.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerView(
    player: VlcVideoPlayer,
    title: String?,
    failure: String?,
    resumedFrom: Long?,
    guide: List<EpgEntry>,
    queue: List<BrowseItem>,
    playingId: String?,
    nowOn: (BrowseItem, nowEpochSeconds: Long) -> String?,
    upNext: BrowseItem?,
    onPlayNext: () -> Unit,
    onStep: (Int) -> Unit,
    onSwitch: (BrowseItem) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onTrackChosen: (TrackOption, audio: Boolean) -> Unit,
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    onStartOver: () -> Unit,
    onRetry: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var status by remember { mutableStateOf(PlaybackStatus()) }
    var scrubbing by remember { mutableStateOf<Long?>(null) }
    var switching by remember { mutableStateOf(false) }
    // Whether a frame has ever arrived for what is playing now. Not the same question as "is it
    // playing": libvlc reports itself as playing while it is still opening the stream, which is
    // exactly the stretch a viewer needs to be told about.
    var hasPicture by remember(title) { mutableStateOf(false) }
    /**
     * Whether the chrome is on show, and when the pointer last said anything.
     *
     * A control bar that never leaves is the whole of what a viewer notices about a client in
     * fullscreen: the picture is the thing, and a violet strip across the bottom of it is not. So
     * everything drawn over the video goes away after a few still seconds and comes back on the
     * first movement of the pointer — which is what every player does, and what this one did not.
     */
    var controlsVisible by remember { mutableStateOf(true) }
    var lastPointerActivity by remember { mutableStateOf(0L) }

    fun stirred() {
        lastPointerActivity = System.nanoTime()
        controlsVisible = true
    }
    // Channels or titles: the same panel serves both, and the word on the button should say which.
    val queueLabel = if (queue.all { it is BrowseItem.Channel || it.isLiveIndexed() }) {
        "Channels"
    } else {
        "Titles"
    }

    /**
     * What puts the chrome away, and the four things that stop it.
     *
     * Paused, failed, not yet started, or with the switch panel open: in each of those the controls
     * are the thing being used, and hiding them would be the client taking away what someone is
     * reaching for. Only a picture that is actually moving gets the screen to itself.
     */
    LaunchedEffect(lastPointerActivity, status.isPlaying, failure, switching, hasPicture) {
        if (!chromeMayHide(
                isPlaying = status.isPlaying,
                hasFailure = failure != null,
                switching = switching,
                hasPicture = hasPicture,
            )
        ) {
            controlsVisible = true
            return@LaunchedEffect
        }
        delay(CONTROLS_LINGER_MS)
        controlsVisible = false
    }

    LaunchedEffect(title) {
        while (title != null) {
            status = player.snapshot()
            if (!hasPicture && player.frames.latest != null) hasPicture = true
            // libvlc drops the level and the rate with each new media, so the poll that reads where
            // playback is also puts back what the viewer chose.
            player.reapplyAudioLevel()
            player.reapplyRate()
            delay(500)
        }
        status = PlaybackStatus()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // On the outer box rather than on the picture, so a pointer moved across the control
            // bar counts as activity too: moves are not consumed by anything, so they reach here
            // from wherever they landed.
            .onPointerEvent(PointerEventType.Move) { stirred() },
    ) {
        VideoSurface(
            player.frames,
            Modifier
                .fillMaxSize()
                // A click on the picture pauses, which is what a click on a picture means
                // everywhere else. On the surface rather than on the box around it, so a click that
                // lands on the control bar — even on the empty space between its buttons — is not
                // also a pause. There is deliberately no double-click for fullscreen: Compose would
                // have to hold every single click back to see whether a second one followed, and a
                // pause that arrives a third of a second late is worse than a keyboard shortcut.
                .pointerInput(Unit) {
                    detectTapGestures {
                        stirred()
                        player.togglePause()
                    }
                }
                // The pointer goes with the chrome. A cursor sitting on a still frame is the same
                // complaint as a control bar sitting on one.
                .pointerHoverIcon(if (controlsVisible) PointerIcon.Default else BlankPointer),
            fill = player.fillsWindow,
        )

        // Between asking for a stream and the first frame there used to be nothing at all: a black
        // rectangle with working controls, which reads as a client that has done nothing. On a 4K
        // film from a busy provider that stretch is several seconds, and the honest thing to do
        // with it is say so. The watchdog behind this still speaks up if the wait becomes a
        // failure; this only covers the ordinary case where it is about to work.
        if (title != null && failure == null && !hasPicture) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(38.dp),
                    strokeWidth = 3.dp,
                    color = VioletBright,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Starting…",
                    color = InkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconPill(Icons.AutoMirrored.Filled.ArrowBack, "Back to browsing", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                title?.let {
                    Text(
                        it,
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        failure?.let { message ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            ) {
                Text(message, color = MaterialTheme.colorScheme.error)
                // Offered for a stream that would not open, not for a missing libvlc: asking the
                // same question again is only worth a button when the answer can change.
                if (onRetry != null && message != VLC_MISSING) {
                    Spacer(Modifier.height(14.dp))
                    TextPill("Try again", onClick = onRetry)
                }
            }
        }

        // Above the control bar rather than in it: this appears for half a minute and then goes, and
        // a row that grows a button for thirty seconds is a row nobody can aim at.
        upNext?.takeIf { failure == null && !switching }?.let { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 150.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(NightRaised.copy(alpha = 0.95f))
                    .clickable(onClick = onPlayNext)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(NightSoft),
                ) {
                    RemoteImage(
                        url = entry.artworkUrl,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = { ArtworkPlaceholder(entry.label) },
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "Up next",
                        color = VioletBright,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        entry.label,
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconPill(Icons.Default.SkipNext, "Play the next episode", onClick = onPlayNext)
            }
        }

        if (switching && failure == null) {
            SwitchPanel(
                label = queueLabel,
                entries = queue,
                playingId = playingId,
                nowOn = nowOn,
                // Deliberately stays open after a choice. Zapping is rarely one channel — the
                // highlight moves, the picture behind changes, and walking down the list is one
                // click a channel instead of three.
                onSelect = onSwitch,
                onClose = { switching = false },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        AnimatedVisibility(
            visible = title != null && failure == null && controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Night.copy(alpha = 0.82f))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                if (guide.isNotEmpty()) {
                    GuideStrip(guide)
                    Spacer(Modifier.height(12.dp))
                }

                if (status.isSeekable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatDuration(scrubbing ?: status.timeMs),
                            color = InkMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = (scrubbing ?: status.timeMs).toFloat(),
                            valueRange = 0f..status.lengthMs.toFloat(),
                            onValueChange = { scrubbing = it.toLong() },
                            onValueChangeFinished = {
                                scrubbing?.let(player::seekTo)
                                scrubbing = null
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = VioletBright,
                                activeTrackColor = VioletBright,
                                inactiveTrackColor = InkMuted.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                        )
                        Text(
                            formatDuration(status.lengthMs),
                            color = InkMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconPill(
                        icon = if (status.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        description = if (status.isPlaying) "Pause" else "Play",
                        prominent = true,
                        onClick = { player.togglePause() },
                    )
                    if (status.isSeekable) {
                        Spacer(Modifier.width(8.dp))
                        IconPill(
                            icon = Icons.Default.Replay10,
                            description = "Back ${secondsPhrase(skipBackSeconds)}",
                        ) { player.skip(-skipBackSeconds * 1_000L) }
                        Spacer(Modifier.width(8.dp))
                        IconPill(
                            icon = Icons.Default.Forward30,
                            description = "Forward ${secondsPhrase(skipForwardSeconds)}",
                        ) { player.skip(skipForwardSeconds * 1_000L) }
                    } else {
                        if (queue.size > 1) {
                            Spacer(Modifier.width(8.dp))
                            IconPill(Icons.Default.SkipPrevious, "Previous channel") { onStep(-1) }
                            Spacer(Modifier.width(8.dp))
                            IconPill(Icons.Default.SkipNext, "Next channel") { onStep(1) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "LIVE",
                            color = VioletBright,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    resumedFrom?.let {
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "resumed from ${formatDuration(it)}",
                            color = InkMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextPill("Start over", onClick = onStartOver)
                    }

                    Spacer(Modifier.weight(1f))
                    IconPill(
                        icon = if (player.fillsWindow) {
                            Icons.Default.FitScreen
                        } else {
                            Icons.Default.Crop
                        },
                        description = if (player.fillsWindow) "Fit the picture" else "Fill the window",
                        onClick = { player.toggleFill() },
                    )
                    Spacer(Modifier.width(8.dp))
                    // Offered only where a timeline is: a rate on a live stream drifts away from
                    // the broadcast and there is nothing to drift back to.
                    if (status.isSeekable) {
                        RateMenu(player)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (queue.size > 1) {
                        IconPill(
                            icon = Icons.AutoMirrored.Filled.List,
                            description = queueLabel,
                            onClick = { switching = !switching },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    VolumeControl(player)
                    Spacer(Modifier.width(8.dp))
                    TrackMenu(
                        icon = Icons.Default.Audiotrack,
                        label = "Audio",
                        tracks = { player.audioTracks() },
                        selectedId = status.audioTrack,
                        onSelect = {
                            player.setAudioTrack(it.id)
                            onTrackChosen(it, true)
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    TrackMenu(
                        icon = Icons.Default.Subtitles,
                        label = "Subtitles",
                        tracks = { player.subtitleTracks() },
                        selectedId = status.subtitleTrack,
                        onSelect = {
                            player.setSubtitleTrack(it.id)
                            onTrackChosen(it, false)
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    IconPill(
                        icon = if (fullscreen) {
                            Icons.Default.FullscreenExit
                        } else {
                            Icons.Default.Fullscreen
                        },
                        description = if (fullscreen) "Leave fullscreen" else "Fullscreen",
                        onClick = onToggleFullscreen,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconPill(Icons.Default.Close, "Stop", onClick = onBack)
                }
            }
        }
    }
}

/**
 * What else can be played, over the picture rather than instead of it.
 *
 * Zapping is the reason: going back to the grid, finding the category again and picking the next
 * channel is four actions for something that should be one, and it stops the stream in between. From
 * here the picture never goes away — the position of what was playing is written down first, so a
 * film switched away from is still resumable.
 */
@Composable
private fun SwitchPanel(
    label: String,
    entries: List<BrowseItem>,
    playingId: String?,
    nowOn: (BrowseItem, nowEpochSeconds: Long) -> String?,
    onSelect: (BrowseItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The panel owns its clock so a programme that ends while it is open stops being called current.
    var nowEpochSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            nowEpochSeconds = System.currentTimeMillis() / 1000L
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(Night.copy(alpha = 0.94f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            IconPill(Icons.Default.Close, "Close", onClick = onClose)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // The control bar is drawn over the foot of this panel, so the list keeps room to scroll
            // past it rather than hiding its last few entries for good.
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 160.dp),
        ) {
            items(entries, key = { it.queueKey }) { entry ->
                val playing = entry.id == playingId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (playing) Violet.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 56.dp, height = 34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NightSoft),
                    ) {
                        RemoteImage(
                            url = entry.artworkUrl,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = { ArtworkPlaceholder(entry.label) },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            entry.label,
                            color = if (playing) VioletBright else Ink,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Zapping blind is what a channel list without a programme is. Where the
                        // guide has already been read, this is that knowledge showing up where the
                        // decision is made rather than only on a page nobody is on.
                        nowOn(entry, nowEpochSeconds)?.let { programme ->
                            Text(
                                programme,
                                color = InkMuted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Speaker and slider.
 *
 * Both read the player's own state rather than a local copy, because the same two values are changed
 * from the keyboard at the window; a private copy here would drift the moment someone used a key.
 */
@Composable
private fun VolumeControl(player: VlcVideoPlayer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconPill(
            icon = when {
                player.isMuted || player.volume == 0 -> Icons.AutoMirrored.Filled.VolumeOff
                player.volume < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            description = if (player.isMuted) "Unmute" else "Mute",
            onClick = { player.toggleMute() },
        )
        Slider(
            value = if (player.isMuted) 0f else player.volume.toFloat(),
            valueRange = 0f..100f,
            onValueChange = { player.volume = it.toInt() },
            colors = SliderDefaults.colors(
                thumbColor = VioletBright,
                activeTrackColor = VioletBright,
                inactiveTrackColor = InkMuted.copy(alpha = 0.3f),
            ),
            modifier = Modifier.width(110.dp).padding(start = 10.dp),
        )
    }
}

/**
 * How fast this title plays.
 *
 * Shown as a pill carrying the current rate rather than an icon, because the one thing a viewer
 * needs to know here is whether anything is off normal — a menu that hides that is how a film ends
 * up watched at 1.25 for an hour by accident.
 */
@Composable
private fun RateMenu(player: VlcVideoPlayer) {
    var open by remember { mutableStateOf(false) }

    Box {
        TextPill(if (player.rate == 1f) "1x" else "${trimmedRate(player.rate)}x") { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(NightRaised),
        ) {
            RATES.forEach { rate ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${trimmedRate(rate)}x",
                            color = if (rate == player.rate) VioletBright else Ink,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        player.rate = rate
                        open = false
                    },
                )
            }
        }
    }
}

/** `1.5` rather than `1.5x` of `1.0`, because a whole number should read as one. */
private fun trimmedRate(rate: Float): String =
    if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()

private val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * A track picker that asks libvlc for its options only when opened.
 *
 * Descriptions exist only once the container has been parsed, so a cached list would be empty
 * exactly when the viewer first looks, and a stream that gains a track would leave a stale menu.
 */
@Composable
private fun TrackMenu(
    icon: ImageVector,
    label: String,
    tracks: () -> List<TrackOption>,
    selectedId: Int,
    onSelect: (TrackOption) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<List<TrackOption>>(emptyList()) }

    Box {
        IconPill(icon, label) {
            options = tracks()
            open = true
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(NightRaised),
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Nothing to choose", color = InkMuted) },
                    onClick = { open = false },
                )
            }
            val labels = readableTrackLabels(options)
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            labels[index],
                            color = if (option.id == selectedId) VioletBright else Ink,
                            fontWeight = if (option.id == selectedId) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                        )
                    },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * What is on now, how far through it is, and what follows.
 *
 * The clock is re-read every twenty seconds, so the bar creeps forward on a channel left running
 * rather than freezing where it was when the guide arrived. Which entry is current comes from the
 * shared `EpgSelection`, which already knows what to do with the overlapping entries, gaps and stale
 * listings providers send.
 */
@Composable
private fun GuideStrip(guide: List<EpgEntry>) {
    var nowEpochSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(guide) {
        while (true) {
            nowEpochSeconds = System.currentTimeMillis() / 1000L
            delay(20_000)
        }
    }

    val current = EpgSelection.nowPlaying(guide, nowEpochSeconds)
    val next = EpgSelection.upNext(guide, nowEpochSeconds)
    val progress = EpgSelection.progress(guide, nowEpochSeconds)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                current?.title ?: "No guide for this channel",
                color = if (current != null) Ink else InkMuted,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            current?.let {
                Spacer(Modifier.width(10.dp))
                Text(
                    clockOf(it.startEpochSeconds) + " - " + clockOf(it.endEpochSeconds),
                    color = InkMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            next?.let {
                Spacer(Modifier.weight(1f))
                Text(
                    "next  " + clockOf(it.startEpochSeconds) + "  " + it.title,
                    color = InkMuted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        progress?.let {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { it },
                color = VioletBright,
                trackColor = InkMuted.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
    }
}

/**
 * Whether the chrome is allowed to go away at all.
 *
 * Four states say no, and they have one thing in common: in each of them the controls are what the
 * viewer is using rather than what is in their way. A paused film is one somebody stopped on
 * purpose; a failure is a message and a *Try again*; an open switch panel is a list being read; and
 * a title that has not produced a frame yet is a spinner, not a picture. Only a picture that is
 * actually moving gets the screen to itself.
 */
internal fun chromeMayHide(
    isPlaying: Boolean,
    hasFailure: Boolean,
    switching: Boolean,
    hasPicture: Boolean,
): Boolean = isPlaying && !hasFailure && !switching && hasPicture

/**
 * How long the chrome stays after the pointer stops.
 *
 * Three seconds: long enough to read a timeline and reach a button that has just been shown, short
 * enough that a still picture is a picture rather than a picture with a bar across it.
 */
private const val CONTROLS_LINGER_MS = 3_000L

/**
 * A cursor that is not there.
 *
 * AWT has no "hide the pointer", so the way everyone does it is a one-pixel transparent image. Built
 * once: `createCustomCursor` goes to the window system each time it is called.
 */
private val BlankPointer = PointerIcon(
    java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
        java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
        java.awt.Point(0, 0),
        "blank",
    ),
)
