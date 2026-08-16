package dev.killua.iptv.core.player

/**
 * The identity Media3 carries for the item currently loaded.
 *
 * It is deliberately credential-free. The authenticated Xtream URL exists only inside the
 * `MediaItem` URI at the playback boundary and must never reach UI state, navigation, or a
 * database write; this id is what those layers pass around instead.
 *
 * Being able to decode it back is what lets a progress write prove it belongs to the title the
 * player is actually showing, rather than to one the user has already navigated away from.
 */
sealed interface PlaybackMediaId {
    val accountId: String
    val contentId: String

    fun encode(): String

    /**
     * Content whose position is worth storing.
     *
     * A live channel has no meaningful position, and expressing that as a type rather than a
     * runtime check is what keeps a checkpoint from ever being written for one.
     */
    sealed interface Resumable : PlaybackMediaId

    data class Live(
        override val accountId: String,
        override val contentId: String,
    ) : PlaybackMediaId {
        override fun encode(): String = "$LIVE_PREFIX$SEPARATOR$accountId$SEPARATOR$contentId"
    }

    data class Movie(
        override val accountId: String,
        override val contentId: String,
    ) : Resumable {
        override fun encode(): String = "$MOVIE_PREFIX$SEPARATOR$accountId$SEPARATOR$contentId"
    }

    /** [contentId] is the provider's own episode ID, never a season/episode number. */
    data class Episode(
        override val accountId: String,
        override val contentId: String,
    ) : Resumable {
        override fun encode(): String = "$EPISODE_PREFIX$SEPARATOR$accountId$SEPARATOR$contentId"
    }

    companion object {
        private const val SEPARATOR = ':'
        private const val LIVE_PREFIX = "live"
        private const val MOVIE_PREFIX = "movie"
        private const val EPISODE_PREFIX = "episode"

        /**
         * Returns null for anything unrecognized rather than guessing. A provider content id may
         * itself contain a separator, so only the first two are split on.
         */
        fun decode(value: String?): PlaybackMediaId? {
            val parts = value?.split(SEPARATOR, limit = 3) ?: return null
            if (parts.size != 3 || parts[1].isEmpty() || parts[2].isEmpty()) return null
            return when (parts[0]) {
                LIVE_PREFIX -> Live(parts[1], parts[2])
                MOVIE_PREFIX -> Movie(parts[1], parts[2])
                EPISODE_PREFIX -> Episode(parts[1], parts[2])
                else -> null
            }
        }
    }
}

/** What the player was asked to play. The account comes from the authenticated session. */
sealed interface PlaybackRequest {
    val contentId: String

    /** A request whose content has a stored position. [restart] ignores it for one playback. */
    sealed interface Resumable : PlaybackRequest {
        val restart: Boolean
    }

    data class Live(override val contentId: String) : PlaybackRequest

    data class Movie(
        override val contentId: String,
        override val restart: Boolean = false,
    ) : Resumable

    data class Episode(
        override val contentId: String,
        override val restart: Boolean = false,
    ) : Resumable
}
