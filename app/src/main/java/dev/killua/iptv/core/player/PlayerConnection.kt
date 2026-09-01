package dev.killua.iptv.core.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.model.TrackLanguageSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class PlayerConnection(
    context: Context,
) : PlaybackStateSource {
    private val appContext = context.applicationContext
    private val mutableController = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = mutableController.asStateFlow()
    private val controllerFailure = MutableStateFlow<AppFailure?>(null)

    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var temporarySpeed: TemporarySpeed? = null

    init {
        connect()
    }

    suspend fun awaitController(): MediaController = combine(controller, controllerFailure) { connected, failure ->
        connected to failure
    }.first { (connected, failure) -> connected != null || failure != null }
        .let { (connected, failure) ->
            connected ?: throw AppFailureException(
                failure ?: AppFailure(FailureKind.StreamUnavailable, retryable = true),
            )
        }

    fun pause() {
        mutableController.value?.pause()
    }

    /**
     * Captures the current position, its duration, and the item they belong to.
     *
     * Must be called from the main thread, like every `MediaController` access. Returns null when
     * no controller is connected or the loaded item carries an id this app did not write, so a
     * caller can never attribute a position to the wrong title.
     */
    override fun capturePosition(): PlaybackPosition? {
        val connected = mutableController.value ?: return null
        val mediaId = PlaybackMediaId.decode(connected.currentMediaItem?.mediaId) ?: return null
        val duration = connected.duration
        return PlaybackPosition(
            mediaId = mediaId,
            positionMs = connected.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration == C.TIME_UNSET || duration < 0L) 0L else duration,
            hasEnded = connected.playbackState == Player.STATE_ENDED,
        )
    }

    /**
     * Reads the track languages the viewer chose by hand, or null when nothing is connected.
     *
     * Main thread only, like [capturePosition]. An automatic selection is deliberately not reported;
     * see [readHandPickedLanguages].
     */
    override fun captureTrackLanguages(): TrackLanguageSelection? =
        mutableController.value?.readHandPickedLanguages()

    /** Applies the remembered languages to whatever is loaded, before the first frame is up. */
    fun applyTrackLanguages(languages: TrackLanguagePreferences) {
        mutableController.value?.applyTrackLanguages(languages)
    }

    fun togglePlayPause(): Boolean {
        val connected = mutableController.value ?: return false
        if (!connected.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return false

        if (connected.playWhenReady) {
            connected.pause()
        } else {
            if (
                connected.playbackState == Player.STATE_ENDED &&
                connected.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            ) {
                connected.seekToDefaultPosition()
            }
            connected.play()
        }
        return true
    }

    /**
     * Seeks relative to the current position when the active item exposes a seekable window.
     * Live streams without a DVR window intentionally return false and remain untouched.
     */
    fun seekBy(deltaMs: Long): Boolean {
        val connected = mutableController.value ?: return false
        if (
            deltaMs == 0L ||
            !connected.isCurrentMediaItemSeekable ||
            !connected.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        ) {
            return false
        }

        val position = connected.currentPosition.coerceAtLeast(0L)
        val target = when {
            deltaMs > 0L -> position + deltaMs.coerceAtMost(Long.MAX_VALUE - position)
            else -> {
                val magnitude = if (deltaMs == Long.MIN_VALUE) Long.MAX_VALUE else -deltaMs
                position - magnitude.coerceAtMost(position)
            }
        }
        val duration = connected.duration
        val boundedTarget = if (duration >= 0L) target.coerceAtMost(duration) else target
        connected.seekTo(boundedTarget)
        return true
    }

    /** Temporarily changes speed and remembers the exact prior value for release/cancel. */
    fun beginTemporarySpeed(speed: Float): Boolean {
        if (!speed.isFinite() || speed <= 0f || temporarySpeed != null) return false
        val connected = mutableController.value ?: return false
        if (
            !connected.playWhenReady ||
            !connected.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)
        ) {
            return false
        }

        temporarySpeed = TemporarySpeed(
            controller = connected,
            restoreParameters = connected.playbackParameters,
        )
        connected.setPlaybackSpeed(speed)
        return true
    }

    fun endTemporarySpeed() {
        val speedToRestore = temporarySpeed ?: return
        temporarySpeed = null
        val connected = mutableController.value
        if (
            connected != null &&
            connected === speedToRestore.controller &&
            connected.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)
        ) {
            connected.playbackParameters = speedToRestore.restoreParameters
        }
    }

    fun stopAndClear() {
        endTemporarySpeed()
        mutableController.value?.run {
            stop()
            clearMediaItems()
        }
        mutableSnapshot.value = PlaybackSnapshot()
    }

    fun retry() {
        val connected = mutableController.value
        if (connected != null) {
            connected.prepare()
            connected.play()
        } else {
            reconnect()
        }
    }

    fun reconnect() {
        if (mutableController.value != null || controllerFailure.value == null) return
        controllerFuture?.cancel(true)
        controllerFailure.value = null
        connect()
    }

    private fun connect() {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val connected = future.get()
                    connected.addListener(listener)
                    controllerFailure.value = null
                    mutableController.value = connected
                    updateSnapshot(connected)
                } catch (_: Exception) {
                    controllerFailure.value = AppFailure(
                        FailureKind.StreamUnavailable,
                        retryable = true,
                    )
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = updateFromController()
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateFromController()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateFromController()
        override fun onPlayerError(error: PlaybackException) = updateFromController(error)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateFromController()
        override fun onVideoSizeChanged(videoSize: VideoSize) = updateFromController()
    }

    private fun updateFromController(error: PlaybackException? = null) {
        mutableController.value?.let { updateSnapshot(it, error) }
    }

    private fun updateSnapshot(controller: MediaController, error: PlaybackException? = null) {
        val videoSize = controller.videoSize
        val playbackError = error ?: controller.playerError
        mutableSnapshot.value = PlaybackSnapshot(
            mediaId = controller.currentMediaItem?.mediaId,
            title = controller.mediaMetadata.title?.toString(),
            isPlaying = controller.isPlaying,
            playWhenReady = controller.playWhenReady,
            playbackState = controller.playbackState,
            error = playbackError?.toFailure(),
            videoWidth = videoSize.width.takeIf { it > 0 } ?: 16,
            videoHeight = videoSize.height.takeIf { it > 0 } ?: 9,
        )
    }

    private fun PlaybackException.toFailure(): AppFailure {
        val code = errorCodeName.uppercase()
        return when {
            "DECOD" in code -> AppFailure(FailureKind.DecoderFailure)
            "PARSING" in code -> AppFailure(FailureKind.UnsupportedStreamFormat)
            "BAD_HTTP_STATUS" in code || "FILE_NOT_FOUND" in code ->
                AppFailure(FailureKind.StreamUnavailable, retryable = true)
            "IO" in code || "TIMEOUT" in code ->
                AppFailure(FailureKind.ServerUnavailable, retryable = true)
            else -> AppFailure(FailureKind.StreamUnavailable, retryable = true)
        }
    }

    private data class TemporarySpeed(
        val controller: MediaController,
        val restoreParameters: PlaybackParameters,
    )
}
