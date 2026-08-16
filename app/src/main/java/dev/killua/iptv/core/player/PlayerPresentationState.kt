package dev.killua.iptv.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Activity needs to know about the player screen, chiefly to decide Picture-in-Picture.
 *
 * Every call carries the screen it came from. Moving to the next episode replaces the player rather
 * than stacking one, and Compose tears the outgoing screen down **after** the incoming one is
 * already running — so the old screen's `hide` arrived last and switched Picture-in-Picture off for
 * an episode that had just started playing. A screen that is no longer the current one is ignored
 * here rather than being allowed to undo its successor.
 */
class PlayerPresentationState {
    private val mutableState = MutableStateFlow(PlayerPresentation())

    /** The screen whose calls currently count. Compared by identity; never held for its contents. */
    private var owner: Any? = null

    val state: StateFlow<PlayerPresentation> = mutableState.asStateFlow()

    fun show(owner: Any, snapshot: PlaybackSnapshot) {
        this.owner = owner
        mutableState.value = snapshot.toPresentation(isVisible = true)
    }

    fun update(owner: Any, snapshot: PlaybackSnapshot) {
        if (owner !== this.owner) return
        if (mutableState.value.isPlayerScreenVisible) {
            mutableState.value = snapshot.toPresentation(isVisible = true)
        }
    }

    fun hide(owner: Any) {
        if (owner !== this.owner) return
        this.owner = null
        mutableState.value = PlayerPresentation()
    }

    /**
     * Unconditional reset, for an ending session rather than a screen. Logout owns no player screen
     * and must not be refused, however the last one left.
     */
    fun clear() {
        owner = null
        mutableState.value = PlayerPresentation()
    }

    private fun PlaybackSnapshot.toPresentation(isVisible: Boolean) = PlayerPresentation(
        isPlayerScreenVisible = isVisible,
        isVideoReady = isReady,
        isPlaying = isPlaying,
        aspectWidth = videoWidth.coerceAtLeast(1),
        aspectHeight = videoHeight.coerceAtLeast(1),
    )
}
