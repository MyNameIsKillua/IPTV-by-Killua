package dev.killua.iptv.feature.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import dev.killua.iptv.domain.model.CategorySelection
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.ui.components.releasesFocusVertically
import dev.killua.iptv.ui.components.focusRing
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveSortOrder

@Composable
fun LiveRoute(
    viewModel: LiveViewModel,
    onPlay: (LiveChannel) -> Unit,
    onToggleSaved: (String) -> Unit,
    onOpenGuide: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()
    LiveScreen(
        state = state,
        channels = channels,
        onSearchInput = viewModel::onSearchInput,
        onClearSearch = viewModel::clearSearch,
        onSelectCategory = viewModel::select,
        onSelectLanguage = viewModel::selectLanguage,
        onSetSortOrder = viewModel::setSortOrder,
        onClearFilters = viewModel::clearFilters,
        onRefresh = viewModel::refresh,
        onPlay = onPlay,
        onToggleSaved = onToggleSaved,
        onOpenGuide = onOpenGuide,
        onRequestEpg = viewModel::requestEpg,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    state: LiveUiState,
    channels: LazyPagingItems<LiveChannel>,
    onSearchInput: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectCategory: (CategorySelection) -> Unit,
    onSelectLanguage: (String?) -> Unit,
    onSetSortOrder: (LiveSortOrder) -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (LiveChannel) -> Unit,
    onToggleSaved: (String) -> Unit,
    onOpenGuide: () -> Unit,
    onRequestEpg: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Live TV", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (state.isRefreshing) "Updating library…" else "${channels.itemCount} channels loaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // Beside Refresh rather than in the filter row: the guide is a different way
                    // of looking at these channels, not another filter on them.
                    IconButton(onClick = onOpenGuide) {
                        Icon(Icons.Outlined.CalendarViewWeek, contentDescription = "Open the guide")
                    }
                    IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh live library")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            SearchField(state.searchInput, onSearchInput, onClearSearch)
            FilterBar(state, onSelectLanguage, onSetSortOrder, onClearFilters)
            CategoryChips(state, onSelectCategory)
            state.errorMessage?.let { error ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(error, modifier = Modifier.padding(14.dp))
                }
            }
            ChannelContent(
                modifier = Modifier.weight(1f),
                state = state,
                channels = channels,
                onRefresh = onRefresh,
                onClearFilters = onClearFilters,
                onPlay = onPlay,
                onToggleSaved = onToggleSaved,
                onRequestEpg = onRequestEpg,
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
            // A one-line box is somewhere to type, not somewhere to be stuck: see
            // `releasesFocusVertically`, which is what lets a remote reach the list below.
            .releasesFocusVertically(),
        singleLine = true,
        placeholder = { Text("Search channels") },
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
    state: LiveUiState,
    onSelectLanguage: (String?) -> Unit,
    onSetSortOrder: (LiveSortOrder) -> Unit,
    onClearFilters: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SortChip(state.filter.sortOrder, onSetSortOrder) }
        // Hidden when the provider's naming reveals no language at all, so the chip never opens
        // onto an empty menu.
        if (state.languages.isNotEmpty()) {
            item { LanguageChip(state, onSelectLanguage) }
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
private fun LanguageChip(state: LiveUiState, onSelect: (String?) -> Unit) {
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
private fun SortChip(current: LiveSortOrder, onSelect: (LiveSortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != LiveSortOrder.ProviderDefault,
            onClick = { expanded = true },
            label = { Text(current.label()) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LiveSortOrder.entries.forEach { option ->
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
private fun CategoryChips(
    state: LiveUiState,
    onSelectCategory: (CategorySelection) -> Unit,
) {
    val options = buildList<Pair<CategorySelection, String>> {
        add(CategorySelection.All to "All")
        add(CategorySelection.Recent to "Recent")
        state.categories.forEach { add(CategorySelection.Provider(it.id) to it.name) }
        add(CategorySelection.Uncategorized to "Other")
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { (selection, _) -> selection.toString() }) { (selection, label) ->
            FilterChip(
                selected = state.selection == selection,
                onClick = { onSelectCategory(selection) },
                label = { Text(label, maxLines = 1) },
                leadingIcon = when (selection) {
                    CategorySelection.All -> ({ Icon(Icons.Outlined.LiveTv, null, Modifier.size(18.dp)) })
                    CategorySelection.Recent -> ({ Icon(Icons.Outlined.History, null, Modifier.size(18.dp)) })
                    else -> null
                },
            )
        }
    }
}

@Composable
private fun ChannelContent(
    modifier: Modifier,
    state: LiveUiState,
    channels: LazyPagingItems<LiveChannel>,
    onRefresh: () -> Unit,
    onClearFilters: () -> Unit,
    onPlay: (LiveChannel) -> Unit,
    onToggleSaved: (String) -> Unit,
    onRequestEpg: (String) -> Unit,
) {
    // A category selection alone keeps its own wording below; a search or language filter is what
    // makes "nothing here" mean "nothing matches".
    val narrowed = !state.filter.searchQuery.isNullOrBlank() || state.filter.languageTag != null
    when {
        channels.loadState.refresh is LoadState.Loading && channels.itemCount == 0 ->
            ChannelSkeletons(modifier)
        channels.loadState.refresh is LoadState.Error && channels.itemCount == 0 ->
            EmptyLiveState(
                modifier = modifier,
                title = "Channels could not be loaded",
                body = "Your cached library is unavailable. Check the server and try again.",
                action = "Retry",
                onAction = { channels.retry() },
            )
        // A narrowed result offers the action that widens it again; only a genuinely empty
        // library asks for a refresh that downloads the whole listing.
        channels.itemCount == 0 && narrowed && !state.isRefreshing ->
            EmptyLiveState(
                modifier = modifier,
                title = "No channels match",
                body = "No cached channel matches the current search and filter combination.",
                action = "Clear filters",
                onAction = onClearFilters,
            )
        channels.itemCount == 0 && state.selection == CategorySelection.Recent &&
            !state.isRefreshing ->
            EmptyLiveState(
                modifier = modifier,
                title = "No recent channels",
                body = "Channels you watch successfully will appear here.",
                action = "Show all channels",
                onAction = onClearFilters,
            )
        channels.itemCount == 0 && !state.isRefreshing ->
            EmptyLiveState(
                modifier = modifier,
                title = "No channels here",
                body = "Refresh the live library or choose another category.",
                action = "Refresh library",
                onAction = onRefresh,
            )
        else -> androidx.compose.foundation.lazy.LazyColumn(modifier = modifier.fillMaxSize()) {
            items(
                count = channels.itemCount,
                key = { index -> channels[index]?.id ?: "placeholder-$index" },
            ) { index ->
                channels[index]?.let { channel ->
                    ChannelRow(
                        channel = channel,
                        nowPlaying = state.nowPlaying[channel.id],
                        isSaved = channel.id in state.savedChannelIds,
                        onPlay = { onPlay(channel) },
                        onToggleSaved = { onToggleSaved(channel.id) },
                        onRequestEpg = onRequestEpg,
                    )
                }
            }
            if (channels.loadState.append is LoadState.Loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: LiveChannel,
    nowPlaying: EpgEntry?,
    isSaved: Boolean,
    onPlay: () -> Unit,
    onToggleSaved: () -> Unit,
    onRequestEpg: (String) -> Unit,
) {
    // The guide is asked for only once a row has stayed on screen. Scrolling past cancels this
    // before the delay elapses, which is what keeps a flick through a six-figure list silent.
    LaunchedEffect(channel.id) {
        delay(EPG_SETTLE_MS)
        onRequestEpg(channel.id)
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            // Inside the padding rather than outside it, so the ring wraps the row instead of the
            // gap between rows. See `focusRing`: on a phone this draws nothing at all.
            .focusRing(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onPlay, onLongClick = onPlay),
        headlineContent = {
            Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Text(
                text = nowPlaying?.title ?: channel.containerExtension?.uppercase() ?: "LIVE",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = "${channel.name} logo",
                        modifier = Modifier.fillMaxSize().padding(5.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(Icons.Outlined.Tv, contentDescription = null)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Left of Play, because the row's whole surface already plays: putting the saved
                // mark last would sit it where a mis-tap costs a channel change.
                IconButton(onClick = onToggleSaved) {
                    Icon(
                        imageVector = if (isSaved) {
                            Icons.Default.Bookmark
                        } else {
                            Icons.Outlined.BookmarkBorder
                        },
                        contentDescription = if (isSaved) {
                            "Remove ${channel.name} from My list"
                        } else {
                            "Add ${channel.name} to My list"
                        },
                    )
                }
                Surface(
                    onClick = onPlay,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play ${channel.name}", Modifier.padding(10.dp))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun ChannelSkeletons(modifier: Modifier) {
    androidx.compose.foundation.lazy.LazyColumn(modifier = modifier.fillMaxSize()) {
        items(8) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(64.dp, 48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                Column(Modifier.padding(start = 14.dp)) {
                    Box(Modifier.fillMaxWidth(0.58f).height(16.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.22f).height(12.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

private fun LiveSortOrder.label(): String = when (this) {
    LiveSortOrder.ProviderDefault -> "Provider order"
    LiveSortOrder.NameAscending -> "Name A–Z"
    LiveSortOrder.NameDescending -> "Name Z–A"
}

@Composable
private fun EmptyLiveState(
    modifier: Modifier,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Icons.Outlined.Tv, null, Modifier.padding(20.dp).size(36.dp))
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

/** How long a row must stay on screen before its programme is worth asking for. */
private const val EPG_SETTLE_MS = 400L
