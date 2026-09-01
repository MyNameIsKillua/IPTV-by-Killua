package dev.killua.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.Rectangle

/**
 * Fullscreen, as a second window rather than as a mode of the first.
 *
 * The obvious way — `WindowPlacement.Fullscreen` — is what this client used, and it is broken on
 * Windows in a way that is worth writing down so nobody puts it back. Compose hands that placement
 * to Skiko, which puts the window into **exclusive** full-screen mode through the graphics device,
 * and exclusive mode has one documented behaviour that ruins it here: the moment the window loses
 * focus, Windows minimizes it. Alt-tabbing to a browser, or clicking anything on a second monitor,
 * therefore made the picture vanish into the taskbar — and coming back out of it left the placement
 * stuck, so fullscreen could not be left either. Both were measured on this machine before this
 * window was written, not guessed at:
 *
 * ```
 * AFTER-ENTER   exclusiveFullscreen=true  iconified=false
 * WHILE-UNFOCUSED exclusiveFullscreen=true iconified=true      <- Windows minimized it
 * AFTER-LEAVE   placement=Maximized       iconified=true       <- and it stayed there
 * ```
 *
 * An undecorated window the size of the screen has neither problem. It is an ordinary window, so it
 * behaves like one: it stays where it is when something else takes the focus, and closing it is
 * closing a window. This is what every video player on Windows actually does.
 *
 * A second window is possible here only because of how the picture is drawn. libvlc renders into a
 * buffer that Compose paints as an ordinary image, so the video is not an AWT component nailed to
 * one window — moving it between windows costs nothing and does not interrupt playback. The window
 * that hosts the browsing screen carries on existing behind this one, which is what keeps every
 * piece of screen state — what is playing, where in the list, what has been marked — alive across a
 * fullscreen toggle. Recreating the main window instead, which is the only way to undecorate it,
 * would throw all of that away.
 *
 * The window takes the whole screen the main window is on, which is what [screenBounds] is for: on
 * two monitors, fullscreen belongs on the one the client was already on.
 */
@Composable
internal fun FullscreenPlayerWindow(
    screenBounds: Rectangle,
    onClose: () -> Unit,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable () -> Unit,
) {
    val state = rememberWindowState(
        position = WindowPosition(screenBounds.x.dp, screenBounds.y.dp),
        size = DpSize(screenBounds.width.dp, screenBounds.height.dp),
    )

    Window(
        onCloseRequest = onClose,
        state = state,
        undecorated = true,
        resizable = false,
        // Alt-F4 and the window manager aside, this window has no chrome to close it with, so the
        // keys are the way out and they have to reach it. Same handler as the main window: which key
        // does what must not depend on which window happens to have the focus.
        onPreviewKeyEvent = onKeyEvent,
        title = "Killua IPTV",
    ) {
        // Over the taskbar, which is the difference between fullscreen and a very large window.
        // Undone on the way out rather than left set, because an always-on-top window that has
        // stopped being fullscreen is a window nobody can get behind.
        DisposableEffect(Unit) {
            window.isAlwaysOnTop = true
            onDispose { window.isAlwaysOnTop = false }
        }
        // Ordinary windows do not necessarily open focused, and one that does not would ignore
        // every key it exists to receive.
        LaunchedEffect(Unit) {
            window.toFront()
            window.requestFocus()
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
    }
}
