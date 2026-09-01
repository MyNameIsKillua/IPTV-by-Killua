package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimedCacheTest {

    private var clock = 1_000L
    private val cache = TimedCache<String, String>(
        ttlMillis = 60_000L,
        maxEntries = 3,
        now = { clock },
    )

    @Test
    fun `what was stored is handed back`() {
        cache.put("501", "A Film")

        assertThat(cache.get("501")).isEqualTo("A Film")
    }

    @Test
    fun `a key that was never stored is absent`() {
        assertThat(cache.get("501")).isNull()
    }

    @Test
    fun `an entry expires on the timer`() {
        cache.put("501", "A Film")

        clock += 59_000L
        assertThat(cache.get("501")).isNotNull()

        clock += 2_000L
        // A provider that corrects a title should not need a restart to be believed.
        assertThat(cache.get("501")).isNull()
    }

    @Test
    fun `storing again restarts the timer`() {
        cache.put("501", "A Film")
        clock += 59_000L
        cache.put("501", "A Film, corrected")

        clock += 2_000L

        assertThat(cache.get("501")).isEqualTo("A Film, corrected")
    }

    @Test
    fun `it does not grow past its bound`() {
        repeat(10) { index -> cache.put("film $index", "value $index") }

        // A session that walks a large category must not keep every record it passed.
        assertThat(cache.get("film 0")).isNull()
        assertThat(cache.get("film 6")).isNull()
        assertThat(cache.get("film 7")).isEqualTo("value 7")
        assertThat(cache.get("film 9")).isEqualTo("value 9")
    }

    @Test
    fun `clearing empties it`() {
        cache.put("501", "A Film")

        cache.clear()

        assertThat(cache.get("501")).isNull()
    }
}
