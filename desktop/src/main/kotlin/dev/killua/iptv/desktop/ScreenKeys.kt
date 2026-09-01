package dev.killua.iptv.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The wire between the window, which handles keys, and the screen, which knows what they mean.
 *
 * Keys are handled at the window so they work wherever the pointer last was — that is the whole
 * point of doing it there. But "the next channel" and "the search box" are things only the browsing
 * screen can answer for: it holds the list, and it owns the field. Rather than lifting either into
 * the window, the screen leaves handlers here and the window calls them.
 *
 * Null means the action is not available right now, which is the state between launching and having
 * something to act on.
 */
class ScreenKeys {
    /** Steps through what is playable, by -1 or +1. Null while nothing is playing. */
    var step: ((Int) -> Unit)? by mutableStateOf(null)

    /** Puts the caret in the search box. Null while the browsing screen is not on show. */
    var focusSearch: (() -> Unit)? by mutableStateOf(null)

    /**
     * Puts everything this screen holds onto the disk, and waits for it.
     *
     * For the window closing. Two things are in the air at that moment and neither survives on its
     * own. Positions are checkpointed every ten seconds while something plays, which is fine for a
     * crash and wrong for the ordinary way an evening ends: the last thing watched is up to ten
     * seconds behind where it was left, or, for a film started a moment ago, has no position at
     * all. And every mark — a heart, a bookmark, a title crossed off — is written by a coroutine
     * launched and not waited for, so one set a moment before the window closes is a coroutine the
     * exiting process has no reason to let finish.
     *
     * Suspending rather than fire-and-forget, for exactly that reason: a coroutine launched into a
     * scope that is being torn down finishes nowhere.
     *
     * Available whenever the browsing screen is, not only while something plays. That was the bug:
     * the hook existed for positions, so a heart set while browsing had nothing holding the door.
     */
    var flushToDisk: (suspend () -> Unit)? by mutableStateOf(null)

    /** What is playing, for the window title. Null while nothing is. */
    var nowPlaying: String? by mutableStateOf(null)

    /**
     * Whether the player is the thing on screen.
     *
     * What the playing keys are gated on. It used to be "the player holds media", which is a
     * different question and the wrong one: media outlives the screen that shows it, so a space bar
     * pressed in a search box could pause a film the viewer had walked away from.
     */
    var playerOnScreen: Boolean by mutableStateOf(false)

    /**
     * Whether the keyboard panel is up.
     *
     * Held here rather than in the window because there are now two windows that can be on top, and
     * the panel belongs over whichever one that is. Both read this; only one of them is showing.
     */
    var helpVisible: Boolean by mutableStateOf(false)

    /**
     * Where a key press goes while a shortcut is being rebound.
     *
     * Non-null exactly while Settings is waiting for a key. The window checks this before it
     * dispatches anything, because otherwise pressing space to bind it would pause playback and
     * pressing `F` would go full-screen — the shortcut would fire instead of being recorded.
     */
    var capture: ((androidx.compose.ui.input.key.KeyEvent) -> Unit)? by mutableStateOf(null)
}
