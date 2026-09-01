package dev.killua.iptv.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.killua.iptv.domain.model.ThemeMode
import dev.killua.iptv.ui.BrandPalette
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider

// The values live in :shared as plain ARGB longs so the desktop client cannot drift into a
// different-looking product. The names here are unchanged, so nothing else in the app had to move.
val Night = Color(BrandPalette.NIGHT)
val NightRaised = Color(BrandPalette.NIGHT_RAISED)
val NightSoft = Color(BrandPalette.NIGHT_SOFT)
val Violet = Color(BrandPalette.VIOLET)
val VioletBright = Color(BrandPalette.VIOLET_BRIGHT)
val Cyan = Color(BrandPalette.CYAN)
val Success = Color(BrandPalette.SUCCESS)
val Warning = Color(BrandPalette.WARNING)
val ErrorRed = Color(BrandPalette.ERROR)
val Ink = Color(BrandPalette.INK)
val InkMuted = Color(BrandPalette.INK_MUTED)

private val DarkScheme = darkColorScheme(
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
    error = ErrorRed,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6D3CCF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF25104B),
    secondary = Color(0xFF00799F),
    background = Color(0xFFF9F7FF),
    onBackground = Color(0xFF1B1920),
    surface = Color.White,
    onSurface = Color(0xFF1B1920),
    surfaceVariant = Color(0xFFEDE9F5),
    onSurfaceVariant = Color(0xFF5E5968),
    error = Color(0xFFBA1A1A),
)

@Composable
fun KilluasIptvTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val colors = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(darkTheme) {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose { }
        }
    }

    /*
     * Decided once. `isTelevision` asks the system three ways and none of them changes while the
     * app is running, so re-asking on every recomposition would be work that can only ever return
     * the same answer.
     */
    val context = LocalContext.current
    val television = remember(context) { context.isTelevision() }

    /*
     * The font scale is applied to the density rather than to the type scale, so it reaches text
     * inside Material components this project never styled - a button, a menu row, a dialog - and
     * not only the places that name a style from [IptvTypography].
     *
     * The viewer's own accessibility font scale is *multiplied*, never replaced: someone who has
     * asked their television for larger text has asked for larger text than this, not for this.
     */
    val density = LocalDensity.current
    val scaled = remember(density, television) {
        if (television) {
            Density(density.density, density.fontScale * TELEVISION_FONT_SCALE)
        } else {
            density
        }
    }

    CompositionLocalProvider(
        LocalIsTelevision provides television,
        LocalDensity provides scaled,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = IptvTypography,
            shapes = IptvShapes,
            content = content,
        )
    }
}
