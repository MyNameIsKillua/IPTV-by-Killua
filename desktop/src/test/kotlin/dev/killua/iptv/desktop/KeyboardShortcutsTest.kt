package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The rule the window's key handler is built on, tested without a window.
 *
 * Everything here is about one thing: the client must not steal a key that belongs to whatever the
 * viewer is typing into. That went wrong once already — the space bar paused the player instead of
 * putting a space in the filter box — and it is exactly the sort of bug that a test can hold shut
 * once the rule lives somewhere a test can reach.
 *
 * The rules now take a key code and its modifiers rather than a Compose event, which is what makes
 * that possible at all: a `KeyEvent` needs an event queue behind it, and none of this is about one.
 */
class KeyboardShortcutsTest {

    private val defaults = ShortcutBindings()

    @Test
    fun `a playing key is left alone while the player is not on screen`() {
        assertThat(defaults.forPress(AwtKeyEvent.VK_SPACE, playerOnScreen = false)).isNull()
        assertThat(defaults.forPress(AwtKeyEvent.VK_M, playerOnScreen = false)).isNull()
        assertThat(defaults.forPress(AwtKeyEvent.VK_F, playerOnScreen = false)).isNull()
    }

    @Test
    fun `a playing key is claimed while the player is on screen`() {
        assertThat(defaults.forPress(AwtKeyEvent.VK_SPACE, playerOnScreen = true))
            .isEqualTo(Shortcut.PlayPause)
        assertThat(defaults.forPress(AwtKeyEvent.VK_C, playerOnScreen = true))
            .isEqualTo(Shortcut.Fill)
    }

    @Test
    fun `escape and help do not wait for a stream`() {
        assertThat(defaults.forPress(AwtKeyEvent.VK_ESCAPE, playerOnScreen = false))
            .isEqualTo(Shortcut.Escape)
        assertThat(defaults.forPress(AwtKeyEvent.VK_F1, playerOnScreen = false))
            .isEqualTo(Shortcut.Help)
    }

    @Test
    fun `control separates the search box from fullscreen`() {
        assertThat(defaults.forPress(AwtKeyEvent.VK_F, ctrl = true, playerOnScreen = true))
            .isEqualTo(Shortcut.Search)
        assertThat(defaults.forPress(AwtKeyEvent.VK_F, playerOnScreen = true))
            .isEqualTo(Shortcut.Fullscreen)
    }

    @Test
    fun `a modifier the client does not claim is left for the focused control`() {
        assertThat(defaults.forPress(AwtKeyEvent.VK_M, ctrl = true, playerOnScreen = true)).isNull()
        assertThat(defaults.forPress(AwtKeyEvent.VK_SPACE, ctrl = true, playerOnScreen = true))
            .isNull()
        assertThat(defaults.forPress(AwtKeyEvent.VK_M, shift = true, playerOnScreen = true)).isNull()
    }

    @Test
    fun `no two shortcuts answer the same press`() {
        assertThat(Shortcut.entries.map { it.defaultBinding }).containsNoDuplicates()
    }

    @Test
    fun `every shortcut can be shown to someone`() {
        Shortcut.entries.forEach {
            assertThat(it.defaultBinding.label).isNotEmpty()
            assertThat(it.describe(10, 30)).isNotEmpty()
            assertThat(it.group).isNotEmpty()
        }
    }

    @Test
    fun `the two skip keys say how far they go`() {
        // The list of keys is what a viewer is told the client does. One that still says "ten
        // seconds" after the setting was changed is the kind of lie this enum exists to prevent.
        assertThat(Shortcut.Back.describe(45, 30)).isEqualTo("Back 45 seconds")
        assertThat(Shortcut.Forward.describe(10, 90)).isEqualTo("Forward 1 minute 30 seconds")
        assertThat(Shortcut.Forward.describe(10, 60)).isEqualTo("Forward 1 minute")
    }

    @Test
    fun `a viewer's own key wins over the default`() {
        val bindings = ShortcutBindings.from(
            mapOf(Shortcut.PlayPause.name to KeyBinding(AwtKeyEvent.VK_K)),
        )

        assertThat(bindings.forPress(AwtKeyEvent.VK_K, playerOnScreen = true))
            .isEqualTo(Shortcut.PlayPause)
        assertThat(bindings.forPress(AwtKeyEvent.VK_SPACE, playerOnScreen = true)).isNull()
    }

    @Test
    fun `a stored key for something this version does not have is ignored`() {
        // A file written by a later version, or edited by hand. Neither may stop the client.
        val bindings = ShortcutBindings.from(mapOf("SomethingElse" to KeyBinding(AwtKeyEvent.VK_K)))

        assertThat(bindings.forPress(AwtKeyEvent.VK_SPACE, playerOnScreen = true))
            .isEqualTo(Shortcut.PlayPause)
    }

    @Test
    fun `escape cannot be rebound even by a file that says it was`() {
        // It is the way out of the screen that changes the keys, and of the panel over a film.
        val bindings = ShortcutBindings.from(
            mapOf(Shortcut.Escape.name to KeyBinding(AwtKeyEvent.VK_K)),
        )

        assertThat(bindings.forPress(AwtKeyEvent.VK_ESCAPE, playerOnScreen = false))
            .isEqualTo(Shortcut.Escape)
        assertThat(bindings.forPress(AwtKeyEvent.VK_K, playerOnScreen = false)).isNull()
    }

    @Test
    fun `a modifier on its own is not a shortcut`() {
        // Otherwise holding control to type the second half of one would be captured as the whole.
        assertThat(KeyBinding.isBindable(AwtKeyEvent.VK_CONTROL)).isFalse()
        assertThat(KeyBinding.isBindable(AwtKeyEvent.VK_SHIFT)).isFalse()
        assertThat(KeyBinding.isBindable(AwtKeyEvent.VK_ALT)).isFalse()
        // Escape is refused because it is what cancels the capture.
        assertThat(KeyBinding.isBindable(AwtKeyEvent.VK_ESCAPE)).isFalse()
        assertThat(KeyBinding.isBindable(AwtKeyEvent.VK_K)).isTrue()
    }

    @Test
    fun `an unbound shortcut answers nothing at all`() {
        val bindings = ShortcutBindings.from(mapOf(Shortcut.Mute.name to KeyBinding(UNBOUND)))

        // VK_UNDEFINED is zero, and a press that somehow carried zero must not silently mute.
        assertThat(bindings.forPress(UNBOUND, playerOnScreen = true)).isNull()
        assertThat(bindings.bindingOf(Shortcut.Mute).label).isEqualTo("not set")
    }

    @Test
    fun `a key is written the way it is printed on the keyboard`() {
        assertThat(KeyBinding(AwtKeyEvent.VK_SPACE).label).isEqualTo("Space")
        assertThat(KeyBinding(AwtKeyEvent.VK_LEFT).label).isEqualTo("←")
        assertThat(KeyBinding(AwtKeyEvent.VK_F, ctrl = true).label).isEqualTo("Ctrl + F")
        assertThat(KeyBinding(AwtKeyEvent.VK_K, shift = true, alt = true).label)
            .isEqualTo("Alt + Shift + K")
    }
}
