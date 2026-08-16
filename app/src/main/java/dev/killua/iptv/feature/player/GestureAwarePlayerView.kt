package dev.killua.iptv.feature.player

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Owns gestures that start on the video surface, while allowing visible Media3 controls to
 * receive touches directly. Delaying a video-surface tap until it is confirmed as a single tap
 * prevents the first half of a double tap or long press from flashing the controller on screen.
 */
@OptIn(UnstableApi::class)
internal class GestureAwarePlayerView(context: Context) : FrameLayout(context) {
    val playerView = PlayerView(context)

    var seekIncrementMs: Long = DEFAULT_SEEK_INCREMENT_MS
    var holdPlaybackSpeed: Float = DEFAULT_HOLD_SPEED
    var onSeekBy: (Long) -> Boolean = { false }
    var onTogglePlayPause: () -> Boolean = { false }
    var onTemporarySpeedStart: (Float) -> Boolean = { false }
    var onTemporarySpeedEnd: () -> Unit = {}
    var onControllerVisibilityChanged: (Boolean) -> Unit = {}

    /**
     * Where Media3's bottom control row sits: its height, and how far above this view's bottom edge
     * it ends. Both in pixels, so the app's own transport controls can share that row instead of
     * floating above it at a guessed offset.
     */
    var onControlRowBoundsChanged: (heightPx: Int, bottomOffsetPx: Int) -> Unit = { _, _ -> }
        set(value) {
            field = value
            // A listener attached after the row was measured would otherwise wait for the next
            // layout pass, which may never come while the controls stay put.
            if (controlRowHeightPx > 0) value(controlRowHeightPx, controlRowBottomPx)
        }

    /**
     * How long the controls stay up before hiding themselves.
     *
     * Taken over from Media3's own timeout so that [hideChrome] runs at a moment this view knows
     * about; see the note there.
     */
    var chromeTimeoutMs: Long = DEFAULT_CHROME_TIMEOUT_MS

    /** Off in Picture-in-Picture, where no overlay of the app's own may appear. */
    var levelDragEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) cancelLevelDrag()
        }

    /** Read when a drag is recognized, so the level continues from the device's current one. */
    var currentLevelOf: (PlayerLevelTarget) -> Float = { 0f }
    var onLevelChange: (PlayerLevelTarget, Float) -> Unit = { _, _ -> }
    var onLevelDragEnd: () -> Unit = {}

    /**
     * How far the finger travels for the whole 0..1 scale, in pixels.
     *
     * Set from Compose to the height of the slider it draws, so the gesture covers exactly the
     * distance the viewer can see. Zero until the screen has measured it, which
     * [playerLevelAfterDrag] treats as "leave the level alone".
     */
    var levelDragRangePx: Float = 0f

    /** Where the sliders are drawn, so only a touch on one of them starts a level drag. */
    var levelBands: PlayerLevelBands = PlayerLevelBands(0f, 0f)

    private var temporarySpeedActive = false
    private var routeCurrentTouchToControl = false
    private var deferPlayPauseButtonTap = false
    private var controllerVisible = false
    private var chromeShown = false

    /** True between asking Media3 to hide and Media3 admitting it has. See the visibility listener. */
    private var chromeHideRequested = false
    private var levelDragTarget: PlayerLevelTarget? = null
    private var levelDragStartLevel = 0f
    private var levelDragOriginY = 0f
    private var controlRowHeightPx = 0
    private var controlRowBottomPx = 0
    private val controlRowBounds = Rect()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val hideCue = Runnable { gestureCue.visibility = View.GONE }
    private val autoHideChrome = Runnable {
        if (shouldKeepChromeVisible()) scheduleChromeTimeout() else hideChrome()
    }
    private val gestureCue = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 17f
        setPadding(18.dp, 10.dp, 18.dp, 10.dp)
        background = GradientDrawable().apply {
            setColor(0xB0000000.toInt())
            cornerRadius = 20.dp.toFloat()
        }
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (!isAttachedToWindow || windowVisibility != View.VISIBLE) return false

                if (deferPlayPauseButtonTap) {
                    val button = playerView.findViewById<View>(
                        androidx.media3.ui.R.id.exo_play_pause,
                    )
                    return button?.takeIf { it.isShown && it.isEnabled }?.performClick()
                        ?: onTogglePlayPause()
                }

                if (isControllerShowing()) hideChrome() else showChrome()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                hideChrome()
                val seekSeconds = seekIncrementMs.coerceAtLeast(0L) / 1_000L
                val handled = when (resolvePlayerGestureZone(event.x, width.toFloat())) {
                    PlayerGestureZone.Left -> {
                        onSeekBy(-seekIncrementMs.coerceAtLeast(0L)).also {
                            showTransientCue(if (it) "-${seekSeconds}s" else "Seeking unavailable")
                        }
                    }

                    PlayerGestureZone.Right -> {
                        onSeekBy(seekIncrementMs.coerceAtLeast(0L)).also {
                            showTransientCue(if (it) "+${seekSeconds}s" else "Seeking unavailable")
                        }
                    }

                    PlayerGestureZone.Center -> {
                        onTogglePlayPause().also {
                            showTransientCue(if (it) "Play / pause" else "Playback unavailable")
                        }
                    }
                }
                if (handled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }

            /**
             * Vertical drag: brightness on the left half, volume on the right.
             *
             * A drag is claimed only once it has clearly gone vertical, so a horizontal wobble
             * cannot steal it, and once claimed it stays claimed for the rest of the touch. The
             * detector stops delivering taps and long presses as soon as it reports a scroll, which
             * is what keeps a level drag from also seeking or opening the controller.
             */
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!levelDragEnabled) return false
                val down = e1 ?: return false
                if (levelDragTarget == null) {
                    val movedY = e2.y - down.y
                    val movedX = e2.x - down.x
                    if (abs(movedY) < touchSlop || abs(movedY) <= abs(movedX)) return false
                    // Where the touch started decides, not where it has reached: a drag that
                    // began on a slider keeps working once the finger wanders off it.
                    val target = resolvePlayerLevelTarget(
                        x = down.x,
                        y = down.y,
                        width = width.toFloat(),
                        height = height.toFloat(),
                        bands = levelBands,
                    ) ?: return false
                    beginLevelDrag(target, e2.y)
                }
                val target = levelDragTarget ?: return false
                // Up is more, which is the direction the volume keys already imply.
                val level = playerLevelAfterDrag(
                    startLevel = levelDragStartLevel,
                    dragPixels = levelDragOriginY - e2.y,
                    rangePixels = levelDragRangePx,
                )
                // The slider itself is drawn in Compose, above every other overlay. The text cue
                // this used to show sat inside this view and disappeared behind the error card.
                onLevelChange(target, level)
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                if (temporarySpeedActive) return

                // A hold is a playback gesture, so controller chrome must remain out of the way.
                hideChrome()
                temporarySpeedActive = onTemporarySpeedStart(holdPlaybackSpeed)
                if (temporarySpeedActive) {
                    showPersistentCue("${holdPlaybackSpeed.formatSpeed()}x")
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
        },
    )

    init {
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                controllerVisible = visibility == View.VISIBLE
                if (controllerVisible) {
                    // Media3 answers a hide request by reporting VISIBLE once more — its own fade
                    // has not finished, so as far as it is concerned the controls are still up.
                    // Taking that at face value immediately undid the hide this view just asked
                    // for, which is precisely why the app's overlays lagged behind by a fade.
                    if (chromeHideRequested) return@ControllerVisibilityListener
                    scheduleChromeTimeout()
                    reportChrome(true)
                } else {
                    chromeHideRequested = false
                    removeCallbacks(autoHideChrome)
                    reportChrome(false)
                }
            },
        )
        addView(
            playerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            gestureCue,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        controlRow()?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            reportControlRowBounds()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        reportControlRowBounds()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            levelDragTarget = null
            deferPlayPauseButtonTap = isControllerShowing() &&
                playerView.isPlayPauseButtonAt(event.rawX.toInt(), event.rawY.toInt())
            routeCurrentTouchToControl = isControllerShowing() &&
                !deferPlayPauseButtonTap &&
                playerView.hasInteractiveDescendantAt(event.rawX.toInt(), event.rawY.toInt())
        }

        if (routeCurrentTouchToControl) {
            val handled = super.dispatchTouchEvent(event)
            if (event.isTouchEnd()) {
                routeCurrentTouchToControl = false
                // Using a control is a reason to keep the controls around, which is Media3's own
                // behaviour and has to be reproduced now that the timeout lives here.
                if (controllerVisible) scheduleChromeTimeout()
            }
            return handled
        }

        gestureDetector.onTouchEvent(event)
        if (event.isTouchEnd()) {
            cancelTemporarySpeed()
            endLevelDrag()
            if (controllerVisible) scheduleChromeTimeout()
        }

        // Video-surface touches are deliberately not forwarded to PlayerView. A confirmed single
        // tap toggles its controller above; double taps and holds therefore cannot wake it first.
        return true
    }

    fun hideControllerAndCancelGesture() {
        cancelTemporarySpeed()
        cancelLevelDrag()
        hideChrome()
    }

    fun cancelTemporarySpeed() {
        if (!temporarySpeedActive) return
        temporarySpeedActive = false
        onTemporarySpeedEnd()
        hideGestureCue()
    }

    override fun onDetachedFromWindow() {
        cancelTemporarySpeed()
        cancelLevelDrag()
        removeCallbacks(autoHideChrome)
        super.onDetachedFromWindow()
    }

    /**
     * Hide the controls, and say so at once.
     *
     * Media3 reports a hide only once its own fade has finished, so every overlay driven by that
     * signal stayed at full opacity over a picture that had already cleared, then popped. Saying it
     * here, the moment the hide is asked for, lets the app's chrome fade alongside Media3's. That is
     * also why the auto-hide timeout was taken over from Media3: this is the only way to know when
     * it fires.
     */
    private fun hideChrome() {
        removeCallbacks(autoHideChrome)
        chromeHideRequested = true
        // Before asking Media3, not after: this is the whole point of owning the timeout.
        reportChrome(false)
        playerView.hideController()
    }

    private fun showChrome() {
        chromeHideRequested = false
        reportChrome(true)
        playerView.showController()
        scheduleChromeTimeout()
    }

    /**
     * What this screen's own overlays follow.
     *
     * Deliberately not Media3's visibility, which is only reported once its fade has finished.
     * Media3's listener still feeds in, for the visibility changes this view did not start, but a
     * hide it did start is announced immediately.
     */
    private fun reportChrome(shown: Boolean) {
        if (chromeShown == shown) return
        chromeShown = shown
        onControllerVisibilityChanged(shown)
    }

    private fun scheduleChromeTimeout() {
        removeCallbacks(autoHideChrome)
        if (chromeTimeoutMs > 0L) postDelayed(autoHideChrome, chromeTimeoutMs)
    }

    /**
     * Media3's own rule, reproduced: the controls stay up while nothing is playing, because a
     * stopped picture is one the viewer is looking at rather than watching.
     */
    private fun shouldKeepChromeVisible(): Boolean {
        val player = playerView.player ?: return true
        return !player.playWhenReady ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
    }

    private fun controlRow(): View? =
        playerView.findViewById(androidx.media3.ui.R.id.exo_bottom_bar)

    private fun reportControlRowBounds() {
        val row = controlRow() ?: return
        if (height <= 0 || row.height <= 0) return
        controlRowBounds.set(0, 0, row.width, row.height)
        offsetDescendantRectToMyCoords(row, controlRowBounds)
        val heightPx = controlRowBounds.height()
        val bottomPx = (height - controlRowBounds.bottom).coerceAtLeast(0)
        if (heightPx == controlRowHeightPx && bottomPx == controlRowBottomPx) return
        controlRowHeightPx = heightPx
        controlRowBottomPx = bottomPx
        onControlRowBoundsChanged(heightPx, bottomPx)
    }

    private fun beginLevelDrag(target: PlayerLevelTarget, originY: Float) {
        // A level drag is not a request for chrome. The speed reset is belt and braces: once a long
        // press has fired, GestureDetector stops reporting scrolls for that touch entirely, so the
        // two gestures cannot normally overlap.
        cancelTemporarySpeed()
        hideChrome()
        levelDragTarget = target
        levelDragStartLevel = currentLevelOf(target)
        levelDragOriginY = originY
    }

    /** The slider lingers briefly after release; how long is Compose's decision, not this view's. */
    private fun endLevelDrag() {
        if (levelDragTarget == null) return
        levelDragTarget = null
        onLevelDragEnd()
    }

    private fun cancelLevelDrag() {
        if (levelDragTarget == null) return
        levelDragTarget = null
        onLevelDragEnd()
    }

    // A hide already asked for counts as hidden, so a tap during Media3's fade brings the controls
    // back rather than asking to hide them a second time.
    private fun isControllerShowing(): Boolean =
        !chromeHideRequested && (controllerVisible || playerView.isControllerFullyVisible)

    private fun showTransientCue(message: String) {
        showPersistentCue(message)
        gestureCue.postDelayed(hideCue, CUE_DURATION_MS)
    }

    private fun showPersistentCue(message: String) {
        gestureCue.removeCallbacks(hideCue)
        gestureCue.text = message
        gestureCue.visibility = View.VISIBLE
        announceForAccessibility(message)
    }

    private fun hideGestureCue() {
        gestureCue.removeCallbacks(hideCue)
        gestureCue.visibility = View.GONE
    }

    private fun View.hasInteractiveDescendantAt(rawX: Int, rawY: Int): Boolean {
        if (!isShown || !isEnabled) return false
        if (this is ViewGroup) {
            for (index in childCount - 1 downTo 0) {
                if (getChildAt(index).hasInteractiveDescendantAt(rawX, rawY)) return true
            }
            return false
        }
        if (!isClickable && !isLongClickable && !isFocusable) return false
        val bounds = Rect()
        return getGlobalVisibleRect(bounds) && bounds.contains(rawX, rawY)
    }

    private fun PlayerView.isPlayPauseButtonAt(rawX: Int, rawY: Int): Boolean {
        val button = findViewById<View>(androidx.media3.ui.R.id.exo_play_pause) ?: return false
        val bounds = Rect()
        return button.isShown && button.isEnabled &&
            button.getGlobalVisibleRect(bounds) && bounds.contains(rawX, rawY)
    }

    private fun MotionEvent.isTouchEnd(): Boolean =
        actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL

    private companion object {
        const val DEFAULT_SEEK_INCREMENT_MS = 10_000L
        const val DEFAULT_HOLD_SPEED = 2f
        const val CUE_DURATION_MS = 650L
        const val DEFAULT_CHROME_TIMEOUT_MS = 4_000L
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun Float.formatSpeed(): String =
        if (this % 1f == 0f) toInt().toString() else toString()
}
