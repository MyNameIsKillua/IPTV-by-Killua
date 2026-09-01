package dev.killua.iptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.ResumableKind
import dev.killua.iptv.domain.model.WatchlistEntry
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.ui.theme.Cyan
import dev.killua.iptv.ui.theme.LocalIsTelevision

/** Provider artwork is portrait for both Movies and Series. */
const val POSTER_ASPECT_RATIO = 2f / 3f

/**
 * One poster tile. Shared by Movies and Series rather than duplicated, because a provider that
 * omits artwork or metadata has to degrade the same way in both.
 */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    meta: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Cropping is right for a poster, which is portrait already. A channel logo is wide, and
     * cropping one into a portrait tile leaves the middle of a wordmark, so the saved list fits
     * those instead.
     */
    contentScale: ContentScale = ContentScale.Crop,
) {
    // Focus is **watched on the tile and drawn on the artwork**, which is two places on purpose.
    // The clickable is what a remote can land on, so that is the only node whose focus state means
    // anything — `onFocusChanged` on a child of it never fires, which is how this was got wrong the
    // first time. And the ring belongs on the picture rather than around the whole tile, because
    // the caption below is two lines of a length nobody controls, and a border that changes height
    // with the title is a list that jumps as the remote moves through it.
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT_RATIO)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 3.dp,
                    color = if (focused) Cyan else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            } else {
                Icon(Icons.Outlined.Movie, contentDescription = null, Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!meta.isNullOrEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The Continue Watching row, shared by Home, Movies, and Series.
 *
 * Entries carry their own content kind, so one row can hold both libraries and the caller decides
 * per entry which screen to open. [showKind] belongs to the mixed row on Home only: inside a
 * library tab every entry is that library, and labelling each tile would be noise.
 */
@Composable
fun ContinueWatchingRow(
    entries: List<ContinueWatchingEntry>,
    onOpen: (ContinueWatchingEntry) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Continue watching",
    showKind: Boolean = false,
    contentPadding: Dp = 16.dp,
) {
    if (entries.isEmpty()) return
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = contentPadding, top = 4.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = contentPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The key mixes kind and id because a Movie and a series can share a provider id.
            items(entries, key = { "${it.kind}:${it.contentId}" }) { entry ->
                PosterCard(
                    title = entry.title,
                    posterUrl = entry.posterUrl,
                    meta = if (showKind && entry.kind == ResumableKind.Series) "Series" else null,
                    onClick = { onOpen(entry) },
                    modifier = Modifier.width(posterWidth()),
                )
            }
        }
    }
}

/**
 * The one saved list, mixing Movies, Series, and channels.
 *
 * Every tile carries its kind, because unlike Continue Watching this row can hold a channel, and a
 * channel opens by playing rather than by opening a details screen. The entries arrive already
 * ordered and trimmed by the query; nothing is re-sorted here.
 */
@Composable
fun SavedListRow(
    entries: List<WatchlistEntry>,
    onOpen: (WatchlistEntry) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "My list",
    contentPadding: Dp = 16.dp,
) {
    if (entries.isEmpty()) return
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = contentPadding, top = 4.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = contentPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Kind and id together: the three libraries have independent provider id spaces.
            items(entries, key = { "${it.kind}:${it.contentId}" }) { entry ->
                PosterCard(
                    title = entry.title,
                    posterUrl = entry.artworkUrl,
                    meta = when (entry.kind) {
                        WatchlistKind.Movie -> "Movie"
                        WatchlistKind.Series -> "Series"
                        WatchlistKind.Channel -> "Channel"
                    },
                    onClick = { onOpen(entry) },
                    modifier = Modifier.width(posterWidth()),
                    contentScale = if (entry.kind == WatchlistKind.Channel) {
                        ContentScale.Fit
                    } else {
                        ContentScale.Crop
                    },
                )
            }
        }
    }
}

/**
 * Newly added films and series.
 *
 * Same shape as the other two rows, and like them it hides itself when empty — which here also
 * covers the case where the provider's timestamps were judged not worth ordering by.
 */
@Composable
fun RecentlyAddedRow(
    entries: List<RecentlyAddedEntry>,
    onOpen: (RecentlyAddedEntry) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Recently added",
    contentPadding: Dp = 16.dp,
) {
    if (entries.isEmpty()) return
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = contentPadding, top = 4.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = contentPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { "${it.kind}:${it.contentId}" }) { entry ->
                PosterCard(
                    title = entry.title,
                    posterUrl = entry.posterUrl,
                    meta = if (entry.kind == ResumableKind.Series) "Series" else null,
                    onClick = { onOpen(entry) },
                    modifier = Modifier.width(posterWidth()),
                )
            }
        }
    }
}

/**
 * How wide a poster is, and how little of one a grid will accept.
 *
 * Two numbers because a television is not a large phone. The Fire TV Stick this was measured
 * against reports 1920x1080 at density 2, which is 960dp wide - so at the phone's 116dp minimum a
 * grid puts **seven** posters across, each of them a thumbnail at three metres. Four is what the
 * same screen holds at 200dp, which is a poster someone can actually read the title under.
 *
 * The row uses the same pair for the same reason: a row of 104dp tiles on a television is a strip
 * of stamps.
 */
@Composable
private fun posterWidth(): Dp = if (LocalIsTelevision.current) 180.dp else 104.dp

@Composable
private fun posterMinimum(): Dp = if (LocalIsTelevision.current) 200.dp else 116.dp

/** The grid both libraries use, so their cell size and spacing cannot drift apart. */
@Composable
fun PosterGrid(
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = posterMinimum()),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun PosterSkeletons(modifier: Modifier = Modifier) {
    PosterGrid(modifier) {
        items(12) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(POSTER_ASPECT_RATIO)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.75f)
                        .height(13.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}
