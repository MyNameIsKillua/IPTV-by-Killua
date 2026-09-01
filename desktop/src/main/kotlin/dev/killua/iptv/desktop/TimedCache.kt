package dev.killua.iptv.desktop

/**
 * Answers kept for a while, because asking again would give the same answer.
 *
 * The desktop client has no database, which is a decision about *listings* — six figures of them, so
 * never cached. A film's own record is the opposite case: one small answer per title, fetched every
 * time its panel opens, and unchanged between one opening and the next. Holding it in memory for
 * half an hour is the difference between browsing and re-downloading.
 *
 * Bounded and expiring, both on purpose. A session that walks a large category would otherwise keep
 * every record it passed, and a provider that corrects a title should not need a restart to be
 * believed.
 */
class TimedCache<K, V>(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry<V>(val storedAtMillis: Long, val value: V)

    private val entries = LinkedHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (now() - entry.storedAtMillis >= ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V) {
        entries.remove(key)
        entries[key] = Entry(now(), value)
        // Insertion-ordered, so this drops what was stored longest ago rather than what was read
        // longest ago. For records that never change, when they arrived is the only ordering there
        // is, and an exact one is not worth a second map.
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    fun clear() = entries.clear()

    private companion object {
        const val DEFAULT_TTL_MILLIS = 30L * 60L * 1_000L
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
