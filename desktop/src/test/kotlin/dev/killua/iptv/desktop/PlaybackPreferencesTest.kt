package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The two settings a viewer can change about how the client plays and listens.
 *
 * How far the skip keys move, and which keys they are. Both are in the sidecar rather than in the
 * export, for the same reason the track languages are: they are settings for this window, not
 * something the phone should be handed.
 */
class PlaybackPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the skips start where every player puts them`() {
        assertThat(DesktopPreferences().safeSkipBack).isEqualTo(10)
        assertThat(DesktopPreferences().safeSkipForward).isEqualTo(30)
    }

    @Test
    fun `a skip edited to nonsense by hand is clamped rather than obeyed`() {
        // This file can be opened in a text editor. A zero-second skip is a key that silently does
        // nothing, which is worse than one that does the wrong amount.
        assertThat(DesktopPreferences(skipBackSeconds = 0).safeSkipBack).isEqualTo(1)
        assertThat(DesktopPreferences(skipForwardSeconds = -30).safeSkipForward).isEqualTo(1)
        assertThat(DesktopPreferences(skipForwardSeconds = 99_999).safeSkipForward).isEqualTo(600)
    }

    @Test
    fun `the skips and the keys survive a round trip`() = runBlocking {
        val store = PreferenceStore(folder.root)
        val preferences = DesktopPreferences(
            skipBackSeconds = 15,
            skipForwardSeconds = 90,
            keys = mapOf(Shortcut.PlayPause.name to KeyBinding(AwtKeyEvent.VK_K, shift = true)),
        )

        store.save(preferences)

        assertThat(store.load()).isEqualTo(preferences)
    }

    @Test
    fun `only the changed keys are stored`() {
        // Storing the whole table would freeze this version's defaults into everyone's file, so a
        // better default later would reach nobody who had ever opened the keyboard settings.
        val changed = DesktopPreferences().withKeyBinding(
            Shortcut.Mute,
            KeyBinding(AwtKeyEvent.VK_K),
        )

        assertThat(changed.keys.keys).containsExactly(Shortcut.Mute.name)
    }

    @Test
    fun `taking a key from another shortcut leaves that one unset`() {
        // Two shortcuts on one key means one of them silently stops working, and which one would
        // depend on declaration order — a rule no viewer can see. Freeing the other says so.
        val changed = DesktopPreferences().withKeyBinding(
            Shortcut.Mute,
            Shortcut.Fill.defaultBinding,
        )

        val bindings = changed.shortcutBindings
        assertThat(bindings.bindingOf(Shortcut.Mute)).isEqualTo(Shortcut.Fill.defaultBinding)
        assertThat(bindings.bindingOf(Shortcut.Fill).isUnbound).isTrue()
        assertThat(bindings.forPress(AwtKeyEvent.VK_C, playerOnScreen = true))
            .isEqualTo(Shortcut.Mute)
    }

    @Test
    fun `moving a key off a shortcut and back leaves nothing behind`() {
        val moved = DesktopPreferences()
            .withKeyBinding(Shortcut.Mute, KeyBinding(AwtKeyEvent.VK_K))
            .withKeyBinding(Shortcut.Fill, KeyBinding(AwtKeyEvent.VK_K))

        // K now belongs to Fill alone, and Mute is the one left without a key.
        val bindings = moved.shortcutBindings
        assertThat(bindings.forPress(AwtKeyEvent.VK_K, playerOnScreen = true))
            .isEqualTo(Shortcut.Fill)
        assertThat(bindings.bindingOf(Shortcut.Mute).isUnbound).isTrue()
    }

    @Test
    fun `escape refuses to be rebound`() {
        val unchanged = DesktopPreferences().withKeyBinding(
            Shortcut.Escape,
            KeyBinding(AwtKeyEvent.VK_K),
        )

        assertThat(unchanged.keys).isEmpty()
    }

    @Test
    fun `putting the keys back forgets every change`() {
        val changed = DesktopPreferences()
            .withKeyBinding(Shortcut.Mute, KeyBinding(AwtKeyEvent.VK_K))
            .withKeyBinding(Shortcut.Fill, KeyBinding(AwtKeyEvent.VK_J))

        assertThat(changed.withDefaultKeys().keys).isEmpty()
        assertThat(changed.withDefaultKeys().shortcutBindings.bindingOf(Shortcut.Mute))
            .isEqualTo(Shortcut.Mute.defaultBinding)
    }

    @Test
    fun `a category is forgotten when the whole library is browsed again`() {
        val account = DesktopUserData.fingerprintOf("https://provider.example/", "alice")
        val remembered = DesktopPreferences().withCategory(account, "Movies", "42")

        assertThat(remembered.categoriesFor(account)).containsEntry("Movies", "42")
        assertThat(remembered.withoutCategory(account, "Movies").categoriesFor(account)).isEmpty()
    }
}
