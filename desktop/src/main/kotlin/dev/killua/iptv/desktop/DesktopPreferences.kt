package dev.killua.iptv.desktop

import dev.killua.iptv.domain.model.TrackLanguagePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where the viewer left off, in the sense of the furniture rather than the content.
 *
 * Deliberately **not** part of `user-data.json`. That file is the export format, byte-for-byte
 * interchangeable with the phone's, and "which tab was open on the Windows machine" is not something
 * the phone should be handed or asked to preserve. This is a sidecar in the same spirit as
 * `titles.json`: local, disposable, and worth nothing more than a moment's re-orientation if it is
 * deleted.
 *
 * It holds no credentials and no names — the category is remembered by its provider id, and what it
 * is called is read back out of the listing like any other.
 */
@Serializable
data class DesktopPreferences(
    /** The rail destination, by enum name. Unknown values fall back rather than fail. */
    val section: String? = null,
    /**
     * Which account the remembered categories belong to, as the export format's own fingerprint.
     *
     * Category ids are small integers the provider hands out per account, so "42" means one thing
     * here and another there. Everything else in this file — the destination, the order, the volume,
     * the window — is about the client rather than the library, and stays across a change of
     * account.
     */
    val categoryAccount: String? = null,
    /** Section name to the category id last opened in it. */
    val categories: Map<String, String> = emptyMap(),
    /**
     * Section name to the browsing order chosen for it, by enum name.
     *
     * A name rather than an ordinal, so reordering or removing an option in the enum cannot silently
     * turn "top rated" into "recently added" on someone's next launch.
     */
    val sorts: Map<String, String> = emptyMap(),
    /**
     * The audio and subtitle languages the viewer has settled on.
     *
     * Here rather than in `user-data.json` because it is a setting for this client, not something
     * the phone should be handed: the phone keeps its own, learned from its own player, and the two
     * are about different track lists.
     */
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesDisabled: Boolean = false,
    /**
     * Fingerprint to the name the viewer gave that account.
     *
     * A name, not an account: the key is the same one-way fingerprint the state file uses, so this
     * file still holds no server address, no username and no password. Which is also why it is
     * remembered per account rather than as one string — someone with two providers should not have
     * to rename their playlist every time they switch.
     */
    val playlistNames: Map<String, String> = emptyMap(),
    val volume: Int = 100,
    val muted: Boolean = false,
    /**
     * How far the two skip keys move, in seconds.
     *
     * Two numbers rather than one, because they are not the same question. Going back is for
     * catching a line of dialogue that was missed; going forward is for getting past something. The
     * defaults are the ones every player has settled on, and the reason they are here at all is that
     * ten and thirty are right for a film and wrong for a two-hour match.
     */
    val skipBackSeconds: Int = DEFAULT_SKIP_BACK,
    val skipForwardSeconds: Int = DEFAULT_SKIP_FORWARD,
    /**
     * The keys the viewer has changed, by [Shortcut] name.
     *
     * Only the changed ones. Storing the whole table would freeze this version's defaults into
     * everyone's file, so a better default in a later release would reach nobody who had ever opened
     * the keyboard settings.
     */
    val keys: Map<String, KeyBinding> = emptyMap(),
    /**
     * Whether the library is kept between launches, and for how long before it is read again.
     *
     * On by default because the alternative is minutes of waiting at every launch on a real
     * provider, and off is one click for anyone who would rather nothing was written down. Zero
     * hours means "until I ask", which is a real answer rather than an absence: the *Read the
     * library again* button is always there.
     */
    val libraryCacheEnabled: Boolean = true,
    val libraryCacheHours: Int = DEFAULT_CACHE_HOURS,
    /**
     * Whether the client asks GitHub, on launch, whether a newer release exists.
     *
     * On by default, and it is the one thing this program contacts that is not the viewer's own
     * provider. The request carries no account, no identifier and nothing about the library - but
     * it does show GitHub an IP address, which is why there is a switch and why the prompt says so
     * itself. See `DesktopUpdateChecker`.
     */
    val updateCheckEnabled: Boolean = true,
    /** When it last asked, so launching four times in an evening asks once. */
    val updateCheckedAtMillis: Long = 0L,
    val windowWidth: Int = DEFAULT_WIDTH,
    val windowHeight: Int = DEFAULT_HEIGHT,
    /**
     * Whether the window fills the screen, short of full-screen.
     *
     * True by default, and that is a choice rather than an accident: this client is a television in
     * a window, and its first screen is a poster grid. Opening at a size that shows four posters
     * when the screen has room for twenty is a first impression of a smaller program than it is.
     *
     * Distinct from full-screen, which stays reserved for the picture: full-screen takes the title
     * bar with it, and a browsing screen with no way back out is a trap rather than a view.
     */
    val windowMaximized: Boolean = true,
) {
    companion object {
        const val DEFAULT_WIDTH = 1360
        const val DEFAULT_HEIGHT = 840

        /**
         * A window can be restored onto a screen that no longer exists, or from a file that has been
         * edited by hand. Neither should be able to produce a window nobody can reach.
         */
        const val MIN_WIDTH = 900
        const val MIN_HEIGHT = 600
        const val MAX_DIMENSION = 8000

        const val DEFAULT_SKIP_BACK = 10
        const val DEFAULT_SKIP_FORWARD = 30

        /**
         * The range a skip may be set to.
         *
         * A second is the smallest step worth a key press, and ten minutes is past the point where
         * the timeline is the better tool. The bounds exist because this file can be edited by hand
         * and a zero-second skip is a key that silently does nothing.
         */
        const val MIN_SKIP = 1
        const val MAX_SKIP = 600

        /** What the two skip controls offer, so the picker and the keys cannot drift apart. */
        val SKIP_CHOICES = listOf(5, 10, 15, 30, 60, 90, 120, 300)

        const val DEFAULT_CACHE_HOURS = 24

        /**
         * How long a kept library may be used before it is read again.
         *
         * Zero is "until I ask", and it is offered because a provider that adds a film a week is a
         * different thing from one that reshuffles its listing nightly, and only the viewer knows
         * which they have.
         */
        val CACHE_HOUR_CHOICES = listOf(0, 1, 6, 12, 24, 72, 168)
    }

    /** The stored languages, as the shared rules expect them. */
    val trackLanguages: TrackLanguagePreferences
        get() = TrackLanguagePreferences(audioLanguage, subtitleLanguage, subtitlesDisabled)

    fun withTrackLanguages(languages: TrackLanguagePreferences) = copy(
        audioLanguage = languages.audioLanguage,
        subtitleLanguage = languages.subtitleLanguage,
        subtitlesDisabled = languages.subtitlesDisabled,
    )

    /** The remembered categories, but only if they belong to [fingerprint]. */
    fun categoriesFor(fingerprint: String): Map<String, String> =
        if (categoryAccount == fingerprint) categories else emptyMap()

    /** Records a category against the account it was opened on, dropping another account's. */
    fun withCategory(fingerprint: String, section: String, categoryId: String) = copy(
        categoryAccount = fingerprint,
        categories = categoriesFor(fingerprint) + (section to categoryId),
    )

    /** Forgets one, for a viewer who has gone back to browsing the whole library. */
    fun withoutCategory(fingerprint: String, section: String) = copy(
        categoryAccount = fingerprint,
        categories = categoriesFor(fingerprint) - section,
    )

    val safeWidth: Int get() = windowWidth.coerceIn(MIN_WIDTH, MAX_DIMENSION)
    val safeHeight: Int get() = windowHeight.coerceIn(MIN_HEIGHT, MAX_DIMENSION)

    /** Clamped, because this file can be edited by hand and a zero-second skip does nothing. */
    val safeSkipBack: Int get() = skipBackSeconds.coerceIn(MIN_SKIP, MAX_SKIP)
    val safeSkipForward: Int get() = skipForwardSeconds.coerceIn(MIN_SKIP, MAX_SKIP)

    /** How old a kept library may be, in milliseconds; zero means it never goes stale on its own. */
    val libraryCacheMaxAgeMillis: Long
        get() = libraryCacheHours.coerceIn(0, 24 * 30).toLong() * 60L * 60L * 1000L

    /** The keys as the window dispatches over them: this viewer's choices over the defaults. */
    val shortcutBindings: ShortcutBindings get() = ShortcutBindings.from(keys)

    /**
     * Binds [binding] to [shortcut], taking it away from whatever held it before.
     *
     * Taking it away is the point. Two shortcuts on one key means one of them silently stops
     * working, and which one depends on declaration order — a rule no viewer can see. Freeing the
     * other one puts it back on its default, which is at least a key that is written down.
     */
    fun withKeyBinding(shortcut: Shortcut, binding: KeyBinding): DesktopPreferences {
        if (!shortcut.isRebindable || binding.isUnbound) return this
        val updated = keys + (shortcut.name to binding)
        // Asked of the *resolved* table rather than of the stored overrides, because a shortcut can
        // arrive at a key two ways — someone bound it there, or it ships there — and both have to
        // give it up. Answering only the first would put a shortcut back on a key that now belongs
        // to another the moment its own override was taken away.
        val resolved = ShortcutBindings.from(updated)
        val freed = Shortcut.entries
            .filter { it != shortcut && it.isRebindable && resolved.bindingOf(it) == binding }
            .associate { it.name to KeyBinding(UNBOUND) }
        return copy(keys = updated + freed)
    }

    /** Puts every key back to what it ships with. */
    fun withDefaultKeys() = copy(keys = emptyMap())
}

/**
 * The code stored for a shortcut whose key was taken by another.
 *
 * `VK_UNDEFINED`. It matches no press — which is the honest state of a shortcut whose key someone
 * else now has — and it is what makes *Reset* the only way back rather than a puzzle.
 */
const val UNBOUND = 0

class PreferenceStore(private val directory: File = DesktopUserData.defaultDirectory()) {

    private val file: File get() = File(directory, "preferences.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun load(): DesktopPreferences = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.isFile }?.readText()?.let {
                json.decodeFromString<DesktopPreferences>(it)
            }
        }.getOrNull() ?: DesktopPreferences()
    }

    suspend fun save(preferences: DesktopPreferences) = withContext(Dispatchers.IO) {
        writeAtomically(directory, "preferences.json") { it.writeText(json.encodeToString(preferences)) }
        Unit
    }
}
