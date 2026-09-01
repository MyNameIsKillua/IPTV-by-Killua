package dev.killua.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.killua.iptv.ui.BrandPalette

val Night = Color(BrandPalette.NIGHT)
val NightRaised = Color(BrandPalette.NIGHT_RAISED)
val NightSoft = Color(BrandPalette.NIGHT_SOFT)
val Violet = Color(BrandPalette.VIOLET)
val VioletBright = Color(BrandPalette.VIOLET_BRIGHT)
val Ink = Color(BrandPalette.INK)
val InkMuted = Color(BrandPalette.INK_MUTED)

/**
 * The brand's second colour, reserved here for **keyboard focus and nothing else**.
 *
 * It was declared as the scheme's `secondary` on the first day and then never drawn, which made it
 * the one colour in the palette that could still be given a meaning without taking one away. That
 * matters because violet already has a job: it is what the client uses for *chosen* — the open
 * section, the programme being read, a mark that is set. Focus is not a choice, it is where the
 * next key will land, and the two are in the same place often enough that they cannot share a hue.
 * The guide's timeline is where that became undeniable: a violet ring for the programme being read
 * and a violet ring for the keyboard say the same thing twice and mean different things.
 */
val Cyan = Color(BrandPalette.CYAN)

private val DesktopScheme = darkColorScheme(
    primary = VioletBright,
    onPrimary = Color(BrandPalette.ON_VIOLET),
    primaryContainer = Color(BrandPalette.VIOLET_DEEP),
    onPrimaryContainer = Color(BrandPalette.ON_VIOLET_CONTAINER),
    secondary = Cyan,
    onSecondary = Color(BrandPalette.ON_CYAN),
    background = Night,
    onBackground = Ink,
    surface = NightRaised,
    onSurface = Ink,
    surfaceVariant = NightSoft,
    onSurfaceVariant = InkMuted,
    error = Color(BrandPalette.ERROR),
)

/**
 * The desktop shell.
 *
 * The content is wrapped in a [Surface] rather than a plain `Box`, and that is not decoration.
 * Material 3 hands text its colour through `LocalContentColor`, which only a `Surface` provides;
 * without one every `Text` falls back to black, which on this background is invisible. That was a
 * real bug in the first version of this client, not a matter of taste.
 */
@Composable
fun KilluaDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DesktopScheme) {
        Surface(
            modifier = Modifier,
            color = DesktopScheme.background,
            contentColor = DesktopScheme.onBackground,
            content = content,
        )
    }
}

/**
 * A violet wash from the top-left, so a full-screen dark surface has somewhere to look.
 *
 * Radial rather than linear: a linear gradient across a wide desktop window reads as a seam, while
 * a soft pool of colour behind the content reads as depth. Kept faint — it must never compete with
 * a video frame drawn beside it.
 */
fun Modifier.brandBackdrop(): Modifier = background(
    Brush.radialGradient(
        colors = listOf(
            Color(BrandPalette.VIOLET).copy(alpha = 0.22f),
            Color(BrandPalette.VIOLET).copy(alpha = 0.06f),
            Night,
        ),
        center = Offset(320f, 220f),
        radius = 1100f,
    ),
)

/**
 * Text that glows rather than merely sits there.
 *
 * A coloured `Shadow` with no offset and a wide blur is a glow; the same shadow black and offset
 * downwards is what the Android login uses for contrast over a photograph. Here the surface is flat,
 * so the violet version is the one that earns its place.
 */
@Composable
fun GlowText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineLarge,
    color: Color = Ink,
    glow: Color = VioletBright,
    glowRadius: Float = 28f,
    fontWeight: FontWeight? = FontWeight.Bold,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        style = style.copy(
            shadow = Shadow(
                color = glow.copy(alpha = 0.55f),
                offset = Offset.Zero,
                blurRadius = glowRadius,
            ),
        ),
    )
}

/**
 * Where the keyboard is.
 *
 * `Modifier.clickable` already makes something focusable and already takes enter and space once it
 * has focus, so every control in this client can be reached with tab and the arrow keys whether
 * anyone meant it to be or not. What it does not do is *show* it: the default indication on desktop
 * draws nothing for focus, so tabbing through the client moved an invisible cursor.
 *
 * Half of that was already fixed where it was noticed — a poster brightens its border and a list row
 * takes a wash. This is the same idea everywhere else, written once so the fix cannot be
 * half-applied again.
 *
 * The colour is [Cyan] wherever focus is shown, including at those two, and violet is left to mean
 * *chosen*. One colour, one meaning.
 *
 * The border draws inside the bounds and never changes the layout, so adding it cannot move
 * anything. Put it directly before the `clickable` it belongs to and after the `clip`, so the ring
 * follows the shape the control already has.
 */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(10.dp), width: Dp = 2.dp): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(width, if (focused) Cyan else Color.Transparent, shape)
}
