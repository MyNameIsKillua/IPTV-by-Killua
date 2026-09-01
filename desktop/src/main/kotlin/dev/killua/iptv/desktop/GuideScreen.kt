package dev.killua.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.killua.iptv.domain.epg.EpgSelection
import dev.killua.iptv.domain.model.EpgEntry
import kotlinx.coroutines.delay

/** One line of the guide: enough to label a row and to play it. */
data class GuideChannel(val id: String, val name: String, val logoUrl: String?)

/**
 * What is on, across the channels this viewer keeps.
 *
 * Xtream answers the programme **one channel at a time** — `get_short_epg` takes a single stream
 * id — so a guide over the whole library is not a layout problem but a request problem: this
 * provider carries six figures of channels, and a grid over all of them is six figures of requests
 * nobody would wait for. The rows are therefore the viewer's own channels, saved first and then recently
 * watched, capped at forty by `ownChannels` in `:shared`. Forty fills in a few seconds.
 *
 * That bound is also why this is its own destination rather than a mode of the Live library:
 * browsing a category is about everything the provider has, and the guide is about the handful the
 * viewer returns to.
 */
@Composable
fun GuideScreen(
    channels: List<GuideChannel>,
    programmes: Map<String, List<EpgEntry>>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onPlay: (GuideChannel) -> Unit,
    onForget: (GuideChannel) -> Unit,
) {
    // One clock for the whole page, re-read every twenty seconds. Forty rows each running their own
    // ticker would be forty recompositions a minute for a screen that changes on the quarter hour.
    var nowEpochSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            nowEpochSeconds = System.currentTimeMillis() / 1000L
        }
    }

    // Which row is open, by channel id. One at a time: this is a page of forty, and several open at
    // once turns a guide into a wall of text.
    var expanded by remember { mutableStateOf<String?>(null) }
    // Which programme in that row is being read. Null means the one on now, which is what the
    // chevron opens; clicking a block in the timeline sets it to that block.
    var focus by remember { mutableStateOf<EpgEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Guide",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(16.dp))
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = VioletBright,
                )
            } else if (channels.isNotEmpty()) {
                Text(
                    "${channels.size}",
                    color = InkMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                clockOf(nowEpochSeconds),
                color = InkMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(14.dp))
            TextPill("Refresh", onClick = onRefresh)
        }

        // The window the whole page shares, so every row lines up under one axis. It follows the
        // clock rather than being scrollable: `get_short_epg` returns eight entries, which is a
        // handful of hours, and a timeline that can be dragged past the end of the data is a
        // timeline that mostly shows nothing.
        val windowStart = nowEpochSeconds - (nowEpochSeconds % HALF_HOUR_SECONDS)
        val windowEnd = windowStart + GUIDE_WINDOW_SECONDS

        if (channels.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Play or bookmark a channel and it appears here.",
                    color = InkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        TimeAxis(windowStart, windowEnd)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                GuideRow(
                    channel = channel,
                    // Absent and empty are different answers: one has not been asked yet, the other
                    // came back with nothing, and a row should not claim the second while waiting
                    // for the first.
                    programme = programmes[channel.id],
                    nowEpochSeconds = nowEpochSeconds,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    expanded = expanded == channel.id,
                    focus = focus.takeIf { expanded == channel.id },
                    onExpand = {
                        expanded = if (expanded == channel.id) null else channel.id
                        focus = null
                    },
                    onSelectProgramme = { entry ->
                        expanded = channel.id
                        focus = entry
                    },
                    onPlay = { onPlay(channel) },
                    onForget = { onForget(channel) },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: GuideChannel,
    programme: List<EpgEntry>?,
    nowEpochSeconds: Long,
    windowStart: Long,
    windowEnd: Long,
    expanded: Boolean,
    focus: EpgEntry?,
    onExpand: () -> Unit,
    onSelectProgramme: (EpgEntry) -> Unit,
    onPlay: () -> Unit,
    onForget: () -> Unit,
) {
    val current = programme?.let { EpgSelection.nowPlaying(it, nowEpochSeconds) }
    val progress = programme?.let { EpgSelection.progress(it, nowEpochSeconds) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(12.dp))
                .focusRing(RoundedCornerShape(12.dp))
                .clickable(onClick = onPlay)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                Modifier
                    .size(width = 64.dp, height = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NightSoft),
            ) {
                RemoteImage(
                    url = channel.logoUrl,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = { ArtworkPlaceholder(channel.name) },
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                channel.name,
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(210.dp),
            )
            Spacer(Modifier.width(16.dp))

            ProgrammeStrip(
                programme = programme,
                current = current,
                focus = focus,
                progress = progress,
                windowStart = windowStart,
                windowEnd = windowEnd,
                nowEpochSeconds = nowEpochSeconds,
                onSelect = onSelectProgramme,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))

            // Its own click target inside the row's, so opening the listing is not the same
            // gesture as starting the channel. The row plays; this reads.
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide the listing" else "Show the listing",
                tint = if (expanded) VioletBright else InkMuted,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .focusRing(CircleShape)
                    .clickable(onClick = onExpand)
                    .padding(4.dp),
            )

            // This list is built from what was watched, and two seconds on the wrong channel is
            // enough to join it. Something built from occurrences needs a way to say "not that one".
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove from the guide",
                tint = InkMuted,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .focusRing(CircleShape)
                    .clickable(onClick = onForget)
                    .padding(5.dp),
            )
        }

        if (expanded) {
            ProgrammeDetail(
                programme = programme.orEmpty(),
                shown = focus ?: current,
                nowEpochSeconds = nowEpochSeconds,
                onSelect = onSelectProgramme,
                onPlay = onPlay,
            )
        }
    }
}

/**
 * One channel's few hours, laid out in proportion to time.
 *
 * This is what turns a list of what is on into a guide: a programme's width is its length, so an
 * hour looks like an hour and a viewer can see at a glance that one channel is in the middle of a
 * film while another has three things in the same span.
 *
 * Everything is clipped to the shared window rather than dropped, so a programme that began before
 * the window starts at its left edge and reads as already running — which is exactly what it is.
 */
@Composable
private fun ProgrammeStrip(
    programme: List<EpgEntry>?,
    current: EpgEntry?,
    focus: EpgEntry?,
    progress: Float?,
    windowStart: Long,
    windowEnd: Long,
    nowEpochSeconds: Long,
    onSelect: (EpgEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = (windowEnd - windowStart).toFloat()

    BoxWithConstraints(modifier.height(44.dp)) {
        val full = maxWidth

        if (programme == null || programme.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(NightSoft.copy(alpha = 0.5f)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    if (programme == null) "…" else "No guide for this channel",
                    color = InkMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            return@BoxWithConstraints
        }

        programme.forEach { entry ->
            val from = ((entry.startEpochSeconds - windowStart) / span).coerceIn(0f, 1f)
            val to = ((entry.endEpochSeconds - windowStart) / span).coerceIn(0f, 1f)
            if (to <= from) return@forEach
            val onNow = entry === current

            Box(
                Modifier
                    .offset(x = full * from)
                    .width(full * (to - from))
                    .fillMaxHeight()
                    .padding(end = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (onNow) Violet.copy(alpha = 0.30f) else NightSoft)
                    // Cyan for the keyboard, violet below for the programme being read: the two
                    // land on the same block often enough that sharing a colour would make each of
                    // them unreadable.
                    .focusRing(RoundedCornerShape(8.dp))
                    // Its own click target inside the row's: a block is a programme to read about,
                    // while the row around it is a channel to watch. An inner clickable takes the
                    // press, so the two never both fire.
                    .clickable { onSelect(entry) }
                    .then(
                        if (entry === focus) {
                            Modifier.border(1.dp, VioletBright, RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (onNow && progress != null && progress > 0f) {
                    // The part of the current programme already gone, drawn as the block filling up
                    // rather than as a separate bar: one thing on the screen instead of two.
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(Violet.copy(alpha = 0.28f)),
                    )
                }
                Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(
                        entry.title,
                        color = if (onNow) Ink else Ink.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        clockOf(entry.startEpochSeconds),
                        color = InkMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }

        // The clock, straight down the page. Without it the blocks are only relatively placed and a
        // viewer has to work out where "now" is from the one that happens to be tinted.
        val nowFraction = ((nowEpochSeconds - windowStart) / span).coerceIn(0f, 1f)
        Box(
            Modifier
                .offset(x = full * nowFraction)
                .width(2.dp)
                .fillMaxHeight()
                .background(VioletBright),
        )
    }
}

/** Half-hour marks across the same window every row uses. */
@Composable
private fun TimeAxis(windowStart: Long, windowEnd: Long) {
    val marks = ((windowEnd - windowStart) / HALF_HOUR_SECONDS).toInt()

    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, bottom = 4.dp)) {
        // The same fixed run-up the rows have: logo, gap, name, gap.
        Spacer(Modifier.width(64.dp + 14.dp + 210.dp + 16.dp + 12.dp))
        repeat(marks) { index ->
            Text(
                clockOf(windowStart + index * HALF_HOUR_SECONDS),
                color = InkMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val HALF_HOUR_SECONDS = 30L * 60L
private const val GUIDE_WINDOW_SECONDS = 3L * 60L * 60L

/**
 * What this channel is showing, in words.
 *
 * `get_short_epg` is asked for eight entries and the row above uses two of them, so the rest is
 * already in hand — this costs no request. The description belongs to whatever is on now;
 * providers fill it in for perhaps half of what they carry, and a heading with nothing under it
 * says less than no heading at all, so it is left out when it is missing.
 */
@Composable
private fun ProgrammeDetail(
    programme: List<EpgEntry>,
    shown: EpgEntry?,
    nowEpochSeconds: Long,
    onSelect: (EpgEntry) -> Unit,
    onPlay: () -> Unit,
) {
    val later = programme.filter { it.startEpochSeconds > nowEpochSeconds }
    val description = shown?.description?.takeIf { it.isNotBlank() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 90.dp, end = 24.dp, bottom = 14.dp),
    ) {
        shown?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it.title + "   " + clockOf(it.startEpochSeconds) + " - " +
                        clockOf(it.endEpochSeconds),
                    color = Ink,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(14.dp))
                // Clicking a block reads rather than plays, so watching has to be one labelled click
                // away from wherever that leaves the viewer. Nothing here can start a programme that
                // has not begun; the channel is what there is to watch.
                TextPill("Watch this channel", onClick = onPlay)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (description != null) {
            Text(
                description,
                color = InkMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.widthIn(max = 760.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        if (later.isEmpty() && description == null) {
            Text(
                "Nothing further listed for this channel.",
                color = InkMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        later.forEach { entry ->
            val reading = entry === shown
            Row(
                Modifier
                    .padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .focusRing(RoundedCornerShape(6.dp), width = 1.dp)
                    .clickable { onSelect(entry) }
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    clockOf(entry.startEpochSeconds),
                    color = InkMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(56.dp),
                )
                Text(
                    entry.title,
                    color = if (reading) VioletBright else Ink.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 700.dp),
                )
            }
        }
    }
}
