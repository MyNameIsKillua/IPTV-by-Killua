package dev.killua.iptv.feature.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.model.displayLabel
import dev.killua.iptv.domain.progress.WatchProgressPolicy
import dev.killua.iptv.ui.components.POSTER_ASPECT_RATIO

@Composable
fun SeriesDetailsRoute(
    viewModel: SeriesDetailsViewModel,
    onBack: () -> Unit,
    onPlayEpisode: (episodeId: String, restart: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SeriesDetailsScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onToggleFavorite = viewModel::toggleFavorite,
        onToggleSaved = viewModel::toggleSaved,
        onSelectSeason = viewModel::selectSeason,
        onPlayEpisode = { episodeId -> onPlayEpisode(episodeId, false) },
        onRestartEpisode = { episodeId -> onPlayEpisode(episodeId, true) },
        onToggleEpisodeWatched = viewModel::toggleEpisodeWatched,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailsScreen(
    state: SeriesDetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSaved: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onRestartEpisode: (String) -> Unit,
    onToggleEpisodeWatched: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (state.isFavorite) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Default.FavoriteBorder
                            },
                            contentDescription = if (state.isFavorite) {
                                "Remove from favorites"
                            } else {
                                "Add to favorites"
                            },
                        )
                    }
                    // The saved list is deliberately a second, differently shaped control: the
                    // heart marks a series inside Series, this puts it on the one list that mixes
                    // films, series, and channels.
                    IconButton(onClick = onToggleSaved) {
                        Icon(
                            imageVector = if (state.isSaved) {
                                Icons.Default.Bookmark
                            } else {
                                Icons.Outlined.BookmarkBorder
                            },
                            contentDescription = if (state.isSaved) {
                                "Remove from My list"
                            } else {
                                "Add to My list"
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item { Backdrop(state) }
            item { Header(state) }
            item { PlaybackActions(state, onPlayEpisode, onRestartEpisode) }
            state.errorMessage?.let { error ->
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(error)
                            Spacer(Modifier.height(8.dp))
                            AssistChip(onClick = onRetry, label = { Text("Retry") })
                        }
                    }
                }
            }
            item { Description(state) }
            if (state.seasons.size > 1) {
                item { SeasonChips(state, onSelectSeason) }
            }
            episodes(state, onRetry, onPlayEpisode, onToggleEpisodeWatched)
        }
    }
}

@Composable
private fun Backdrop(state: SeriesDetailsUiState) {
    val url = state.details?.backdropUrl ?: state.summary?.posterUrl
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(40.dp))
        }
    }
}

@Composable
private fun Header(state: SeriesDetailsUiState) {
    Row(Modifier.padding(16.dp)) {
        Box(
            modifier = Modifier
                .width(104.dp)
                .aspectRatio(POSTER_ASPECT_RATIO)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val poster = state.details?.posterUrl ?: state.summary?.posterUrl
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Outlined.VideoLibrary, null)
            }
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(state.title, style = MaterialTheme.typography.titleLarge)
            val meta = listOfNotNull(
                (state.details?.releaseYear ?: state.summary?.releaseYear)?.toString(),
                (state.details?.rating ?: state.summary?.rating)?.let { "★ %.1f".format(it) },
                state.details?.episodes?.size?.takeIf { it > 0 }?.let { "$it episodes" },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.details?.genre?.let { genre ->
                Spacer(Modifier.height(6.dp))
                Text(genre, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The primary action for the series, mirroring the Movie details screen.
 *
 * It targets one specific episode rather than the series as a whole, and names it, so the button
 * can never be ambiguous about what pressing it starts.
 */
@Composable
private fun PlaybackActions(
    state: SeriesDetailsUiState,
    onPlayEpisode: (String) -> Unit,
    onRestartEpisode: (String) -> Unit,
) {
    val episode = state.nextEpisode ?: return
    Column(Modifier.padding(horizontal = 16.dp)) {
        state.nextEpisodeProgress?.takeIf { it.durationMs > 0L && !it.completed }?.let { progress ->
            LinearProgressIndicator(
                progress = {
                    WatchProgressPolicy.fraction(progress.positionMs, progress.durationMs)
                        .toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onPlayEpisode(episode.id) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.canResume) "Resume" else "Play")
            }
            // Only offered when it would do something different from the primary button; a
            // finished episode already starts from the beginning.
            if (state.canResume) {
                OutlinedButton(onClick = { onRestartEpisode(episode.id) }) { Text("Restart") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = episode.displayLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun Description(state: SeriesDetailsUiState) {
    val details = state.details ?: return
    Column(Modifier.padding(horizontal = 16.dp)) {
        details.plot?.let { plot ->
            Text("Plot", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(plot, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
        details.director?.let { director ->
            Text("Director", style = MaterialTheme.typography.titleMedium)
            Text(director, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
        details.cast?.let { cast ->
            Text("Cast", style = MaterialTheme.typography.titleMedium)
            Text(cast, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SeasonChips(state: SeriesDetailsUiState, onSelectSeason: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.seasons, key = { it }) { season ->
            FilterChip(
                selected = state.selectedSeason == season,
                onClick = { onSelectSeason(season) },
                label = { Text(if (season == 0) "Episodes" else "Season $season") },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.episodes(
    state: SeriesDetailsUiState,
    onRetry: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    onToggleEpisodeWatched: (String) -> Unit,
) {
    val episodes = state.episodesOfSelectedSeason
    when {
        state.isLoading && episodes.isEmpty() -> item {
            Box(
                Modifier.fillMaxWidth().padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            }
        }

        episodes.isEmpty() -> item {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No episodes listed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "The provider returned no episodes for this series.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                AssistChip(onClick = onRetry, label = { Text("Reload") })
            }
        }

        else -> items(episodes, key = SeriesEpisode::id) { episode ->
            EpisodeRow(
                episode = episode,
                progress = state.episodeProgress[episode.id],
                onPlay = { onPlayEpisode(episode.id) },
                onToggleWatched = { onToggleEpisodeWatched(episode.id) },
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: SeriesEpisode,
    progress: WatchProgress?,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    val watched = progress?.completed == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (episode.stillUrl != null) {
                AsyncImage(
                    model = episode.stillUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = episode.episodeNumber?.toString() ?: "–",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = episode.episodeNumber?.let { "$it. ${episode.title}" } ?: episode.title,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = episode.durationSeconds?.let { seconds ->
                val minutes = seconds / 60
                if (minutes > 0) "$minutes min" else null
            }
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EpisodeProgress(progress)
            episode.plot?.let { plot ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = plot,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Its own button rather than part of the row tap, which already means "play this".
        IconButton(
            onClick = onToggleWatched,
            modifier = Modifier.align(Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = if (watched) {
                    Icons.Default.CheckCircle
                } else {
                    Icons.Outlined.CheckCircle
                },
                contentDescription = if (watched) {
                    "Mark as unwatched"
                } else {
                    "Mark as watched"
                },
                tint = if (watched) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * How far into an unfinished episode the viewer got.
 *
 * A finished one shows nothing here: the row's own toggle already states it, and it says the same
 * thing in a place that can also be tapped to change it. Two check marks in one row was one too
 * many, and only one of them was ever actionable.
 */
@Composable
private fun EpisodeProgress(progress: WatchProgress?) {
    if (progress == null || progress.durationMs <= 0L || progress.completed) return
    Spacer(Modifier.height(4.dp))
    if (progress.positionMs <= 0L) return
    LinearProgressIndicator(
        progress = {
            WatchProgressPolicy.fraction(progress.positionMs, progress.durationMs).toFloat()
        },
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .height(3.dp),
    )
    val remainingMinutes = ((progress.durationMs - progress.positionMs) / 60_000L).toInt()
    if (remainingMinutes > 0) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = "$remainingMinutes min left",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
