package dev.killua.iptv.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What is in My list, and why each part of it is there. */
internal data class MyList(
    val favourites: List<BrowseItem> = emptyList(),
    val saved: List<BrowseItem> = emptyList(),
) {
    val isEmpty: Boolean get() = favourites.isEmpty() && saved.isEmpty()
}

/**
 * What this viewer has kept: hearted, and put aside for later.
 *
 * **Two sections, not three.** It used to open with continue-watching, which was right while there
 * was nowhere else for that to live and wrong the moment there was a start screen. Something half
 * finished is not something you kept — and having it in both places made this a second, worse Start.
 * What is left answers one question each: a heart is a verdict, a bookmark is an intention.
 *
 * Rows rather than a grid, so both stay visible together instead of the first pushing the other off
 * the screen. A section with nothing in it is left out entirely rather than shown empty: an empty
 * heading is a reproach, and the client has no business telling anyone they have not marked enough.
 */
@Composable
internal fun MyListScreen(
    list: MyList,
    marks: (BrowseItem) -> Marks?,
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocus: FocusRequester,
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
                "My list",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            FilterField(query, onQueryChange, placeholder = "Search…", focusRequester = searchFocus)
        }

        if (list.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing marked yet. Use the heart or the bookmark on a title.",
                    color = InkMuted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (list.favourites.isNotEmpty()) {
                item {
                    PosterRow("Favourites") {
                        items(list.favourites, key = { it.queueKey }) { item ->
                            Box(Modifier.width(PosterRowWidth)) {
                                PosterTile(
                                    item = item,
                                    marks = marks(item),
                                    onToggleFavourite = onToggleFavourite,
                                    onToggleSaved = onToggleSaved,
                                    onClick = onClick,
                                    aspect = if (item.isLiveIndexed()) 16f / 9f else 2f / 3f,
                                )
                            }
                        }
                    }
                }
            }
            if (list.saved.isNotEmpty()) {
                item {
                    PosterRow("Saved for later") {
                        items(list.saved, key = { it.queueKey }) { item ->
                            Box(Modifier.width(PosterRowWidth)) {
                                PosterTile(
                                    item = item,
                                    marks = marks(item),
                                    onToggleFavourite = onToggleFavourite,
                                    onToggleSaved = onToggleSaved,
                                    onClick = onClick,
                                    // The saved list is the one place all three kinds sit together,
                                    // and a channel's logo is wide. In a portrait tile it is cropped
                                    // to its middle third, which is the part without the name.
                                    aspect = if (item.isLiveIndexed()) 16f / 9f else 2f / 3f,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
