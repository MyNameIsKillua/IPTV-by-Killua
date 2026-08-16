package dev.killua.iptv.feature.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.killua.iptv.domain.progress.WatchProgressPolicy

@Composable
fun MovieDetailsRoute(
    viewModel: MovieDetailsViewModel,
    onBack: () -> Unit,
    onPlay: (restart: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MovieDetailsScreen(
        state = state,
        onBack = onBack,
        onToggleFavorite = viewModel::toggleFavorite,
        onToggleSaved = viewModel::toggleSaved,
        onToggleWatched = viewModel::toggleWatched,
        onRetry = viewModel::load,
        onPlay = { onPlay(false) },
        onRestart = { onPlay(true) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailsScreen(
    state: MovieDetailsUiState,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSaved: () -> Unit,
    onToggleWatched: () -> Unit,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onRestart: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Beside the heart rather than among the playback buttons: this states what
                    // the viewer already did, it does not start anything.
                    IconButton(onClick = onToggleWatched) {
                        Icon(
                            imageVector = if (state.isWatched) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Outlined.CheckCircle
                            },
                            contentDescription = if (state.isWatched) {
                                "Mark as unwatched"
                            } else {
                                "Mark as watched"
                            },
                        )
                    }
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
                    // heart marks a film inside Movies, this puts it on the one list that mixes
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
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Backdrop(state)
            Spacer(Modifier.height(16.dp))
            Header(state)
            Spacer(Modifier.height(16.dp))
            PlaybackActions(state, onPlay, onRestart)
            state.errorMessage?.let { error ->
                Spacer(Modifier.height(14.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
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
            Details(state)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Backdrop(state: MovieDetailsUiState) {
    val image = state.details?.backdropUrl ?: state.details?.posterUrl ?: state.summary?.posterUrl
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Outlined.Movie, contentDescription = null, Modifier.size(44.dp))
        }
        if (state.isLoading) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun Header(state: MovieDetailsUiState) {
    Row(Modifier.padding(horizontal = 16.dp)) {
        val poster = state.details?.posterUrl ?: state.summary?.posterUrl
        Box(
            modifier = Modifier
                .width(96.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Outlined.Movie, contentDescription = null)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(state.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            val meta = listOfNotNull(
                (state.details?.releaseYear ?: state.summary?.releaseYear)?.toString(),
                (state.details?.rating ?: state.summary?.rating)?.let { "★ %.1f".format(it) },
                state.details?.durationSeconds?.let(::formatDuration),
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.details?.genre?.let { genre ->
                Spacer(Modifier.height(6.dp))
                Text(genre, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PlaybackActions(
    state: MovieDetailsUiState,
    onPlay: () -> Unit,
    onRestart: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        state.progress?.takeIf { it.durationMs > 0L && !it.completed }?.let { progress ->
            val fraction = WatchProgressPolicy.fraction(progress.positionMs, progress.durationMs)
            LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.canResume) "Resume" else "Play")
            }
            // Only offered when it would do something different from the primary button; a
            // finished title already starts from the beginning.
            if (state.canResume) {
                OutlinedButton(onClick = onRestart) { Text("Restart") }
            }
        }
        if (state.isWatched) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Watched",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Details(state: MovieDetailsUiState) {
    val details = state.details ?: return
    Column(Modifier.padding(horizontal = 16.dp)) {
        details.plot?.let { plot ->
            Spacer(Modifier.height(18.dp))
            Text("Plot", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(plot, style = MaterialTheme.typography.bodyMedium)
        }
        listOfNotNull(
            details.director?.let { "Director" to it },
            details.cast?.let { "Cast" to it },
        ).forEach { (label, value) ->
            Spacer(Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
}
