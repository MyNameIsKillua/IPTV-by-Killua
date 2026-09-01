package dev.killua.iptv.feature.search

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.killua.iptv.core.database.LikeSearchTerm
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.ui.components.releasesFocusVertically
import dev.killua.iptv.ui.components.POSTER_ASPECT_RATIO

@Composable
fun SearchRoute(
    viewModel: SearchViewModel,
    onPlayChannel: (LiveChannel) -> Unit,
    onOpenMovie: (movieId: String) -> Unit,
    onOpenSeries: (seriesId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        onInput = viewModel::onInput,
        onClear = viewModel::clear,
        onRetry = viewModel::retry,
        onShowMoreChannels = viewModel::showMoreChannels,
        onShowMoreMovies = viewModel::showMoreMovies,
        onShowMoreSeries = viewModel::showMoreSeries,
        onPlayChannel = onPlayChannel,
        onOpenMovie = onOpenMovie,
        onOpenSeries = onOpenSeries,
    )
}

@Composable
fun SearchScreen(
    state: SearchUiState,
    onInput: (String) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onShowMoreChannels: () -> Unit,
    onShowMoreMovies: () -> Unit,
    onShowMoreSeries: () -> Unit,
    onPlayChannel: (LiveChannel) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        OutlinedTextField(
            value = state.input,
            onValueChange = onInput,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .releasesFocusVertically(),
            singleLine = true,
            placeholder = { Text("Channels, movies and series") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.input.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            shape = MaterialTheme.shapes.medium,
        )

        state.errorMessage?.let { error ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(error)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onRetry) { Text("Try again") }
                }
            }
        }

        when {
            state.isTermTooShort -> Hint(
                "Keep typing — at least ${LikeSearchTerm.MINIMUM_GLOBAL_LENGTH} characters.",
            )

            state.submittedTerm.isEmpty() && !state.isSearching && state.errorMessage == null ->
                Hint("Search everything already on this device. Nothing is sent to your provider.")

            state.isEmptyResult -> Hint("Nothing matches “${state.submittedTerm}”.")

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                channelSection(state.channels, onShowMoreChannels, onPlayChannel)
                movieSection(state.movies, onShowMoreMovies, onOpenMovie)
                seriesSection(state.series, onShowMoreSeries, onOpenSeries)
                if (state.isSearching) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        )
    }
}

/** Appears only when the query returned more than the section shows. */
private fun LazyListScope.showMore(section: SearchSection<*>, onClick: () -> Unit) {
    if (!section.hasMore) return
    item {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text("Show more")
        }
    }
}

private fun LazyListScope.channelSection(
    section: SearchSection<LiveChannel>,
    onShowMore: () -> Unit,
    onPlay: (LiveChannel) -> Unit,
) {
    if (section.isEmpty) return
    sectionHeader("Live channels")
    items(section.items, key = { "live-${it.id}" }) { channel ->
        ResultRow(
            title = channel.name,
            subtitle = null,
            artworkUrl = channel.logoUrl,
            artworkAspectRatio = 16f / 9f,
            placeholder = { Icon(Icons.Outlined.LiveTv, null, Modifier.size(20.dp)) },
            onClick = { onPlay(channel) },
        )
    }
    showMore(section, onShowMore)
}

private fun LazyListScope.movieSection(
    section: SearchSection<MovieSummary>,
    onShowMore: () -> Unit,
    onOpen: (String) -> Unit,
) {
    if (section.isEmpty) return
    sectionHeader("Movies")
    items(section.items, key = { "movie-${it.id}" }) { movie ->
        ResultRow(
            title = movie.name,
            subtitle = listOfNotNull(
                movie.releaseYear?.toString(),
                movie.rating?.let { "★ %.1f".format(it) },
            ).joinToString(" · ").takeIf { it.isNotEmpty() },
            artworkUrl = movie.posterUrl,
            artworkAspectRatio = POSTER_ASPECT_RATIO,
            placeholder = { Icon(Icons.Outlined.Movie, null, Modifier.size(20.dp)) },
            onClick = { onOpen(movie.id) },
        )
    }
    showMore(section, onShowMore)
}

private fun LazyListScope.seriesSection(
    section: SearchSection<SeriesSummary>,
    onShowMore: () -> Unit,
    onOpen: (String) -> Unit,
) {
    if (section.isEmpty) return
    sectionHeader("Series")
    items(section.items, key = { "series-${it.id}" }) { series ->
        ResultRow(
            title = series.name,
            subtitle = listOfNotNull(
                series.releaseYear?.toString(),
                series.rating?.let { "★ %.1f".format(it) },
            ).joinToString(" · ").takeIf { it.isNotEmpty() },
            artworkUrl = series.posterUrl,
            artworkAspectRatio = POSTER_ASPECT_RATIO,
            placeholder = { Icon(Icons.Outlined.VideoLibrary, null, Modifier.size(20.dp)) },
            onClick = { onOpen(series.id) },
        )
    }
    showMore(section, onShowMore)
}

/**
 * One hit. A list row rather than a poster tile: results from three libraries sit under each
 * other here, and a mixed grid of 16:9 logos and 2:3 posters reads as broken rather than varied.
 */
@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    artworkAspectRatio: Float,
    placeholder: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(artworkAspectRatio)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                placeholder()
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
