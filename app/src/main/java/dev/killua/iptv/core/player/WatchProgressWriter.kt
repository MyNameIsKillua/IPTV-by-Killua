package dev.killua.iptv.core.player

import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.SeriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persists watch positions on an application-owned scope, for every resumable content type.
 *
 * The last checkpoint of a title is written exactly when the screen is going away — on back,
 * navigation, or `onCleared`. A `viewModelScope` is already cancelled at that point, so the write
 * would be dropped silently; this scope outlives the ViewModel and completes it.
 *
 * Every write is guarded twice. The caller must pass the title it believes is playing, and the
 * captured position carries the id it was actually read from, so a checkpoint that arrives after
 * the user moved to another title writes nothing. The repository then rechecks that the account
 * still owns the content before touching the database.
 *
 * Movies and episodes deliberately share one writer rather than owning one each: the ownership,
 * clamping, and duplicate rules below are the part that must not drift between content types.
 * Only the final store differs, and the `when` over [PlaybackMediaId.Resumable] is exhaustive, so
 * a third resumable type cannot be added without deciding where its positions go.
 *
 * Not thread-safe by design: it is driven from the player ViewModel on the main thread, the same
 * thread `MediaController` positions must be read on.
 */
class WatchProgressWriter(
    private val scope: CoroutineScope,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
) {
    private var lastWritten: Checkpoint? = null

    /**
     * Writes [position] when it is usable and new, and reports whether it was accepted.
     *
     * A position is skipped when it belongs to a different title than [expected], when the source
     * has not reported a duration yet — a resume point without one cannot be shown as progress —
     * or when it repeats the previous value, which is what a paused player would otherwise write
     * on every lifecycle callback.
     */
    fun checkpoint(position: PlaybackPosition, expected: PlaybackMediaId.Resumable): Boolean {
        if (position.mediaId != expected) return false
        if (position.durationMs <= 0L) return false

        // A finished title reports a position slightly short of its duration; storing the
        // duration is what makes the completion rule fire deterministically.
        val positionMs = if (position.hasEnded) {
            position.durationMs
        } else {
            position.positionMs.coerceAtMost(position.durationMs)
        }
        val checkpoint = Checkpoint(expected, positionMs)
        if (checkpoint == lastWritten) return false
        lastWritten = checkpoint

        scope.launch {
            try {
                store(expected, positionMs, position.durationMs)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A lost checkpoint costs at most one interval of resume accuracy and must never
                // surface as a playback error.
            }
        }
        return true
    }

    private suspend fun store(
        mediaId: PlaybackMediaId.Resumable,
        positionMs: Long,
        durationMs: Long,
    ) = when (mediaId) {
        is PlaybackMediaId.Movie -> movieRepository.saveProgress(
            accountId = mediaId.accountId,
            movieId = mediaId.contentId,
            positionMs = positionMs,
            durationMs = durationMs,
        )

        is PlaybackMediaId.Episode -> seriesRepository.saveEpisodeProgress(
            accountId = mediaId.accountId,
            episodeId = mediaId.contentId,
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }

    private data class Checkpoint(val mediaId: PlaybackMediaId.Resumable, val positionMs: Long)
}
