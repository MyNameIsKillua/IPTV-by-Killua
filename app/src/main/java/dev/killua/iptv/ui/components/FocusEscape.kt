package dev.killua.iptv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Lets a remote out of a text field.
 *
 * A Compose text field consumes the up and down arrows for moving a caret, which is right in a
 * paragraph and wrong in a one-line search box — and on a television it is a dead end: measured on
 * an emulator, `DPAD_DOWN` pressed four times in the Live search field left `uiautomator` reporting
 * the same `EditText` focused each time, so the channel list underneath could not be reached with a
 * remote at all.
 *
 * The fix has to be a **preview** handler rather than a focus property. `focusProperties { down = … }`
 * only steers a focus *search*, and no search ever happens while the field is eating the key.
 *
 * `moveFocus` returns whether it found anywhere to go, and that answer is passed straight back: a
 * press this cannot use is left for whatever would have had it, so nothing is swallowed. On a phone
 * the whole thing is invisible — a finger does not move focus, and the only way to reach this is a
 * keyboard or a remote, where moving on is exactly what the key means.
 */
@Composable
fun Modifier.releasesFocusVertically(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                else -> false
            }
        }
    }
}
