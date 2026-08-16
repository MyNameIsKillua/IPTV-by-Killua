package dev.killua.iptv.feature.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.WatchlistEntry
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.ui.components.ContinueWatchingRow
import dev.killua.iptv.ui.components.RecentlyAddedRow
import dev.killua.iptv.ui.components.SavedListRow

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    /**
     * Passed in rather than read off the ViewModel, which holds the account it was built with.
     * The ViewModel is keyed by account id and so survives a rename, and would keep greeting the
     * viewer by the old name.
     */
    account: Account,
    validationWarning: AppFailure?,
    onBrowseLive: () -> Unit,
    onPlay: (LiveChannel) -> Unit,
    onOpenEntry: (ContinueWatchingEntry) -> Unit,
    onOpenSaved: (WatchlistEntry) -> Unit,
) {
    val recent by viewModel.recentChannels.collectAsStateWithLifecycle()
    val newlyAdded by viewModel.recentlyAdded.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val saved by viewModel.watchlist.collectAsStateWithLifecycle()
    HomeScreen(
        account = account,
        validationWarning = validationWarning,
        recentChannels = recent,
        continueWatching = continueWatching,
        saved = saved,
        recentlyAdded = newlyAdded,
        onBrowseLive = onBrowseLive,
        onPlay = onPlay,
        onOpenEntry = onOpenEntry,
        onOpenSaved = onOpenSaved,
    )
}

@Composable
fun HomeScreen(
    account: Account,
    validationWarning: AppFailure?,
    recentChannels: List<LiveChannel>,
    continueWatching: List<ContinueWatchingEntry>,
    saved: List<WatchlistEntry>,
    recentlyAdded: List<RecentlyAddedEntry>,
    onBrowseLive: () -> Unit,
    onPlay: (LiveChannel) -> Unit,
    onOpenEntry: (ContinueWatchingEntry) -> Unit,
    onOpenSaved: (WatchlistEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                Text("Welcome back", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(account.label, style = MaterialTheme.typography.headlineMedium)
            }
        }
        validationWarning?.let { warning ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "  ${warning.userMessage()} Showing your cached library.",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(20.dp).clickable(onClick = onBrowseLive),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Outlined.LiveTv, null, Modifier.size(40.dp))
                    Spacer(Modifier.height(32.dp))
                    Text("Live television", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Browse provider categories and start a channel in one tap.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onBrowseLive) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text("  Browse Live TV")
                    }
                }
            }
        }
        item {
            // Above Continue Watching: this is the list the viewer put together deliberately,
            // which is a stronger invitation than something they merely left unfinished. It hides
            // itself while empty, so a viewer who never saves anything never sees it.
            SavedListRow(
                entries = saved,
                onOpen = onOpenSaved,
                contentPadding = 20.dp,
            )
        }
        item {
            // Shown above the channel history: a stored position is a more specific invitation
            // than "you watched this once". The row hides itself when nothing is in progress.
            ContinueWatchingRow(
                entries = continueWatching,
                onOpen = onOpenEntry,
                showKind = true,
                contentPadding = 20.dp,
            )
        }
        item {
            // Below what the viewer chose and what they left unfinished: new arrivals are the
            // provider's suggestion, not theirs.
            RecentlyAddedRow(
                entries = recentlyAdded,
                onOpen = { entry ->
                    onOpenEntry(
                        ContinueWatchingEntry(
                            contentId = entry.contentId,
                            kind = entry.kind,
                            title = entry.title,
                            posterUrl = entry.posterUrl,
                            lastWatchedAtEpochMillis = 0L,
                        ),
                    )
                },
                contentPadding = 20.dp,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recently watched", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (recentChannels.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Tv, null)
                        Text(
                            "  Channels you watch will appear here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(recentChannels, key = LiveChannel::id) { channel ->
                        RecentChannelCard(channel, onPlay)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text("Coming next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "Live TV, Movies, and Series all browse, search, and play. The bookmark on " +
                    "any of them builds My list, and bookmarked channels become the rows of " +
                    "your guide, reached from the grid button in Live TV.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentChannelCard(channel: LiveChannel, onPlay: (LiveChannel) -> Unit) {
    Card(
        modifier = Modifier.size(width = 178.dp, height = 132.dp).clickable { onPlay(channel) },
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(82.dp), contentAlignment = Alignment.Center) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = "${channel.name} logo",
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(Icons.Outlined.Tv, null, Modifier.size(32.dp))
            }
        }
        Text(
            text = channel.name,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
    }
}
