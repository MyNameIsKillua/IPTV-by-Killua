package dev.killua.iptv.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import dev.killua.iptv.domain.model.TrackLanguage
import dev.killua.iptv.domain.model.chooseTrackFor
import dev.killua.iptv.domain.model.languageDisplayName
import kotlinx.coroutines.delay
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import dev.killua.iptv.domain.model.StreamHeaders

/**
 * Video as ordinary Compose content.
 *
 * The shape of this was settled by measurement rather than preference; `docs/ROADMAP.md` records the
 * numbers. Two choices carry it:
 *
 * **VLC hands over its decoder's own I420 planes** instead of converting to BGRA itself. That
 * conversion costs about 28ms a frame at 4K on the CPU and caps the pipeline at 19fps; skipping it
 * reaches the full 50, and it drops the payload per frame from 33MB to 12.4MB.
 *
 * **Each frame becomes an immutable Skia image before Compose sees it.** Handing Compose a bitmap
 * whose pixels are then overwritten by the next frame crashes the JVM natively — Skia keeps reading
 * that memory while libvlc writes into it. The copy is what makes the race impossible rather than
 * merely unlikely.
 *
 * Because the video is Compose content, controls drawn over it are just Compose too. That is the
 * property the whole desktop client is built on.
 */
class VlcVideoPlayer {
    /** False when VLC is not installed. Reported rather than crashed on. */
    val isAvailable: Boolean = NativeDiscovery().discover()

    private val factory: MediaPlayerFactory? =
        if (isAvailable) MediaPlayerFactory("--quiet") else null
    private val player: EmbeddedMediaPlayer? = factory?.mediaPlayers()?.newEmbeddedMediaPlayer()

    val frames = FrameSink()

    /**
     * Whether something is loaded, as Compose state.
     *
     * The window handles the playback keys, and it has to know when a key is a shortcut and when it
     * is a character: the window sees every key before the focused control does, so an ungated space
     * bar is a filter field that cannot contain a space.
     */
    var hasMedia by mutableStateOf(false)
        private set

    /**
     * The level the viewer chose, 0..100 on libvlc's own scale, and whether they muted it.
     *
     * Compose state rather than something read back from libvlc with the rest of the status, because
     * two things change it — the slider and the keyboard, which is handled at the window — and both
     * have to show the same number at once. Polling it would make a key press take up to half a
     * second to appear.
     */
    var volume: Int
        get() = level
        set(value) {
            level = value.coerceIn(0, 100)
            // Raising the volume from nothing is the same intent as unmuting, and leaving it muted
            // while the slider says forty is the kind of small lie that costs a viewer a minute.
            if (level > 0) isMuted = false
            applyAudioLevel()
        }

    var isMuted by mutableStateOf(false)
        private set

    /**
     * True when libvlc gave up on the current media.
     *
     * Without this a refused stream is a black rectangle with working controls: a dead channel, a
     * provider at its connection limit and a film the account cannot reach all look identical to a
     * picture that has not arrived yet.
     *
     * Set from libvlc's own event thread, which is why it only ever *assigns* — vlcj is explicit
     * that calling back into the player from an event handler can deadlock, so this touches nothing
     * but a flag and lets the polling side decide what to do about it.
     */
    var failedToOpen by mutableStateOf(false)
        private set

    private var level by mutableStateOf(100)

    /**
     * How fast the current title plays, and only the current one.
     *
     * Deliberately **not** remembered. A rate is chosen for one film — a lecture at 1.25, a slow
     * scene at 0.75 — and carrying it into the next title, still less into live television, would be
     * a setting nobody asked for wearing the costume of a preference. Every new medium starts at
     * one, which is also what libvlc does on its own; this only keeps the two in step.
     */
    var rate: Float
        get() = speed
        set(value) {
            speed = value.coerceIn(MIN_RATE, MAX_RATE)
            player?.controls()?.setRate(speed)
        }

    private var speed by mutableStateOf(1f)

    /**
     * Whether the picture fills the window, cropping what will not fit.
     *
     * For the case letterboxing serves badly: four-by-three material in a wide window, where the
     * bars are wider than the picture. Off by default, because cutting the sides off a film nobody
     * asked to have cut is worse than a black border, and reset with each new medium for the same
     * reason the rate is — it is a decision about *this* picture's shape.
     */
    var fillsWindow by mutableStateOf(false)
        private set

    fun toggleFill() {
        fillsWindow = !fillsWindow
    }

    init {
        player?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun error(mediaPlayer: MediaPlayer) {
                failedToOpen = true
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                failedToOpen = false
            }
        })

        player?.videoSurface()?.set(
            factory!!.videoSurfaces().newVideoSurface(
                object : BufferFormatCallback {
                    /**
                     * The buffer libvlc wants, and the part of it that is the picture.
                     *
                     * **They are not the same, and assuming they were put a grey band along the
                     * bottom of every video this client played.** libvlc asks three times for one
                     * medium — measured on a 1280x720 file: 1280x720, 1280x720, then 1280x738 — and
                     * the last answer is the one it writes into. Encoding the luma plane and
                     * comparing row means showed what those extra eighteen rows are: every one of
                     * them identical, spread 0.0, against a spread of 13.7 across the picture. They
                     * are filler.
                     *
                     * So the buffer is allocated exactly as asked, because writing outside it would
                     * be libvlc writing outside memory this client owns — and the *image* is built
                     * from the source's own dimensions, which the player knows independently and
                     * which stay right when a stream changes resolution mid-play. Where it does not
                     * know them yet, the asked-for size is the honest fallback: a band at the bottom
                     * is better than a picture that is not drawn at all.
                     */
                    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                        val picture = player?.video()?.videoDimension()
                        frames.resize(
                            bufferWidth = sourceWidth,
                            bufferHeight = sourceHeight,
                            pictureWidth = picture?.width?.takeIf { it in 1..sourceWidth }
                                ?: sourceWidth,
                            pictureHeight = picture?.height?.takeIf { it in 1..sourceHeight }
                                ?: sourceHeight,
                        )
                        return BufferFormat(
                            "I420",
                            sourceWidth,
                            sourceHeight,
                            intArrayOf(sourceWidth, sourceWidth / 2, sourceWidth / 2),
                            intArrayOf(sourceHeight, sourceHeight / 2, sourceHeight / 2),
                        )
                    }

                    override fun allocatedBuffers(buffers: Array<ByteBuffer>) = Unit
                },
                RenderCallback { _, buffers, _ -> frames.accept(buffers) },
                true,
            ),
        )
    }

    /**
     * Starts [url], optionally at [startSeconds].
     *
     * The position is handed to libvlc as a media option rather than seeked to after playback
     * begins. Seeking afterwards means the viewer sees the opening seconds first and then a jump;
     * `:start-time` means the first frame they see is the right one. Same reasoning as the Android
     * player handing its resume position over with the item, before prepare.
     *
     * The URL carries the account's credentials and is deliberately never logged.
     */
    fun play(url: String, startSeconds: Long = 0L, headers: StreamHeaders? = null) {
        val media = player?.media() ?: return
        hasMedia = true
        failedToOpen = false
        speed = 1f
        fillsWindow = false
        // Options rather than a request built by hand, because libvlc opens the connection: it is
        // the thing making the request, so it is the thing that has to be told. A playlist channel
        // whose server wants a particular user agent answers 403 without this, and 403 arrives
        // looking exactly like a stream that is simply broken.
        val options = buildList {
            if (startSeconds > 0L) add(":start-time=$startSeconds")
            headers?.userAgent?.let { add(":http-user-agent=$it") }
            headers?.referrer?.let { add(":http-referrer=$it") }
        }
        media.play(url, *options.toTypedArray())
    }

    fun stop() {
        hasMedia = false
        failedToOpen = false
        player?.controls()?.stop()
        frames.clear()
    }

    /**
     * Puts back the level chosen in an earlier session.
     *
     * Not the [volume] setter, because that treats any positive level as an intent to unmute, which
     * is right for a slider and wrong for a restore: someone who quit muted meant it.
     */
    fun restoreAudio(percent: Int, muted: Boolean) {
        level = percent.coerceIn(0, 100)
        isMuted = muted
    }

    /**
     * Puts the rate back where libvlc forgets it.
     *
     * The same problem the volume has: libvlc resets it with each new media and will not take it
     * before playback is running, so it is nudged from the poll that reads the status.
     */
    fun reapplyRate() {
        val controls = player?.controls() ?: return
        if (player.status().rate() != speed) controls.setRate(speed)
    }

    /** Relative change, for the keyboard. */
    fun adjustVolume(delta: Int) {
        volume += delta
    }

    fun toggleMute() {
        isMuted = !isMuted
        applyAudioLevel()
    }

    /**
     * Puts the chosen level back where libvlc can forget it.
     *
     * libvlc resets the volume with each new media and refuses to set it before playback is actually
     * running, so there is no one moment to apply it at. Instead it is re-applied from the same poll
     * that reads the status, which costs a comparison every half second and means a channel change
     * never resets what the viewer set.
     */
    fun reapplyAudioLevel() {
        val audio = player?.audio() ?: return
        // -1 is what libvlc answers while nothing is loaded; it is not a level to correct. Muted is
        // left alone entirely: mute and level are separate switches there, and correcting one while
        // the other is on invites the two to fight every half second.
        if (!isMuted && audio.volume() >= 0 && audio.volume() != volume) audio.setVolume(volume)
        if (audio.isMute != isMuted) audio.setMute(isMuted)
    }

    private fun applyAudioLevel() {
        val audio = player?.audio() ?: return
        audio.setVolume(volume)
        audio.setMute(isMuted)
    }

    /** Returns the new intent: true when playback should now be running. */
    fun togglePause(): Boolean {
        val controls = player?.controls() ?: return false
        val wasPlaying = player.status().isPlaying
        controls.setPause(wasPlaying)
        return !wasPlaying
    }

    /**
     * Jumps to an absolute position.
     *
     * Refused on anything without a known length. A live stream reports none, and asking libvlc to
     * seek in one either does nothing or drops the connection — neither is a useful answer to give
     * a viewer who dragged a slider.
     */
    fun seekTo(millis: Long) {
        val status = snapshot()
        if (!status.isSeekable) return
        player?.controls()?.setTime(millis.coerceIn(0L, status.lengthMs))
    }

    /** Relative skip, clamped the same way, for the keyboard and the skip buttons. */
    fun skip(deltaMillis: Long) {
        val status = snapshot()
        if (!status.isSeekable) return
        seekTo(status.timeMs + deltaMillis)
    }

    /**
     * A consistent view of where playback is.
     *
     * Read as one snapshot rather than field by field, so a control row cannot render a position
     * from one instant against a length from another.
     */
    fun snapshot(): PlaybackStatus {
        val status = player?.status() ?: return PlaybackStatus()
        return PlaybackStatus(
            isPlaying = status.isPlaying,
            // libvlc answers -1 for both while nothing is loaded, and 0 length is what a live
            // stream reports for as long as it runs.
            timeMs = status.time().coerceAtLeast(0L),
            lengthMs = status.length().coerceAtLeast(0L),
            audioTrack = player.audio().track(),
            subtitleTrack = player.subpictures().track(),
        )
    }

    /**
     * The audio tracks the stream actually carries.
     *
     * Only meaningful once playback has started and libvlc has parsed the container, so this is
     * asked when a menu opens rather than kept in state. On this provider a film usually has one
     * and a sports channel four.
     */
    fun audioTracks(): List<TrackOption> = player?.audio()?.trackDescriptions().orEmpty()
        .map { TrackOption(it.id(), it.description(), languageOf(it.id(), audio = true)) }

    /**
     * The subtitle tracks, with libvlc's own "Disable" entry left in.
     *
     * That entry carries id -1 and is how subtitles are turned off, so removing it and inventing an
     * "Off" row would only mean describing the same thing twice.
     */
    fun subtitleTracks(): List<TrackOption> = player?.subpictures()?.trackDescriptions().orEmpty()
        .map { TrackOption(it.id(), it.description(), languageOf(it.id(), audio = false)) }

    /**
     * The container's language for one track, from the media's own track information.
     *
     * The selectable descriptions and the parsed track information are two different libvlc lists,
     * joined here by id. A track the two do not agree on simply has no language, which degrades to
     * "the player decides" rather than to a wrong choice.
     */
    private fun languageOf(trackId: Int, audio: Boolean): String? {
        val info = runCatching { player?.media()?.info() }.getOrNull() ?: return null
        val tracks = if (audio) info.audioTracks() else info.textTracks()
        // `language()` rather than `language`: vlcj names its accessors without `get`, and the
        // field behind them is private, so the property syntax reaches the wrong thing.
        return tracks.firstOrNull { it.id() == trackId }?.language()
    }

    /**
     * Puts the viewer's remembered languages onto the current media, where it carries them.
     *
     * Returns true once it has had something to work with, so the caller can stop asking. Nothing
     * matching is **not** a failure to retry: a film that does not carry the preferred language
     * should play in what it has.
     */
    fun applyLanguages(
        audioLanguage: String?,
        subtitleLanguage: String?,
        subtitlesDisabled: Boolean,
    ): Boolean {
        val audio = audioTracks()
        val subtitles = subtitleTracks()
        if (audio.isEmpty() && subtitles.isEmpty()) return false

        chooseTrackFor(audioLanguage, audio.map { TrackLanguage(it.id, it.language) })
            ?.let { setAudioTrack(it) }
        when {
            subtitlesDisabled -> setSubtitleTrack(SUBTITLES_OFF)
            else -> chooseTrackFor(subtitleLanguage, subtitles.map { TrackLanguage(it.id, it.language) })
                ?.let { setSubtitleTrack(it) }
        }
        return true
    }

    fun setAudioTrack(id: Int) {
        player?.audio()?.setTrack(id)
    }

    fun setSubtitleTrack(id: Int) {
        player?.subpictures()?.setTrack(id)
    }

    fun release() {
        player?.release()
        factory?.release()
    }
}

/**
 * One selectable track, as libvlc describes it. The id is libvlc's, not an index.
 *
 * [language] is the container's own field where the two libvlc lists agree, and null otherwise —
 * including for the *Disable* entry, which is a control rather than a track.
 */
data class TrackOption(val id: Int, val label: String, val language: String? = null)

/** Holds the newest decoded frame in a form the render thread can read safely. */
class FrameSink {
    private val decoded = AtomicLong()

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    @Volatile
    var latest: VideoFrame? = null
        private set

    private var infoY = ImageInfo(0, 0, ColorType.ALPHA_8, ColorAlphaType.OPAQUE)
    private var infoChroma = ImageInfo(0, 0, ColorType.ALPHA_8, ColorAlphaType.OPAQUE)
    private var scratchY = ByteArray(0)
    private var scratchU = ByteArray(0)
    private var scratchV = ByteArray(0)

    /** How wide a row is in the buffer, which is not how wide the picture is. */
    private var strideY = 0
    private var strideChroma = 0

    /**
     * Sizes the buffers libvlc writes into, and the images drawn out of them.
     *
     * Two sizes rather than one. The **buffer** is whatever libvlc asked for and must be allocated
     * exactly: it writes there. The **picture** is the part of it that is a picture — libvlc pads
     * the buffer, and drawing the padding puts a band along the bottom of the video and squashes
     * everything above it by the same fraction.
     *
     * The images are built at the picture size but with the buffer's row stride, which is what makes
     * one a window onto the other rather than a copy of it.
     */
    fun resize(bufferWidth: Int, bufferHeight: Int, pictureWidth: Int, pictureHeight: Int) {
        width = pictureWidth
        height = pictureHeight
        strideY = bufferWidth
        strideChroma = bufferWidth / 2
        infoY = ImageInfo(pictureWidth, pictureHeight, ColorType.ALPHA_8, ColorAlphaType.OPAQUE)
        infoChroma = ImageInfo(
            pictureWidth / 2,
            pictureHeight / 2,
            ColorType.ALPHA_8,
            ColorAlphaType.OPAQUE,
        )
        scratchY = ByteArray(bufferWidth * bufferHeight)
        scratchU = ByteArray(bufferWidth / 2 * (bufferHeight / 2))
        scratchV = ByteArray(bufferWidth / 2 * (bufferHeight / 2))
    }

    fun clear() {
        latest = null
    }

    /** Called on libvlc's own video thread. */
    fun accept(buffers: Array<ByteBuffer>) {
        if (scratchY.isEmpty() || buffers.size < 3) return
        buffers[0].duplicate().get(scratchY, 0, minOf(buffers[0].remaining(), scratchY.size))
        buffers[1].duplicate().get(scratchU, 0, minOf(buffers[1].remaining(), scratchU.size))
        buffers[2].duplicate().get(scratchV, 0, minOf(buffers[2].remaining(), scratchV.size))
        latest = VideoFrame(
            // The stride is the buffer's, the size is the picture's: the rows past the picture are
            // still in the array and simply never read.
            y = Image.makeRaster(infoY, scratchY, strideY),
            u = Image.makeRaster(infoChroma, scratchU, strideChroma),
            v = Image.makeRaster(infoChroma, scratchV, strideChroma),
            width = width,
            height = height,
        )
        decoded.incrementAndGet()
    }
}

/**
 * What to call each track in the menu.
 *
 * libvlc describes a track the way the container did: `Track 1 - [Deutsch]`, `Audio - [eng]`, or
 * whatever a muxer wrote. Where the language is known, the language is what a viewer is choosing, so
 * that is what the menu says — in one vocabulary rather than in the provider's several.
 *
 * **Except when two tracks would then read alike.** A film with German stereo and German 5.1 must not
 * offer "German" twice, so where a name is not unique the container's own description is appended to
 * every track sharing it. Disambiguating only where it is needed keeps the common case short.
 */
internal fun readableTrackLabels(options: List<TrackOption>): List<String> {
    val names = options.map { option ->
        option.language?.let(::languageDisplayName) ?: option.label
    }
    val ambiguous = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return options.mapIndexed { index, option ->
        val name = names[index]
        if (name in ambiguous && name != option.label) "$name · ${option.label}" else name
    }
}

/** What libvlc will actually hold together, and what anyone would use. */
private const val MIN_RATE = 0.25f
private const val MAX_RATE = 4f

/** libvlc's own "no subtitles" id, which is what its Disable entry carries. */
const val SUBTITLES_OFF = -1

class VideoFrame(
    val y: Image,
    val u: Image,
    val v: Image,
    val width: Int,
    val height: Int,
)

/**
 * BT.709 limited range, which is what broadcast HD and UHD are.
 *
 * The planes arrive as `ALPHA_8` rather than `GRAY_8` deliberately: alpha is not colour managed, so
 * a value put in survives sampling unchanged, while a grey image can pick up a colour-space
 * transform on the way through and skew the conversion.
 */
private const val YUV_SHADER = """
uniform shader yPlane;
uniform shader uPlane;
uniform shader vPlane;

half4 main(float2 coord) {
    half y = yPlane.eval(coord).a;
    half u = uPlane.eval(coord).a;
    half v = vPlane.eval(coord).a;

    y = (y - 0.0627) * 1.1644;
    u = u - 0.5;
    v = v - 0.5;

    half r = y + 1.7927 * v;
    half g = y - 0.2132 * u - 0.5329 * v;
    half b = y + 2.1124 * u;

    return half4(clamp(half3(r, g, b), 0.0, 1.0), 1.0);
}
"""

/** Draws whatever the sink last decoded, letterboxed, converting colour on the GPU. */
@Composable
fun VideoSurface(sink: FrameSink, modifier: Modifier = Modifier, fill: Boolean = false) {
    val effect = remember { RuntimeEffect.makeForShader(YUV_SHADER) }
    var frame by remember { mutableStateOf<VideoFrame?>(null) }

    // Polled rather than pushed: the decode thread must never touch Compose state, and a frame that
    // arrives between two draws is simply the one that gets skipped.
    LaunchedEffect(sink) {
        while (true) {
            val latest = sink.latest
            if (latest !== frame) frame = latest
            delay(2)
        }
    }

    Canvas(modifier = modifier) {
        val current = frame ?: return@Canvas
        if (current.width == 0 || current.height == 0) return@Canvas

        // Fit takes the smaller scale and leaves bars; fill takes the larger and puts the overflow
        // outside the canvas, which is the crop. Both keep the picture's own proportions — nothing
        // here ever stretches, because a stretched face is worse than either.
        val scale = if (fill) {
            maxOf(size.width / current.width, size.height / current.height)
        } else {
            minOf(size.width / current.width, size.height / current.height)
        }
        val drawWidth = current.width * scale
        val drawHeight = current.height * scale
        val offsetX = (size.width - drawWidth) / 2f
        val offsetY = (size.height - drawHeight) / 2f

        fun shaderFor(image: Image, planeScale: Float) = image.makeShader(
            FilterTileMode.CLAMP,
            FilterTileMode.CLAMP,
            SamplingMode.LINEAR,
            // Each plane carries its own matrix, so the shader samples all three at one coordinate
            // and the half-resolution chroma lands correctly without index arithmetic.
            Matrix33(planeScale, 0f, offsetX, 0f, planeScale, offsetY, 0f, 0f, 1f),
        )

        val builder = RuntimeShaderBuilder(effect)
        builder.child("yPlane", shaderFor(current.y, scale))
        builder.child("uPlane", shaderFor(current.u, scale * 2f))
        builder.child("vPlane", shaderFor(current.v, scale * 2f))

        drawIntoCanvas { canvas ->
            val paint = Paint().apply { shader = builder.makeShader() }
            canvas.nativeCanvas.drawRect(
                Rect.makeXYWH(offsetX, offsetY, drawWidth, drawHeight),
                paint,
            )
        }
    }
}

/**
 * Where playback is, at one instant.
 *
 * [isSeekable] is derived from the length rather than asked of libvlc: a live stream reports no
 * length, a film reports its duration, and that distinction is exactly the one the controls need.
 */
data class PlaybackStatus(
    val isPlaying: Boolean = false,
    val timeMs: Long = 0L,
    val lengthMs: Long = 0L,
    /** libvlc's own track ids. -1 means the track type is switched off. */
    val audioTrack: Int = -1,
    val subtitleTrack: Int = -1,
) {
    val isSeekable: Boolean get() = lengthMs > 0L
}
