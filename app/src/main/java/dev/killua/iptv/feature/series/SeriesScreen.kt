package dev.killua.iptv.feature.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VideoLibrary
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import dev.killua.iptv.domain.model.SeriesSortOrder
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.ui.components.releasesFocusVertically
import dev.killua.iptv.ui.components.ContinueWatchingRow
import dev.killua.iptv.ui.components.PosterCard
import dev.killua.iptv.ui.components.PosterGrid
import dev.killua.iptv.ui.components.PosterSkeletons

@Composable
fun SeriesRoute(
    viewModel: SeriesViewModel,
    onOpenSeries: (seriesId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val series = viewModel.series.collectAsLazyPagingItems()
    SeriesScreen(
        state = state,
        series = series,
        onSearchInput = viewModel::onSearchInput,
        onClearSearch = viewModel::clearSearch,
        onSelectCategory = viewModel::selectCategory,
        onSelectLanguage = viewModel::selectLanguage,
        onSetSortOrder = viewModel::setSortOrder,
        onToggleFavorites = viewModel::toggleFavoritesOnly,
        onToggleInProgress = viewModel::toggleInProgressOnly,
        onClearFilters = viewModel::clearFilters,
        onRefresh = viewModel::refresh,
        onOpenSeries = onOpenSeries,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    state: SeriesUiState,
    series: LazyPagingItems<SeriesSummary>,
    onSearchInput: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectLanguage: (String?) -> Unit,
    onSetSortOrder: (SeriesSortOrder) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleInProgress: () -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSeries: (seriesId: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Series", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (state.isRefreshing) {
                                "Updating library…"
                            } else {
                                "${series.itemCount} series loaded"
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
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh series library")
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
                    onOpen = { entry -> onOpenSeries(entry.contentId) },
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
            SeriesContent(
                modifier = Modifier.weight(1f),
                state = state,
                series = series,
                onRefresh = onRefresh,
                onClearFilters = onClearFilters,
                onOpenSeries = onOpenSeries,
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
        placeholder = { Text("Search series") },
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
    state: SeriesUiState,
    onSelectLanguage: (String?) -> Unit,
    onSetSortOrder: (SeriesSortOrder) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleInProgress: () -> Unit,
    onClearFilters: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SortChip(state.filter.sortOrder, onSetSortOrder) }
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
private fun SortChip(current: SeriesSortOrder, onSelect: (SeriesSortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != SeriesSortOrder.ProviderDefault,
            onClick = { expanded = true },
            label = { Text(current.label()) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SeriesSortOrder.entries.forEach { option ->
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
private fun LanguageChip(state: SeriesUiState, onSelect: (String?) -> Unit) {
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
private fun CategoryChips(state: SeriesUiState, onSelectCategory: (String?) -> Unit) {
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
private fun SeriesContent(
    modifier: Modifier,
    state: SeriesUiState,
    series: LazyPagingItems<SeriesSummary>,
    onRefresh: () -> Unit,
    onClearFilters: () -> Unit,
    onOpenSeries: (seriesId: String) -> Unit,
) {
    when {
        series.loadState.refresh is LoadState.Loading && series.itemCount == 0 ->
            PosterSkeletons(modifier)

        series.itemCount == 0 && state.hasActiveFilter && !state.isRefreshing ->
            EmptySeriesState(
                modifier = modifier,
                title = "Nothing matches these filters",
                body = "No cached series matches the current search and filter combination.",
                action = "Clear filters",
                onAction = onClearFilters,
            )

        series.itemCount == 0 && !state.isRefreshing ->
            EmptySeriesState(
                modifier = modifier,
                title = "No series cached yet",
                body = "Refresh to download your provider's series library.",
                action = "Refresh library",
                onAction = onRefresh,
            )

        else -> PosterGrid(modifier) {
            items(
                count = series.itemCount,
                key = { index -> series[index]?.id ?: "placeholder-$index" },
            ) { index ->
                series[index]?.let { entry ->
                    PosterCard(
                        title = entry.name,
                        posterUrl = entry.posterUrl,
                        meta = listOfNotNull(
                            entry.releaseYear?.toString(),
                            entry.rating?.let { "★ %.1f".format(it) },
                        ).joinToString(" · "),
                        onClick = { onOpenSeries(entry.id) },
                    )
                }
            }
            if (series.loadState.append is LoadState.Loading) {
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
private fun EmptySeriesState(
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
                Icon(Icons.Outlined.VideoLibrary, null, Modifier.padding(20.dp).size(36.dp))
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

private fun SeriesSortOrder.label(): String = when (this) {
    SeriesSortOrder.ProviderDefault -> "Provider order"
    SeriesSortOrder.NameAscending -> "Name A–Z"
    SeriesSortOrder.RatingDescending -> "Top rated"
    SeriesSortOrder.ReleaseYearDescending -> "Newest year"
    SeriesSortOrder.RecentlyUpdated -> "Recently updated"
}
