package dev.killua.iptv.desktop

/**
 * A picture that is supposed to be moving and is not.
 *
 * The client already distinguishes two silences at the start of playback: libvlc saying it gave up,
 * and nothing being said while no frame arrives. Both are about *opening* a stream. Neither could
 * ever fire again afterwards, because the test for "all is well" is that the player is playing or
 * has a position at all — and once a stream has produced a single frame, that stays true whatever
 * happens next.
 *
 * So the case this exists for had no answer: a provider drops the connection forty minutes into a
 * film, libvlc keeps reporting the state it last had, the position stops moving, and the viewer sits
 * in front of a frozen frame with working controls and no explanation. That is the same complaint
 * the opening messages were written to fix, arriving at a different moment.
 *
 * The test is deliberately narrow. A **paused** player is not playing, so it can never be called
 * stalled; nor can one that has not started. What is left is a player that says it is playing while
 * its clock stands still, which is either a stream that has died or one rebuffering — and the
 * tolerance is what separates those. Fifteen seconds is far longer than any rebuffer and far shorter
 * than a viewer's patience with a picture that has stopped.
 *
 * A false alarm costs little by design: the message never stops the player, so a picture that comes
 * back clears it by itself, exactly as the opening messages do.
 */
class StallWatch(private val toleranceMillis: Long = DEFAULT_TOLERANCE_MILLIS) {
    private var lastPosition: Long? = null
    private var standingMillis = 0L

    /**
     * Takes one reading and answers whether the picture has stopped.
     *
     * [sinceLastMillis] is how long ago the previous reading was taken, so the caller's poll rate is
     * the caller's business and this stays a rule rather than a timer.
     */
    fun observe(isPlaying: Boolean, positionMs: Long, sinceLastMillis: Long): Boolean {
        if (!isPlaying || positionMs <= 0L) {
            reset()
            return false
        }
        if (positionMs != lastPosition) {
            lastPosition = positionMs
            standingMillis = 0L
            return false
        }
        standingMillis += sinceLastMillis
        return standingMillis >= toleranceMillis
    }

    /** For a new title, and for anything that means the last reading no longer describes this one. */
    fun reset() {
        lastPosition = null
        standingMillis = 0L
    }

    private companion object {
        const val DEFAULT_TOLERANCE_MILLIS = 15_000L
    }
}
