package dev.killua.iptv.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.killua.iptv.domain.epg.EpgSelection
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.VideoScaleMode
import dev.killua.iptv.domain.model.displayLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.killua.iptv.core.player.PlayerConnection
import kotlinx.coroutines.delay

@Composable
fun PlayerRoute(
    viewModel: PlayerViewModel,
    connection: PlayerConnection,
    isInPictureInPictureMode: Boolean,
    videoScaleMode: VideoScaleMode = VideoScaleMode.Fit,
    seekIncrementMs: Long = 10_000L,
    holdPlaybackSpeed: Float = 2f,
    onBack: () -> Unit,
    onPlayEpisode: (episodeId: String) -> Unit = {},
    onVideoScaleModeChange: (VideoScaleMode) -> Unit = {},
    onBrightnessSettled: (Float) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller by connection.controller.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        viewModel.onScreenVisible()
        onDispose { viewModel.onScreenHidden() }
    }
    // Autoplay is a navigation, not a player command: the route is keyed by content, so the next
    // episode gets its own screen state instead of one being mutated underneath.
    LaunchedEffect(state.autoAdvanceTo) {
        val next = state.autoAdvanceTo ?: return@LaunchedEffect
        viewModel.consumeAutoAdvance()
        onPlayEpisode(next.id)
    }
    PlayerScreen(
        state = state,
        controller = controller,
        isInPictureInPictureMode = isInPictureInPictureMode,
        videoScaleMode = videoScaleMode,
        seekIncrementMs = seekIncrementMs,
        holdPlaybackSpeed = holdPlaybackSpeed,
        onVideoScaleModeChange = onVideoScaleModeChange,
        onBrightnessSettled = onBrightnessSettled,
        onBack = onBack,
        onRetry = viewModel::retry,
        onSeekBy = viewModel::seekBy,
        onTogglePlayPause = viewModel::togglePlayPause,
        onTemporarySpeedStart = viewModel::beginTemporarySpeed,
        onTemporarySpeedEnd = viewModel::endTemporarySpeed,
        onPlayNextEpisode = { state.nextEpisode?.let { onPlayEpisode(it.id) } },
        onPlayPreviousEpisode = { state.previousEpisode?.let { onPlayEpisode(it.id) } },
        onCancelAutoAdvance = viewModel::cancelAutoAdvance,
    )
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    controller: MediaController?,
    isInPictureInPictureMode: Boolean,
    videoScaleMode: VideoScaleMode,
    seekIncrementMs: Long,
    holdPlaybackSpeed: Float,
    onBack: () -> Unit,
    onVideoScaleModeChange: (VideoScaleMode) -> Unit = {},
    onBrightnessSettled: (Float) -> Unit = {},
    onRetry: () -> Unit,
    onSeekBy: (Long) -> Boolean,
    onTogglePlayPause: () -> Boolean,
    onTemporarySpeedStart: (Float) -> Boolean,
    onTemporarySpeedEnd: () -> Unit,
    onPlayNextEpisode: () -> Unit = {},
    onPlayPreviousEpisode: () -> Unit = {},
    onCancelAutoAdvance: () -> Unit = {},
) {
    val hostView = LocalView.current
    val levelControls = remember(hostView) { playerLevelControls(hostView.context) }
    // Immersive mode and the brightness override are **not** released here. Both belong to the
    // player route rather than to one player screen: moving to the next episode replaces the
    // screen, and Compose disposes the outgoing one after the incoming one is already running, so a
    // teardown here undid the setup its own successor had just done. `PlayerRouteWindow` in
    // IptvApp owns both and hands them back when the route itself is left.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        var controllerVisible by remember { mutableStateOf(false) }
        var levelIndicator by remember { mutableStateOf<PlayerLevelIndicator?>(null) }
        var levelDragActive by remember { mutableStateOf(false) }
        var restingLevels by remember { mutableStateOf(0f to 0f) }
        var controlRowHeight by remember { mutableStateOf(0.dp) }
        var controlRowBottom by remember { mutableStateOf(0.dp) }
        var draggedBrightness by remember { mutableStateOf<Float?>(null) }
        val density = LocalDensity.current
        // The drag covers exactly the track the viewer can see, and only a band around that same
        // track responds at all. Both come from the measurements below, so the slider cannot end
        // up somewhere other than where dragging works. The track scales with the picture, so it
        // stays a modest thing on a short landscape phone.
        val trackHeight = playerLevelTrackHeightDp(maxHeight.value).dp
        val levelRangePx = with(density) { trackHeight.toPx() }
        val levelBands = remember(density, trackHeight) {
            with(density) {
                PlayerLevelBands(
                    widthPx = LEVEL_BAND_WIDTH.toPx(),
                    heightPx = (trackHeight + LEVEL_BAND_EXTRA_HEIGHT).toPx(),
                )
            }
        }
        LaunchedEffect(levelIndicator, levelDragActive) {
            if (levelIndicator == null || levelDragActive) return@LaunchedEffect
            delay(900)
            levelIndicator = null
        }
        // Read when the controls appear, so the resting sliders show the real values rather than
        // whatever the last drag left. The hardware keys can move volume while they are up; that
        // is the one case this deliberately does not chase.
        LaunchedEffect(controllerVisible, levelDragActive) {
            // Also on first composition, so the error screen's resting sliders are right without
            // the controls ever having appeared.
            if (!levelDragActive) {
                restingLevels = levelControls.brightness() to levelControls.volume()
            }
        }
        AndroidView(
            factory = { context ->
                GestureAwarePlayerView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    playerView.useController = true
                    playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    playerView.controllerAutoShow = false
                    playerView.controllerHideOnTouch = true
                    // Media3 must not run the auto-hide itself: it announces a hide only once its
                    // fade has finished, which left this screen's own overlays sitting there after
                    // the picture had cleared. GestureAwarePlayerView owns the timeout instead.
                    playerView.controllerShowTimeoutMs = 0
                    playerView.setShowSubtitleButton(true)
                    // The service holds a single MediaItem, so the stock previous/next arrows
                    // could never do anything. The app offers its own Next episode control; two
                    // next buttons where one is inert is worse than either alone.
                    playerView.setShowPreviousButton(false)
                    playerView.setShowNextButton(false)
                    playerView.hideController()
                    onControllerVisibilityChanged = { controllerVisible = it }
                    keepScreenOn = true
                }
            },
            update = { gestureView ->
                if (gestureView.playerView.player !== controller) {
                    gestureView.playerView.player = controller
                    gestureView.playerView.hideController()
                }
                val shouldUseController = !isInPictureInPictureMode
                if (gestureView.playerView.useController != shouldUseController) {
                    gestureView.playerView.useController = shouldUseController
                    gestureView.hideControllerAndCancelGesture()
                }
                gestureView.playerView.resizeMode = videoScaleMode.resizeMode
                gestureView.seekIncrementMs = seekIncrementMs
                gestureView.holdPlaybackSpeed = holdPlaybackSpeed
                gestureView.onSeekBy = onSeekBy
                gestureView.onTogglePlayPause = onTogglePlayPause
                gestureView.onTemporarySpeedStart = onTemporarySpeedStart
                gestureView.onTemporarySpeedEnd = onTemporarySpeedEnd
                gestureView.onControllerVisibilityChanged = { controllerVisible = it }
                gestureView.onControlRowBoundsChanged = { heightPx, bottomPx ->
                    with(density) {
                        controlRowHeight = heightPx.toDp()
                        controlRowBottom = bottomPx.toDp()
                    }
                }
                gestureView.levelDragEnabled = !isInPictureInPictureMode
                gestureView.levelDragRangePx = levelRangePx
                gestureView.levelBands = levelBands
                gestureView.currentLevelOf = { target ->
                    when (target) {
                        PlayerLevelTarget.Brightness -> levelControls.brightness()
                        PlayerLevelTarget.Volume -> levelControls.volume()
                    }
                }
                gestureView.onLevelChange = { target, level ->
                    when (target) {
                        PlayerLevelTarget.Brightness -> {
                            levelControls.setBrightness(level)
                            draggedBrightness = level
                        }
                        PlayerLevelTarget.Volume -> levelControls.setVolume(level)
                    }
                    levelIndicator = PlayerLevelIndicator(target, level)
                    levelDragActive = true
                }
                // Stored when the finger lifts, not on every frame of the drag: a single stroke
                // would otherwise be a few hundred writes for one decision.
                gestureView.onLevelDragEnd = {
                    levelDragActive = false
                    draggedBrightness?.let(onBrightnessSettled)
                    draggedBrightness = null
                }
            },
            onRelease = { gestureView ->
                gestureView.hideControllerAndCancelGesture()
                gestureView.onControllerVisibilityChanged = {}
                gestureView.onControlRowBoundsChanged = { _, _ -> }
                gestureView.currentLevelOf = { 0f }
                gestureView.onLevelChange = { _, _ -> }
                gestureView.onLevelDragEnd = {}
                gestureView.playerView.player = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        val error = state.startError ?: state.snapshot.error
        // One fade for everything this screen draws alongside Media3's controls, started at the
        // same instant Media3 starts its own — the view reports the hide when it asks for it rather
        // than when the animation ends. Before that these overlays popped away a beat later, which
        // read as them lingering. Shown on an error too: that is a screen the viewer is looking at.
        val chromeAlpha by animateFloatAsState(
            targetValue = if (controllerVisible || error != null) 1f else 0f,
            animationSpec = tween(durationMillis = CHROME_FADE_MS),
            label = "playerChromeAlpha",
        )
        if (!isInPictureInPictureMode && chromeAlpha > 0f) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp)
                    .alpha(chromeAlpha),
                color = Color.Black.copy(alpha = 0.56f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                    // Only alongside the controls, so neither the title nor the guide ever sits
                    // over the picture. A live channel gets both; a Movie or episode gets the
                    // title alone, which is the one thing the stock controller never names.
                    val title = state.title?.takeIf { it.isNotBlank() }
                    if (title != null || state.epg.isNotEmpty()) {
                        Column(modifier = Modifier.padding(end = 14.dp).widthIn(max = 320.dp)) {
                            if (title != null) {
                                Text(
                                    text = title,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (state.epg.isNotEmpty()) {
                                NowAndNext(
                                    entries = state.epg,
                                    modifier = Modifier.padding(
                                        top = if (title == null) 0.dp else 4.dp,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Opposite the Back overlay, which is the one corner nothing else uses. It states the
        // current mode rather than hiding it behind an icon, because "Zoom" and "Stretch" look
        // alike on a stream whose shape you cannot predict.
        if (!isInPictureInPictureMode && chromeAlpha > 0f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .alpha(chromeAlpha),
                onClick = { onVideoScaleModeChange(videoScaleMode.next()) },
                color = Color.Black.copy(alpha = 0.56f),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.AspectRatio,
                        contentDescription = "Picture size, currently ${videoScaleMode.label}",
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "  ${videoScaleMode.label}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // In Media3's own bottom row, between the clock and the subtitle button. They are transport
        // controls like the ones already there, and the pair that used to float above the bar sat
        // in the middle of the picture. The row is measured rather than guessed at, so the two sets
        // of controls line up whatever height Media3 gives its bar.
        //
        // Shown on an error as well as with the controls, which is the same rule the Back overlay
        // follows: an episode that will not play is exactly when skipping it is worth offering.
        if (!isInPictureInPictureMode && chromeAlpha > 0f &&
            (state.nextEpisode != null || state.previousEpisode != null)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = controlRowBottom)
                    // Never below the minimum touch target, however short Media3 makes its row.
                    .height(
                        (controlRowHeight.takeIf { it > 0.dp } ?: FALLBACK_CONTROL_ROW_HEIGHT)
                            .coerceAtLeast(48.dp),
                    )
                    .alpha(chromeAlpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icons rather than labelled buttons, because that is what everything else in this
                // row is. They are only ever offered when the episode they point at exists.
                if (state.previousEpisode != null) {
                    IconButton(onClick = onPlayPreviousEpisode) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous episode",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                if (state.nextEpisode != null) {
                    IconButton(onClick = onPlayNextEpisode) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next episode",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }

        // Drawn here rather than inside the video view, so they sit above every other overlay
        // instead of behind the error card. Never in Picture-in-Picture, like the rest.
        //
        // While the controls are up both sliders rest at low opacity, so the viewer can see where
        // the gesture lives before reaching for it. The one being dragged goes fully opaque.
        if (!isInPictureInPictureMode) {
            PlayerLevelTarget.entries.forEach { target ->
                val dragged = levelIndicator?.takeIf { it.target == target }
                val restingAlpha = RESTING_SLIDER_ALPHA * chromeAlpha
                if (dragged == null && restingAlpha <= 0f) return@forEach
                val level = dragged?.level ?: when (target) {
                    PlayerLevelTarget.Brightness -> restingLevels.first
                    PlayerLevelTarget.Volume -> restingLevels.second
                }
                PlayerLevelSlider(
                    target = target,
                    level = level,
                    trackHeight = trackHeight,
                    modifier = Modifier
                        .align(
                            when (target) {
                                PlayerLevelTarget.Brightness -> Alignment.CenterStart
                                PlayerLevelTarget.Volume -> Alignment.CenterEnd
                            },
                        )
                        .safeDrawingPadding()
                        .padding(horizontal = LEVEL_EDGE_INSET)
                        .alpha(if (dragged != null) 1f else restingAlpha),
                )
            }
        }

        // The countdown replaces nothing: it appears over the finished picture whether or not the
        // controls are up, because an ending the viewer walked away from is exactly when it has to
        // be visible. Still never in Picture-in-Picture.
        val countdown = state.autoAdvanceCountdownSeconds
        if (!isInPictureInPictureMode && countdown != null && state.nextEpisode != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 150.dp),
                color = Color.Black.copy(alpha = 0.78f),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.widthIn(max = 260.dp)) {
                        Text(
                            text = "Next episode in $countdown",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = state.nextEpisode.displayLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onCancelAutoAdvance) {
                        Text("Cancel", color = Color.White)
                    }
                }
            }
        }

        if (error != null && !isInPictureInPictureMode) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color(0xEE17151F),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Stream could not be played", style = MaterialTheme.typography.titleLarge)
                    Text(error.userMessage(), modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
                    Button(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, null)
                        Text("  Retry")
                    }
                }
            }
        } else if (state.isStarting && controller == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        }
    }
}

/**
 * The Media3 constant behind each mode.
 *
 * Kept here rather than on the enum so the domain model stays free of a player library: which
 * constant expresses "fill and crop" is a Media3 detail, not something the rest of the app knows.
 */
@get:OptIn(UnstableApi::class)
private val VideoScaleMode.resizeMode: Int
    get() = when (this) {
        VideoScaleMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoScaleMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoScaleMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

/** How far the slider sits from the edge of the picture. */
private val LEVEL_EDGE_INSET = 24.dp

/** The width of the drawn track. The band that responds to a touch is far wider; see below. */
private val LEVEL_TRACK_WIDTH = 5.dp

/** Used until Media3's bottom row has been measured, which is roughly the height it settles at. */
private val FALLBACK_CONTROL_ROW_HEIGHT = 60.dp

/** Close to Media3's own show/hide animation, which this screen's chrome now runs alongside. */
private const val CHROME_FADE_MS = 240

/**
 * How far in from each edge a touch still counts as reaching for the slider.
 *
 * Comfortably wider than the slider itself: it has to be findable without looking, but it is no
 * longer the whole half of the screen, which made it far too easy to trigger by accident.
 */
private val LEVEL_BAND_WIDTH = 108.dp

/** Added to the track height for the band, so the ends of the scale are not awkward to reach. */
private val LEVEL_BAND_EXTRA_HEIGHT = 96.dp

/** Visible enough to show where the gesture is, faint enough not to sit on top of the picture. */
private const val RESTING_SLIDER_ALPHA = 0.36f

/**
 * The brightness/volume slider.
 *
 * It shows what it is, where the level sits, and how much of the scale is left — the percentage
 * alone said none of that. [trackHeight] is also the drag distance, so what is drawn is exactly what
 * the gesture measures, and the band that responds to a touch is derived from the same measurement.
 *
 * Deliberately not a `Surface`: that installs a no-op pointer input to block touches behind it,
 * which meant the slider swallowed the very drag it advertises for as long as it was on screen —
 * that is, whenever the controls were up. A `Column` with a background is inert, so the touch
 * reaches the video view underneath, which is where the gesture lives.
 */
@Composable
private fun PlayerLevelSlider(
    target: PlayerLevelTarget,
    level: Float,
    trackHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val clamped = level.coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.large)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${playerLevelPercent(clamped)}%",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(LEVEL_TRACK_WIDTH)
                .height(trackHeight)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.26f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(LEVEL_TRACK_WIDTH)
                    .height(trackHeight * clamped)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
        Spacer(Modifier.height(8.dp))
        Icon(
            imageVector = when (target) {
                PlayerLevelTarget.Brightness -> Icons.Default.LightMode
                PlayerLevelTarget.Volume -> Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = target.cueLabel,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The window state that belongs to the player **route**: immersive system bars and the brightness
 * override.
 *
 * Deliberately not owned by `PlayerScreen`. Moving to the next episode replaces the player screen,
 * and Compose disposes the outgoing one *after* the incoming one is already running — so a teardown
 * inside the screen undid what its own successor had just set up. The visible symptoms were the
 * system bars coming back and the brightness snapping to the device setting one episode in.
 *
 * Brightness is held for as long as the route is, Picture-in-Picture included, so returning from PiP
 * does not land on a different brightness than leaving it. Immersive mode is not: PiP has no room
 * for it, which is why the two flags are separate.
 */
@Composable
internal fun PlayerRouteWindow(
    playerVisible: Boolean,
    isInPictureInPictureMode: Boolean,
    rememberedBrightness: Float? = null,
) {
    PlayerImmersiveSystemUi(
        routeActive = playerVisible,
        immersive = playerVisible && !isInPictureInPictureMode,
    )
    PlayerLandscapeLock(locked = playerVisible && !isInPictureInPictureMode)
    val hostView = LocalView.current
    val levelControls = remember(hostView) { playerLevelControls(hostView.context) }
    // Applied once per visit rather than continuously: the viewer may drag it somewhere else
    // while watching, and re-applying the stored value would fight them.
    LaunchedEffect(playerVisible, rememberedBrightness) {
        if (playerVisible && rememberedBrightness != null) {
            levelControls.setBrightness(rememberedBrightness)
        }
    }
    // Handed back on leaving the player, so no later screen inherits it, and on leaving the app.
    LaunchedEffect(playerVisible) { if (!playerVisible) levelControls.release() }
    DisposableEffect(levelControls) { onDispose { levelControls.release() } }
}

/**
 * Immersive system bars for the player route.
 *
 * Split deliberately in two. What to put back is captured **once per visit to the route**, while the
 * hiding follows [immersive], which also switches off for Picture-in-Picture. Reading the bar state
 * every time immersive mode toggled meant the trip back out of Picture-in-Picture captured "the bars
 * are already hidden" — and the restore on leaving the player then hid them for the rest of the
 * session, on every screen.
 */
/**
 * Turns the player sideways and lets the rest of the app alone.
 *
 * Video is wide; a portrait player wastes most of the screen on black. `SENSOR_LANDSCAPE` rather
 * than plain `LANDSCAPE` so the phone can still be held either way round. The previous orientation
 * is restored on the way out, so a viewer who had the app in portrait gets it back.
 *
 * Not applied in Picture-in-Picture, where the window shape is not this app's to decide, and only
 * for the route: an episode change keeps the same lock because the route never goes away.
 */
@Composable
private fun PlayerLandscapeLock(locked: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, locked) {
        val activity = view.context.findActivity()
        if (activity == null || !locked) return@DisposableEffect onDispose { }
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = previous }
    }
}

@Composable
private fun PlayerImmersiveSystemUi(routeActive: Boolean, immersive: Boolean) {
    val view = LocalView.current

    DisposableEffect(view, routeActive) {
        val window = view.context.findActivity()?.window
        if (window == null || !routeActive) return@DisposableEffect onDispose { }
        val insetsController = WindowCompat.getInsetsController(window, view)
        val previousBehavior = insetsController.systemBarsBehavior
        val initialInsets = ViewCompat.getRootWindowInsets(view)
        val statusBarsWereVisible = initialInsets?.isVisible(
            WindowInsetsCompat.Type.statusBars(),
        ) ?: true
        val navigationBarsWereVisible = initialInsets?.isVisible(
            WindowInsetsCompat.Type.navigationBars(),
        ) ?: true

        onDispose {
            if (statusBarsWereVisible) {
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
            if (navigationBarsWereVisible) {
                insetsController.show(WindowInsetsCompat.Type.navigationBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
            insetsController.systemBarsBehavior = previousBehavior
        }
    }

    DisposableEffect(view, immersive) {
        val window = view.context.findActivity()?.window
        if (window == null || !immersive) return@DisposableEffect onDispose { }
        val insetsController = WindowCompat.getInsetsController(window, view)
        val hideSystemBars = {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
        // Regaining focus re-shows the bars on some devices; this puts them back away.
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) hideSystemBars()
        }
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBars()
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

        onDispose {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * What is on now and what follows, beside the Back button while the controls are visible.
 *
 * Times come from the device clock against the provider's epoch timestamps, so no timezone has to
 * be guessed. Nothing is shown for a channel whose guide is empty or has already run out.
 */
@Composable
private fun NowAndNext(entries: List<EpgEntry>, modifier: Modifier = Modifier) {
    val nowSeconds = System.currentTimeMillis() / 1_000L
    val current = EpgSelection.nowPlaying(entries, nowSeconds)
    val next = EpgSelection.upNext(entries, nowSeconds)
    if (current == null && next == null) return

    Column(modifier) {
        current?.let { entry ->
            Text(
                text = entry.title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatClock(entry.startEpochSeconds)} – ${formatClock(entry.endEpochSeconds)}",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
            EpgSelection.progress(entries, nowSeconds)?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.padding(top = 4.dp).width(160.dp).height(3.dp),
                )
            }
        }
        next?.let { entry ->
            Text(
                text = "Next: ${entry.title} · ${formatClock(entry.startEpochSeconds)}",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (current == null) 0.dp else 4.dp),
            )
        }
    }
}

/** Device-local wall clock, which is what a viewer compares against their own clock. */
private fun formatClock(epochSeconds: Long): String = DateTimeFormatter
    .ofLocalizedTime(FormatStyle.SHORT)
    .withLocale(Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochSecond(epochSeconds))
