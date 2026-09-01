package dev.killua.iptv.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import kotlinx.serialization.Serializable
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * One key press, as something that can be written down and compared.
 *
 * The native code rather than Compose's `Key`, for two reasons. It is what survives being stored:
 * `Key.keyCode` packs the key *and its location* into one long, so a binding saved from the numeric
 * keypad would not match the same key pressed on the main block. And it is what can be named — AWT
 * already knows what every code is called in the viewer's own language, which is a table this
 * project has no business keeping a second copy of.
 *
 * Only the three modifiers a viewer can reasonably reach without the client fighting the window
 * manager. Meta is left out deliberately: on Windows it belongs to the shell.
 */
@Serializable
data class KeyBinding(
    val nativeKeyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /** True when nothing is bound, which is how a shortcut whose key was taken over is stored. */
    val isUnbound: Boolean get() = nativeKeyCode == UNBOUND

    /** How the binding is written on a key cap. */
    val label: String
        get() = if (isUnbound) "not set" else buildString {
            if (ctrl) append("Ctrl + ")
            if (alt) append("Alt + ")
            if (shift) append("Shift + ")
            append(keyName(nativeKeyCode))
        }

    /**
     * True when this press is this binding, modifiers included rather than merely tolerated.
     *
     * Taking the press apart rather than taking a `KeyEvent` is what lets every rule in this file be
     * tested without a window, an event queue or a running Compose scene. The overload beside it is
     * the only line that has to know what a Compose key event looks like.
     */
    fun matches(nativeKeyCode: Int, ctrl: Boolean, shift: Boolean = false, alt: Boolean = false): Boolean =
        !isUnbound &&
            nativeKeyCode == this.nativeKeyCode &&
            ctrl == this.ctrl &&
            shift == this.shift &&
            alt == this.alt

    fun matches(event: KeyEvent): Boolean = matches(
        nativeKeyCode = event.key.nativeKeyCode,
        ctrl = event.isCtrlPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    )

    companion object {
        fun of(event: KeyEvent) = KeyBinding(
            nativeKeyCode = event.key.nativeKeyCode,
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
        )

        /**
         * Whether a press is worth binding at all.
         *
         * A modifier on its own is not: holding Ctrl to type the second half of a shortcut would
         * otherwise be captured as the shortcut. Escape is refused too, because it is what closes
         * the capture, and a viewer who cannot get out of a rebinding dialog has been trapped by a
         * convenience.
         */
        fun isBindable(nativeKeyCode: Int): Boolean = when (nativeKeyCode) {
            AwtKeyEvent.VK_CONTROL,
            AwtKeyEvent.VK_SHIFT,
            AwtKeyEvent.VK_ALT,
            AwtKeyEvent.VK_ALT_GRAPH,
            AwtKeyEvent.VK_META,
            AwtKeyEvent.VK_WINDOWS,
            AwtKeyEvent.VK_UNDEFINED,
            AwtKeyEvent.VK_ESCAPE,
            -> false

            else -> true
        }

        fun isBindable(event: KeyEvent): Boolean = isBindable(event.key.nativeKeyCode)
    }
}

/** A `Key` as a binding, for the defaults, which are written in Compose's own vocabulary. */
fun Key.asBinding(ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
    KeyBinding(nativeKeyCode, ctrl = ctrl, shift = shift, alt = alt)

/**
 * What a key is called.
 *
 * The handful this client ships with are named the way a viewer already reads them — an arrow is an
 * arrow, not "Left". Everything else falls through to AWT, which names the whole keyboard and does
 * it in the system language, so a rebinding onto a key nobody anticipated still reads as itself.
 */
private fun keyName(nativeKeyCode: Int): String = when (nativeKeyCode) {
    AwtKeyEvent.VK_SPACE -> "Space"
    AwtKeyEvent.VK_LEFT -> "←"
    AwtKeyEvent.VK_RIGHT -> "→"
    AwtKeyEvent.VK_UP -> "↑"
    AwtKeyEvent.VK_DOWN -> "↓"
    AwtKeyEvent.VK_PAGE_UP -> "Page ↑"
    AwtKeyEvent.VK_PAGE_DOWN -> "Page ↓"
    AwtKeyEvent.VK_ESCAPE -> "Esc"
    else -> AwtKeyEvent.getKeyText(nativeKeyCode)
}
