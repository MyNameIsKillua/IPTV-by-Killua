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

val Night = Color(0xFF09090E)
val NightRaised = Color(0xFF14131D)
val NightSoft = Color(0xFF1D1B29)
val Violet = Color(0xFF8B5CF6)
val VioletBright = Color(0xFFA78BFA)
val Cyan = Color(0xFF38BDF8)
val Success = Color(0xFF34D399)
val Warning = Color(0xFFFBBF24)
val ErrorRed = Color(0xFFFB7185)
val Ink = Color(0xFFF5F3FF)
val InkMuted = Color(0xFFAAA6B9)

private val DarkScheme = darkColorScheme(
    primary = VioletBright,
    onPrimary = Color(0xFF1B1039),
    primaryContainer = Color(0xFF3B256A),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Cyan,
    onSecondary = Color(0xFF002B3A),
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

    MaterialTheme(
        colorScheme = colors,
        typography = IptvTypography,
        shapes = IptvShapes,
        content = content,
    )
}
