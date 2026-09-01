package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.EpgEntry
import org.junit.Test

class EpgCacheTest {

    private var clock = 1_000_000L
    private val cache = EpgCache(ttlMillis = 60_000L) { clock }

    @Test
    fun `a listing is handed back without asking again`() {
        val listing = listOf(entry(from = clock, to = clock + 3_600_000L))

        cache.put("501", listing)

        assertThat(cache.get("501")).isEqualTo(listing)
    }

    @Test
    fun `a channel never asked for is not in it`() {
        assertThat(cache.get("501")).isNull()
    }

    @Test
    fun `a listing expires on the timer`() {
        cache.put("501", listOf(entry(from = clock, to = clock + 24 * 3_600_000L)))

        clock += 59_000L
        assertThat(cache.get("501")).isNotNull()

        clock += 2_000L
        // Even a listing that still covers tomorrow is re-asked eventually: a provider correcting
        // its schedule should not take a restart to show up.
        assertThat(cache.get("501")).isNull()
    }

    @Test
    fun `a listing that has run out expires early`() {
        cache.put("501", listOf(entry(from = clock - 3_600_000L, to = clock + 10_000L)))

        clock += 11_000L

        // Well inside the timer, but the last programme has ended: the list has stopped describing
        // anything current, and showing it would mean captioning a channel with what was on before.
        assertThat(cache.get("501")).isNull()
    }

    @Test
    fun `an empty listing is remembered rather than asked for over and over`() {
        cache.put("501", emptyList())

        // A channel with no guide at all is common on this kind of provider. Without this, every
        // visit to the guide asks for nothing forty times.
        assertThat(cache.get("501")).isEmpty()
    }

    @Test
    fun `refresh means ask again`() {
        cache.put("501", listOf(entry(from = clock, to = clock + 3_600_000L)))

        cache.clear()

        assertThat(cache.get("501")).isNull()
    }

    @Test
    fun `it does not grow without limit`() {
        repeat(250) { index ->
            cache.put("channel $index", listOf(entry(from = clock, to = clock + 3_600_000L)))
        }

        // The oldest are dropped, the newest are kept: a session that plays through a large category
        // must not turn the guide cache into a slow leak.
        assertThat(cache.get("channel 0")).isNull()
        assertThat(cache.get("channel 249")).isNotNull()
    }

    @Test
    fun `putting a channel again replaces its listing and its age`() {
        cache.put("501", listOf(entry(from = clock, to = clock + 3_600_000L)))
        clock += 59_000L
        val fresher = listOf(entry(from = clock, to = clock + 3_600_000L))
        cache.put("501", fresher)

        clock += 2_000L

        // The second put restarts the timer; without that a refreshed listing would expire on the
        // schedule of the one it replaced.
        assertThat(cache.get("501")).isEqualTo(fresher)
    }

    private fun entry(from: Long, to: Long) = EpgEntry(
        title = "Programme",
        description = null,
        startEpochSeconds = from / 1_000L,
        endEpochSeconds = to / 1_000L,
    )
}
