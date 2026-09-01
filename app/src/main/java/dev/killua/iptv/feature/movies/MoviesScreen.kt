package dev.killua.iptv.feature.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import dev.killua.iptv.domain.model.MovieSortOrder
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.ui.components.releasesFocusVertically
import dev.killua.iptv.ui.components.ContinueWatchingRow
import dev.killua.iptv.ui.components.PosterCard
import dev.killua.iptv.ui.components.PosterGrid
import dev.killua.iptv.ui.components.PosterSkeletons

@Composable
fun MoviesRoute(
    viewModel: MoviesViewModel,
    onOpenMovie: (movieId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val movies = viewModel.movies.collectAsLazyPagingItems()
    MoviesScreen(
        state = state,
        movies = movies,
        onSearchInput = viewModel::onSearchInput,
        onClearSearch = viewModel::clearSearch,
        onSelectCategory = viewModel::selectCategory,
        onSelectLanguage = viewModel::selectLanguage,
        onSetSortOrder = viewModel::setSortOrder,
        onToggleFavorites = viewModel::toggleFavoritesOnly,
        onToggleInProgress = viewModel::toggleInProgressOnly,
        onClearFilters = viewModel::clearFilters,
        onRefresh = viewModel::refresh,
        onOpenMovie = onOpenMovie,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesScreen(
    state: MoviesUiState,
    movies: LazyPagingItems<MovieSummary>,
    onSearchInput: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectLanguage: (String?) -> Unit,
    onSetSortOrder: (MovieSortOrder) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleInProgress: () -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onOpenMovie: (movieId: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Movies", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = when {
                                state.isRefreshing -> "Updating library…"
                                else -> "${movies.itemCount} movies loaded"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh movie library")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            SearchField(state.searchInput, onSearchInput, onClearSearch)
            FilterBar(
                state = state,
                onSelectLanguage = onSelectLanguage,
                onSetSortOrder = onSetSortOrder,
                onToggleFavorites = onToggleFavorites,
                onToggleInProgress = onToggleInProgress,
                onClearFilters = onClearFilters,
            )
            CategoryChips(state, onSelectCategory)
            // Hidden while filtering, so the row cannot contradict the grid below it.
            if (!state.hasActiveFilter) {
                ContinueWatchingRow(
                    entries = state.continueWatching,
                    onOpen = { entry -> onOpenMovie(entry.contentId) },
                )
            }
            state.errorMessage?.let { error ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(error, Modifier.padding(14.dp))
                }
            }
            MovieContent(
                modifier = Modifier.weight(1f),
                state = state,
                movies = movies,
                onRefresh = onRefresh,
                onClearFilters = onClearFilters,
                onOpenMovie = onOpenMovie,
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .releasesFocusVertically(),
        singleLine = true,
        placeholder = { Text("Search movies") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
private fun FilterBar(
    state: MoviesUiState,
    onSelectLanguage: (String?) -> Unit,
    onSetSortOrder: (MovieSortOrder) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleInProgress: () -> Unit,
    onClearFilters: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SortChip(state.filter.sortOrder, onSetSortOrder)
        }
        if (state.languages.isNotEmpty()) {
            item { LanguageChip(state, onSelectLanguage) }
        }
        item {
            FilterChip(
                selected = state.filter.inProgressOnly,
                onClick = onToggleInProgress,
                label = { Text("Continue") },
                leadingIcon = { Icon(Icons.Outlined.PlayCircle, null, Modifier.size(18.dp)) },
            )
        }
        item {
            FilterChip(
                selected = state.filter.favoritesOnly,
                onClick = onToggleFavorites,
                label = { Text("Favorites") },
                leadingIcon = { Icon(Icons.Outlined.Favorite, null, Modifier.size(18.dp)) },
            )
        }
        if (state.hasActiveFilter) {
            item {
                AssistChip(
                    onClick = onClearFilters,
                    label = { Text("Clear filters") },
                    leadingIcon = { Icon(Icons.Default.Clear, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun SortChip(current: MovieSortOrder, onSelect: (MovieSortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != MovieSortOrder.ProviderDefault,
            onClick = { expanded = true },
            label = { Text(current.label()) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MovieSortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageChip(state: MoviesUiState, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val active = state.filter.languageTag
    Box {
        FilterChip(
            selected = active != null,
            onClick = { expanded = true },
            label = { Text(active?.uppercase() ?: "Language") },
            leadingIcon = { Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All languages") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            state.languages.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(tag.uppercase()) },
                    onClick = {
                        expanded = false
                        onSelect(tag)
                    },
                )
            }
        }
    }
}

@Composable
private fun CategoryChips(state: MoviesUiState, onSelectCategory: (String?) -> Unit) {
    if (state.categories.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.filter.categoryId == null,
                onClick = { onSelectCategory(null) },
                label = { Text("All") },
            )
        }
        items(state.categories, key = { it.id }) { category ->
            FilterChip(
                selected = state.filter.categoryId == category.id,
                onClick = { onSelectCategory(category.id) },
                label = { Text(category.name, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun MovieContent(
    modifier: Modifier,
    state: MoviesUiState,
    movies: LazyPagingItems<MovieSummary>,
    onRefresh: () -> Unit,
    onClearFilters: () -> Unit,
    onOpenMovie: (movieId: String) -> Unit,
) {
    when {
        movies.loadState.refresh is LoadState.Loading && movies.itemCount == 0 ->
            PosterSkeletons(modifier)

        movies.itemCount == 0 && state.hasActiveFilter && !state.isRefreshing ->
            EmptyMovieState(
                modifier = modifier,
                title = "Nothing matches these filters",
                body = "No cached movie matches the current search and filter combination.",
                action = "Clear filters",
                onAction = onClearFilters,
            )

        movies.itemCount == 0 && !state.isRefreshing ->
            EmptyMovieState(
                modifier = modifier,
                title = "No movies cached yet",
                body = "Refresh to download your provider's movie library.",
                action = "Refresh library",
                onAction = onRefresh,
            )

        else -> PosterGrid(modifier) {
            items(
                count = movies.itemCount,
                key = { index -> movies[index]?.id ?: "placeholder-$index" },
            ) { index ->
                movies[index]?.let { movie ->
                    PosterCard(movie = movie, onClick = { onOpenMovie(movie.id) })
                }
            }
            if (movies.loadState.append is LoadState.Loading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PosterCard(movie: MovieSummary, onClick: () -> Unit) {
    PosterCard(
        title = movie.name,
        posterUrl = movie.posterUrl,
        meta = listOfNotNull(
            movie.releaseYear?.toString(),
            movie.rating?.let { "★ %.1f".format(it) },
        ).joinToString(" · "),
        onClick = onClick,
    )
}

@Composable
private fun EmptyMovieState(
    modifier: Modifier,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(Icons.Outlined.Movie, null, Modifier.padding(20.dp).size(36.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(7.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            AssistChip(onClick = onAction, label = { Text(action) })
        }
    }
}

private fun MovieSortOrder.label(): String = when (this) {
    MovieSortOrder.ProviderDefault -> "Provider order"
    MovieSortOrder.NameAscending -> "Name A–Z"
    MovieSortOrder.RatingDescending -> "Top rated"
    MovieSortOrder.ReleaseYearDescending -> "Newest year"
    MovieSortOrder.RecentlyAdded -> "Recently added"
}
