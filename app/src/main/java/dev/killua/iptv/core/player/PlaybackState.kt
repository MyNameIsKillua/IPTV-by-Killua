package dev.killua.iptv.core.player

import androidx.media3.common.Player
import dev.killua.iptv.domain.model.AppFailure

data class PlaybackSnapshot(
    val mediaId: String? = null,
    val title: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val error: AppFailure? = null,
    val videoWidth: Int = 16,
    val videoHeight: Int = 9,
) {
    val isReady: Boolean get() = playbackState == Player.STATE_READY
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
}

/**
 * A position read off the controller together with the item it belongs to.
 *
 * The three values are captured in one go on purpose: a position taken separately from the media
 * id could be attributed to the wrong title after a media transition.
 */
data class PlaybackPosition(
    val mediaId: PlaybackMediaId,
    val positionMs: Long,
    /** Zero when the source has not reported a duration yet. */
    val durationMs: Long,
    val hasEnded: Boolean,
)

data class PlayerPresentation(
    val isPlayerScreenVisible: Boolean = false,
    val isVideoReady: Boolean = false,
    val isPlaying: Boolean = false,
    val aspectWidth: Int = 16,
    val aspectHeight: Int = 9,
)
