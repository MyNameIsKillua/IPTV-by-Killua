package dev.killua.iptv.desktop

import dev.killua.iptv.domain.model.EpgEntry

/**
 * The short guide, kept for as long as it still describes the present.
 *
 * `get_short_epg` answers one channel per request, so a guide over forty channels is forty requests
 * — and without this, every visit to the guide made all forty again, plus one more each time a
 * channel was played for the strip over the picture. The listing it returns covers hours.
 *
 * **Validity is two rules rather than a timer.** A cached listing expires after [ttlMillis], so a
 * provider correcting its schedule is picked up within the hour; and it expires the moment its last
 * entry has ended, because at that point the list has stopped describing anything current no matter
 * how recently it arrived. Whichever comes first wins.
 *
 * An empty answer is cached too. On this provider a channel with no guide at all is common, and
 * re-asking for nothing forty times a visit is the behaviour this exists to prevent. The cost is
 * that a guide which failed to load stays missing until the hour is up or *Refresh* is pressed,
 * which is why that button clears this outright.
 */
class EpgCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val fetchedAtMillis: Long, val entries: List<EpgEntry>)

    private val cached = LinkedHashMap<String, Entry>()

    /** The listing for [channelId] if it is still current, or null when it has to be asked for. */
    fun get(channelId: String): List<EpgEntry>? {
        val entry = cached[channelId] ?: return null
        val moment = now()
        if (moment - entry.fetchedAtMillis >= ttlMillis) {
            cached.remove(channelId)
            return null
        }
        // An empty listing has no last entry to expire against; it stands until the timer says so.
        val lastEnd = entry.entries.maxOfOrNull { it.endEpochSeconds }
        if (lastEnd != null && lastEnd * 1_000L <= moment) {
            cached.remove(channelId)
            return null
        }
        return entry.entries
    }

    fun put(channelId: String, entries: List<EpgEntry>) {
        cached.remove(channelId)
        cached[channelId] = Entry(now(), entries)
        // Insertion-ordered, so this drops the channels asked for longest ago. A viewer's own list
        // is capped at forty; the room above that is for the ones played from a category.
        while (cached.size > MAX_CHANNELS) {
            cached.remove(cached.keys.first())
        }
    }

    /** For *Refresh*, which has to mean "ask again" rather than "show me the same thing". */
    fun clear() = cached.clear()

    private companion object {
        const val DEFAULT_TTL_MILLIS = 60L * 60L * 1_000L
        const val MAX_CHANNELS = 200
    }
}
