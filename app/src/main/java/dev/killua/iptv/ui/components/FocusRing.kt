package dev.killua.iptv.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.killua.iptv.ui.theme.Cyan

/**
 * Where the remote is.
 *
 * On a phone this draws nothing anyone ever sees: a finger does not move focus, so nothing is ever
 * focused, and the border stays transparent. On a **television** it is the difference between an app
 * and a shrug. `clickable` makes every tile and row focusable whether anyone meant it to be or not,
 * so a D-pad already walks the whole screen — what was missing is any sign of where it had got to.
 *
 * The same idea, and the same colour, as the desktop client's `focusRing`: **cyan means focus** and
 * violet is left to mean *chosen*. One colour, one meaning, across two clients that a viewer may
 * well use in the same evening.
 *
 * The border draws inside the bounds and never changes the layout, so adding it cannot move
 * anything. Put it directly before the `clickable` it belongs to and after the `clip`, so the ring
 * follows the shape the control already has.
 */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(12.dp), width: Dp = 3.dp): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(width, if (focused) Cyan else Color.Transparent, shape)
}
