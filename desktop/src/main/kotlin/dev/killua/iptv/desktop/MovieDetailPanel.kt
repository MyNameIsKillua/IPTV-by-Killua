package dev.killua.iptv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.userdata.MOVIE_CONTENT_TYPE

/**
 * One film, before watching it.
 *
 * Films are the one thing here that is *chosen* rather than switched to: a channel is zapped to and
 * an episode was already decided when the series was, but nobody starts a two-hour film off a poster
 * alone. So a poster opens this and this offers to play, rather than the poster playing.
 *
 * Everything except the plot comes from the listing, which is already in hand — so the panel is
 * complete the instant it opens and the fetched record only fills in the paragraph. If that request
 * fails, the panel still plays; metadata is what someone reads before deciding, not a precondition
 * for watching.
 */
@Composable
internal fun MovieDetailPanel(
    item: BrowseItem,
    details: MovieDetails?,
    resumeFrom: Long?,
    marks: Marks,
    onToggleFavourite: () -> Unit,
    onToggleSaved: () -> Unit,
    watched: Float?,
    onToggleWatched: (() -> Unit)?,
    onPlay: (fromStart: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val summary = (item as? BrowseItem.Movie)?.value
    val year = details?.releaseYear ?: summary?.releaseYear
    val rating = details?.rating ?: summary?.rating
    val meta = listOfNotNull(
        year?.toString(),
        rating?.takeIf { it > 0.0 }?.let { "%.1f".format(it) },
        details?.durationSeconds?.takeIf { it > 0 }?.let { runtimeOf(it) },
        details?.genre?.takeIf { it.isNotBlank() },
    )

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconPill(Icons.AutoMirrored.Filled.ArrowBack, "Back to browsing", onClick = onClose)
            Spacer(Modifier.width(12.dp))
            Text(
                item.label,
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(240.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NightSoft),
            ) {
                RemoteImage(
                    url = details?.posterUrl ?: item.artworkUrl,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = { ArtworkPlaceholder(item.label) },
                )
            }
            Spacer(Modifier.width(28.dp))

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
                    Button(
                        onClick = { onPlay(false) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VioletBright,
                            contentColor = Night,
                        ),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            resumeFrom?.let { "Resume from ${formatDuration(it)}" } ?: "Play",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (resumeFrom != null) {
                        Spacer(Modifier.width(10.dp))
                        TextPill("Start over", onClick = { onPlay(true) })
                    }
                    Spacer(Modifier.width(16.dp))
                    MarkButton(
                        marked = marks.favourite,
                        markedIcon = Icons.Default.Favorite,
                        unmarkedIcon = Icons.Default.FavoriteBorder,
                        description = "Favourite",
                        onClick = onToggleFavourite,
                    )
                    MarkButton(
                        marked = marks.saved,
                        markedIcon = Icons.Default.Bookmark,
                        unmarkedIcon = Icons.Default.BookmarkBorder,
                        description = "Save to my list",
                        onClick = onToggleSaved,
                    )
                    onToggleWatched?.let { toggle ->
                        val seen = watched != null && watched >= WATCHED_ENOUGH
                        MarkButton(
                            marked = seen,
                            markedIcon = Icons.Default.CheckCircle,
                            unmarkedIcon = Icons.Default.RadioButtonUnchecked,
                            description = if (seen) "Mark as not watched" else "Mark as watched",
                            onClick = toggle,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    details?.plot?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            color = Ink.copy(alpha = 0.86f),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.widthIn(max = 760.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    details?.cast?.takeIf { it.isNotBlank() }?.let { DetailLine("Cast", it) }
                    details?.director?.takeIf { it.isNotBlank() }?.let { DetailLine("Director", it) }
                }
            }
        }
    }
}

@Composable
internal fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp).widthIn(max = 760.dp)) {
        Text(
            label,
            color = InkMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(84.dp),
        )
        Text(value, color = InkMuted, style = MaterialTheme.typography.labelMedium)
    }
}

/** `1 h 52 m`, or `48 m` for anything under the hour. */
private fun runtimeOf(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "$hours h $minutes m" else "$minutes m"
}

/** Films open a panel; everything else is played or drilled into. */
internal fun BrowseItem.isMovie(): Boolean = when (this) {
    is BrowseItem.Movie -> true
    is BrowseItem.Indexed -> contentType == MOVIE_CONTENT_TYPE
    else -> false
}
