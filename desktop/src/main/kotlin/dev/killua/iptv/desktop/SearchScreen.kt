package dev.killua.iptv.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One field over all three libraries.
 *
 * This is the screen the whole in-memory library was worth building for. Xtream has no search
 * action — `player_api.php` can filter by category and nothing else — so a client that has not read
 * the listing can only search what happens to be on screen, which is not searching. With the listing
 * in hand it is three scans over folded names, and a title can be found without anyone knowing which
 * shelf a provider filed it on.
 *
 * Results are grouped by library rather than merged. A channel, a film and a series are three
 * different things to do with an evening, and interleaving them by some score would bury whichever
 * one the viewer meant.
 *
 * A library that has not been read says so rather than reporting nothing found — the difference
 * between "there is no such film" and "I have not looked at the films yet" is the whole trust of a
 * search box.
 */
@Composable
internal fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocus: FocusRequester,
    hits: LibraryHits,
    /** Libraries that were never read, so the results are known to be incomplete. */
    missing: List<LibraryKind>,
    loading: Boolean,
    channels: List<BrowseItem>,
    movies: List<BrowseItem>,
    series: List<BrowseItem>,
    marks: (BrowseItem) -> Marks?,
    watched: (BrowseItem) -> Float?,
    onToggleFavourite: (BrowseItem) -> Unit,
    onToggleSaved: (BrowseItem) -> Unit,
    onClick: (BrowseItem) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Search",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(16.dp))
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = VioletBright,
                )
                Spacer(Modifier.width(12.dp))
            }
            Spacer(Modifier.weight(1f))
            FilterField(
                query,
                onQueryChange,
                placeholder = "Films, series, channels…",
                focusRequester = searchFocus,
            )
        }

        if (missing.isNotEmpty()) {
            Text(
                "Not searching ${missing.joinToString(" or ") { it.label.lowercase() }} — that " +
                    "listing was not read. Settings can read it again.",
                color = InkMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
            )
        }

        when {
            query.length < LibraryIndex.MIN_SEARCH_LENGTH -> Hint(
                if (loading) {
                    "Type at least two letters. Your library is still being read, so results will " +
                        "fill in."
                } else {
                    "Type at least two letters to search every library at once."
                },
            )

            hits.isEmpty -> Hint("Nothing matches that.")

            else -> {
                val handlers = SearchHandlers(
                    marks = marks,
                    watched = watched,
                    onToggleFavourite = onToggleFavourite,
                    onToggleSaved = onToggleSaved,
                    onClick = onClick,
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    section("Channels", channels, hits.totalOf(LibraryKind.Channels), 16f / 9f, handlers)
                    section("Films", movies, hits.totalOf(LibraryKind.Movies), 2f / 3f, handlers)
                    section("Series", series, hits.totalOf(LibraryKind.Series), 2f / 3f, handlers)
                }
            }
        }
    }
}

/** What a tile in the results needs from the screen around it, carried as one thing. */
internal data class SearchHandlers(
    val marks: (BrowseItem) -> Marks?,
    val watched: (BrowseItem) -> Float?,
    val onToggleFavourite: (BrowseItem) -> Unit,
    val onToggleSaved: (BrowseItem) -> Unit,
    val onClick: (BrowseItem) -> Unit,
)

/** One library's hits, with how many there were in total when only some are shown. */
private fun LazyListScope.section(
    title: String,
    items: List<BrowseItem>,
    total: Int,
    aspect: Float,
    handlers: SearchHandlers,
) {
    if (items.isEmpty()) return
    item {
        PosterRow(
            title = title,
            subtitle = if (total > items.size) "${items.size} of $total — keep typing" else null,
        ) {
            items(items, key = { it.queueKey }) { item ->
                Box(Modifier.width(PosterRowWidth)) {
                    PosterTile(
                        item = item,
                        marks = handlers.marks(item),
                        onToggleFavourite = handlers.onToggleFavourite,
                        onToggleSaved = handlers.onToggleSaved,
                        onClick = handlers.onClick,
                        watched = handlers.watched(item),
                        aspect = aspect,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = InkMuted, style = MaterialTheme.typography.bodyLarge)
    }
}
