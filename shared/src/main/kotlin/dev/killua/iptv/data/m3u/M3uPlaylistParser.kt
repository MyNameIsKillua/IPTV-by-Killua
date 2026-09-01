package dev.killua.iptv.data.m3u

import dev.killua.iptv.core.network.StreamUrlPolicy
import dev.killua.iptv.core.network.StreamUrlVerdict
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.StreamHeaders
import java.security.MessageDigest

/**
 * Reads an extended M3U playlist into the same [LiveChannel] the Xtream listing produces.
 *
 * The format is two lines per entry and nothing else:
 *
 * ```
 * #EXTINF:-1 tvg-id="BBCNews.uk" tvg-logo="https://example/logo.png" group-title="News",BBC News
 * https://example/stream.m3u8
 * ```
 *
 * Which is the whole reason a playlist account is **Live only**. `group-title` becomes a category,
 * `tvg-logo` a logo and `tvg-id` the EPG id, and there the format ends: it has no films, no series,
 * no per-title metadata and no guide endpoint. A screen that offered those for a playlist would be
 * offering something the file cannot answer.
 *
 * Three things about this parser are deliberate.
 *
 * **It is lazy, and the caller must keep it that way.** The listing arrives as a [Sequence] and
 * leaves as one. `v0.2.0-alpha.2` streamed a provider's JSON and still died, because it then held
 * every parsed object at once - 153,000 realistic rows were enough to exhaust a 192MB heap. The
 * lesson was that streaming the input is only half of it, and it applies here unchanged: a provider
 * playlist is the same six figures of channels as its API.
 *
 * **Identity is a hash of the address, never the address.** [LiveChannel.id] ends up in a database
 * key, in watch progress and in an exported file, and a provider's playlist puts the username and
 * password inside every stream URL. An id derived from the raw address would carry those into all
 * three. A SHA-256 prefix carries nothing, and it is stable for as long as the address is, which is
 * the best any playlist offers - the format has no id of its own.
 *
 * **Every address is checked, including the artwork.** Stream addresses go through
 * [StreamUrlPolicy] and so do logos, because a logo is a request to a host the playlist chose and
 * loading one tells its author who is watching.
 *
 * Malformed input is skipped, never fatal. A playlist is a text file from a stranger, and one bad
 * line should cost that line rather than the library; what was skipped is counted in [M3uParseReport]
 * so a screen can say so instead of quietly showing less than the file contained.
 */
object M3uPlaylistParser {
    private const val HEADER = "#EXTM3U"
    private const val ENTRY = "#EXTINF:"
    private const val GROUP = "#EXTGRP:"
    private const val PLAYER_OPTION = "#EXTVLCOPT:"

    private const val MAX_LINE_CHARACTERS = 8_192
    private const val MAX_ENTRIES = 250_000
    private const val MAX_GROUPS = 5_000
    private const val MAX_NAME_CHARACTERS = 500
    private const val MAX_ATTRIBUTE_CHARACTERS = 200
    private const val ID_HEX_CHARACTERS = 32
    private const val MAX_HEADER_CHARACTERS = 512

    private val KNOWN_CONTAINERS =
        setOf("m3u8", "ts", "mpd", "mp4", "mkv", "avi", "m4v", "mov", "webm", "flv")

    private val attributePattern =
        Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")

    /**
     * [report] is filled in **as the sequence is consumed**, not when this returns. Read it after
     * the caller has finished walking the result, never before.
     */
    fun parse(lines: Sequence<String>, report: M3uParseReport = M3uParseReport()): Sequence<LiveChannel> =
        sequence {
            val digest = MessageDigest.getInstance("SHA-256")
            var pending: PendingEntry? = null
            var order = 0
            var sawHeader = false

            for (rawLine in lines) {
                if (order >= MAX_ENTRIES) {
                    report.truncated = true
                    break
                }
                if (rawLine.length > MAX_LINE_CHARACTERS) {
                    report.skippedOverlongLines++
                    pending = null
                    continue
                }
                val line = rawLine.trim { it.isWhitespace() || it == '\uFEFF' }
                if (line.isEmpty()) continue

                when {
                    !sawHeader && line.startsWith(HEADER, ignoreCase = true) -> {
                        sawHeader = true
                        report.epgUrl = attributesOf(line)["x-tvg-url"]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { candidate ->
                                (StreamUrlPolicy.check(candidate) as? StreamUrlVerdict.Allowed)?.url
                            }
                    }

                    line.startsWith(ENTRY, ignoreCase = true) -> {
                        if (pending != null) report.skippedEntriesWithoutUrl++
                        pending = pendingFrom(line)
                    }

                    line.startsWith(GROUP, ignoreCase = true) -> {
                        // A legacy way of saying group-title, and only honoured when the attribute
                        // did not already say it.
                        val group = line.substringAfter(':').trim().take(MAX_ATTRIBUTE_CHARACTERS)
                        val current = pending
                        if (current != null && current.group == null && group.isNotEmpty()) {
                            pending = current.copy(group = group)
                        }
                    }

                    line.startsWith(PLAYER_OPTION, ignoreCase = true) -> {
                        // The older spelling of the same two hints. It only fills a gap: an
                        // attribute on the `#EXTINF` line has already been read and wins.
                        val option = line.substringAfter(':')
                        val key = option.substringBefore('=').trim().lowercase()
                        val value = safeText(option.substringAfter('=', ""), MAX_HEADER_CHARACTERS)
                        val current = pending
                        if (current != null && value.isNotEmpty()) {
                            pending = when {
                                isUserAgentKey(key) && current.userAgent == null ->
                                    current.copy(userAgent = value)
                                isReferrerKey(key) && current.referrer == null ->
                                    current.copy(referrer = value)
                                else -> current
                            }
                        }
                    }

                    line.startsWith("#") -> Unit // Any other directive: not ours, not an error.

                    else -> {
                        val entry = pending
                        pending = null
                        if (entry == null) {
                            report.skippedUrlsWithoutEntry++
                            continue
                        }
                        when (val verdict = StreamUrlPolicy.check(line)) {
                            is StreamUrlVerdict.Refused -> report.skippedRefusedUrls++
                            is StreamUrlVerdict.Allowed -> {
                                if (verdict.isCleartext) report.cleartextChannels++
                                entry.group?.let { group ->
                                    if (report.groups.size < MAX_GROUPS) report.groups += group
                                }
                                // Counted *before* the yield, not after. A `yield` suspends, and a
                                // caller that stops early - `take(10)`, the item cap - never resumes
                                // the sequence, so anything written after it silently does not
                                // happen and the report undercounts by exactly one.
                                val channel = entry.toChannel(verdict.url, order, digest)
                                order++
                                report.accepted++
                                yield(channel)
                            }
                        }
                    }
                }
            }
            if (pending != null) report.skippedEntriesWithoutUrl++
            report.sawHeader = sawHeader
            report.finished = true
        }

    private fun pendingFrom(line: String): PendingEntry {
        val body = line.substringAfter(':')
        val separator = displayNameComma(body)
        val attributeText = if (separator < 0) body else body.substring(0, separator)
        val name = if (separator < 0) "" else body.substring(separator + 1).trim()
        val attributes = attributesOf(attributeText)
        return PendingEntry(
            name = safeText(name, MAX_NAME_CHARACTERS),
            group = attributes["group-title"]
                ?.let { safeText(it, MAX_ATTRIBUTE_CHARACTERS) }
                ?.takeIf { it.isNotEmpty() },
            epgChannelId = attributes["tvg-id"]
                ?.let { safeText(it, MAX_ATTRIBUTE_CHARACTERS) }
                ?.takeIf { it.isNotEmpty() },
            logoUrl = attributes["tvg-logo"]
                ?.takeIf { it.isNotBlank() }
                ?.let { (StreamUrlPolicy.check(it) as? StreamUrlVerdict.Allowed)?.url },
            fallbackName = attributes["tvg-name"]
                ?.let { safeText(it, MAX_ATTRIBUTE_CHARACTERS) }
                ?.takeIf { it.isNotEmpty() },
            userAgent = attributes.entries
                .firstOrNull { isUserAgentKey(it.key) }
                ?.let { safeText(it.value, MAX_HEADER_CHARACTERS) }
                ?.takeIf { it.isNotEmpty() },
            referrer = attributes.entries
                .firstOrNull { isReferrerKey(it.key) }
                ?.let { safeText(it.value, MAX_HEADER_CHARACTERS) }
                ?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The comma that ends the attributes and begins the display name - the first one **outside a
     * quoted value**, because `group-title="News, Sport"` is legal and splitting on the first comma
     * anywhere would cut a name in half and leave `Sport"` as the channel.
     */
    private fun displayNameComma(body: String): Int {
        var quoted = false
        body.forEachIndexed { index, character ->
            when {
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> return index
            }
        }
        return -1
    }

    private fun isUserAgentKey(key: String) = key == "http-user-agent" || key == "user-agent"

    private fun isReferrerKey(key: String) =
        key == "http-referrer" || key == "http-referer" || key == "referer" || key == "referrer"

    private fun attributesOf(text: String): Map<String, String> =
        attributePattern.findAll(text).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2]
        }

    /** Display text out of a playlist is a stranger's text: bounded, and with the controls removed. */
    private fun safeText(raw: String, limit: Int): String =
        raw.filterNot(Char::isISOControl).trim().take(limit)

    private data class PendingEntry(
        val name: String,
        val group: String?,
        val epgChannelId: String?,
        val logoUrl: String?,
        val fallbackName: String?,
        val userAgent: String? = null,
        val referrer: String? = null,
    ) {
        fun toChannel(url: String, order: Int, digest: MessageDigest): LiveChannel {
            val display = name.ifEmpty { fallbackName.orEmpty() }
            return LiveChannel(
                id = identityOf(url, digest),
                categoryId = group,
                // A channel with no name at all still has an address, and dropping it would lose a
                // playable channel over a cosmetic gap. The screen shows what it has.
                name = display.ifEmpty { "Channel ${order + 1}" },
                logoUrl = logoUrl,
                epgChannelId = epgChannelId,
                containerExtension = extensionOf(url),
                directSource = url,
                providerOrder = order,
                streamHeaders = StreamHeaders(userAgent = userAgent, referrer = referrer)
                    .takeUnless { it.isEmpty },
            )
        }
    }

    private fun identityOf(url: String, digest: MessageDigest): String {
        digest.reset()
        return digest.digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(ID_HEX_CHARACTERS)
    }

    /**
     * Only a container this program recognises, never whatever followed the last dot.
     *
     * A real playlist ends plenty of addresses in something that is not a container at all -
     * `.../georgia_play.php?id=1plus1` is a live channel, and reading `php` off it would put a
     * server-side script name in a field that means "what this media is". Nothing plays a playlist
     * channel by extension, since the address is used as it stands, so an unrecognised one is
     * better absent than wrong.
     */
    private fun extensionOf(url: String): String? =
        url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in KNOWN_CONTAINERS }
}

/**
 * What a parse saw. Complete only once the sequence it was passed to has been walked to the end;
 * [finished] says whether that has happened.
 */
class M3uParseReport {
    /** Channels yielded. */
    var accepted: Int = 0
        internal set

    /** `#EXTINF` lines with no address after them. */
    var skippedEntriesWithoutUrl: Int = 0
        internal set

    /** Addresses with no `#EXTINF` before them, which name nothing and belong to no one. */
    var skippedUrlsWithoutEntry: Int = 0
        internal set

    /** Addresses [StreamUrlPolicy] would not allow. Worth showing: it is the security-relevant one. */
    var skippedRefusedUrls: Int = 0
        internal set

    /** Lines longer than the parser will look at. */
    var skippedOverlongLines: Int = 0
        internal set

    /** Accepted channels served over `http`. The screen should say when this is not zero. */
    var cleartextChannels: Int = 0
        internal set

    /** Group titles in the order they first appeared, which is the category list. */
    val groups: MutableSet<String> = LinkedHashSet()

    /** The `x-tvg-url` from the header, if it had one and it survived the policy. */
    var epgUrl: String? = null
        internal set

    /** Whether the file began with `#EXTM3U`. A file that did not is suspicious, not invalid. */
    var sawHeader: Boolean = false
        internal set

    /** Whether the entry cap was hit and the rest of the file was not read. */
    var truncated: Boolean = false
        internal set

    /** Whether the sequence was consumed to the end. Every count above is provisional until it is. */
    var finished: Boolean = false
        internal set
}
