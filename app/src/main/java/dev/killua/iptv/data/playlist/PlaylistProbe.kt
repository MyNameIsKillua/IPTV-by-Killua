package dev.killua.iptv.data.playlist

/**
 * Opening a playlist address once, to see whether it is one.
 *
 * A playlist has no sign-in: there is no account to authenticate and nothing that can answer yes.
 * Without this, a mistyped address would be accepted happily and then arrive as a library that is
 * empty for reasons the viewer cannot see - which is the worst of both, because the failure shows
 * up minutes later and somewhere else.
 *
 * A separate interface from `LiveListingSource` because it is a different question asked at a
 * different time: the source is what a *refresh* reads, this is what a *sign-in* checks.
 */
fun interface PlaylistProbe {
    suspend fun probe(url: String): PlaylistProbeResult
}

/** What opening a playlist address once told us. */
sealed interface PlaylistProbeResult {
    /** [url] is the address as the policy canonicalised it, which is what should be stored. */
    data class Ok(val url: String) : PlaylistProbeResult

    /** It answered, and what came back was not a playlist. A web page looks like this. */
    data object NotAPlaylist : PlaylistProbeResult

    /** The address rule would not open it. [reasonName] never contains the address itself. */
    data class Refused(val reasonName: String) : PlaylistProbeResult

    /** Nothing answered. */
    data object Unreachable : PlaylistProbeResult
}
