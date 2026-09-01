package dev.killua.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.displayLabel

/**
 * One series: what it is, and every episode of it.
 *
 * The client used to drop straight into a bare list of episodes with the plot squeezed above it as
 * three lines of grey text. That is fine for a show you already know and useless for one you are
 * deciding about — which is the same argument films already won when they got a panel of their own.
 * So a series now opens the way a film does, with the difference that its episodes are on the same
 * screen rather than behind it: nobody wants to read a synopsis and then navigate to find episode
 * one.
 *
 * **One primary button**, naming the episode it will start. The rule for which episode that is lives
 * in `:shared` — carry on with whatever was left unfinished, otherwise the first unwatched one — so
 * the phone and this window cannot disagree about where someone is up to. A per-row play control
 * would have been the obvious alternative and is worse: it makes the common case (carry on) exactly
 * as much work as the rare one (rewatch episode four of season two).
 */
@Composable
internal fun SeriesDetailPanel(
    title: String,
    details: SeriesDetails?,
    posterUrl: String?,
    /** Every episode, in provider order, already turned into rows. */
    episodes: List<BrowseItem>,
    seasons: List<Int>,
    season: Int?,
    onSeason: (Int) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocus: FocusRequester,
    /** The episode the big button starts, and where it would resume from. */
    nextEpisode: BrowseItem?,
    nextEpisodeLabel: String?,
    resumeFrom: Long?,
    marks: Marks?,
    onToggleFavourite: () -> Unit,
    onToggleSaved: () -> Unit,
    watched: (BrowseItem) -> Float?,
    watchedToggle: (BrowseItem) -> (() -> Unit)?,
    loading: Boolean,
    onPlay: (BrowseItem, fromStart: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val meta = listOfNotNull(
        details?.releaseYear?.toString(),
        details?.rating?.takeIf { it > 0.0 }?.let { "%.1f".format(it) },
        details?.genre?.takeIf { it.isNotBlank() },
        seasons.size.takeIf { it > 1 }?.let { "$it seasons" },
        episodes.size.takeIf { it > 0 }?.let { "$it episodes" },
    )

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconPill(Icons.AutoMirrored.Filled.ArrowBack, "Back to browsing", onClick = onClose)
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            if (seasons.size > 1) {
                Spacer(Modifier.width(14.dp))
                SortMenu(
                    options = seasons.map { it.toString() to "Season $it" },
                    selected = season?.toString(),
                    onSelect = { chosen -> chosen.toIntOrNull()?.let(onSeason) },
                )
            }
            Spacer(Modifier.width(16.dp))
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = VioletBright,
                )
            }
            Spacer(Modifier.weight(1f))
            FilterField(
                query,
                onQueryChange,
                placeholder = "Find an episode…",
                focusRequester = searchFocus,
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                    Box(
                        Modifier
                            .width(200.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(NightSoft),
                    ) {
                        RemoteImage(
                            url = details?.posterUrl ?: posterUrl,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = { ArtworkPlaceholder(title) },
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        if (meta.isNotEmpty()) {
                            Text(
                                meta.joinToString("   ·   "),
                                color = InkMuted,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(Modifier.height(14.dp))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            nextEpisode?.let { episode ->
                                Button(
                                    onClick = { onPlay(episode, false) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = VioletBright,
                                        contentColor = Night,
                                    ),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        buildString {
                                            append(
                                                if (resumeFrom != null) "Resume" else "Play",
                                            )
                                            nextEpisodeLabel?.let { append(" $it") }
                                            resumeFrom?.let {
                                                append(" from ${formatDuration(it)}")
                                            }
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                if (resumeFrom != null) {
                                    Spacer(Modifier.width(10.dp))
                                    TextPill("Start over", onClick = { onPlay(episode, true) })
                                }
                            }
                            marks?.let {
                                Spacer(Modifier.width(16.dp))
                                MarkButton(
                                    marked = it.favourite,
                                    markedIcon = Icons.Default.Favorite,
                                    unmarkedIcon = Icons.Default.FavoriteBorder,
                                    description = "Favourite",
                                    onClick = onToggleFavourite,
                                )
                                MarkButton(
                                    marked = it.saved,
                                    markedIcon = Icons.Default.Bookmark,
                                    unmarkedIcon = Icons.Default.BookmarkBorder,
                                    description = "Save to my list",
                                    onClick = onToggleSaved,
                                )
                            }
                        }

                        details?.plot?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(18.dp))
                            Text(
                                it,
                                color = Ink.copy(alpha = 0.86f),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.widthIn(max = 820.dp),
                            )
                        }
                        details?.cast?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(12.dp))
                            DetailLine("Cast", it)
                        }
                        details?.director?.takeIf { it.isNotBlank() }?.let {
                            DetailLine("Director", it)
                        }
                    }
                }
            }

            if (episodes.isEmpty()) {
                item {
                    Text(
                        if (loading) "" else "No episodes matched that.",
                        color = InkMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            items(episodes, key = { it.queueKey }) { item ->
                ListRow(
                    item = item,
                    marks = null,
                    onToggleFavourite = {},
                    onToggleSaved = {},
                    onClick = { onPlay(it, false) },
                    watched = watched(item),
                    onToggleWatched = watchedToggle(item),
                    subtitle = (item as? BrowseItem.Episode)?.value?.plot
                        ?.takeIf { it.isNotBlank() },
                )
            }
        }
    }
}

/** `S2 E4 · Title`, or as much of it as the provider bothered to number. */
internal fun BrowseItem.episodeLabel(): String? =
    (this as? BrowseItem.Episode)?.value?.displayLabel
