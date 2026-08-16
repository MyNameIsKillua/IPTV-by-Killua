package dev.killua.iptv.feature.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun GuideRoute(
    viewModel: GuideViewModel,
    onBack: () -> Unit,
    onPlayChannel: (channelId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GuideScreen(state = state, onBack = onBack, onPlayChannel = onPlayChannel)
}

/**
 * The guide grid.
 *
 * Every row scrolls one shared [rememberScrollState], which is what keeps the columns lined up: a
 * per-row scroll state would let the rows drift apart and make the time axis a lie. The channel
 * column stays outside that scroll so names remain readable at any horizontal position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    state: GuideUiState,
    onBack: () -> Unit,
    onPlayChannel: (channelId: String) -> Unit,
) {
    val timeScroll = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guide") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isEmpty) {
                GuideEmptyState()
                return@Column
            }

            GuideRuler(state = state, timeScroll = timeScroll)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(state.channels, key = GuideChannel::id) { channel ->
                    GuideRow(
                        channel = channel,
                        slots = guideSlots(
                            entries = state.programmes[channel.id].orEmpty(),
                            windowStartEpochSeconds = state.windowStartEpochSeconds,
                            windowEndEpochSeconds = state.windowEndEpochSeconds,
                        ),
                        windowStartEpochSeconds = state.windowStartEpochSeconds,
                        windowEndEpochSeconds = state.windowEndEpochSeconds,
                        nowEpochSeconds = state.nowEpochSeconds,
                        timeScroll = timeScroll,
                        onPlay = { onPlayChannel(channel.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRuler(
    state: GuideUiState,
    timeScroll: androidx.compose.foundation.ScrollState,
) {
    Row(modifier = Modifier.fillMaxWidth().height(28.dp)) {
        Spacer(Modifier.width(CHANNEL_COLUMN_WIDTH))
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(timeScroll),
        ) {
            Box(Modifier.width(TIMELINE_WIDTH).fillMaxSize()) {
                guideHourMarks(
                    state.windowStartEpochSeconds,
                    state.windowEndEpochSeconds,
                ).forEach { mark ->
                    val fraction = guideFractionOf(
                        mark,
                        state.windowStartEpochSeconds,
                        state.windowEndEpochSeconds,
                    ) ?: return@forEach
                    Text(
                        text = formatClock(mark),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .offset(x = TIMELINE_WIDTH * fraction)
                            .padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: GuideChannel,
    slots: List<GuideSlot>,
    windowStartEpochSeconds: Long,
    windowEndEpochSeconds: Long,
    nowEpochSeconds: Long,
    timeScroll: androidx.compose.foundation.ScrollState,
    onPlay: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .width(CHANNEL_COLUMN_WIDTH)
                .fillMaxSize()
                .clickable(onClick = onPlay)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(Icons.Outlined.Tv, contentDescription = null, Modifier.size(16.dp))
                }
            }
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize().horizontalScroll(timeScroll)) {
            Box(Modifier.width(TIMELINE_WIDTH).fillMaxSize()) {
                if (slots.isEmpty()) {
                    // Not an error: either the request has not landed yet or the provider has no
                    // guide for this channel. Both look the same to the viewer, and both are fine.
                    Text(
                        text = "No guide",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                    )
                }
                guideFractionOf(
                    nowEpochSeconds,
                    windowStartEpochSeconds,
                    windowEndEpochSeconds,
                )?.let { fraction ->
                    Box(
                        Modifier
                            .offset(x = TIMELINE_WIDTH * fraction)
                            .width(2.dp)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                slots.forEach { slot ->
                    GuideProgramme(
                        slot = slot,
                        modifier = Modifier
                            .offset(x = TIMELINE_WIDTH * slot.startFraction)
                            .width(TIMELINE_WIDTH * slot.widthFraction),
                        onClick = onPlay,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideProgramme(
    slot: GuideSlot,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize().padding(end = 2.dp),
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = slot.entry.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatClock(slot.entry.startEpochSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GuideEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Bookmark,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "The guide follows your channels",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = "Bookmark a channel in Live TV, or watch one, and it appears here. " +
                "Your provider sends the programme one channel at a time, so a grid over " +
                "every channel would be one request each.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Device-local wall clock, which is what a viewer compares against their own. */
private fun formatClock(epochSeconds: Long): String = DateTimeFormatter
    .ofLocalizedTime(FormatStyle.SHORT)
    .withLocale(Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochSecond(epochSeconds))

/** Wide enough for a channel name at two lines, narrow enough to leave the axis room on a phone. */
private val CHANNEL_COLUMN_WIDTH: Dp = 130.dp

/** Four hours at this width is legible on a phone and scrolls in about three screenfuls. */
private val TIMELINE_WIDTH: Dp = 900.dp

private val ROW_HEIGHT: Dp = 56.dp
