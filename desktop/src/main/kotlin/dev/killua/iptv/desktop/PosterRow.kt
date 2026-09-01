package dev.killua.iptv.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A heading and a sideways strip of posters.
 *
 * Shared by Start, My list and the search results, because all three answer the same question in the
 * same shape: several short lists that have to stay visible together rather than one long one that
 * pushes the others off the screen.
 *
 * [action] is the way into the whole of whatever the row is a sample of, and it is deliberately part
 * of the heading rather than a tile at the end of the strip: a viewer scrolling a row is looking at
 * pictures, and a button hiding at the end of twenty of them is a button nobody finds.
 */
@Composable
internal fun PosterRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    action: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.padding(bottom = 18.dp)) {
        Row(
            Modifier.padding(start = 28.dp, end = 28.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = InkMuted,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.let {
                Spacer(Modifier.width(10.dp))
                Text(it, color = InkMuted, style = MaterialTheme.typography.labelMedium)
            }
            if (action != null && actionLabel != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    actionLabel,
                    color = VioletBright,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .focusRing(RoundedCornerShape(8.dp))
                        .clickable(onClick = action)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** The width one poster gets in a row, so the strips line up across three screens. */
internal val PosterRowWidth = 172.dp
