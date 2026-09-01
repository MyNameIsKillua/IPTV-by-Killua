package dev.killua.iptv.core.player

import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.model.TrackLanguageSelection
import dev.killua.iptv.domain.model.learnFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persists the track languages the viewer picked by hand, on an application-owned scope.
 *
 * Same reasoning as [WatchProgressWriter]: the choice most worth keeping is often made shortly
 * before the player is left, and a `viewModelScope` is already cancelled by then.
 *
 * Two guards keep this quiet. A selection that carries no hand-made choice is ignored outright, and
 * a selection identical to the one already handled never reaches the store — this is driven on the
 * same ten-second rhythm as a watch-progress checkpoint, and the common case is a viewer who never
 * opened the track menu at all.
 *
 * Not thread-safe by design: it is driven from the player ViewModel on the main thread, like every
 * other `MediaController` read.
 */
class TrackLanguageWriter(
    private val scope: CoroutineScope,
    private val load: suspend () -> TrackLanguagePreferences,
    private val store: suspend (TrackLanguagePreferences) -> Unit,
) {
    private var lastHandled: TrackLanguageSelection? = null

    /** Reports whether [selection] was new enough to be worth a store read. */
    fun remember(selection: TrackLanguageSelection): Boolean {
        if (selection.isEmpty) return false
        if (selection == lastHandled) return false
        lastHandled = selection

        scope.launch {
            try {
                val learned = load().learnFrom(selection) ?: return@launch
                store(learned)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A lost preference costs one more trip through the track menu and must never
                // surface as a playback error.
            }
        }
        return true
    }

    /**
     * Forgets what was handled, so the next selection is written even if it repeats the last one.
     *
     * Called when the stored preferences are cleared from Settings: without it, re-picking the
     * language that was just cleared would be swallowed as a duplicate.
     */
    fun reset() {
        lastHandled = null
    }
}
