package dev.killua.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val PLAYBACK = "Playing"
private const val SOUND = "Sound"
private const val PICTURE = "Picture"
private const val WINDOW = "Window"

/**
 * The keys, written down once.
 *
 * They used to live in three places that had no way of agreeing: the `when` block in the window's
 * key handler, which is what actually happens; a paragraph in Settings, which is what a viewer is
 * told happens; and `docs/PLAYER.md`. Nothing checked any of them against the others, and a list of
 * keys that lies is worse than no list at all — someone presses the key, nothing happens, and now
 * they distrust the rest of it too.
 *
 * So the list is data, and the handler dispatches over it with an exhaustive `when`. Adding an entry
 * here without giving it something to do is a **compile error**, which is the only kind of reminder
 * that cannot be forgotten. The screen that shows the keys to a viewer reads the same list, so it
 * cannot offer a key that does nothing.
 *
 * What each entry carries is its **default** binding. What is actually pressed comes from
 * [ShortcutBindings], because a viewer can change any of these — see `DesktopPreferences.keys`.
 *
 * [needsPlayer] is the gate that keeps the window from eating a text field's keystrokes. The window
 * sees every key before the focused control does, so a bare `Space` handled unconditionally is a
 * search box that cannot contain a space. Anything that only makes sense while watching says so, and
 * reaches the field untouched the rest of the time.
 */
enum class Shortcut(
    val description: String,
    val group: String,
    val defaultBinding: KeyBinding,
    val needsPlayer: Boolean = true,
) {
    PlayPause("Pause, or carry on", PLAYBACK, Key.Spacebar.asBinding()),
    Back("Back", PLAYBACK, Key.DirectionLeft.asBinding()),
    Forward("Forward", PLAYBACK, Key.DirectionRight.asBinding()),
    PreviousChannel("The one before this in the list", PLAYBACK, Key.PageUp.asBinding()),
    NextChannel("The one after this in the list", PLAYBACK, Key.PageDown.asBinding()),

    Louder("Louder", SOUND, Key.DirectionUp.asBinding()),
    Quieter("Quieter", SOUND, Key.DirectionDown.asBinding()),
    Mute("Silence, or sound again", SOUND, Key.M.asBinding()),

    Fullscreen("Fill the screen, or leave it", PICTURE, Key.F.asBinding()),
    // VLC's own key for the same idea, which is where anyone coming to this already learned it.
    Fill("Crop to the window, or fit the picture", PICTURE, Key.C.asBinding()),

    // Escape does whatever is topmost, so it is described that way rather than as one action. It is
    // also the one binding that cannot be changed: it is the way out of the screen that changes the
    // others, and a viewer who rebinds it there has locked themselves in.
    Escape(
        "Close what is open, or leave fullscreen",
        WINDOW,
        Key.Escape.asBinding(),
        needsPlayer = false,
    ),

    // A modifier rather than a bare key, deliberately: a bare "/" would be a search box that cannot
    // contain a slash.
    Search(
        "Jump to the search box",
        WINDOW,
        Key.F.asBinding(ctrl = true),
        needsPlayer = false,
    ),
    Help("This list", WINDOW, Key.F1.asBinding(), needsPlayer = false),
    ;

    /** Whether a viewer may change this one. See [Escape]. */
    val isRebindable: Boolean get() = this != Escape

    /**
     * The description with the numbers in it, where there are any.
     *
     * The two skip keys are the only entries whose meaning a setting changes, and a list that still
     * says "ten seconds" after the viewer asked for thirty is exactly the kind of lie this enum
     * exists to prevent.
     */
    fun describe(skipBackSeconds: Int, skipForwardSeconds: Int): String = when (this) {
        Back -> "Back ${secondsPhrase(skipBackSeconds)}"
        Forward -> "Forward ${secondsPhrase(skipForwardSeconds)}"
        else -> description
    }
}

/** `30 seconds`, `1 minute`, `2 minutes 30 seconds` — as much as is needed and no more. */
fun secondsPhrase(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    val parts = buildList {
        if (minutes > 0) add(if (minutes == 1) "1 minute" else "$minutes minutes")
        if (rest > 0 || minutes == 0) add(if (rest == 1) "1 second" else "$rest seconds")
    }
    return parts.joinToString(" ")
}

/**
 * Which key means what, once the viewer's own choices are folded into the defaults.
 *
 * A class rather than a map passed around, because two questions are asked of it — what is bound to
 * this shortcut, and what does this press mean — and answering the second by scanning the first is
 * how the two drift apart.
 *
 * Resolution is **first match in declaration order**, and a binding a viewer has taken for one
 * shortcut is removed from every other as it is set, so the order can never decide anything a
 * viewer would notice. It is a tiebreak against a corrupted file, not a rule.
 */
class ShortcutBindings(private val overrides: Map<Shortcut, KeyBinding> = emptyMap()) {

    fun bindingOf(shortcut: Shortcut): KeyBinding =
        overrides[shortcut]?.takeIf { shortcut.isRebindable } ?: shortcut.defaultBinding

    /** The shortcut a press means, or null when the press belongs to whatever has focus. */
    fun forPress(
        nativeKeyCode: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        playerOnScreen: Boolean,
    ): Shortcut? = Shortcut.entries.firstOrNull {
        (playerOnScreen || !it.needsPlayer) &&
            bindingOf(it).matches(nativeKeyCode, ctrl, shift, alt)
    }

    fun forPress(event: KeyEvent, playerOnScreen: Boolean): Shortcut? = forPress(
        nativeKeyCode = event.key.nativeKeyCode,
        ctrl = event.isCtrlPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
        playerOnScreen = playerOnScreen,
    )

    /** The shortcut already using [binding], if any — what the rebinding screen warns about. */
    fun holderOf(binding: KeyBinding, except: Shortcut): Shortcut? =
        Shortcut.entries.firstOrNull { it != except && bindingOf(it) == binding }

    companion object {
        /** The stored overrides, by enum name, ignoring anything this version does not know. */
        fun from(stored: Map<String, KeyBinding>): ShortcutBindings = ShortcutBindings(
            stored.mapNotNull { (name, binding) ->
                Shortcut.entries.firstOrNull { it.name == name && it.isRebindable }?.to(binding)
            }.toMap(),
        )
    }
}

/**
 * The keys as a viewer reads them, grouped the way they are used.
 *
 * Shown twice: in Settings, where someone goes to find out what a program can do — and where
 * [onRebind] makes each row a button — and as an overlay over the picture, because fullscreen has no
 * Settings to go to and fullscreen is exactly where knowing the keys matters most.
 */
@Composable
fun ShortcutTable(
    bindings: ShortcutBindings,
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    modifier: Modifier = Modifier,
    /** Null in the overlay, which is a reminder rather than a settings screen. */
    onRebind: ((Shortcut) -> Unit)? = null,
    /** The one row waiting for a key press, if any. */
    capturing: Shortcut? = null,
) {
    Column(modifier) {
        Shortcut.entries.groupBy { it.group }.forEach { (group, shortcuts) ->
            Text(
                group,
                color = VioletBright,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            shortcuts.forEach { shortcut ->
                val rebind = onRebind?.takeIf { shortcut.isRebindable }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyCap(
                        label = if (capturing == shortcut) {
                            "press a key"
                        } else {
                            bindings.bindingOf(shortcut).label
                        },
                        active = capturing == shortcut,
                        onClick = rebind?.let { { it(shortcut) } },
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        shortcut.describe(skipBackSeconds, skipForwardSeconds),
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "The playing keys only work while something is on screen, so they never swallow what " +
                "you are typing.",
            color = InkMuted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .widthIn(min = 96.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) Violet.copy(alpha = 0.35f) else Night)
            .border(
                width = 1.dp,
                color = if (active) VioletBright else Violet.copy(alpha = 0.3f),
                shape = RoundedCornerShape(7.dp),
            )
            .then(
                if (onClick != null) {
                    Modifier.focusRing(RoundedCornerShape(7.dp)).clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) VioletBright else Ink,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The same list over whatever is on screen, for the case Settings cannot serve.
 *
 * The scrim swallows clicks, so a press meant for *close* never lands on the picture behind it, and
 * closing is offered four ways — the button, the key that opened it, escape, and a click anywhere —
 * because a panel that appears over fullscreen video and cannot be dismissed is a panic.
 *
 * Two details are about the keyboard rather than the mouse. The scrim takes its click through
 * `pointerInput` rather than `clickable`, because `clickable` would make a full-screen rectangle a
 * tab stop, and a tab stop the size of the window that looks like nothing is worse than no ring at
 * all. And *Close* takes the focus as the panel opens, so the first tab press has somewhere sensible
 * to have come from.
 *
 * What this still does not do is **contain** the focus: tab from *Close* eventually reaches the
 * controls behind the scrim, which are covered but not switched off. Compose can be made to hold
 * focus inside a subtree, but every way of doing it changes how key events reach the window — where
 * escape and `F1` are handled — and that is not a thing to change without being able to run it. The
 * panel has one control and four ways out, so the leak is untidy rather than a trap.
 */
@Composable
fun KeyboardHelpOverlay(
    bindings: ShortcutBindings,
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    onClose: () -> Unit,
) {
    val closeButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeButton.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NightRaised)
                .border(1.dp, Violet.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                .padding(26.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Keyboard",
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Violet.copy(alpha = 0.2f))
                        .focusRequester(closeButton)
                        .focusRing(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("Close", color = VioletBright, style = MaterialTheme.typography.labelLarge)
                }
            }
            ShortcutTable(bindings, skipBackSeconds, skipForwardSeconds)
        }
    }
}
