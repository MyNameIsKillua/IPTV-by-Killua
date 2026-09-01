package dev.killua.iptv.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Artwork loading for the desktop client.
 *
 * There is no Coil here, so this is the smallest thing that does the job honestly: fetch, decode,
 * remember, and never let a broken image become a broken screen.
 *
 * **Bounded on purpose.** A provider category can hold hundreds of posters and the library holds
 * six figures of them; an unbounded map would be a slow memory leak that only shows up after an
 * hour of browsing. The cache keeps the most recently used few hundred and drops the rest, which is
 * far more than any grid shows at once.
 *
 * **Failures are silent by design.** A missing or malformed poster is extremely common on this kind
 * of provider. It draws the placeholder and nothing else happens — no retry storm, no error state
 * for something nobody asked for.
 *
 * **Bounded in flight, too**, which became necessary the day a grid stopped being one category of a
 * few hundred and became the whole library. See [IN_FLIGHT] and the settle delay in [RemoteImage]:
 * between them, flicking through ten thousand posters costs the provider nothing.
 */
object ArtworkLoader {
    private const val MAX_ENTRIES = 300

    /**
     * How many posters are fetched at once.
     *
     * Eight, and the number matters less than the fact that there is one. A lazy grid composes only
     * what is on screen, so this was never about how many tiles exist — it is about what happens
     * when someone *scrolls*: every tile that passes starts a fetch, and a synchronous HTTP call
     * does not stop because the coroutine around it was cancelled. Without a bound, a flick through
     * a six-figure library leaves the provider serving hundreds of pictures nobody will ever see.
     *
     * Acquiring is a suspension point, which is the other half of the fix: a tile that has already
     * scrolled away is cancelled *while queueing* and never asks at all.
     */
    private val inFlight = Semaphore(IN_FLIGHT)

    /** Access-ordered, so reading a poster keeps it alive and the oldest untouched one is evicted. */
    private val cache: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > MAX_ENTRIES
        },
    )

    /** Remembered separately, so a URL that has already failed is not fetched again on every scroll. */
    private val failed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    /** The disk half. Bounded, disposable, and pruned once at startup rather than on every write. */
    private val store = ArtworkStore(File(DesktopUserData.defaultDirectory(), "artwork"))

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun load(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        if (url in failed) return null
        return withContext(Dispatchers.IO) {
            // Disk first, and *outside* the bound: reading a file that is already there is not what
            // the bound exists for, and queueing behind eight downloads to read one would make a
            // cached grid scroll worse than an uncached one.
            store.read(url)?.let(::decode)?.let { bitmap ->
                cache[url] = bitmap
                return@withContext bitmap
            }
            inFlight.withPermit {
                // Asked again after the wait: the tile in front of this one may have fetched the
                // same poster while this coroutine was queueing.
                cache[url]?.let { return@withContext it }
                // A file that no longer decodes is treated as a miss rather than as a failure: it
                // is fetched again and overwritten, because the alternative is one truncated
                // download poisoning a poster until someone deletes the directory by hand.
                val bitmap = fetch(url)?.let { bytes -> decode(bytes)?.also { store.write(url, bytes) } }
                if (bitmap == null) failed += url else cache[url] = bitmap
                bitmap
            }
        }
    }

    /** Called once at startup: pruning on every write would stat the whole directory per poster. */
    suspend fun prune() = withContext(Dispatchers.IO) { store.prune() }

    suspend fun cachedBytes(): Long = withContext(Dispatchers.IO) { store.size() }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        store.clear()
        // The memory copy has to go too, or a cleared cache still shows every poster on screen and
        // the settings screen looks like it lied.
        cache.clear()
        failed.clear()
    }

    /**
     * Drops what is held in memory without touching the disk.
     *
     * For signing out: the posters of one account's library have no business being on screen for the
     * next. The files stay, because they are keyed by URL and a URL belongs to whoever asks for it.
     */
    fun forget() {
        cache.clear()
        failed.clear()
    }

    /**
     * One picture, abandoned if whoever asked for it has gone.
     *
     * `execute()` blocks and does not care that the coroutine around it was cancelled, so a poster
     * scrolled past used to be downloaded in full regardless. Cancelling the call when the job ends
     * is what actually stops it — the read then fails, which is caught here and read as "no
     * picture", which is exactly what it is.
     */
    private suspend fun fetch(url: String): ByteArray? {
        val call = http.newCall(Request.Builder().url(url).build())
        val stopIfAbandoned = coroutineContext.job.invokeOnCompletion { call.cancel() }
        return try {
            runCatching {
                call.execute().use { response ->
                    if (response.isSuccessful) response.body.bytes() else null
                }
            }.getOrNull()
        } finally {
            stopIfAbandoned.dispose()
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

/**
 * How many posters this client fetches at once.
 *
 * Internal rather than private so the test can hold the client to its own number instead of to a
 * copy of it.
 */
internal const val IN_FLIGHT = 8

/**
 * How long a tile has to stay on screen before its picture is asked for.
 *
 * The same rule the guide uses for its programme requests, and for the same reason: scrolling past
 * cancels the effect before this elapses, so a flick through a library costs nothing at all. A fifth
 * of a second is under what anyone notices when they stop.
 */
private const val SETTLE_MS = 180L

/**
 * Draws artwork once it arrives, fading it in over whatever placeholder sits behind it.
 *
 * The fade is not decoration: posters arrive at whatever speed the provider manages, and a grid
 * where a dozen images pop in at random instants reads as broken. A short fade makes the same
 * sequence read as loading.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {},
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        val wanted = url?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        // Nothing at all for a tile that is only passing through. Cached posters wait too, which
        // costs a fifth of a second on a grid that is standing still and saves a provider from
        // being asked for ten thousand pictures during one flick.
        delay(SETTLE_MS)
        bitmap = ArtworkLoader.load(wanted)
    }

    val fade by animateFloatAsState(if (bitmap != null) 1f else 0f)

    Box(modifier) {
        if (bitmap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { placeholder() }
        }
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().alpha(fade),
            )
        }
    }
}

/** A poster-shaped placeholder: the initial on a flat tile, which is quieter than a spinner. */
@Composable
fun ArtworkPlaceholder(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(NightSoft),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = label.trim().take(1).uppercase(),
            color = InkMuted,
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
        )
    }
}
