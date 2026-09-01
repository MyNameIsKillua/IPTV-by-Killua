package dev.killua.iptv.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Whether this is running on a television, and what that has to change.
 *
 * The app is **one build for both**, which is the decision `docs/ROADMAP.md` recorded when Android
 * TV support was added: a phone has no leanback launcher and a television has no touchscreen, and
 * both features are declared not required so one APK serves either. What was never done is making
 * the *drawing* differ, and the owner installed it on a Fire TV Stick on 24 August 2026 and got
 * exactly what was predicted - a phone screen stretched to 1080p.
 *
 * Two things are wrong on a television rather than merely unpolished, and this file is about those
 * two.
 */

/**
 * True on a television, decided once at the top of the tree.
 *
 * Defaults to false, so anything that reads it outside the theme behaves like a phone rather than
 * guessing. Static because it cannot change while the app is running: a device does not stop being
 * a television.
 */
val LocalIsTelevision = staticCompositionLocalOf { false }

/**
 * Three answers to the same question, because no single one is reliable across the devices this has
 * to run on.
 *
 * [UiModeManager] is the documented way and is what Google TV answers correctly. The leanback
 * feature catches boxes whose UI mode is misreported, which happens on cheaper hardware. And the
 * television UI-mode bit in the configuration is what an emulator set to a TV profile reports. Any
 * one of them saying yes is enough: a false positive costs larger text on a tablet, and a false
 * negative costs a viewer the edges of their screen.
 */
fun Context.isTelevision(): Boolean {
    val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    val configured = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return configured == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * How much bigger every piece of text is on a television.
 *
 * Applied as a font scale rather than by rewriting the type scale, so it reaches text inside
 * Material components this project never declared a style for - a button label, a menu item, a
 * dialog - instead of only the places that happen to use [IptvTypography] directly.
 *
 * 1.2 rather than something bolder because the layout is the same layout: every row, chip and tile
 * is sized in dp and does not grow with the text, so a large multiplier buys legibility by clipping
 * words. This is the amount that fits without moving anything, and a browse screen shaped for a
 * remote - which is a slice of its own - is where a real 10-foot type scale belongs.
 */
const val TELEVISION_FONT_SCALE = 1.2f

/**
 * The margin a television is likely to eat.
 *
 * Overscan is a habit inherited from cathode-ray tubes that flat panels kept: many still crop the
 * outermost few percent of the picture, and a set that does it has no way to tell the app. Anything
 * drawn out there - the first channel in a row, the edge of a poster, a button - is simply not on
 * the viewer's screen, and there is no error to notice.
 *
 * Five percent of 1920x1080 is 96 by 54, and the vertical figure is the one worth stating: rows are
 * horizontal here, so the sides lose the beginning and end of a list while the top and bottom lose
 * headings. Applied at the root so nothing has to remember it.
 */
val TelevisionOverscan = PaddingValues(horizontal = 48.dp, vertical = 27.dp)
