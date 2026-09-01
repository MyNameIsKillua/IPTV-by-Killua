package dev.killua.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The first screen after signing in.
 *
 * It exists for the reason the phone's does: opening a client on a category picker asks the viewer a
 * question before it has told them anything. What someone actually wants on opening a television is
 * the thing they were half way through, and after that whatever is new — so those are the first two
 * rows, and the libraries are one click away rather than in the way.
 *
 * Everything here is read from what the client already has: the stored state supplies what is
 * unfinished and what is marked, and the in-memory library supplies what is new. Nothing on this
 * screen costs a request of its own.
 */
@Composable
internal fun HomeScreen(
    accountLabel: String,
    continueWatching: List<Pair<BrowseItem, Float>>,
    recentlyAdded: List<BrowseItem>,
    channels: List<BrowseItem>,
    favourites: List<BrowseItem>,
    /** True while the first library read is still running, which is why a row can be empty. */
    loading: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocus: FocusRequester,
    marks: (BrowseItem) -> Marks?,
    onToggleFavourite: (BrowseItem) -> Unit,
    onToggleSaved: (BrowseItem) -> Unit,
    onForgetProgress: (BrowseItem) -> Unit,
    onClick: (BrowseItem) -> Unit,
    onOpen: (Section) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Welcome back",
                    color = InkMuted,
                    style = MaterialTheme.typography.labelLarge,
                )
                GlowText(
                    accountLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    glowRadius = 20f,
                )
            }
            Spacer(Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = VioletBright,
                )
                Spacer(Modifier.width(12.dp))
            }
            FilterField(query, onQueryChange, placeholder = "Search…", focusRequester = searchFocus)
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (continueWatching.isNotEmpty()) {
                item {
                    PosterRow("Continue watching") {
                        items(continueWatching, key = { it.first.queueKey }) { (item, watched) ->
                            Box(Modifier.width(PosterRowWidth)) {
                                PosterTile(
                                    item = item,
                                    marks = marks(item),
                                    onToggleFavourite = onToggleFavourite,
                                    onToggleSaved = onToggleSaved,
                                    onClick = onClick,
                                    watched = watched,
                                    onForget = { onForgetProgress(item) },
                                    aspect = if (item.isLiveIndexed()) 16f / 9f else 2f / 3f,
                                )
                            }
                        }
                    }
                }
            }

            if (recentlyAdded.isNotEmpty()) {
                item {
                    PosterRow(
                        title = "Recently added",
                        actionLabel = "All films",
                        action = { onOpen(Section.Movies) },
                    ) {
                        items(recentlyAdded, key = { it.queueKey }) { item ->
                            Box(Modifier.width(PosterRowWidth)) {
                                PosterTile(item, marks(item), onToggleFavourite, onToggleSaved, onClick)
                            }
                        }
                    }
                }
            }

            if (channels.isNotEmpty()) {
                item {
                    PosterRow(
                        title = "Your channels",
                        actionLabel = "All channels",
                        action = { onOpen(Section.Live) },
                    ) {
                        items(channels, key = { it.queueKey }) { item ->
                            Box(Modifier.width(PosterRowWidth)) {
                                PosterTile(
                                    item = item,
                                    marks = marks(item),
                                    onToggleFavourite = onToggleFavourite,
                                    onToggleSaved = onToggleSaved,
                                    onClick = onClick,
                                    aspect = 16f / 9f,
                                )
                            }
                        }
                    }
                }
            }

            if (favourites.isNotEmpty()) {
                item {
                    PosterRow(
                        title = "Favourites",
                        actionLabel = "My list",
                        action = { onOpen(Section.Saved) },
                    ) {
                        items(favourites, key = { it.queueKey }) { item ->
                            Box(Modifier.width(PosterRowWidth)) {
                                PosterTile(item, marks(item), onToggleFavourite, onToggleSaved, onClick)
                            }
                        }
                    }
                }
            }

            // Only when there is genuinely nothing to show. A first run has no history and no marks,
            // and four empty headings would be a screen telling someone off for being new.
            if (continueWatching.isEmpty() && recentlyAdded.isEmpty() &&
                channels.isEmpty() && favourites.isEmpty()
            ) {
                item { FirstRun(loading = loading, onOpen = onOpen) }
            }
        }
    }
}

/**
 * What the start screen says before anyone has watched anything.
 *
 * Three destinations and a sentence, rather than an apology. The sentence changes while the library
 * is still arriving, because "nothing here yet" and "still reading your library" are different
 * facts and only one of them is worth waiting through.
 */
@Composable
private fun FirstRun(loading: Boolean, onOpen: (Section) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(NightRaised)
                .border(1.dp, Violet.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                .padding(24.dp),
        ) {
            Text(
                if (loading) "Your library is still arriving" else "Nothing watched yet",
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (loading) {
                    "Films and series appear here as soon as they have been read. You can start " +
                        "browsing in the meantime."
                } else {
                    "Whatever you start turns up here, and so does anything you heart or bookmark."
                },
                color = InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Section.Live, Section.Movies, Section.Series).forEach { section ->
                    TextPill(section.label) { onOpen(section) }
                }
            }
        }
    }
}
