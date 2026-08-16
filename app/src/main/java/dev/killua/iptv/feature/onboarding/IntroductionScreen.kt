package dev.killua.iptv.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class IntroductionPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

/**
 * The one-time tour.
 *
 * It covers the things the app cannot explain on its own — gestures leave no trace on screen, and a
 * bookmark that quietly builds two other screens is not guessable. It deliberately does **not**
 * explain what is already visible, like where the tabs are, beyond naming them once.
 *
 * Skippable from the first page. Anything a viewer must sit through to reach their television is a
 * toll, not an introduction.
 */
private val pages = listOf(
    IntroductionPage(
        icon = Icons.Outlined.VideoLibrary,
        title = "Three libraries, one search",
        body = "Live TV, Movies and Series each have their own tab along the bottom, with their " +
            "own filters and sorting. Search looks through all three at once — it searches what " +
            "is already on your device, so it works even when your provider is slow.",
    ),
    IntroductionPage(
        icon = Icons.Default.SwipeVertical,
        title = "The player is mostly gestures",
        body = "Drag up and down on the slider at the left edge for brightness, and the one at " +
            "the right edge for volume. Double-tap the left or right of the picture to skip, the " +
            "middle to pause. Press and hold to speed playback up until you let go. The button " +
            "at the top right changes how the picture fills the screen.",
    ),
    IntroductionPage(
        icon = Icons.Default.Bookmark,
        title = "The bookmark does two jobs",
        body = "Bookmark a film, a series or a channel and it lands on My list on the home " +
            "screen — one list across all three. Bookmarked channels also become the rows of " +
            "your guide. The heart is a different thing: it marks a title inside its own library " +
            "and drives that library's Favorites filter.",
    ),
    IntroductionPage(
        icon = Icons.Default.CheckCircle,
        title = "Watched, and where you left off",
        body = "Films and episodes remember where you stopped and offer Resume. The check mark " +
            "in the title bar marks something watched by hand, which clears its stored position " +
            "and takes it out of Continue watching.",
    ),
    IntroductionPage(
        icon = Icons.Outlined.CalendarViewWeek,
        title = "What is on, at a glance",
        body = "The grid button in Live TV opens a guide over the channels you keep — the ones " +
            "you bookmarked plus the ones you have watched. Your provider sends the programme " +
            "one channel at a time, which is why the guide follows your channels rather than all " +
            "of them.",
    ),
)

@Composable
fun IntroductionOverlay(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onFinish) { Text("Skip") }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { index ->
                val page = pages[index]
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    Text(
                        text = page.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(pages.size) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (index == pagerState.currentPage) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                    )
                }
            }

            val isLast = pagerState.currentPage == pages.lastIndex
            Button(
                onClick = {
                    if (isLast) {
                        onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isLast) "Start watching" else "Next")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
