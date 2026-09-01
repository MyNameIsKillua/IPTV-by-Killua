package dev.killua.iptv.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.killua.iptv.core.text.SearchTextNormalizer
import dev.killua.iptv.data.xtream.XtreamStreamUrlFactory
import dev.killua.iptv.domain.epg.EpgSelection
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.data.xtream.XtreamLanguageTagger
import dev.killua.iptv.domain.account.ExpiryWarning
import dev.killua.iptv.domain.account.expiryWarningFor
import dev.killua.iptv.domain.browse.orderedBy
import dev.killua.iptv.domain.browse.nextEpisodeToWatch
import dev.killua.iptv.domain.browse.seasonToOpen
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveSortOrder
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieSortOrder
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.ResumableKind
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesSortOrder
import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.model.TrackLanguageSelection
import dev.killua.iptv.domain.model.learnFrom
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.displayLabel
import dev.killua.iptv.domain.model.languageDisplayName
import dev.killua.iptv.domain.model.StreamHeaders
import dev.killua.iptv.domain.progress.WatchProgressPolicy
import dev.killua.iptv.domain.userdata.EPISODE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.CHANNEL_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.MOVIE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.ProgressRecord
import dev.killua.iptv.domain.userdata.SERIES_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.UserDataExport
import dev.killua.iptv.domain.userdata.UserDataImportPlan
import dev.killua.iptv.domain.userdata.planImportOf
import dev.killua.iptv.domain.userdata.clearProgress
import dev.killua.iptv.domain.userdata.continueWatching
import dev.killua.iptv.domain.userdata.markWatched
import dev.killua.iptv.domain.userdata.isSaved
import dev.killua.iptv.domain.userdata.mergedWith
import dev.killua.iptv.domain.userdata.resumePositionOf
import dev.killua.iptv.domain.userdata.withProgress
import dev.killua.iptv.domain.userdata.withoutRecentChannel
import dev.killua.iptv.domain.userdata.ownChannels
import dev.killua.iptv.domain.userdata.toggleMovieFavourite
import dev.killua.iptv.domain.userdata.toggleSaved
import dev.killua.iptv.domain.userdata.toggleSeriesFavourite
import dev.killua.iptv.domain.userdata.withRecentChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Rectangle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everywhere the rail can go, in the order it lists them. */
enum class Section(val label: String, val icon: ImageVector) {
    /**
     * Where the client opens: what is unfinished, what is new, what this viewer keeps.
     *
     * First because a category picker is a question, and a client should say something before it
     * asks one. It is the same argument the phone's Home screen was built on.
     */
    Home("Start", Icons.Default.Home),

    Live("Live", Icons.Default.LiveTv),

    /** Not a library either: what is on across the channels this viewer keeps. */
    Guide("Guide", Icons.Default.Schedule),

    Movies("Movies", Icons.Default.Movie),
    Series("Series", Icons.Default.VideoLibrary),

    /** Not a library: one field over all three of them at once. */
    Search("Search", Icons.Default.Search),

    /** Not a library: what this viewer has marked, read out of the stored state. */
    Saved("My list", Icons.Default.Bookmark),

    Settings("Settings", Icons.Default.Settings),
    ;

    /** Which listing this destination browses, or null for the ones that browse none. */
    val library: LibraryKind?
        get() = when (this) {
            Live -> LibraryKind.Channels
            Movies -> LibraryKind.Movies
            Series -> LibraryKind.Series
            Home, Guide, Search, Saved, Settings -> null
        }

    companion object {
        /**
         * The destinations that make sense for this account.
         *
         * A playlist loses three. **Movies** and **Series** because the M3U format has neither, and
         * **Guide** because a playlist has no `get_short_epg` behind it - the guide is built from
         * per-channel requests to a provider's API, and a file has no API. A playlist can name an
         * XMLTV address in its header, which is a different thing to build and is not built.
         *
         * They are removed rather than shown empty. An empty rail destination reads as a library
         * that failed to load, and sends someone looking for a fault that is not there.
         */
        fun forAccount(playlist: Boolean): List<Section> =
            if (playlist) entries.filterNot { it in PLAYLIST_ABSENT } else entries

        private val PLAYLIST_ABSENT = setOf(Movies, Series, Guide)
    }
}

private data class CategoryRow(val id: String, val name: String)

/**
 * The series whose screen is open, as much of it as the caller knew.
 *
 * Not a `SeriesSummary`: a series is opened from four places now and only one of them has a listing
 * row. A name and a poster are enough to draw the screen while the record is being fetched, which is
 * the difference between opening a series and waiting for one.
 */
private data class OpenSeries(val id: String, val name: String, val posterUrl: String?)

/**
 * A row or tile in the content area.
 *
 * Everything selectable becomes one of these so one grid and one list serve all three libraries; the
 * domain object is carried along because only it knows how to build a stream URL and where its
 * artwork lives.
 */
internal sealed interface BrowseItem {
    val id: String
    val label: String
    val artworkUrl: String?

    data class Channel(val value: LiveChannel) : BrowseItem {
        override val id get() = value.id
        override val label get() = value.name
        override val artworkUrl get() = value.logoUrl
    }

    data class Movie(val value: MovieSummary) : BrowseItem {
        override val id get() = value.id
        override val label get() = value.name
        override val artworkUrl get() = value.posterUrl
    }

    /** Selecting a series does not play anything; it drills into its episodes. */
    data class Series(val value: SeriesSummary) : BrowseItem {
        override val id get() = value.id
        override val label get() = value.name
        override val artworkUrl get() = value.posterUrl
    }

    data class Episode(val value: SeriesEpisode) : BrowseItem {
        override val id get() = value.id
        override val label get() = value.displayLabel
        override val artworkUrl get() = value.stillUrl
    }

    /**
     * A marked or started title reconstructed from the title cache.
     *
     * The stored state holds only provider ids, so this is what a saved item looks like when it is
     * shown without the category it came from being loaded. It carries just enough to be played.
     */
    data class Indexed(
        val contentType: String,
        override val id: String,
        override val label: String,
        override val artworkUrl: String?,
        val containerExtension: String?,
    ) : BrowseItem
}

@Composable
fun BrowseScreen(
    client: XtreamDesktopClient,
    player: VlcVideoPlayer,
    session: DesktopSession,
    keys: ScreenKeys,
    preferences: DesktopPreferences,
    onPreferencesChange: (DesktopPreferences) -> Unit,
    /**
     * The whole library, where it has been read.
     *
     * Where it has not — a listing the provider refused, or one the viewer skipped — every screen
     * falls back to asking for one category at a time, which is how this client worked before it
     * had an index at all. Nothing here treats it as a precondition.
     */
    library: LibraryIndex,
    /** True while that read is still running, which is why a row can be empty and still fill in. */
    librarySyncing: Boolean,
    onReloadLibrary: () -> Unit,
    /** Read only from here: what is kept, how big it is, and the button that throws it away. */
    libraryCache: LibraryCache,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    /** Where fullscreen belongs: the screen the main window is on, read when it is entered. */
    screenBounds: () -> Rectangle,
    /** The window's own key handler, so the fullscreen window answers keys identically. */
    onKeyEvent: (KeyEvent) -> Boolean,
    onSignOut: () -> Unit,
    onExportUserData: (UserDataExport, report: (Boolean) -> Unit) -> Unit,
    onImportUserData: ((String?) -> Unit) -> Unit,
    /** True while a check the viewer asked for is in flight. */
    checkingForUpdate: Boolean,
    /** What the last asked-for check said, or null before one was asked for. */
    updateCheckMessage: String?,
    onCheckForUpdate: () -> Unit,
) {
    // Read once, at first composition. This screen only exists after a sign-in round trip, so a
    // local file read at launch has long since finished; if it somehow has not, the client opens on
    // Live, which is where it opened before any of this existed.
    /**
     * The destinations this account has. A playlist has fewer; see [Section.forAccount].
     */
    val sections = remember(session) { Section.forAccount(session.playlistUrl != null) }
    var section by remember {
        // Restored only if it still exists: someone who left the client on Movies and came back on
        // a playlist would otherwise land on a rail entry that is no longer drawn, with no way to
        // see where they are.
        mutableStateOf(sections.firstOrNull { it.name == preferences.section } ?: Section.Home)
    }
    var categories by remember { mutableStateOf<List<CategoryRow>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<CategoryRow?>(null) }
    var items by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var openSeries by remember { mutableStateOf<OpenSeries?>(null) }
    var seriesDetails by remember { mutableStateOf<SeriesDetails?>(null) }
    var loadingCategories by remember { mutableStateOf(false) }
    var loadingItems by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var nowPlaying by remember { mutableStateOf<String?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loadFailure by remember { mutableStateOf<LoadFailure?>(null) }
    var categoryReload by remember { mutableStateOf(0) }
    var resumable by remember { mutableStateOf<Pair<String, String>?>(null) }
    /**
     * Which series the thing being played belongs to, when it is an episode.
     *
     * Written into the progress row so that the other device can say what it is: an episode id is a
     * number nothing else can resolve. See `ProgressRecord.seriesId`.
     */
    var playingSeriesId by remember { mutableStateOf<String?>(null) }
    var resumedFrom by remember { mutableStateOf<Long?>(null) }
    var userData by remember { mutableStateOf<UserDataExport?>(null) }
    var guide by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }
    var guideForChannel by remember { mutableStateOf<String?>(null) }
    var titles by remember { mutableStateOf<Map<String, IndexedTitle>>(emptyMap()) }
    var queue by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var detailFor by remember { mutableStateOf<BrowseItem?>(null) }
    /**
     * What else was on screen when a film's panel was opened.
     *
     * Carried rather than derived, because a film is now opened from five places and "what else is
     * there" is a different answer in each of them. Without it, a film started from the start screen
     * played with an empty switch panel behind it.
     */
    var detailFrom by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var detail by remember { mutableStateOf<MovieDetails?>(null) }
    var season by remember { mutableStateOf<Int?>(null) }
    /** The language chosen in the header, and the ones this listing actually offers. */
    var language by remember { mutableStateOf<String?>(null) }
    /** What was typed before a series was opened, so backing out of one does not lose it. */
    var queryBeforeSeries by remember { mutableStateOf("") }
    var languages by remember { mutableStateOf<List<String>>(emptyList()) }
    var artworkBytes by remember { mutableStateOf(0L) }
    var libraryCacheBytes by remember { mutableStateOf(0L) }
    // Ids that have already been asked about and answered with nothing. Session-scoped on purpose:
    // a provider that has dropped a title should not be re-asked on every recomposition, but it
    // should be asked again on the next launch, because providers do put things back.
    val unresolvable = remember { mutableSetOf<String>() }
    /** Which destinations have been entered since launch; see the category-restoring effect. */
    val visited = remember { mutableSetOf<Section>() }
    var dataMessage by remember { mutableStateOf<String?>(null) }
    var expiryDismissed by remember { mutableStateOf(false) }
    /** The name a damaged state file was kept under, until the viewer has read it once. */
    var unreadableFile by remember { mutableStateOf<String?>(null) }
    /** The same files as Settings lists them, which outlives the banner being dismissed. */
    var keptFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    val searchFocus = remember { FocusRequester() }
    // Declared here rather than beside the player watchdog that first needed it, because the
    // browsing side asks the same question: this is the one thing the client knows that the
    // provider's status code does not say.
    val accountExpired = remember(session) {
        expiryWarningFor(
            session.account.expiresAtEpochSeconds,
            System.currentTimeMillis() / 1000L,
        ) == ExpiryWarning.Expired
    }
    var upNext by remember { mutableStateOf<BrowseItem?>(null) }
    var guidePages by remember { mutableStateOf<Map<String, List<EpgEntry>>>(emptyMap()) }
    var guideLoading by remember { mutableStateOf(false) }
    var guideRefresh by remember { mutableStateOf(0) }
    val store = remember { DesktopUserData() }
    // Only ever read and cleared from here; it is written on the sign-in screen, which is the one
    // place a viewer says whether they want it kept at all.
    val vault = remember { CredentialVault() }
    var credentialsStored by remember { mutableStateOf(vault.hasStored) }
    val index = remember { TitleIndex() }
    val epg = remember { EpgCache() }
    // One record per title, unchanged between one opening of its panel and the next. Kept in memory
    // for half an hour so that walking back and forth through a category is browsing rather than
    // re-downloading; the listings themselves are still never cached.
    val movieRecords = remember { TimedCache<String, MovieDetails>() }
    val seriesRecords = remember { TimedCache<String, SeriesDetails>() }
    val scope = rememberCoroutineScope()

    // Everything the client keeps by provider id is scoped by this: the state file, the names, and
    // the category it reopens. It is the export format's own one-way hash — no host, no username,
    // no password — so scoping by it costs nothing that has to be guarded.
    val fingerprint = remember(session) {
        DesktopUserData.fingerprintOf(session.credentials.serverUrl, session.credentials.username)
    }

    /** Remembers what a title is called, so it can be shown from the saved list later. */
    fun record(contentType: String, item: BrowseItem) {
        val key = TitleIndex.keyOf(contentType, item.id)
        if (titles[key]?.label == item.label) return
        val entry = IndexedTitle(
            label = item.label,
            artworkUrl = item.artworkUrl,
            containerExtension = when (item) {
                is BrowseItem.Movie -> item.value.containerExtension
                is BrowseItem.Episode -> item.value.containerExtension
                is BrowseItem.Indexed -> item.containerExtension
                else -> null
            },
        )
        val updated = titles + (key to entry)
        titles = updated
        scope.launch { index.save(fingerprint, updated) }
    }

    LaunchedEffect(session) {
        titles = index.load(fingerprint)
        userData = store.load(fingerprint)
        // Asked once, straight after the load that could have set it: it describes this launch
        // rather than this account.
        unreadableFile = store.setAside?.name
    }

    /**
     * What a refusal says, so that browsing and playback tell the same story about the same cause.
     *
     * A 403 already hedges — expired, or every connection in use — because the status code cannot
     * tell those apart. When the account's own expiry date says which it is, the client says so
     * rather than offering the viewer both. That is the same exception the player makes, for the
     * same reason: it is a fact held here, not a guess about a failure it cannot see into.
     *
     * Null for anything that is not a refusal, so each caller keeps its own words for a fault.
     */
    fun refusalMessage(failure: Throwable): String? = (failure as? ProviderRefused)?.let { refused ->
        if (accountExpired && refused.code == 403) LIBRARY_EXPIRED
        else providerRefusedMessage(refused.code)
    }

    LaunchedEffect(section, session, categoryReload) {
        selectedCategory = null
        items = emptyList()
        openSeries = null
        seriesDetails = null
        detailFor = null
        // Cleared for every destination except the one a query is the whole point of. Typing on the
        // start screen moves to Search, and this effect runs on that move: clearing here would eat
        // the letter that caused it.
        if (section != Section.Search) query = ""
        language = null
        languages = emptyList()
        loadingCategories = true
        failure = null
        loadFailure = null
        // Still fetched even when the whole listing is in memory. The listing carries a category
        // *id* per title and nothing else, so the names in the picker can only come from here — and
        // this is the one request that was always small.
        categories = catchingExceptCancellation {
            when (section) {
                Section.Home, Section.Search, Section.Saved, Section.Guide, Section.Settings ->
                    emptyList<CategoryRow>()

                Section.Live -> client.liveCategories(session.credentials)
                    .map { CategoryRow(it.id, it.name) }
                Section.Movies -> client.movieCategories(session.credentials)
                    .map { CategoryRow(it.id, it.name) }
                Section.Series -> client.seriesCategories(session.credentials)
                    .map { CategoryRow(it.id, it.name) }
            }
        }.onFailure { failure ->
            // A banner rather than a screen: the rail still works, what is already loaded is still
            // there, and the one thing missing is the one thing the button offers to fetch again.
            loadFailure = LoadFailure(
                refusalMessage(failure) ?: "That library could not be loaded.",
            ) { categoryReload++ }
        }.getOrDefault(emptyList())
        loadingCategories = false
    }

    /**
     * What this viewer has kept, assembled from the stored state rather than fetched.
     *
     * Two lists, not three. It used to carry continue-watching as well, which was right while there
     * was nowhere else to put it and wrong once there was a start screen: something half-watched is
     * not something you *kept*, and having it in both places made My list a second, worse Start.
     * What is left answers one question — a heart is a verdict, a bookmark is an intention.
     */
    val myList = remember(userData, titles, library) {
        val data = userData ?: return@remember MyList()
        MyList(
            favourites = (
                data.movieFavorites.map { MOVIE_CONTENT_TYPE to it } +
                    data.seriesFavorites.map { SERIES_CONTENT_TYPE to it }
                )
                .sortedByDescending { (_, mark) -> mark.atEpochMillis }
                .mapNotNull { (type, mark) -> indexedItem(type, mark.contentId, titles, library) },
            saved = data.watchlist
                .sortedByDescending { it.addedAtEpochMillis }
                .mapNotNull { indexedItem(it.contentType, it.contentId, titles, library) },
        )
    }

    /**
     * What was left unfinished, newest first — the first row of the start screen.
     *
     * Separate from [myList] because it is a different kind of fact and now lives somewhere else.
     * The fraction travels with it: a poster with a bar under it is the only thing on that row that
     * says how much of an evening is left.
     */
    val continueWatching = remember(userData, titles, library) {
        val data = userData ?: return@remember emptyList<Pair<BrowseItem, Float>>()
        data.continueWatching().mapNotNull { record ->
            record.asIndexed(titles, library)?.let {
                it to WatchProgressPolicy.fraction(record.positionMs, record.durationMs).toFloat()
            }
        }
    }

    // Marking, playing and searching still work over the whole of it, so those paths need one list.
    val savedItems = remember(myList, continueWatching) {
        (continueWatching.map { it.first } + myList.favourites + myList.saved)
            .distinctBy { it.queueKey }
    }

    // Saved first, then recently watched, capped at forty — the rule lives in `:shared` because it
    // is about the format, not about this window. A channel with no name yet is left out rather than
    // shown as its id: the export carries ids, the title cache carries names, and one marked on the
    // phone and never opened here has no name to show.
    val guideChannels = remember(userData, titles, library) {
        val data = userData ?: return@remember emptyList<GuideChannel>()
        data.ownChannels().mapNotNull { id ->
            indexedItem(CHANNEL_CONTENT_TYPE, id, titles, library)?.let {
                GuideChannel(id = id, name = it.label, logoUrl = it.artworkUrl)
            }
        }
    }

    /**
     * Opens [category], optionally keeping what is in the search box.
     *
     * Keeping it is what makes the search work at all: type a word, pick one of the categories it
     * matched, and the same word goes on to filter what is inside it.
     */
    /**
     * What Live shows before a category is chosen: the channels this viewer actually uses.
     *
     * A provider with nine hundred categories and no starting point is a wall. The same list the
     * guide covers — saved first, then recently watched — costs no request at all, since the names
     * are in the title cache and the ids are in the stored state. Someone with neither sees the
     * category picker and a sentence, which is the honest state of a client that has not been used
     * yet.
     */
    val ownChannelRows = remember(guideChannels) { guideChannels.map { it.asBrowseItem() } }

    /** True when this section's whole listing is in memory, which is what makes a request moot. */
    val indexed = section.library?.let { library.has(it) } == true

    /**
     * Opens [category], or the whole library when it is null.
     *
     * **Null is now the normal state.** A category used to be compulsory because nothing could be
     * shown without one being fetched; with the listing in memory, opening Movies means all of the
     * films, exactly as it does on the phone, and a category is a way to narrow that rather than a
     * toll to pay first.
     *
     * Keeping the query is what makes the category search work: type a word, pick one of the
     * categories it matched, and the same word goes on to filter what is inside it.
     */
    fun openCategory(category: CategoryRow?, keepQuery: Boolean = false) {
        selectedCategory = category
        openSeries = null
        seriesDetails = null
        if (!keepQuery) query = ""
        onPreferencesChange(
            if (category == null) {
                preferences.withoutCategory(fingerprint, section.name)
            } else {
                preferences.withCategory(fingerprint, section.name, category.id)
            },
        )
        loadFailure = null
        // Nothing to fetch: the listing is already here, and filtering it by category id is a scan
        // over a list rather than a round trip.
        if (indexed) {
            items = emptyList()
            loadingItems = false
            return
        }
        if (category == null) {
            // Without the listing there is nothing honest to show for "everything", and asking for
            // it here would be the six-figure request outside the one place it is accounted for.
            items = emptyList()
            loadingItems = false
            return
        }
        loadingItems = true
        scope.launch {
            items = catchingExceptCancellation {
                when (section) {
                    Section.Live -> client.channels(session.credentials, category.id)
                        .map(BrowseItem::Channel)
                    Section.Movies -> client.movies(session.credentials, category.id)
                        .map(BrowseItem::Movie)
                    Section.Series -> client.series(session.credentials, category.id)
                        .map(BrowseItem::Series)
                    // None of these fetches a category; none reaches here.
                    Section.Home, Section.Search, Section.Saved, Section.Guide, Section.Settings ->
                        emptyList()
                }
            }.onFailure { failure ->
                // Without this the screen said "This category is empty", which is a different claim
                // from "the request failed" and the wrong one to make about someone's provider.
                loadFailure = LoadFailure(
                    refusalMessage(failure) ?: "That category could not be opened.",
                ) { openCategory(category, keepQuery = true) }
            }.getOrDefault(emptyList<BrowseItem>())
            loadingItems = false
        }
    }

    /** One film's record, from memory where it is there and from the provider where it is not. */
    suspend fun movieRecordFor(id: String): MovieDetails =
        movieRecords.get(id) ?: client.movieDetails(session.credentials, id).also {
            movieRecords.put(id, it)
        }

    suspend fun seriesRecordFor(id: String): SeriesDetails =
        seriesRecords.get(id) ?: client.seriesDetails(session.credentials, id).also {
            seriesRecords.put(id, it)
        }

    /**
     * Folds newly learned names into the cache and writes it out.
     *
     * Pulled out of the resolver below because a second caller now needs it: opening a series
     * teaches this client every episode in it, and that is worth keeping for the same reason a
     * title is.
     */
    suspend fun learn(found: Map<String, IndexedTitle>) {
        if (found.isEmpty()) return
        val updated = titles + found
        titles = updated
        index.save(fingerprint, updated)
    }

    /**
     * Opening a series: its record and its episodes, on one screen.
     *
     * By id rather than by listing row, because a series is now reachable from four places — its
     * category, the search results, My list and the start screen — and only the first of those has
     * a `SeriesSummary` in hand. What the caller *does* know is enough to draw the screen before the
     * request answers: a name and a poster, so the panel is never blank while it waits.
     *
     * A function rather than an effect so that a failure has something to repeat.
     */
    fun openSeriesById(id: String, name: String, posterUrl: String?) {
        openSeries = OpenSeries(id, name, posterUrl)
        seriesDetails = null
        // Put back on the way out. Inside a series the field filters episodes, so it has to be
        // cleared — but a series is now opened from the search results too, and coming back to an
        // empty search box after reading about one show is losing the thing you had just found.
        queryBeforeSeries = query
        query = ""
        loadingItems = true
        loadFailure = null
        season = null
        scope.launch {
            catchingExceptCancellation { seriesRecordFor(id) }
                .onSuccess { details ->
                    // The viewer may have gone somewhere else while this was in the air.
                    if (openSeries?.id != id) return@onSuccess
                    seriesDetails = details
                    // Every episode, named after the show it is from.
                    //
                    // This is what puts a half-watched episode back on the start screen. Continue
                    // watching resolves a progress row through this cache, and an episode id
                    // resolves through nothing else: the resolver above can ask a provider about a
                    // film or a series by id, and there is no `get_episode_info` to ask about an
                    // episode. An export carries `seriesId` for exactly this, but a file written by
                    // a build older than `v0.2.0-alpha.38` has none - and then the row was silently
                    // dropped and the viewer saw their films come back and their series not.
                    //
                    // Costs nothing: the episode list is already in hand, and it is the one moment
                    // this client ever knows which series an episode belongs to.
                    learn(
                        details.episodes.associate { episode ->
                            TitleIndex.keyOf(EPISODE_CONTENT_TYPE, episode.id) to IndexedTitle(
                                label = details.name,
                                artworkUrl = details.posterUrl,
                                containerExtension = episode.containerExtension,
                            )
                        },
                    )
                    // Open on the season this viewer was last in rather than on the first: someone
                    // who is four seasons deep does not want to be handed the pilot every time.
                    season = details.episodes.seasonToOpen(userData)
                }
                .onFailure {
                    loadFailure = LoadFailure(
                        refusalMessage(it) ?: "That series could not be opened.",
                    ) { openSeriesById(id, name, posterUrl) }
                }
            loadingItems = false
        }
    }

    /** The same, from whatever row was clicked. A stored id and a listing row both arrive here. */
    fun openSeries(item: BrowseItem) {
        when (item) {
            is BrowseItem.Series ->
                openSeriesById(item.value.id, item.value.name, item.value.posterUrl)

            is BrowseItem.Indexed ->
                openSeriesById(item.id, item.label, item.artworkUrl)

            else -> Unit
        }
    }

    /**
     * Reopens the category this section was left in.
     *
     * Keyed on the listing as well as the section, because the id can only be turned back into a
     * category once the names have arrived, and it gives way to any choice already made.
     */
    LaunchedEffect(section, categories) {
        if (selectedCategory != null || categories.isEmpty()) return@LaunchedEffect
        val firstVisit = visited.add(section)
        // The whole library is what someone means by opening Movies, so the **first** visit after
        // launch is not sent to a remembered shelf. Coming back to the same section later in the
        // session does restore it, because then it is where they just were rather than where they
        // were last week. Before the listing was held in memory this was the other way round out of
        // necessity: there was nothing to show without a category.
        if (firstVisit && indexed) return@LaunchedEffect
        // Live without a listing opens on the viewer's own channels instead — what someone returns
        // to on a television is a channel, not a shelf.
        if (section == Section.Live && firstVisit && ownChannelRows.isNotEmpty()) {
            return@LaunchedEffect
        }
        val remembered = preferences.categoriesFor(fingerprint)[section.name]
            ?: return@LaunchedEffect
        categories.firstOrNull { it.id == remembered }?.let { openCategory(it) }
    }

    /**
     * Plays [item], remembering [from] as what else is reachable without going back.
     *
     * The list is carried in rather than derived, because the same item can be started from a
     * category, from My list or from the guide, and "what else is there" is a different answer each
     * time. Whatever the viewer was looking at when they picked is the honest one.
     */
    fun start(item: BrowseItem, fromStart: Boolean, from: List<BrowseItem> = queue) {
        queue = from.filter { it !is BrowseItem.Series }
        val kind = when (item) {
            is BrowseItem.Movie -> MOVIE_CONTENT_TYPE
            is BrowseItem.Episode -> EPISODE_CONTENT_TYPE
            is BrowseItem.Indexed -> item.contentType.takeIf {
                it == MOVIE_CONTENT_TYPE || it == EPISODE_CONTENT_TYPE
            }
            else -> null
        }
        playingId = item.id
        // An episode's own label is "S2 E7 · Title", which says nothing about the show once the
        // list behind it is gone — and autoplay means the list is gone for hours at a time.
        nowPlaying = openSeries?.name
            ?.takeIf { item is BrowseItem.Episode }
            ?.let { "$it — ${item.label}" }
            ?: item.label
        resumable = kind?.let { it to item.id }
        playingSeriesId = when {
            item is BrowseItem.Episode -> item.value.seriesId
            // Reached from the start screen or My list, where the row was built from a stored
            // progress record that already carried it.
            item is BrowseItem.Indexed && item.contentType == EPISODE_CONTENT_TYPE ->
                userData?.watchProgress
                    ?.firstOrNull { it.contentType == EPISODE_CONTENT_TYPE && it.contentId == item.id }
                    ?.seriesId
            else -> null
        }
        kind?.let { record(it, item) }
        failure = if (player.isAvailable) null else VLC_MISSING
        if (!player.isAvailable) return

        // Live only, and asked for after playback has started rather than before it: the guide is
        // decoration around the picture, and making the first frame wait on metadata trades the
        // thing the viewer asked for against one they did not.
        guide = emptyList()
        // A channel can arrive here from its category, from the saved list or from the guide, and in
        // the last two cases only its id is known. All three are live, so all three get the strip and
        // count as watched.
        val liveId = when {
            item is BrowseItem.Channel -> item.value.id
            item is BrowseItem.Indexed && item.contentType == CHANNEL_CONTENT_TYPE -> item.id
            else -> null
        }
        guideForChannel = liveId
        if (liveId != null) record(CHANNEL_CONTENT_TYPE, item)

        val resume = if (fromStart || kind == null) null else userData?.resumePositionOf(kind, item.id)
        resumedFrom = resume
        val target = urlFor(session, item, library)
        player.play(target.url, (resume ?: 0L) / 1000L, target.headers)
    }

    /**
     * The stored state with the current playback position folded in, or null when there is none.
     *
     * Separate from writing it because two callers want the same fold and only one of them wants a
     * file: ending a title writes, and a window closing wants this *and* whatever else is unsaved,
     * in one write rather than two.
     */
    fun userDataWithPosition(): UserDataExport? {
        // Read the position before anything stops: afterwards there is none to read.
        val status = player.snapshot()
        val current = resumable ?: return null
        if (!status.isSeekable || status.timeMs <= 0L) return null
        return userData?.withProgress(
            contentType = current.first,
            contentId = current.second,
            positionMs = status.timeMs,
            durationMs = status.lengthMs,
            seriesId = playingSeriesId,
        )
    }

    /**
     * Writes down where the current title had got to.
     *
     * Its own step because two things end a title — going back and switching to another — and a
     * switch that forgot the position would be the more annoying of the two: nothing visibly stopped,
     * so nothing looks like it should have been saved.
     */
    suspend fun writePosition() {
        // Unconditional, unlike the ten-second checkpoint: this is the last chance the position
        // gets. Going back, switching title, signing out and closing the window all come through
        // here, and a write skipped because it looks like the one before it would be a write that
        // never happens at all.
        val updated = userDataWithPosition() ?: return
        userData = updated
        store.save(updated)
    }

    /**
     * Everything this screen has that the disk might not, for a window that is closing.
     *
     * The position is the obvious half. The other half is every mark — a heart, a bookmark, a title
     * crossed off — each of which updates the state in memory and then launches its write without
     * waiting. That is right while the client is running and wrong at the end of it: the dispatcher
     * threads are daemons, so a mark set a moment before the window closes is a write the exiting
     * process has no reason to finish.
     *
     * One write covers both, because the position is folded in before the document goes down. Saving
     * a document that has not changed costs a small file and settles the question; the alternative
     * is tracking which of half a dozen callers still has a job in flight.
     */
    suspend fun flushToDisk() {
        val updated = userDataWithPosition() ?: userData ?: return
        userData = updated
        store.save(updated)
    }

    fun rememberPosition() {
        scope.launch { writePosition() }
    }

    /**
     * Ends playback and puts the screen back to browsing.
     *
     * [waitForTheWrite] is for the one caller whose composition is about to end. Everywhere else —
     * the back button, a change of section — this screen is still here afterwards, so launching the
     * write and carrying on is right. Signing out is different: the screen leaves the composition in
     * the same breath, and a coroutine launched into a scope being torn down finishes nowhere. That
     * is the same reason the window's close handler blocks, and the same thing lost if it does not:
     * up to ten seconds of a film, or a film started a moment ago with no position at all.
     */
    fun stop(waitForTheWrite: Boolean = false) {
        if (waitForTheWrite) runBlocking { writePosition() } else rememberPosition()
        // Without this, backing out of a playback failure left the message on screen with nothing
        // behind it, because the failure is half of what puts the player there in the first place.
        failure = null
        queue = emptyList()
        player.stop()
        // Fullscreen exists for the picture. Returning to a grid of posters with no title bar and no
        // obvious way back is a trap, so it ends with the playback it was entered for.
        onFullscreenChange(false)
        playingId = null
        nowPlaying = null
        resumable = null
        resumedFrom = null
        guide = emptyList()
        guideForChannel = null
    }

    /**
     * Looks up the names of things marked somewhere else.
     *
     * The stored state carries provider ids and nothing else — deliberately, because it is the
     * export format and it has to stay interchangeable with the phone. So a film hearted on the
     * phone arrives here as the number 501 and cannot be shown at all. Until now the honest answer
     * was to leave it out until it happened to be browsed; the better honest answer is to ask.
     *
     * **Only what is marked, and only when My list is open.** This is a request per title, which is
     * affordable for the handful someone has kept and absurd for a library. Bounded at forty, four
     * at a time, and the results go into the same title cache that browsing fills.
     *
     * **Films and series only.** An episode is identified in the export by its own id, and there is
     * no endpoint that turns an episode id into anything without knowing its series first; a channel
     * would need the whole live listing, which is the one request this client refuses. Both stay
     * invisible until they are browsed, which is the limitation the format buys interchange with.
     */
    LaunchedEffect(section, userData, titles) {
        if (section != Section.Saved) return@LaunchedEffect
        val data = userData ?: return@LaunchedEffect
        val wanted = (
            data.movieFavorites.map { MOVIE_CONTENT_TYPE to it.contentId } +
                data.seriesFavorites.map { SERIES_CONTENT_TYPE to it.contentId } +
                data.watchlist.map { it.contentType to it.contentId } +
                data.watchProgress.map { it.contentType to it.contentId }
            )
            .distinct()
            .filter { (type, _) -> type == MOVIE_CONTENT_TYPE || type == SERIES_CONTENT_TYPE }
            .filter { (type, id) ->
                val key = TitleIndex.keyOf(type, id)
                titles[key] == null && key !in unresolvable
            }
            .take(40)
        if (wanted.isEmpty()) return@LaunchedEffect

        val found = mutableMapOf<String, IndexedTitle>()
        wanted.chunked(4).forEach { batch ->
            coroutineScope {
                batch.map { (type, id) ->
                    async {
                        val key = TitleIndex.keyOf(type, id)
                        val entry = catchingExceptCancellation {
                            if (type == MOVIE_CONTENT_TYPE) {
                                movieRecordFor(id).let {
                                    IndexedTitle(it.name, it.posterUrl, it.containerExtension)
                                }
                            } else {
                                seriesRecordFor(id).let { IndexedTitle(it.name, it.posterUrl) }
                            }
                        }.getOrNull()
                        if (entry == null) unresolvable += key else found[key] = entry
                    }
                }.awaitAll()
            }
        }
        learn(found)
    }

    /**
     * The film's own record, fetched when its panel opens.
     *
     * A failure leaves [detail] null and the panel open on what the listing already knew, which is
     * enough to play. Metadata is what someone reads before deciding; it is not a precondition for
     * watching, and a client that refuses to play a film because its plot did not arrive would have
     * the priorities backwards.
     */
    LaunchedEffect(detailFor) {
        val item = detailFor ?: run {
            detail = null
            return@LaunchedEffect
        }
        // Shown at once when it is already known, so reopening a panel does not blank the paragraph
        // and fill it in again.
        detail = movieRecords.get(item.id)
        if (detail == null) {
            detail = catchingExceptCancellation { movieRecordFor(item.id) }.getOrNull()
        }
    }

    fun switchTo(item: BrowseItem) {
        if (item.id == playingId) return
        rememberPosition()
        start(item, fromStart = false)
    }

    /**
     * One place along the list, wrapping at the ends.
     *
     * Wrapping because a channel list is how a television behaves and stopping dead at the last
     * entry is a worse answer than starting again at the first.
     */
    fun step(direction: Int) {
        if (queue.size < 2) return
        val index = queue.indexOfFirst { it.id == playingId }
        if (index < 0) return
        switchTo(queue[(index + direction + queue.size) % queue.size])
    }

    // Set from composition rather than passed down, because the window that reads it is above this
    // screen and the list it steps through is below.
    SideEffect {
        keys.step = if (playingId != null) ::step else null
        // Unconditionally, unlike the step handler beside it: a window closed on the browsing
        // screen has no position to write but may well have a mark that has not reached the disk.
        keys.flushToDisk = ::flushToDisk
        keys.nowPlaying = nowPlaying
    }

    // Signing out takes this screen out of composition; a handler left behind would keep answering
    // for a list that is gone.
    DisposableEffect(Unit) {
        onDispose {
            keys.step = null
            keys.focusSearch = null
            keys.flushToDisk = null
            keys.nowPlaying = null
            keys.playerOnScreen = false
        }
    }

    LaunchedEffect(guideForChannel) {
        val channelId = guideForChannel ?: return@LaunchedEffect
        val entries = epg.get(channelId)
            ?: client.shortEpg(session.credentials, channelId).also { epg.put(channelId, it) }
        if (guideForChannel == channelId) guide = entries
    }

    // Two seconds of actual playback before a channel counts as watched, the same rule the phone
    // follows. Clicking through a category should not fill the recent list with channels nobody
    // stayed on.
    LaunchedEffect(guideForChannel) {
        val channelId = guideForChannel ?: return@LaunchedEffect
        delay(2_000)
        if (guideForChannel != channelId || !player.snapshot().isPlaying) return@LaunchedEffect
        val updated = (userData ?: return@LaunchedEffect).withRecentChannel(channelId)
        userData = updated
        store.save(updated)
    }

    // Keyed on the *set* of channels rather than the list, so coming back from a channel — which
    // moves it to the front of the recent list — does not re-ask for forty programmes that have not
    // changed. Results are merged in per batch, so rows fill from the top rather than all at once,
    // and what was already on screen stays there until its replacement arrives.
    LaunchedEffect(section, guideRefresh, guideChannels.map { it.id }.toSet()) {
        if (section != Section.Guide || guideChannels.isEmpty()) return@LaunchedEffect
        // What is still current is shown before anything is asked for, so returning to the guide
        // paints immediately instead of filling in from the top all over again.
        guidePages = guideChannels.mapNotNull { channel ->
            epg.get(channel.id)?.let { channel.id to it }
        }.toMap()
        val missing = guideChannels.filter { it.id !in guidePages }
        if (missing.isEmpty()) return@LaunchedEffect

        guideLoading = true
        // Four at a time. `get_short_epg` answers one channel per request, so forty channels are
        // forty requests; a small bound keeps that from arriving at the provider as a burst.
        missing.chunked(4).forEach { batch ->
            val fetched = coroutineScope {
                batch.map { channel ->
                    async { channel.id to client.shortEpg(session.credentials, channel.id) }
                }.awaitAll()
            }
            fetched.forEach { (id, entries) -> epg.put(id, entries) }
            guidePages = guidePages + fetched
        }
        guideLoading = false
    }

    /**
     * The next episode, offered near the end and started at it.
     *
     * Episodes only. Rolling from one film into whatever the provider happened to list after it is
     * nobody's idea of an evening, and a category is not a playlist. A series' episode list is.
     */
    LaunchedEffect(resumable, queue) {
        upNext = null
        val (kind, contentId) = resumable ?: return@LaunchedEffect
        if (kind != EPISODE_CONTENT_TYPE) return@LaunchedEffect
        val index = queue.indexOfFirst { it.id == contentId }
        val next = queue.getOrNull(index + 1) ?: return@LaunchedEffect
        var sawTheEndComing = false
        while (true) {
            delay(1_000)
            val status = player.snapshot()
            val remaining = status.lengthMs - status.timeMs
            val closing = status.lengthMs > 0L && remaining in 0L..UP_NEXT_LEAD_MS
            if (closing) sawTheEndComing = true
            upNext = next.takeIf { closing }
            // The second condition is for libvlc resetting its clock the instant a file finishes,
            // which can land between two polls. A pause never resets it, so this cannot fire on one.
            val ended = WatchProgressPolicy.hasReachedEnd(status.timeMs, status.lengthMs) ||
                (sawTheEndComing && status.timeMs == 0L)
            if (!ended) continue
            rememberPosition()
            start(next, fromStart = false)
            return@LaunchedEffect
        }
    }

    /**
     * Watches for a stream that never arrives.
     *
     * Two different silences, both of which used to look like a black screen with working controls.
     * libvlc says so itself when it gives up — a dead channel, an account at its connection limit —
     * and that is reported at once. When it says nothing at all and no frame comes either, the wait
     * is given twenty seconds before the client admits it, which is long enough for a slow provider
     * opening a 4K stream and short enough that nobody sits there wondering.
     *
     * Neither case stops the player. It keeps trying underneath, so a picture that arrives late
     * clears the message by itself rather than needing the viewer to dismiss it.
     */
    LaunchedEffect(playingId) {
        val id = playingId ?: return@LaunchedEffect
        if (!player.isAvailable) return@LaunchedEffect
        var waitedMillis = 0L
        // Per title, because a standstill on the last one says nothing about this one.
        val stall = StallWatch()
        while (playingId == id) {
            val status = player.snapshot()
            // libvlc says only *that* it gave up — its error event carries no reason, and reading
            // `libvlc_errmsg` from an event thread is not something to rely on. So the client says
            // no more than it knows, with one exception: if the account has already expired, that
            // is a fact it holds rather than a guess about a failure it cannot see into.
            val refusedMessage = if (accountExpired) STREAM_EXPIRED else STREAM_REFUSED
            val stalled = stall.observe(status.isPlaying, status.timeMs, WATCH_POLL_MS)
            when {
                player.failedToOpen -> failure = refusedMessage
                // Ahead of the branch below, which is the one that used to make every later
                // failure invisible: once a stream has produced a frame it has a position, and a
                // position was taken as proof that all was well for the rest of the film.
                stalled -> failure = STREAM_STALLED
                status.isPlaying || status.timeMs > 0L ->
                    if (failure in CLEARABLE_FAILURES) failure = null
                waitedMillis >= 20_000L -> failure = STREAM_SILENT
            }
            delay(WATCH_POLL_MS)
            waitedMillis += WATCH_POLL_MS
        }
    }

    /**
     * Puts the remembered languages onto whatever has just started.
     *
     * Tracks do not exist until libvlc has parsed the container, so this waits for them rather than
     * asking once and giving up — and stops as soon as it has had something to work with, because a
     * title that does not carry the preferred language should play in what it has rather than be
     * asked about for the length of the film.
     */
    LaunchedEffect(playingId) {
        if (playingId == null || preferences.trackLanguages.isEmpty) return@LaunchedEffect
        val languages = preferences.trackLanguages
        repeat(30) {
            delay(500)
            if (playingId == null) return@LaunchedEffect
            val applied = player.applyLanguages(
                audioLanguage = languages.audioLanguage,
                subtitleLanguage = languages.subtitleLanguage,
                subtitlesDisabled = languages.subtitlesDisabled,
            )
            if (applied) return@LaunchedEffect
        }
    }

    /**
     * Learns from a track the viewer picked **by hand**.
     *
     * Only a deliberate choice: what libvlc selected on its own must never reach the preferences, or
     * a film carrying nothing but French audio would make French the preference for everything
     * afterwards without anyone having asked. The shared rule returns null when nothing changed,
     * which is what keeps this from writing a file per menu click.
     */
    fun learnLanguage(option: TrackOption, audio: Boolean) {
        val selection = if (audio) {
            TrackLanguageSelection(audioLanguage = option.language)
        } else {
            TrackLanguageSelection(
                subtitleLanguage = option.language,
                subtitlesTurnedOff = option.id == SUBTITLES_OFF,
            )
        }
        if (selection.isEmpty) return
        preferences.trackLanguages.learnFrom(selection)?.let {
            onPreferencesChange(preferences.withTrackLanguages(it))
        }
    }

    /**
     * Where playback has got to, written down every ten seconds.
     *
     * For the ways an evening ends that nothing else covers: a crash, a power cut, a laptop lid.
     * Going back, switching title and closing the window each write on their own.
     *
     * It asks first whether there is anything to write. Without that the answer was always yes, and
     * a film left paused rewrote the whole user-data file every ten seconds to record that nothing
     * had happened — which is also a recomposition every ten seconds, for the same nothing.
     */
    LaunchedEffect(resumable) {
        val (kind, contentId) = resumable ?: return@LaunchedEffect
        var writtenPosition: Long? = null
        var writtenDuration: Long? = null
        while (true) {
            delay(10_000)
            val status = player.snapshot()
            if (!status.isSeekable || status.timeMs <= 0L) continue
            if (!WatchProgressPolicy.isWorthWriting(
                    writtenPosition,
                    writtenDuration,
                    status.timeMs,
                    status.lengthMs,
                )
            ) {
                continue
            }
            val updated = (userData ?: continue).withProgress(
                contentType = kind,
                contentId = contentId,
                positionMs = status.timeMs,
                durationMs = status.lengthMs,
                seriesId = playingSeriesId,
            )
            userData = updated
            store.save(updated)
            writtenPosition = status.timeMs
            writtenDuration = status.lengthMs
        }
    }

    /**
     * What has been typed, once the typing has stopped.
     *
     * **Debounced, and shared by both jobs it feeds**: narrowing a library and searching all three.
     * Neither is the cheap per-keystroke filter it used to be — a category of two hundred has become
     * a listing of six figures — so a fifth of a second of quiet is what stands between a filter box
     * and a stutter. Clearing the box is immediate, because putting a list back is not work anyone
     * should wait for.
     */
    var term by remember { mutableStateOf("") }
    LaunchedEffect(query, section) {
        if (query == term) return@LaunchedEffect
        if (query.isBlank()) {
            term = ""
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        term = query
    }

    /**
     * The categories whose names match, which is the only kind of search this provider can answer.
     *
     * `player_api.php` has no search action, and the library it would have to search is six figures
     * of titles — the one request this client refuses to make. What it *does* have in hand is the
     * category list, all nine hundred of them, and on a provider organised as `DE | SPORT`,
     * `UK | MOVIES 4K` and so on, that list is how anyone actually finds anything.
     *
     * Eight, because this is a hint above the content rather than a results page.
     */
    val matchingCategories = remember(categories, query) {
        if (query.isBlank()) {
            emptyList()
        } else {
            val needle = SearchTextNormalizer.normalize(query)
            categories.filter { SearchTextNormalizer.normalize(it.name).contains(needle) }.take(8)
        }
    }

    /**
     * The browsing order for this section, and the ordered list.
     *
     * Episodes are never reordered: a series lists them in the order they were made, and no
     * alternative to that is an improvement. My list is not reordered either — it is already in the
     * order it was built, continue-watching first.
     */
    val sortOptions = remember(section) { sortOptionsFor(section) }
    val sortName = preferences.sorts[section.name]
        ?.takeIf { name -> sortOptions.any { it.first == name } }
        ?: sortOptions.firstOrNull()?.first

    /**
     * The whole of whatever this section browses, from memory where it is held.
     *
     * Mapping a six-figure listing into rows is a hundred thousand small objects, so it is done once
     * per section and category rather than once per frame — which is what `remember` is for here.
     * The grid underneath is lazy, so the rows that are never scrolled to are never measured.
     */
    val indexedItems = remember(section, selectedCategory, library, term, openSeries) {
        // Nothing to browse while a series is open: what is on screen then is its episodes, and the
        // typed word belongs to those. Without this, searching inside a series would also re-filter
        // the twenty thousand series behind it on every keystroke, for a list nobody is looking at.
        if (openSeries != null) return@remember null
        val kind = section.library ?: return@remember null
        if (!library.has(kind)) return@remember null
        val category = selectedCategory?.id
        when (kind) {
            LibraryKind.Channels -> library.channelsIn(category, term).map(BrowseItem::Channel)
            LibraryKind.Movies -> library.moviesIn(category, term).map(BrowseItem::Movie)
            LibraryKind.Series -> library.seriesIn(category, term).map(BrowseItem::Series)
        }
    }

    /**
     * Category names by id, which is the only thing the listing itself does not carry.
     *
     * A provider's listing gives every title a category *id* and nothing else, so the names have to
     * come from the picker's own request — which is fetched anyway, and is the one request that was
     * always small.
     */
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }

    /** Every episode of the open series, in the order the provider listed them. */
    val episodes = remember(seriesDetails) {
        seriesDetails?.episodes?.map(BrowseItem::Episode).orEmpty()
    }

    val shown = when {
        openSeries != null -> episodes
        section == Section.Saved -> savedItems
        indexedItems != null -> indexedItems
        section == Section.Live && selectedCategory == null -> ownChannelRows
        else -> items
    }
    val seasons = remember(episodes) {
        episodes.map { it.value.seasonNumber }.distinct().sorted()
    }

    /**
     * What is actually on screen, arranged **off the composition thread**.
     *
     * This used to be a `remember`, which is right for work measured in single milliseconds and
     * wrong at the size a library turned out to be: ordering 180,000 films by name is 732ms on the
     * owner's machine — the shared rule folds a sort key per title — and as part of a composition
     * that is three-quarters of a second of a window that does not redraw. Choosing a sort order is
     * exactly when a viewer is watching the screen.
     *
     * The previous list stays up while the next one is built, so nothing blinks; [arranging] is what
     * keeps the empty-list hint from claiming a category is empty during the gap.
     */
    var visible by remember { mutableStateOf<List<BrowseItem>>(emptyList()) }
    var arranging by remember { mutableStateOf(true) }
    LaunchedEffect(shown, term, sortName, openSeries, season, seasons, indexedItems, language) {
        arranging = true
        visible = withContext(Dispatchers.Default) {
            // One season at a time, unless the series has only one — a picker over a single choice
            // is furniture. A search inside a series looks across all of them, because someone
            // typing a title is not thinking about which season it is in.
            val bySeason =
                if (openSeries != null && seasons.size > 1 && season != null && term.isBlank()) {
                    shown.filter { (it as? BrowseItem.Episode)?.value?.seasonNumber == season }
                } else {
                    shown
                }
            val filtered = when {
                // Already narrowed, by names the index folded once when the listing arrived. Doing
                // it again here would fold a six-figure library on every keystroke.
                indexedItems != null && openSeries == null -> bySeason
                term.isBlank() -> bySeason
                else -> {
                    val needle = SearchTextNormalizer.normalize(term)
                    bySeason.filter { SearchTextNormalizer.normalize(it.label).contains(needle) }
                }
            }
            // Never inside a series: every episode of one is in the language the series is.
            val byLanguage = if (language == null || openSeries != null) {
                filtered
            } else {
                filtered.filter { it.languageTag(categoryNames) == language }
            }
            if (openSeries != null) byLanguage else byLanguage.ordered(sortName)
        }
        arranging = false
    }

    /**
     * Which languages this listing actually offers.
     *
     * Computed off the composition thread for the same reason the ordering is: it is one pass of a
     * string scan over a six-figure list. Only offered when there is more than one — a menu with a
     * single entry is furniture, and on a provider that names nothing there are none at all.
     */
    LaunchedEffect(shown, categoryNames) {
        languages = withContext(Dispatchers.Default) {
            shown.asSequence()
                .mapNotNull { it.languageTag(categoryNames) }
                .distinct()
                .sortedBy { languageDisplayName(it) }
                .toList()
        }
        // A language that has gone with the category it was chosen in must not go on filtering
        // invisibly.
        if (language != null && language !in languages) language = null
    }

    /**
     * What the search box finds across every listing that was read.
     *
     * Over the settled term rather than over every keystroke — see [term] — and refused under two
     * characters by the index itself.
     */
    val hits = remember(term, library, section) {
        if (section == Section.Search) library.search(term) else LibraryHits()
    }
    val hitItems = remember(hits) {
        Triple(
            hits.channels.map(BrowseItem::Channel),
            hits.movies.map(BrowseItem::Movie),
            hits.series.map(BrowseItem::Series),
        )
    }

    /** The newest titles across both video libraries, for the start screen. */
    val recentlyAdded = remember(library) {
        library.recentlyAdded().mapNotNull { entry ->
            indexedItem(
                if (entry.kind == ResumableKind.Movie) MOVIE_CONTENT_TYPE else SERIES_CONTENT_TYPE,
                entry.contentId,
                titles,
                library,
            )
        }
    }

    /** Which stored list a browse item belongs to, or null when it cannot be marked. */
    fun contentTypeOf(item: BrowseItem): String? = when (item) {
        is BrowseItem.Movie -> MOVIE_CONTENT_TYPE
        is BrowseItem.Series -> SERIES_CONTENT_TYPE
        is BrowseItem.Channel -> CHANNEL_CONTENT_TYPE
        is BrowseItem.Indexed -> item.contentType
        else -> null
    }

    /**
     * The stored state, arranged for asking about one title at a time.
     *
     * Every tile in a grid asks whether it is marked, saved and how far through it is — three
     * questions, once per item, on every recomposition. Answered against the lists directly, that is
     * a scan per question: a category of two hundred against a few hundred stored rows is tens of
     * thousands of comparisons for one frame, and it grows with how much someone has watched, which
     * is the worst way for a slowdown to arrive.
     *
     * Rebuilt only when the stored state changes, which is what `remember` is for.
     */
    val progressByKey = remember(userData) {
        userData?.watchProgress?.associateBy { "${it.contentType}:${it.contentId}" }.orEmpty()
    }
    val savedKeys = remember(userData) {
        userData?.watchlist?.map { "${it.contentType}:${it.contentId}" }?.toSet().orEmpty()
    }
    val favouriteFilms = remember(userData) {
        userData?.movieFavorites?.map { it.contentId }?.toSet().orEmpty()
    }
    val favouriteSeries = remember(userData) {
        userData?.seriesFavorites?.map { it.contentId }?.toSet().orEmpty()
    }

    /**
     * How far through a listed title this viewer is, or null for one they have not started.
     *
     * The stored state already knows; the lists simply were not asking. Picking up a series after a
     * fortnight otherwise means opening episodes until one of them starts somewhere other than the
     * beginning, which is the question the row could have answered without being clicked.
     */
    fun watchedFraction(item: BrowseItem): Float? {
        val type = contentTypeOf(item)?.let { if (item is BrowseItem.Episode) EPISODE_CONTENT_TYPE else it }
            ?: return null
        val record = progressByKey["$type:${item.id}"] ?: return null
        return WatchProgressPolicy.fraction(record.positionMs, record.durationMs).toFloat()
    }

    /**
     * How long this title runs, where the client actually knows.
     *
     * An episode carries it in the listing; a film only once its record has been fetched, which is
     * why marking one watched is offered on its panel rather than on its poster. Nothing is invented
     * where it is unknown — the format refuses a progress row without a duration, and a made-up one
     * would travel to the phone and be believed there.
     */
    fun durationMsOf(item: BrowseItem): Long? = when (item) {
        is BrowseItem.Episode -> item.value.durationSeconds?.toLong()?.times(1_000L)
        is BrowseItem.Movie -> detail?.takeIf { it.id == item.id }?.durationSeconds
            ?.toLong()?.times(1_000L)
        else -> null
    }

    /**
     * Marks a title watched, or forgets that it was.
     *
     * The case this exists for is a title seen somewhere else entirely — another device, another
     * client, television years ago. Without it the list keeps offering something the viewer has
     * already finished, and no amount of watching it here will fix that.
     */
    fun toggleWatched(item: BrowseItem) {
        val type = if (item is BrowseItem.Episode) EPISODE_CONTENT_TYPE else contentTypeOf(item)
        if (type == null) return
        val data = userData ?: return
        val fraction = watchedFraction(item)
        val updated = if (fraction != null && fraction >= WATCHED_ENOUGH) {
            data.clearProgress(type, item.id)
        } else {
            data.markWatched(
                contentType = type,
                contentId = item.id,
                durationMs = durationMsOf(item) ?: return,
                // Carried for the same reason playback carries it: without it the start screen
                // cannot name the episode, and an export hands the other device a number.
                seriesId = (item as? BrowseItem.Episode)?.value?.seriesId,
            )
        }
        record(type, item)
        userData = updated
        scope.launch { store.save(updated) }
    }

    /** Offered only where a duration is known, which is what makes the mark honest. */
    fun watchedToggleFor(item: BrowseItem): (() -> Unit)? {
        val known = durationMsOf(item) != null
        val alreadyMarked = (watchedFraction(item) ?: 0f) >= WATCHED_ENOUGH
        return if (known || alreadyMarked) ({ toggleWatched(item) }) else null
    }

    fun marksFor(item: BrowseItem): Marks? {
        val type = contentTypeOf(item) ?: return null
        if (userData == null) return null
        return Marks(
            favourite = when (type) {
                MOVIE_CONTENT_TYPE -> item.id in favouriteFilms
                SERIES_CONTENT_TYPE -> item.id in favouriteSeries
                else -> false
            },
            saved = "$type:${item.id}" in savedKeys,
            canFavourite = type != CHANNEL_CONTENT_TYPE,
        )
    }

    fun toggleFavourite(item: BrowseItem) {
        val type = contentTypeOf(item) ?: return
        val data = userData ?: return
        val updated = when (type) {
            MOVIE_CONTENT_TYPE -> data.toggleMovieFavourite(item.id)
            SERIES_CONTENT_TYPE -> data.toggleSeriesFavourite(item.id)
            // The export format has no live favourites, only a saved list. Rather than invent one
            // the phone would not read, a channel offers the bookmark alone.
            else -> return
        }
        record(type, item)
        userData = updated
        scope.launch { store.save(updated) }
    }

    fun toggleSaved(item: BrowseItem) {
        val type = contentTypeOf(item) ?: return
        val data = userData ?: return
        record(type, item)
        val updated = data.toggleSaved(type, item.id)
        userData = updated
        scope.launch { store.save(updated) }
    }

    /**
     * What clicking a row means, in one place.
     *
     * Five screens now open things — a category, the start screen, the search results, My list and
     * the guide — and each of them was deciding for itself what a click on a series meant. One of
     * them decided wrong: a series reached from My list was passed to the player, which built an
     * *episode* URL out of a series id and played nothing at all.
     *
     * [from] is what else is reachable without going back, and it is different for every caller:
     * the category you were looking at, the things you have kept, or the results you searched for.
     */
    fun open(item: BrowseItem, from: List<BrowseItem> = visible) {
        when {
            item is BrowseItem.Series -> openSeries(item)
            item is BrowseItem.Indexed && item.contentType == SERIES_CONTENT_TYPE -> openSeries(item)
            // A film is read about before it is watched. A channel and an episode are not: one is
            // zapped to and the other was already chosen when the series was.
            item.isMovie() -> {
                detailFor = item
                detailFrom = from
            }
            item is BrowseItem.Episode -> start(item, fromStart = false, from = episodes)
            else -> start(item, fromStart = false, from = from)
        }
    }

    /** Takes a title off the unfinished row without pretending it was finished. */
    fun forgetProgress(item: BrowseItem) {
        val type = if (item is BrowseItem.Episode) {
            EPISODE_CONTENT_TYPE
        } else {
            contentTypeOf(item)
        } ?: return
        val updated = (userData ?: return).clearProgress(type, item.id)
        userData = updated
        scope.launch { store.save(updated) }
    }

    /**
     * The shortcut waiting for a key, and the wire that delivers it.
     *
     * While this is set the window stops acting on keys and hands each press here instead —
     * otherwise pressing space to rebind it would pause a film, and pressing `F` would go
     * fullscreen. A press that cannot be a shortcut, escape included, simply ends the capture,
     * which is what makes escape the way out of a rebinding nobody meant to start.
     */
    var capturingShortcut by remember { mutableStateOf<Shortcut?>(null) }
    val currentPreferences by rememberUpdatedState(preferences)
    DisposableEffect(capturingShortcut) {
        val target = capturingShortcut
        keys.capture = if (target == null) {
            null
        } else {
            { event ->
                if (KeyBinding.isBindable(event)) {
                    onPreferencesChange(
                        currentPreferences.withKeyBinding(target, KeyBinding.of(event)),
                    )
                }
                capturingShortcut = null
            }
        }
        onDispose { keys.capture = null }
    }

    /**
     * Whether the player is the thing on screen.
     *
     * The keys are gated on this rather than on the player merely holding media, which is the
     * difference the owner asked for: a space bar pressed while browsing belongs to the search box,
     * not to a film in another section. It also drives the rail below.
     */
    val playerOnScreen = nowPlaying != null || failure != null
    SideEffect { keys.playerOnScreen = playerOnScreen }

    /**
     * The player, written once and drawn in one of two windows.
     *
     * Declared here rather than inline because fullscreen is a **different window** — see
     * [FullscreenPlayerWindow] for why it has to be — and the same twenty arguments must not be
     * written out twice with a chance of drifting apart.
     */
    val playerContent: @Composable () -> Unit = {
        PlayerView(
            player = player,
            title = nowPlaying,
            failure = failure,
            resumedFrom = resumedFrom,
            guide = guide,
            queue = queue,
            playingId = playingId,
            // Only what the guide cache already holds. Fetching here would be a request per
            // row while a stream is playing, over the same connection the video wants, for a
            // panel that is open for seconds.
            nowOn = { item, seconds ->
                val id = (item as? BrowseItem.Indexed)
                    ?.takeIf { it.contentType == CHANNEL_CONTENT_TYPE }?.id
                    ?: (item as? BrowseItem.Channel)?.id
                id?.let { epg.get(it) }
                    ?.let { EpgSelection.nowPlaying(it, seconds) }
                    ?.title
            },
            upNext = upNext,
            onPlayNext = { upNext?.let { switchTo(it) } },
            onStep = { step(it) },
            onSwitch = { switchTo(it) },
            fullscreen = fullscreen,
            onToggleFullscreen = { onFullscreenChange(!fullscreen) },
            onTrackChosen = { option, audio -> learnLanguage(option, audio) },
            skipBackSeconds = preferences.safeSkipBack,
            skipForwardSeconds = preferences.safeSkipForward,
            onStartOver = {
                queue.firstOrNull { it.id == playingId }?.let { start(it, fromStart = true) }
            },
            onRetry = queue.firstOrNull { it.id == playingId }?.let { item ->
                { start(item, fromStart = false) }
            },
            onBack = { stop() },
        )
    }

    Row(Modifier.fillMaxSize().background(Night)) {
        // The rail goes with the browsing screen rather than framing the picture. It used to sit
        // beside a playing film taking ninety-six pixels of it, which is a strip of the picture
        // spent on the list of things nobody is watching — and it made the difference between
        // playing and playing full-screen look like nothing at all.
        if (!playerOnScreen) {
            NavigationRail(
                section = section,
                sections = sections,
                accountLabel = session.account.label,
                onSectionChange = {
                    if (it != section) {
                        stop()
                        // A rebinding left waiting would go on swallowing keys on a screen that
                        // cannot show what it is waiting for.
                        capturingShortcut = null
                        section = it
                        onPreferencesChange(preferences.copy(section = it.name))
                    }
                },
            )
        }

        Box(Modifier.fillMaxSize()) {
            if (playerOnScreen && fullscreen) {
                // Read when fullscreen is entered rather than every frame: it is an AWT call, and
                // the answer only changes when the window is dragged to another monitor.
                val bounds = remember(fullscreen) { screenBounds() }
                FullscreenPlayerWindow(
                    screenBounds = bounds,
                    onClose = { onFullscreenChange(false) },
                    onKeyEvent = onKeyEvent,
                ) {
                    playerContent()
                    // F1 belongs over whatever is on top, and while this window is up, that is
                    // this window. The main one draws the same panel when it is.
                    if (keys.helpVisible) {
                        KeyboardHelpOverlay(
                            bindings = preferences.shortcutBindings,
                            skipBackSeconds = preferences.safeSkipBack,
                            skipForwardSeconds = preferences.safeSkipForward,
                            onClose = { keys.helpVisible = false },
                        )
                    }
                }
                // What the main window shows meanwhile. Leaving the last frame frozen there looked
                // like two players, and a black rectangle looked like a fault.
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        nowPlaying ?: "Playing",
                        color = Ink,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Playing full-screen.",
                        color = InkMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextPill("Leave fullscreen") { onFullscreenChange(false) }
                }
            } else if (playerOnScreen) {
                playerContent()
            } else if (detailFor != null) {
                val item = detailFor!!
                MovieDetailPanel(
                    item = item,
                    details = detail,
                    resumeFrom = userData?.resumePositionOf(MOVIE_CONTENT_TYPE, item.id),
                    // The same answer the grid gets, from the same lookups: one screen must not
                    // decide differently from another about the same title.
                    marks = marksFor(item) ?: Marks(favourite = false, saved = false),
                    onToggleFavourite = { toggleFavourite(item) },
                    onToggleSaved = { toggleSaved(item) },
                    watched = watchedFraction(item),
                    onToggleWatched = watchedToggleFor(item),
                    onPlay = { fromStart ->
                        detailFor = null
                        start(item, fromStart = fromStart, from = detailFrom)
                    },
                    onClose = { detailFor = null },
                )
            } else if (openSeries != null) {
                val openedSeries = openSeries!!
                val next = remember(seriesDetails, userData) {
                    seriesDetails?.episodes?.nextEpisodeToWatch(userData)
                }
                SeriesDetailPanel(
                    title = openedSeries.name,
                    details = seriesDetails,
                    posterUrl = openedSeries.posterUrl,
                    episodes = visible,
                    seasons = seasons,
                    season = season,
                    onSeason = { season = it },
                    query = query,
                    onQueryChange = { query = it },
                    searchFocus = searchFocus,
                    nextEpisode = next?.let(BrowseItem::Episode),
                    nextEpisodeLabel = next?.displayLabel,
                    resumeFrom = next?.let {
                        userData?.resumePositionOf(EPISODE_CONTENT_TYPE, it.id)
                    },
                    marks = marksFor(
                        BrowseItem.Indexed(SERIES_CONTENT_TYPE, openedSeries.id, openedSeries.name, openedSeries.posterUrl, null),
                    ),
                    onToggleFavourite = {
                        toggleFavourite(
                            BrowseItem.Indexed(
                                SERIES_CONTENT_TYPE, openedSeries.id, openedSeries.name, openedSeries.posterUrl, null,
                            ),
                        )
                    },
                    onToggleSaved = {
                        toggleSaved(
                            BrowseItem.Indexed(
                                SERIES_CONTENT_TYPE, openedSeries.id, openedSeries.name, openedSeries.posterUrl, null,
                            ),
                        )
                    },
                    watched = { item -> watchedFraction(item) },
                    watchedToggle = { item -> watchedToggleFor(item) },
                    loading = loadingItems || arranging,
                    // The queue is every episode, not the season on screen: the last episode of a
                    // season leads into the next one, which is what a viewer means by "next" and
                    // what the provider already sent.
                    onPlay = { item, fromStart -> start(item, fromStart, from = episodes) },
                    onClose = {
                        openSeries = null
                        seriesDetails = null
                        query = queryBeforeSeries
                    },
                )
                DisposableEffect(Unit) {
                    keys.focusSearch = { runCatching { searchFocus.requestFocus() } }
                    onDispose { keys.focusSearch = null }
                }
            } else if (section == Section.Home) {
                HomeScreen(
                    accountLabel = session.account.label,
                    continueWatching = continueWatching,
                    recentlyAdded = recentlyAdded,
                    channels = ownChannelRows,
                    favourites = myList.favourites,
                    loading = librarySyncing,
                    query = query,
                    onQueryChange = {
                        query = it
                        // Typing on the start screen means searching everything, because there is
                        // nothing on this screen to filter — it is four short rows, not a library.
                        if (it.isNotBlank()) section = Section.Search
                    },
                    searchFocus = searchFocus,
                    marks = { item -> marksFor(item) },
                    onToggleFavourite = { toggleFavourite(it) },
                    onToggleSaved = { toggleSaved(it) },
                    onForgetProgress = { item -> forgetProgress(item) },
                    onClick = { item -> open(item, savedItems) },
                    onOpen = { section = it },
                )
                DisposableEffect(Unit) {
                    keys.focusSearch = { runCatching { searchFocus.requestFocus() } }
                    onDispose { keys.focusSearch = null }
                }
            } else if (section == Section.Search) {
                SearchScreen(
                    query = query,
                    onQueryChange = { query = it },
                    searchFocus = searchFocus,
                    hits = hits,
                    missing = LibraryKind.entries.filterNot { library.has(it) },
                    loading = librarySyncing,
                    channels = hitItems.first,
                    movies = hitItems.second,
                    series = hitItems.third,
                    marks = { item -> marksFor(item) },
                    watched = { item -> watchedFraction(item) },
                    onToggleFavourite = { toggleFavourite(it) },
                    onToggleSaved = { toggleSaved(it) },
                    onClick = { item ->
                        open(item, hitItems.first + hitItems.second + hitItems.third)
                    },
                )
                DisposableEffect(Unit) {
                    keys.focusSearch = { runCatching { searchFocus.requestFocus() } }
                    onDispose { keys.focusSearch = null }
                }
                // The caret belongs in the field on a screen that is nothing but a field. It also
                // carries the focus over from the start screen, where typing is what got here.
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
            } else if (section == Section.Saved && query.isBlank()) {
                MyListScreen(
                    list = myList,
                    marks = { item -> marksFor(item) },
                    query = query,
                    onQueryChange = { query = it },
                    searchFocus = searchFocus,
                    onToggleFavourite = { toggleFavourite(it) },
                    onToggleSaved = { toggleSaved(it) },
                    onClick = { item -> open(item, savedItems) },
                )
                DisposableEffect(Unit) {
                    keys.focusSearch = { runCatching { searchFocus.requestFocus() } }
                    onDispose { keys.focusSearch = null }
                }
            } else if (section == Section.Guide) {
                GuideScreen(
                    channels = guideChannels,
                    programmes = guidePages,
                    loading = guideLoading,
                    onRefresh = {
                        // Otherwise this button would clear the screen and put the same listings
                        // straight back, which is worse than not having it.
                        epg.clear()
                        guidePages = emptyMap()
                        guideRefresh++
                    },
                    onForget = { channel ->
                        // Whatever put it here is what is undone: a visit is forgotten, and a
                        // bookmark — which is a decision rather than an occurrence — is taken back
                        // as well, because a row that returns immediately is a button that lied.
                        var updated = (userData ?: return@GuideScreen)
                            .withoutRecentChannel(channel.id)
                        if (updated.isSaved(CHANNEL_CONTENT_TYPE, channel.id)) {
                            updated = updated.toggleSaved(CHANNEL_CONTENT_TYPE, channel.id)
                        }
                        userData = updated
                        scope.launch { store.save(updated) }
                    },
                    onPlay = { channel ->
                        // The whole guide is the list here: what else is on is exactly what else is
                        // worth switching to.
                        val entries = guideChannels.map { it.asBrowseItem() }
                        start(
                            item = channel.asBrowseItem(),
                            fromStart = false,
                            from = entries,
                        )
                    },
                )
            } else if (section == Section.Settings) {
                // Measured when the screen opens rather than kept up to date: it is a directory
                // walk, and nowhere else in the client is the number worth a single stat call.
                LaunchedEffect(Unit) {
                    artworkBytes = ArtworkLoader.cachedBytes()
                    libraryCacheBytes = libraryCache.bytesOnDisk()
                    keptFiles = store.keptFiles().map { it.name }
                }
                SettingsScreen(
                    session = session,
                    userData = userData,
                    vlcAvailable = player.isAvailable,
                    artworkBytes = artworkBytes,
                    keptFiles = keptFiles,
                    onClearArtwork = {
                        scope.launch {
                            ArtworkLoader.clearCache()
                            artworkBytes = ArtworkLoader.cachedBytes()
                        }
                    },
                    dataDirectory = DesktopUserData.defaultDirectory(),
                    message = dataMessage,
                    languages = preferences.trackLanguages,
                    onForgetLanguages = {
                        onPreferencesChange(
                            preferences.withTrackLanguages(TrackLanguagePreferences()),
                        )
                    },
                    credentialsSupported = vault.isSupported,
                    credentialsStored = credentialsStored,
                    onForgetCredentials = {
                        vault.forget()
                        credentialsStored = vault.hasStored
                    },
                    library = library,
                    librarySyncing = librarySyncing,
                    onReloadLibrary = onReloadLibrary,
                    libraryCacheBytes = libraryCacheBytes,
                    onForgetLibraryCache = {
                        libraryCache.forgetAll()
                        scope.launch { libraryCacheBytes = libraryCache.bytesOnDisk() }
                    },
                    preferences = preferences,
                    onPreferencesChange = onPreferencesChange,
                    capturingShortcut = capturingShortcut,
                    onCaptureKey = { capturingShortcut = it },
                    onExport = {
                        dataMessage = null
                        userData?.let { export ->
                            onExportUserData(export) { written ->
                                dataMessage = if (written) {
                                    "Exported."
                                } else {
                                    "That file could not be written."
                                }
                            }
                        }
                    },
                    checkingForUpdate = checkingForUpdate,
                    updateCheckMessage = updateCheckMessage,
                    onCheckForUpdate = onCheckForUpdate,
                    onImport = {
                        dataMessage = null
                        onImportUserData { document ->
                            val current = userData
                            // Planned before anything is written, so the three outcomes can be told
                            // apart and named. Merging first and reporting afterwards would make
                            // "wrong account" indistinguishable from "nothing new".
                            val plan = if (document == null || current == null) {
                                null
                            } else {
                                current.planImportOf(document)
                            }
                            dataMessage = when (plan) {
                                null -> "That file could not be read."
                                is UserDataImportPlan.Unreadable -> "That file is not an export."
                                UserDataImportPlan.WrongAccount ->
                                    "That file belongs to a different account."
                                is UserDataImportPlan.Ready -> {
                                    if (plan.changeCount == 0) {
                                        "Nothing new in that file."
                                    } else {
                                        // Merged with the same newest-wins rule the phone applies,
                                        // so carrying a file in either direction behaves identically.
                                        val merged = current!!.mergedWith(plan.export)
                                        userData = merged
                                        scope.launch { store.save(merged) }
                                        "Imported ${plan.changeCount} entries."
                                    }
                                }
                            }
                        }
                    },
                    onSignOut = {
                        // Signing out is a decision about this machine, so the sealed account goes
                        // with it. Anything else would be a client that says goodbye and then lets
                        // itself back in.
                        vault.forget()
                        credentialsStored = false
                        stop(waitForTheWrite = true)
                        // The screen's own state goes with the composition, but the artwork cache
                        // outlives it: one account's posters have no business on the next one's
                        // screen. The files stay — they are keyed by URL, and a URL belongs to
                        // whoever asks for it.
                        ArtworkLoader.forget()
                        onSignOut()
                    },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Registered while this column is on show and taken back when it goes. A
                    // requester whose field has left the composition throws when it is asked for
                    // focus, so leaving the handler behind would turn Ctrl+F on the settings screen
                    // into a crash rather than a no-op.
                    DisposableEffect(Unit) {
                        keys.focusSearch = { runCatching { searchFocus.requestFocus() } }
                        onDispose { keys.focusSearch = null }
                    }
                    ContentHeader(
                        section = section,
                        categories = categories,
                        selected = selectedCategory,
                        // Only where "everything" is something the client can actually show.
                        offerEverything = indexed,
                        loading = loadingCategories || loadingItems || librarySyncing || arranging,
                        count = visible.size,
                        query = query,
                        onQueryChange = { query = it },
                        onCategory = { openCategory(it) },
                        sortOptions = sortOptions,
                        selectedSort = sortName,
                        languages = languages,
                        selectedLanguage = language,
                        onLanguage = { language = it },
                        searchFocus = searchFocus,
                        onSort = { chosen ->
                            onPreferencesChange(
                                preferences.copy(
                                    sorts = preferences.sorts + (section.name to chosen),
                                ),
                            )
                        },
                    )
                    // Read once per composition rather than on a ticker: an account that expires
                    // while someone is watching can wait until the next launch to say so.
                    val expiry = remember(session) {
                        expiryWarningFor(
                            session.account.expiresAtEpochSeconds,
                            System.currentTimeMillis() / 1000L,
                        )
                    }
                    expiry?.takeIf { !expiryDismissed }?.let { warning ->
                        ExpiryBanner(warning) { expiryDismissed = true }
                    }
                    unreadableFile?.let { name ->
                        NoticeBanner(
                            "Your stored watch history could not be read, so this account starts " +
                                "empty. The old file was kept as $name — nothing was deleted.",
                        ) { unreadableFile = null }
                    }
                    loadFailure?.let { problem ->
                        FailureBanner(
                            message = problem.message,
                            onRetry = {
                                loadFailure = null
                                problem.retry()
                            },
                            onDismiss = { loadFailure = null },
                        )
                    }
                    if (matchingCategories.isNotEmpty()) {
                        CategorySuggestions(
                            categories = matchingCategories,
                            selected = selectedCategory,
                            onCategory = { openCategory(it, keepQuery = true) },
                        )
                    }
                    ContentArea(
                        section = section,
                        items = visible,
                        showingEpisodes = false,
                        marks = { item -> marksFor(item) },
                        watched = { item -> watchedFraction(item) },
                        // The guide cache again, where it has been filled. A channel row that says
                        // what is on is the difference between a list of names and a television.
                        subtitle = { item ->
                            val id = (item as? BrowseItem.Channel)?.id
                                ?: (item as? BrowseItem.Indexed)
                                    ?.takeIf { it.contentType == CHANNEL_CONTENT_TYPE }?.id
                            id?.let { epg.get(it) }
                                ?.let { EpgSelection.nowPlaying(it, System.currentTimeMillis() / 1000L) }
                                ?.title
                        },
                        watchedToggle = { item -> watchedToggleFor(item) },
                        onToggleFavourite = { toggleFavourite(it) },
                        onToggleSaved = { toggleSaved(it) },
                        emptyHint = when {
                            loadingItems || librarySyncing || arranging -> ""
                            // The banner above is already saying what happened, and "this category
                            // is empty" underneath it would be the client contradicting itself.
                            loadFailure != null -> ""
                            section == Section.Saved ->
                                "Nothing marked yet. Use the heart or the bookmark on a title."
                            // With categories offered above, the query has already done something
                            // useful even though nothing here matched it.
                            matchingCategories.isNotEmpty() && selectedCategory == null ->
                                "Categories matching that are above."
                            section == Section.Live && selectedCategory == null ->
                                "Play a channel once and it waits for you here. Until then, pick a " +
                                    "category or type to find one."
                            selectedCategory == null -> "Pick a category, or type to find one."
                            query.isNotBlank() -> "Nothing in this category matches that."
                            else -> "This category is empty."
                        },
                        onClick = { item -> open(item) },
                    )
                }
            }
        }
    }
}

/**
 * The left rail: which library, and who is signed in.
 *
 * A rail rather than tabs along the top, because the window is wide and vertical space is what the
 * content needs, and because three destinations with icons read faster down the side.
 */
@Composable
private fun NavigationRail(
    section: Section,
    sections: List<Section>,
    accountLabel: String,
    onSectionChange: (Section) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .fillMaxHeight()
            .background(NightRaised)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlowText(
            "K",
            style = MaterialTheme.typography.headlineSmall,
            glowRadius = 26f,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        sections.forEach { entry ->
            val active = entry == section
            val tint by animateColorAsState(if (active) VioletBright else InkMuted)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) Violet.copy(alpha = 0.2f) else Color.Transparent)
                    .focusRing(RoundedCornerShape(14.dp))
                    .clickable { onSectionChange(entry) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(entry.icon, contentDescription = entry.label, tint = tint)
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.label,
                    color = tint,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Violet.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                accountLabel.trim().take(1).uppercase(),
                color = VioletBright,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Category picker, filter and count.
 *
 * The category is a **dropdown** rather than a permanent column. There are over nine hundred of them
 * on this provider, and a column that long spends most of the window listing categories nobody is
 * looking at while the content it leads to takes what is left.
 */
@Composable
private fun ContentHeader(
    section: Section,
    categories: List<CategoryRow>,
    selected: CategoryRow?,
    /** Whether "everything" is on offer, which it is exactly when the listing is in memory. */
    offerEverything: Boolean,
    loading: Boolean,
    count: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onCategory: (CategoryRow?) -> Unit,
    sortOptions: List<Pair<String, String>>,
    selectedSort: String?,
    /** The languages this listing offers, or empty where a provider names none. */
    languages: List<String>,
    selectedLanguage: String?,
    onLanguage: (String?) -> Unit,
    searchFocus: FocusRequester,
    onSort: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (section == Section.Saved) {
            Text(
                "My list",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            CategoryDropdown(section, categories, selected, offerEverything, onCategory)
        }

        Spacer(Modifier.width(16.dp))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = VioletBright,
            )
        } else if (count > 0) {
            // Grouped, because this number is now the size of a whole library rather than of one
            // category, and 104675 is not a number anyone reads at a glance.
            Text("%,d".format(count), color = InkMuted, style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.weight(1f))
        // Only where the provider's naming distinguishes anything. One language is not a choice.
        if (languages.size > 1) {
            SortMenu(
                options = listOf(ANY_LANGUAGE to "Any language") +
                    languages.map { it to languageDisplayName(it) },
                selected = selectedLanguage ?: ANY_LANGUAGE,
                onSelect = { chosen -> onLanguage(chosen.takeIf { it != ANY_LANGUAGE }) },
            )
            Spacer(Modifier.width(12.dp))
        }
        if (sortOptions.size > 1) {
            SortMenu(sortOptions, selectedSort, onSort)
            Spacer(Modifier.width(12.dp))
        }
        // "Search" rather than "Filter": it now reaches the categories as well as what is on screen.
        FilterField(query, onQueryChange, placeholder = "Search…", focusRequester = searchFocus)
    }
}

@Composable
private fun CategoryDropdown(
    section: Section,
    categories: List<CategoryRow>,
    selected: CategoryRow?,
    offerEverything: Boolean,
    onCategory: (CategoryRow?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var menuQuery by remember { mutableStateOf("") }

    // The menu carries its own filter. Finding one category among nine hundred is searching rather
    // than browsing, and a picker that cannot be typed into is the wrong control at that size.
    val shown = remember(categories, menuQuery) {
        if (menuQuery.isBlank()) {
            categories
        } else {
            val needle = SearchTextNormalizer.normalize(menuQuery)
            categories.filter { SearchTextNormalizer.normalize(it.name).contains(needle) }
        }
    }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(NightSoft)
                .border(1.dp, Violet.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                .focusRing(RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                selected?.name
                    ?: if (offerEverything) "All ${section.label.lowercase()}" else "${section.label} categories",
                color = if (selected == null && !offerEverything) InkMuted else Ink,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 340.dp),
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = InkMuted)
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                open = false
                menuQuery = ""
            },
            modifier = Modifier.background(NightRaised).width(440.dp).height(540.dp),
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                FilterField(menuQuery, { menuQuery = it }, placeholder = "Find a category…")
            }
            // First, and only where it means something: without the listing in memory there is
            // nothing to show for "everything", and an entry that does nothing is worse than none.
            if (offerEverything && menuQuery.isBlank()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "All ${section.label.lowercase()}",
                            color = if (selected == null) VioletBright else Ink,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        onCategory(null)
                        open = false
                        menuQuery = ""
                    },
                )
            }
            // Only the first few hundred are built. A dropdown composes its whole content at once —
            // it is a Column with a scrollbar, not a lazy list — so nine hundred categories would be
            // nine hundred rows measured and laid out on the click that opens it. A lazy list cannot
            // go in here either: the menu measures its content with intrinsic width, which
            // subcomposition does not support.
            //
            // The cap is not a loss of access: the field above narrows, and typing anywhere outside
            // the menu offers matching categories as chips. Nobody reads to row four hundred.
            shown.take(MENU_CATEGORY_LIMIT).forEach { category ->
                DropdownMenuItem(
                    text = {
                        Text(
                            category.name,
                            color = if (category.id == selected?.id) VioletBright else Ink,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onCategory(category)
                        open = false
                        menuQuery = ""
                    },
                )
            }
            if (shown.size > MENU_CATEGORY_LIMIT) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "and ${shown.size - MENU_CATEGORY_LIMIT} more — type to narrow",
                            color = InkMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    onClick = {},
                )
            }
        }
    }
}

/**
 * How many categories a menu builds at once.
 *
 * Two hundred is more than anyone scrolls and far less than nine hundred is to compose.
 */
private const val MENU_CATEGORY_LIMIT = 200

@Composable
internal fun FilterField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Filter…",
    focusRequester: FocusRequester? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NightSoft)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = InkMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
            cursorBrush = SolidColor(VioletBright),
            modifier = Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(placeholder, color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                }
                inner()
            },
        )
    }
}

/** Posters for the video-on-demand libraries, rows for channels and episodes. */
/** Whether a title is hearted and whether it is on the list; null when it cannot be marked. */
internal data class Marks(
    val favourite: Boolean,
    val saved: Boolean,
    /** False for live channels: the export format keeps no favourites for them. */
    val canFavourite: Boolean = true,
)

@Composable
private fun ContentArea(
    section: Section,
    items: List<BrowseItem>,
    showingEpisodes: Boolean,
    emptyHint: String,
    marks: (BrowseItem) -> Marks?,
    watched: (BrowseItem) -> Float?,
    watchedToggle: (BrowseItem) -> (() -> Unit)?,
    subtitle: (BrowseItem) -> String?,
    onToggleFavourite: (BrowseItem) -> Unit,
    onToggleSaved: (BrowseItem) -> Unit,
    onClick: (BrowseItem) -> Unit,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyHint, color = InkMuted, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    if (section != Section.Live && !showingEpisodes) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 172.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items.size) { index ->
                val item = items[index]
                PosterTile(
                    item = item,
                    marks = marks(item),
                    onToggleFavourite = onToggleFavourite,
                    onToggleSaved = onToggleSaved,
                    onClick = onClick,
                    watched = watched(item),
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items) { item ->
                ListRow(
                    item = item,
                    marks = marks(item),
                    onToggleFavourite = onToggleFavourite,
                    onToggleSaved = onToggleSaved,
                    onClick = onClick,
                    watched = watched(item),
                    onToggleWatched = watchedToggle(item),
                    subtitle = subtitle(item),
                )
            }
        }
    }
}

@Composable
internal fun PosterTile(
    item: BrowseItem,
    marks: Marks?,
    onToggleFavourite: (BrowseItem) -> Unit,
    onToggleSaved: (BrowseItem) -> Unit,
    onClick: (BrowseItem) -> Unit,
    /** How far through it the viewer is, where that is known. Null everywhere but continue-watching. */
    watched: Float? = null,
    /** Offered only where a tile is there because of a position, which is continue-watching. */
    onForget: (() -> Unit)? = null,
    /**
     * The shape of the artwork.
     *
     * A poster is two by three and a channel logo is not: cropping a wide logo into a portrait tile
     * takes the middle third of it, which is the part with no name on it.
     */
    aspect: Float = 2f / 3f,
) {
    // `clickable` already makes a tile focusable and already accepts enter and space once it has
    // focus, so arrow keys traverse this grid out of the box. What was missing is the only half a
    // viewer can see: which tile the keyboard is on.
    var focused by remember { mutableStateOf(false) }

    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick(item) }
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(12.dp))
                .background(NightSoft)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Cyan else Violet.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            RemoteImage(
                url = item.artworkUrl,
                modifier = Modifier.fillMaxSize(),
                placeholder = { ArtworkPlaceholder(item.label) },
            )
            onForget?.let { forget ->
                // Opposite corner from the marks, because it undoes something different: a mark is
                // a decision to keep, and this is a row you are only in because you stopped.
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Forget where I stopped",
                    tint = Ink,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Night.copy(alpha = 0.55f))
                        .focusRing(CircleShape)
                        .clickable(onClick = forget)
                        .padding(4.dp)
                        .size(18.dp),
                )
            }
            marks?.let {
                // Always visible rather than revealed on hover: a mark nobody can see is a mark set
                // twice by accident, and it is the only thing on the tile besides the picture.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Night.copy(alpha = 0.62f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    if (it.canFavourite) {
                        MarkButton(
                            marked = it.favourite,
                            markedIcon = Icons.Default.Favorite,
                            unmarkedIcon = Icons.Default.FavoriteBorder,
                            description = "Favourite",
                            onClick = { onToggleFavourite(item) },
                        )
                    }
                    MarkButton(
                        marked = it.saved,
                        markedIcon = Icons.Default.Bookmark,
                        unmarkedIcon = Icons.Default.BookmarkBorder,
                        description = "Save to my list",
                        onClick = { onToggleSaved(item) },
                    )
                }
            }
        }
        watched?.takeIf { it > 0f }?.let {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { it },
                color = VioletBright,
                trackColor = InkMuted.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.label,
            color = Ink,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ListRow(
    item: BrowseItem,
    marks: Marks?,
    onToggleFavourite: (BrowseItem) -> Unit,
    onToggleSaved: (BrowseItem) -> Unit,
    onClick: (BrowseItem) -> Unit,
    watched: Float? = null,
    onToggleWatched: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Cyan.copy(alpha = 0.16f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick(item) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(width = 64.dp, height = 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NightSoft),
        ) {
            RemoteImage(
                url = item.artworkUrl,
                modifier = Modifier.fillMaxSize(),
                placeholder = { ArtworkPlaceholder(item.label) },
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                color = if (watched != null && watched >= WATCHED_ENOUGH) InkMuted else Ink,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            /*
             * A bar for something in the middle of being watched, and a word for one that is done.
             *
             * The word is new, and it replaces a signal that did not work. "Watched" used to be
             * conveyed by dimming the title to `InkMuted` and nothing else - which is the exact
             * colour the plot underneath is already drawn in, so on a dark row the difference was a
             * shade nobody could see. The owner's report was precise about it: pressing the mark
             * filled a small circle at the far right, and the row itself still looked unwatched.
             *
             * So it says so. The two questions an episode list is asked are "where was I" and
             * "which of these have I seen", and they want different answers.
             */
            if (watched != null && watched >= WATCHED_ENOUGH) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "Watched",
                    color = VioletBright,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                subtitle?.let {
                    Text(
                        it,
                        color = InkMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            watched?.takeIf { it > 0f && it < WATCHED_ENOUGH }?.let {
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { it },
                    color = VioletBright,
                    trackColor = InkMuted.copy(alpha = 0.25f),
                    modifier = Modifier.width(180.dp).height(3.dp),
                )
            }
        }
        onToggleWatched?.let { toggle ->
            val seen = watched != null && watched >= WATCHED_ENOUGH
            MarkButton(
                marked = seen,
                markedIcon = Icons.Default.CheckCircle,
                unmarkedIcon = Icons.Default.RadioButtonUnchecked,
                description = if (seen) "Mark as not watched" else "Mark as watched",
                onClick = toggle,
            )
        }
        marks?.let {
            if (it.canFavourite) {
                MarkButton(
                    marked = it.favourite,
                    markedIcon = Icons.Default.Favorite,
                    unmarkedIcon = Icons.Default.FavoriteBorder,
                    description = "Favourite",
                    onClick = { onToggleFavourite(item) },
                )
            }
            MarkButton(
                marked = it.saved,
                markedIcon = Icons.Default.Bookmark,
                unmarkedIcon = Icons.Default.BookmarkBorder,
                description = "Save to my list",
                onClick = { onToggleSaved(item) },
            )
        }
    }
}

/** A small mark: filled and violet when set, outlined and quiet when not. */
@Composable
internal fun MarkButton(
    marked: Boolean,
    markedIcon: ImageVector,
    unmarkedIcon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = if (marked) markedIcon else unmarkedIcon,
        contentDescription = description,
        tint = if (marked) VioletBright else InkMuted,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .focusRing(CircleShape)
            .clickable(onClick = onClick)
            .padding(4.dp),
    )
}

/**
 * A stored id as a browse item, or null when nothing is known about it.
 *
 * Null is the honest answer for a title marked on the phone and never seen here: the id travels in
 * the export, the name does not, and inventing a caption would be worse than leaving it out until it
 * is browsed once.
 */
internal fun indexedItem(
    contentType: String,
    id: String,
    titles: Map<String, IndexedTitle>,
    library: LibraryIndex,
): BrowseItem.Indexed? {
    titles[TitleIndex.keyOf(contentType, id)]?.let { entry ->
        return BrowseItem.Indexed(
            contentType = contentType,
            id = id,
            label = entry.label,
            artworkUrl = entry.artworkUrl,
            containerExtension = entry.containerExtension,
        )
    }
    // Then the library, which is the better answer and the newer one. The title cache only knows
    // what this window has already shown, so a film hearted on the phone used to be invisible here
    // until it happened to be browsed; the listing knows every title the provider has.
    return when (contentType) {
        MOVIE_CONTENT_TYPE -> library.movie(id)?.let {
            BrowseItem.Indexed(contentType, id, it.name, it.posterUrl, it.containerExtension)
        }

        SERIES_CONTENT_TYPE -> library.series(id)?.let {
            BrowseItem.Indexed(contentType, id, it.name, it.posterUrl, null)
        }

        CHANNEL_CONTENT_TYPE -> library.channel(id)?.let {
            BrowseItem.Indexed(contentType, id, it.name, it.logoUrl, null)
        }

        // An episode is the one kind neither source can name on its own: the export carries its id,
        // and nothing turns an episode id into a title without knowing its series first.
        else -> null
    }
}

/**
 * Which language a title is in, as far as a provider's naming lets anyone tell.
 *
 * **Category first, then the title's own prefix** — the same order the phone uses, and the order
 * matters: a channel called `Sky Sport` filed under `DE | SPORT` is German, while one called
 * `FR | Sky Sport` in the same category is not. Answering from the title alone would miss the first
 * and answering from the category alone would miss the second.
 *
 * Null for anything neither says, and a language filter never matches those rather than guessing:
 * a heuristic that hides titles it was unsure about is worse than one that leaves them in.
 */
internal fun BrowseItem.languageTag(categoryNames: Map<String, String>): String? {
    val categoryId = when (this) {
        is BrowseItem.Channel -> value.categoryId
        is BrowseItem.Movie -> value.categoryId
        is BrowseItem.Series -> value.categoryId
        // An episode belongs to its series and a stored id carries no category at all.
        is BrowseItem.Episode, is BrowseItem.Indexed -> null
    }
    return XtreamLanguageTagger.languageOfCategory(categoryId?.let(categoryNames::get))
        ?: XtreamLanguageTagger.languageOfTitle(label)
}

/** A guide row as something playable: id, name and logo are all a live stream needs. */
private fun GuideChannel.asBrowseItem(): BrowseItem.Indexed = BrowseItem.Indexed(
    contentType = CHANNEL_CONTENT_TYPE,
    id = id,
    label = name,
    artworkUrl = logoUrl,
    containerExtension = null,
)

/**
 * Identity within one queue.
 *
 * The provider numbers each library separately, so a film and a series can both be 501 and My list
 * can hold both at once. A list keyed on the id alone would refuse to draw that.
 */
internal val BrowseItem.queueKey: String
    get() = when (this) {
        is BrowseItem.Channel -> "live:$id"
        is BrowseItem.Movie -> "movie:$id"
        is BrowseItem.Series -> "series:$id"
        is BrowseItem.Episode -> "episode:$id"
        is BrowseItem.Indexed -> "$contentType:$id"
    }

internal fun BrowseItem.isLiveIndexed(): Boolean =
    this is BrowseItem.Indexed && contentType == CHANNEL_CONTENT_TYPE

internal fun ProgressRecord.asIndexed(
    titles: Map<String, IndexedTitle>,
    library: LibraryIndex,
): BrowseItem.Indexed? {
    indexedItem(contentType, contentId, titles, library)?.let { return it }
    // An episode names itself after its series, which is what the phone shows too — and is the only
    // thing that can be said about an episode id without asking the provider about every series in
    // the library. The row still plays the episode: it keeps the episode's own id.
    if (contentType != EPISODE_CONTENT_TYPE) return null
    val series = seriesId?.let { library.series(it) } ?: return null
    return BrowseItem.Indexed(
        contentType = EPISODE_CONTENT_TYPE,
        id = contentId,
        label = series.name,
        artworkUrl = series.posterUrl,
        containerExtension = null,
    )
}

/**
 * How long before the end the next episode is offered.
 *
 * Half a minute: long enough to read and reach, short enough that it is not sitting over the picture
 * for an act.
 */
private const val UP_NEXT_LEAD_MS = 30_000L

/**
 * Where a list stops offering a position and starts calling something seen.
 *
 * Not the completion policy: that answers whether to resume, and it says yes at ninety-three percent
 * so a resume does not land in the credits. This answers what to *draw*, and a bar that is a sliver
 * from the end tells a viewer less than a dimmed title does.
 */
internal const val WATCHED_ENOUGH = 0.93f

/** libvlc gave up: a dead channel, an account at its connection limit, a title that is not there. */
private const val STREAM_REFUSED = "That stream could not be opened."

/** The same fact on the browsing side, where a 403 alone cannot say which of two things happened. */
private const val LIBRARY_EXPIRED =
    "This account has expired, which is the likely reason nothing will load."

/** The one case where the client knows more than libvlc told it. */
private const val STREAM_EXPIRED =
    "This account has expired, which is the likely reason nothing will play."

/** No refusal and no picture either — said plainly, because the client does not know which it is. */
private const val STREAM_SILENT =
    "This is not starting. The provider may be busy or the stream dead."

/** A picture that was moving and has stopped, which nothing used to be able to say. */
private const val STREAM_STALLED =
    "The picture has stopped. The stream may have dropped, or it may come back on its own."

/**
 * The messages a picture arriving is allowed to clear.
 *
 * Not every message: a missing libvlc is not something a frame can disprove, and it never reaches
 * this loop in the first place. These three are all guesses about a stream, and a stream that starts
 * behaving is the evidence against them.
 */
private val CLEARABLE_FAILURES = setOf(STREAM_REFUSED, STREAM_EXPIRED, STREAM_SILENT, STREAM_STALLED)

/** What "no language filter" is called in the menu, since a null cannot be an entry. */
private const val ANY_LANGUAGE = "any"

/**
 * How long the search waits for typing to stop.
 *
 * Under what anyone notices, over how fast anyone types, and the difference between one scan of the
 * library per word and one per letter.
 */
private const val SEARCH_DEBOUNCE_MS = 180L

/** How often the watchdog looks. Half a second is well under the tolerances it feeds. */
private const val WATCH_POLL_MS = 500L


/** The authenticated stream URL, always built by the shared factory. */
/**
 * An address and, where the listing named any, the headers its server insists on.
 *
 * Two values rather than one because a playlist channel can need both and an Xtream channel needs
 * neither: the account is the authorisation there, and the address is built rather than read.
 */
private class PlaybackTarget(val url: String, val headers: StreamHeaders? = null)

/**
 * Where to play [item] from.
 *
 * A **playlist** channel carries its own address in `directSource`, and that is the only way to
 * start it - there is no id to build a URL from and no account to put in one. An Xtream channel
 * never has that field filled, because `XtreamJsonParser` deliberately does not read
 * `direct_source`, so its presence is what distinguishes the two without a flag being passed down.
 *
 * [library] is here for the one case the item itself cannot answer: a channel played from My list
 * or from what was watched recently arrives as an id and nothing else, and for a playlist the
 * address has to be looked back up.
 */
private fun urlFor(
    session: DesktopSession,
    item: BrowseItem,
    library: LibraryIndex,
): PlaybackTarget = when (item) {
    is BrowseItem.Channel -> channelTarget(session, item.value)
    is BrowseItem.Movie -> PlaybackTarget(
        XtreamStreamUrlFactory.buildMovieUrl(
            session.credentials,
            item.value.id,
            XtreamStreamUrlFactory.selectVodExtension(item.value.containerExtension),
        ),
    )
    is BrowseItem.Episode -> PlaybackTarget(
        XtreamStreamUrlFactory.buildEpisodeUrl(
            session.credentials,
            item.value.id,
            XtreamStreamUrlFactory.selectVodExtension(item.value.containerExtension),
        ),
    )
    is BrowseItem.Series -> error("A series is opened, not played")
    is BrowseItem.Indexed -> when (item.contentType) {
        MOVIE_CONTENT_TYPE -> PlaybackTarget(
            XtreamStreamUrlFactory.buildMovieUrl(
                session.credentials,
                item.id,
                XtreamStreamUrlFactory.selectVodExtension(item.containerExtension),
            ),
        )
        // An id and nothing else, so the listing is asked what it was. For a playlist that is the
        // only way back to the address; for Xtream the account alone decides the format, exactly as
        // it did before, and the lookup simply finds nothing to add.
        CHANNEL_CONTENT_TYPE -> library.channel(item.id)?.let { channelTarget(session, it) }
            ?: PlaybackTarget(
                XtreamStreamUrlFactory.buildLiveUrl(
                    session.credentials,
                    item.id,
                    XtreamStreamUrlFactory.selectFormat(session.account),
                ),
            )
        else -> PlaybackTarget(
            XtreamStreamUrlFactory.buildEpisodeUrl(
                session.credentials,
                item.id,
                XtreamStreamUrlFactory.selectVodExtension(item.containerExtension),
            ),
        )
    }
}

/**
 * One channel, from whichever of the two kinds of library it came.
 *
 * `directSource` is the whole test. A playlist channel has it and cannot be played without it; an
 * Xtream channel never does, because `XtreamJsonParser` deliberately leaves `direct_source`
 * unparsed. So no flag has to be threaded down here to say which kind of account is signed in.
 */
private fun channelTarget(session: DesktopSession, channel: LiveChannel): PlaybackTarget =
    channel.directSource
        ?.let { PlaybackTarget(it, channel.streamHeaders) }
        ?: PlaybackTarget(
            XtreamStreamUrlFactory.buildLiveUrl(
                session.credentials,
                channel.id,
                XtreamStreamUrlFactory.selectFormat(session.account, channel),
            ),
        )

/**
 * Something did not load, and what to do about it.
 *
 * The retry is carried with the message because only the thing that failed knows how to ask again —
 * a library, one category, or one series are three different requests, and a button that says "try
 * again" has to mean the one that just did not work.
 */
private data class LoadFailure(val message: String, val retry: () -> Unit)

/**
 * The one piece of account state with a deadline, said where browsing happens.
 *
 * Nothing here can renew anything, so it is a sentence and a way to put it away rather than an
 * action — and it is deliberately quiet: a viewer who is told twice a day about something they
 * cannot act on has been given a decoration, not a warning.
 */
@Composable
private fun ExpiryBanner(warning: ExpiryWarning, onDismiss: () -> Unit) {
    NoticeBanner(
        when (warning) {
            ExpiryWarning.Expired -> "This account has expired."
            is ExpiryWarning.Soon -> when (warning.days) {
                0 -> "This account expires today."
                1 -> "This account expires tomorrow."
                else -> "This account expires in ${warning.days} days."
            }
        },
        onDismiss,
    )
}

/**
 * Something the viewer should know, with no button but the one that makes it go away.
 *
 * Distinct from [FailureBanner], which offers to try again: these say what has already happened, and
 * repeating the question would not change the answer.
 */
@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NightRaised)
            .border(1.dp, Violet.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(message, color = Ink, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Default.Close,
            contentDescription = "Dismiss",
            tint = InkMuted,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .focusRing(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(4.dp),
        )
    }
}

@Composable
private fun FailureBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NightRaised)
            .border(1.dp, Violet.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(message, color = Ink, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        TextPill("Try again", onClick = onRetry)
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Close,
            contentDescription = "Dismiss",
            tint = InkMuted,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .focusRing(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(4.dp),
        )
    }
}

/**
 * The browsing order, as a small menu beside the search box.
 *
 * The options differ per library because the provider's listing does: a channel has no rating, no
 * year and no added date, so offering to sort by them would be offering to sort by nothing.
 */
@Composable
internal fun SortMenu(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second
        ?: options.firstOrNull()?.second.orEmpty()

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(NightSoft)
                .focusRing(RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Order",
                tint = InkMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, color = Ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = InkMuted)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(NightRaised),
        ) {
            options.forEach { (name, text) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text,
                            color = if (name == selected) VioletBright else Ink,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        onSelect(name)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * What each library can honestly be ordered by, as enum name to label.
 *
 * The names are the shared enums' own, so the choice stored in preferences survives this list being
 * reordered — and the ordering itself is the shared rule rather than a comparator written here.
 */
private fun sortOptionsFor(section: Section): List<Pair<String, String>> = when (section) {
    // Neither is a list to order: one is four short rows, the other is ordered by how well each
    // entry matches what was typed.
    Section.Home, Section.Search -> emptyList()

    Section.Live -> listOf(
        LiveSortOrder.ProviderDefault.name to "Provider order",
        LiveSortOrder.NameAscending.name to "A to Z",
        LiveSortOrder.NameDescending.name to "Z to A",
    )
    Section.Movies -> listOf(
        MovieSortOrder.ProviderDefault.name to "Provider order",
        MovieSortOrder.NameAscending.name to "A to Z",
        MovieSortOrder.RatingDescending.name to "Top rated",
        MovieSortOrder.ReleaseYearDescending.name to "Newest first",
        MovieSortOrder.RecentlyAdded.name to "Recently added",
    )
    Section.Series -> listOf(
        SeriesSortOrder.ProviderDefault.name to "Provider order",
        SeriesSortOrder.NameAscending.name to "A to Z",
        SeriesSortOrder.RatingDescending.name to "Top rated",
        SeriesSortOrder.ReleaseYearDescending.name to "Newest first",
        SeriesSortOrder.RecentlyUpdated.name to "Recently updated",
    )
    Section.Guide, Section.Saved, Section.Settings -> emptyList()
}

/**
 * Applies [sortName] to a homogeneous list of browse items.
 *
 * A mixed list is returned untouched. My list holds films, series and channels at once, and there is
 * no order over that mixture that means anything — it is already in the order it was assembled.
 */
private fun List<BrowseItem>.ordered(sortName: String?): List<BrowseItem> {
    if (sortName == null || isEmpty()) return this
    val channels = filterIsInstance<BrowseItem.Channel>()
    if (channels.size == size) {
        val order = LiveSortOrder.entries.firstOrNull { it.name == sortName } ?: return this
        return channels.map { it.value }.orderedBy(order).map(BrowseItem::Channel)
    }
    val films = filterIsInstance<BrowseItem.Movie>()
    if (films.size == size) {
        val order = MovieSortOrder.entries.firstOrNull { it.name == sortName } ?: return this
        return films.map { it.value }.orderedBy(order).map(BrowseItem::Movie)
    }
    val shows = filterIsInstance<BrowseItem.Series>()
    if (shows.size == size) {
        val order = SeriesSortOrder.entries.firstOrNull { it.name == sortName } ?: return this
        return shows.map { it.value }.orderedBy(order).map(BrowseItem::Series)
    }
    return this
}

/**
 * Categories the search matched, as a row of chips above the content.
 *
 * Above rather than inside the list, because they are a different kind of answer: the list is
 * titles, and these are places to look. One click opens one and keeps the word, so a search reads as
 * one gesture rather than two.
 */
@Composable
private fun CategorySuggestions(
    categories: List<CategoryRow>,
    selected: CategoryRow?,
    onCategory: (CategoryRow) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories, key = { it.id }) { category ->
            val open = category.id == selected?.id
            Text(
                category.name,
                color = if (open) VioletBright else Ink,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (open) Violet.copy(alpha = 0.22f) else NightSoft)
                    .focusRing(RoundedCornerShape(10.dp))
                    .clickable { onCategory(category) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** A round control. Prominent means the primary one: filled violet rather than a dark disc. */
@Composable
internal fun IconPill(
    icon: ImageVector,
    description: String,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(if (prominent) 46.dp else 38.dp)
            .clip(CircleShape)
            .background(if (prominent) VioletBright else NightSoft.copy(alpha = 0.92f))
            .focusRing(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (prominent) Night else Ink)
    }
}

@Composable
internal fun TextPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = VioletBright,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Violet.copy(alpha = 0.18f))
            .focusRing(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Local wall-clock time, because a viewer compares it against their own clock. */
internal fun clockOf(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("HH:mm"))

/** `h:mm:ss` past an hour, `m:ss` below it — an eighty-minute film should not read `0:20:00`. */
internal fun formatDuration(millis: Long): String {
    val total = (millis / 1000L).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

internal const val VLC_MISSING =
    "VLC is not installed. This client plays through libvlc and does not bundle it."
