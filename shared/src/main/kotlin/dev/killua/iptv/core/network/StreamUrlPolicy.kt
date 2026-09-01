package dev.killua.iptv.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The rule for a stream address this program did not build itself.
 *
 * Every Xtream address is assembled by `XtreamStreamUrlFactory` from a host the viewer typed, so the
 * program has always known who it was about to talk to. A playlist reverses that: the file decides,
 * and whoever wrote the file is not necessarily the viewer. This is the check that stands between
 * the two. It runs on every address read out of a playlist - once when the listing is read, and
 * again before anything is played, because a listing can outlive the check that produced it.
 *
 * What it refuses, and why each one rather than a general sense of caution:
 *
 * - **Anything but `http` and `https`.** `file:`, `rtsp:` and `udp:` either reach the local disk or
 *   reach a stack this program does not speak.
 * - **Credentials in the address.** `http://user:pass@host/` smuggles a login past a UI that shows
 *   only a host, and `ServerUrlNormalizer` already refuses it for that reason.
 * - **Loopback, private, link-local and carrier-NAT addresses.** This is the one that matters.
 *   Without it a playlist can point the program at `192.168.1.1` or `127.0.0.1:8080` - at the
 *   viewer's own router or whatever else is listening on their network - and use the program as a
 *   way to reach machines the playlist's author cannot reach directly.
 *
 * **What it cannot do, stated rather than glossed over:** a name is not an address. `evil.example`
 * may resolve to `192.168.1.1` and nothing here will know, because resolving it would mean a DNS
 * lookup inside a pure function that has no business making one. Catching that needs the check
 * repeated at the socket, which belongs wherever the HTTP client lives - not in `:shared`, which
 * deliberately has none. This raises the cost of that attack from trivial to deliberate; it does
 * not remove it.
 *
 * Cleartext is **allowed and reported**, not refused. A public playlist is a mixture of `http` and
 * `https` and always will be, so refusing would refuse the format. The honest answer is to let the
 * screen say so, which keeps the project rule intact - never *silently* downgrade - while leaving
 * the decision with the person making it.
 */
object StreamUrlPolicy {
    private const val MAX_URL_CHARACTERS = 8_192

    fun check(rawUrl: String): StreamUrlVerdict {
        val trimmed = rawUrl.trim { it.isWhitespace() || it == '\uFEFF' }
        if (trimmed.isEmpty()) return refuse(StreamUrlRefusal.Empty)
        if (trimmed.length > MAX_URL_CHARACTERS) return refuse(StreamUrlRefusal.TooLong)
        if (trimmed.any(Char::isISOControl)) return refuse(StreamUrlRefusal.ControlCharacter)

        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return refuse(StreamUrlRefusal.UnsupportedScheme)

        val parsed = trimmed.toHttpUrlOrNull() ?: return refuse(StreamUrlRefusal.Malformed)
        if (parsed.host.isBlank()) return refuse(StreamUrlRefusal.MissingHost)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return refuse(StreamUrlRefusal.UserInfoNotAllowed)
        }
        hostRefusal(parsed.host)?.let { return refuse(it) }

        return StreamUrlVerdict.Allowed(
            url = parsed.toString(),
            isCleartext = parsed.scheme == "http",
        )
    }

    /**
     * `null` when the host is one this program is willing to reach.
     *
     * OkHttp has already lower-cased the host and stripped the brackets from an IPv6 literal by the
     * time this sees it, so these comparisons are against a canonical form rather than against
     * whatever the file happened to spell.
     */
    private fun hostRefusal(host: String): StreamUrlRefusal? {
        if (host == "localhost" || host.endsWith(".localhost")) {
            return StreamUrlRefusal.LoopbackAddress
        }
        // mDNS: a `.local` name is by definition answered by something on the same link.
        if (host == "local" || host.endsWith(".local")) return StreamUrlRefusal.PrivateAddress

        ipv4Octets(host)?.let { return ipv4Refusal(it) }
        if (host.contains(':')) return ipv6Refusal(host)
        return null
    }

    private fun ipv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[index] = value
        }
        return octets
    }

    private fun ipv4Refusal(octets: IntArray): StreamUrlRefusal? {
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 127 -> StreamUrlRefusal.LoopbackAddress
            // "This network", 0.0.0.0/8, and the all-ones broadcast address.
            first == 0 -> StreamUrlRefusal.PrivateAddress
            first == 255 && second == 255 -> StreamUrlRefusal.PrivateAddress
            first == 10 -> StreamUrlRefusal.PrivateAddress
            first == 192 && second == 168 -> StreamUrlRefusal.PrivateAddress
            first == 172 && second in 16..31 -> StreamUrlRefusal.PrivateAddress
            // Link-local, which is also where cloud metadata answers: 169.254.169.254.
            first == 169 && second == 254 -> StreamUrlRefusal.PrivateAddress
            // Carrier-grade NAT: not the viewer's own network, but not the public internet either.
            first == 100 && second in 64..127 -> StreamUrlRefusal.PrivateAddress
            else -> null
        }
    }

    private fun ipv6Refusal(host: String): StreamUrlRefusal? {
        val bare = host.removePrefix("[").removeSuffix("]").substringBefore('%')
        if (bare == "::1") return StreamUrlRefusal.LoopbackAddress
        if (bare == "::" || bare.isEmpty()) return StreamUrlRefusal.PrivateAddress
        // An IPv4-mapped or IPv4-compatible address carries the v4 rules with it: `::ffff:127.0.0.1`
        // is loopback however it is spelled.
        ipv4Octets(bare.substringAfterLast(':'))?.let { return ipv4Refusal(it) }

        val leading = bare.substringBefore("::").substringBefore(':').lowercase().padStart(4, '0')
        return when {
            // fe80::/10, link-local.
            leading.startsWith("fe") && leading[2] in "89ab" -> StreamUrlRefusal.PrivateAddress
            // fc00::/7, unique-local.
            leading.startsWith("fc") || leading.startsWith("fd") -> StreamUrlRefusal.PrivateAddress
            else -> null
        }
    }

    private fun refuse(reason: StreamUrlRefusal) = StreamUrlVerdict.Refused(reason)
}

sealed interface StreamUrlVerdict {
    /**
     * [url] is the address as OkHttp canonicalised it, which is what should be stored and played
     * rather than the raw text - a playlist is untrusted input and its spelling is not worth
     * preserving.
     */
    data class Allowed(val url: String, val isCleartext: Boolean) : StreamUrlVerdict

    data class Refused(val reason: StreamUrlRefusal) : StreamUrlVerdict
}

enum class StreamUrlRefusal {
    Empty,
    TooLong,
    ControlCharacter,
    UnsupportedScheme,
    Malformed,
    MissingHost,
    UserInfoNotAllowed,
    LoopbackAddress,
    PrivateAddress,
}
