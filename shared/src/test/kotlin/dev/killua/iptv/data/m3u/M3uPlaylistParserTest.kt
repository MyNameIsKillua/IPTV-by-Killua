package dev.killua.iptv.data.m3u

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.LiveChannel
import org.junit.Test

class M3uPlaylistParserTest {
    @Test
    fun `an entry becomes a channel with its group, logo and guide id`() {
        val report = M3uParseReport()
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="BBCNews.uk" tvg-logo="https://images.example/bbc.png" group-title="News",BBC News HD
            https://stream.example/live/bbc.m3u8
            """,
            report,
        )

        assertThat(channels).hasSize(1)
        val channel = channels.single()
        assertThat(channel.name).isEqualTo("BBC News HD")
        assertThat(channel.categoryId).isEqualTo("News")
        assertThat(channel.logoUrl).isEqualTo("https://images.example/bbc.png")
        assertThat(channel.epgChannelId).isEqualTo("BBCNews.uk")
        assertThat(channel.directSource).isEqualTo("https://stream.example/live/bbc.m3u8")
        assertThat(channel.containerExtension).isEqualTo("m3u8")
        assertThat(channel.providerOrder).isEqualTo(0)
        assertThat(report.accepted).isEqualTo(1)
        assertThat(report.sawHeader).isTrue()
        assertThat(report.finished).isTrue()
    }

    @Test
    fun `identity is a hash, so a provider playlist's credentials never reach the id`() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,One
            http://provider.example/live/killua/s3cret/1.ts
            """,
        )

        val id = channels.single().id
        assertThat(id).doesNotContain("killua")
        assertThat(id).doesNotContain("s3cret")
        assertThat(id).hasLength(32)
        assertThat(id).matches("[0-9a-f]{32}")
    }

    @Test
    fun `the same address gives the same id on a second read, and a different one a different id`() {
        val first = parse("#EXTM3U\n#EXTINF:-1,One\nhttps://stream.example/a.ts").single().id
        val again = parse("#EXTM3U\n#EXTINF:-1,Renamed\nhttps://stream.example/a.ts").single().id
        val other = parse("#EXTM3U\n#EXTINF:-1,One\nhttps://stream.example/b.ts").single().id

        assertThat(again).isEqualTo(first)
        assertThat(other).isNotEqualTo(first)
    }

    @Test
    fun `a comma inside a quoted attribute does not cut the channel name in half`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News, Sport" tvg-id="mix.tv",Sport 1, the second feed
            https://stream.example/one.ts
            """,
        ).single()

        assertThat(channel.categoryId).isEqualTo("News, Sport")
        assertThat(channel.name).isEqualTo("Sport 1, the second feed")
    }

    @Test
    fun `an address a playlist has no business naming is skipped and counted`() {
        val report = M3uParseReport()
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,Router
            http://192.168.1.1/admin
            #EXTINF:-1,Local
            http://localhost:8080/stream.ts
            #EXTINF:-1,Disk
            file:///C:/Windows/win.ini
            #EXTINF:-1,Real
            https://stream.example/one.ts
            """,
            report,
        )

        assertThat(channels.map(LiveChannel::name)).containsExactly("Real")
        assertThat(report.skippedRefusedUrls).isEqualTo(3)
        assertThat(report.accepted).isEqualTo(1)
    }

    @Test
    fun `a logo the policy refuses is dropped without costing the channel`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="http://192.168.1.1/logo.png",One
            https://stream.example/one.ts
            """,
        ).single()

        assertThat(channel.logoUrl).isNull()
        assertThat(channel.name).isEqualTo("One")
    }

    @Test
    fun `provider order follows accepted channels, so a skipped entry leaves no gap`() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,Refused
            http://10.0.0.1/one.ts
            #EXTINF:-1,First
            https://stream.example/a.ts
            #EXTINF:-1,Second
            https://stream.example/b.ts
            """,
        )

        assertThat(channels.map(LiveChannel::providerOrder)).containsExactly(0, 1).inOrder()
        assertThat(channels.map(LiveChannel::name)).containsExactly("First", "Second").inOrder()
    }

    @Test
    fun `an entry with no address and an address with no entry are both counted, not fatal`() {
        val report = M3uParseReport()
        val channels = parse(
            """
            #EXTM3U
            https://stream.example/orphan.ts
            #EXTINF:-1,Dangling
            #EXTINF:-1,Good
            https://stream.example/good.ts
            #EXTINF:-1,Last and dangling
            """,
            report,
        )

        assertThat(channels.map(LiveChannel::name)).containsExactly("Good")
        assertThat(report.skippedUrlsWithoutEntry).isEqualTo(1)
        assertThat(report.skippedEntriesWithoutUrl).isEqualTo(2)
    }

    @Test
    fun `cleartext channels are counted so a screen can say so`() {
        val report = M3uParseReport()
        parse(
            """
            #EXTM3U
            #EXTINF:-1,Plain
            http://stream.example/a.ts
            #EXTINF:-1,Sealed
            https://stream.example/b.ts
            """,
            report,
        )

        assertThat(report.cleartextChannels).isEqualTo(1)
        assertThat(report.accepted).isEqualTo(2)
    }

    @Test
    fun `groups arrive in the order they first appeared, which is the category list`() {
        val report = M3uParseReport()
        parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sport",A
            https://stream.example/a.ts
            #EXTINF:-1 group-title="News",B
            https://stream.example/b.ts
            #EXTINF:-1 group-title="Sport",C
            https://stream.example/c.ts
            #EXTINF:-1,D
            https://stream.example/d.ts
            """,
            report,
        )

        assertThat(report.groups).containsExactly("Sport", "News").inOrder()
    }

    @Test
    fun `the legacy EXTGRP line names a group when the attribute did not`() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,A
            #EXTGRP:Documentaries
            https://stream.example/a.ts
            #EXTINF:-1 group-title="News",B
            #EXTGRP:Ignored
            https://stream.example/b.ts
            """,
        )

        assertThat(channels.map(LiveChannel::categoryId))
            .containsExactly("Documentaries", "News").inOrder()
    }

    @Test
    fun `the guide address in the header is kept when it survives the policy`() {
        val withGuide = M3uParseReport()
        parse("#EXTM3U x-tvg-url=\"https://guide.example/xmltv.xml\"\n", withGuide)
        assertThat(withGuide.epgUrl).isEqualTo("https://guide.example/xmltv.xml")

        val withPrivateGuide = M3uParseReport()
        parse("#EXTM3U x-tvg-url=\"http://192.168.1.1/xmltv.xml\"\n", withPrivateGuide)
        assertThat(withPrivateGuide.epgUrl).isNull()
    }

    @Test
    fun `control characters and absurd lengths in display text are removed, not trusted`() {
        val channel = parse(
            "#EXTM3U\n#EXTINF:-1 group-title=\"" + "G".repeat(400) + "\",A\u0007B" +
                "!".repeat(900) + "\nhttps://stream.example/a.ts",
        ).single()

        assertThat(channel.name).doesNotContain("\u0007")
        assertThat(channel.name).hasLength(500)
        assertThat(channel.categoryId).hasLength(200)
    }

    @Test
    fun `an overlong line is skipped and takes only its own entry with it`() {
        val report = M3uParseReport()
        val channels = parse(
            "#EXTM3U\n#EXTINF:-1,Huge\nhttps://stream.example/" + "a".repeat(9_000) +
                "\n#EXTINF:-1,Fine\nhttps://stream.example/fine.ts",
            report,
        )

        assertThat(channels.map(LiveChannel::name)).containsExactly("Fine")
        assertThat(report.skippedOverlongLines).isEqualTo(1)
    }

    @Test
    fun `a file with no header is read anyway and says that it had none`() {
        val report = M3uParseReport()
        val channels = parse("#EXTINF:-1,A\nhttps://stream.example/a.ts", report)

        assertThat(channels).hasSize(1)
        assertThat(report.sawHeader).isFalse()
    }

    @Test
    fun `an unnamed channel keeps its place rather than being dropped`() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,
            https://stream.example/a.ts
            #EXTINF:-1 tvg-name="From the attribute",
            https://stream.example/b.ts
            """,
        )

        assertThat(channels.map(LiveChannel::name))
            .containsExactly("Channel 1", "From the attribute").inOrder()
    }

    @Test
    fun `the listing is lazy, so a caller that wants ten does not parse a hundred thousand`() {
        val lines = sequence {
            yield("#EXTM3U")
            var index = 0
            while (true) {
                yield("#EXTINF:-1,Channel $index")
                yield("https://stream.example/$index.ts")
                index++
            }
        }

        val firstTen = M3uPlaylistParser.parse(lines).take(10).toList()

        assertThat(firstTen).hasSize(10)
        assertThat(firstTen.last().name).isEqualTo("Channel 9")
    }

    @Test
    fun `a playlist past the entry cap stops rather than reading on`() {
        val report = M3uParseReport()
        val lines = sequence {
            yield("#EXTM3U")
            var index = 0
            while (index < 260_000) {
                yield("#EXTINF:-1,Channel $index")
                yield("https://stream.example/$index.ts")
                index++
            }
        }

        val count = M3uPlaylistParser.parse(lines, report).count()

        assertThat(count).isEqualTo(250_000)
        assertThat(report.truncated).isTrue()
    }

    /*
     * The four cases below are shapes taken from a real public playlist rather than invented:
     * a player-option directive sitting between an entry and its address, a user-agent attribute
     * whose value contains both commas and semicolons, an address that ends in a script name, and
     * the streaming protocols that are not HTTP. The hosts are examples; the shapes are not.
     */

    @Test
    fun `a player-option directive between an entry and its address does not lose the entry`() {
        val report = M3uParseReport()
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="General",1+1 International
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)
            #EXTVLCOPT:http-referrer=https://example/
            https://stream.example/one.m3u8
            """,
            report,
        )

        assertThat(channels.map(LiveChannel::name)).containsExactly("1+1 International")
        assertThat(report.skippedEntriesWithoutUrl).isEqualTo(0)
    }

    @Test
    fun `a user-agent attribute full of commas and brackets does not become the channel name`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="One.ua" http-user-agent="Mozilla/5.0 (Windows NT 10.0; Win64) AppleWebKit/537.36 (KHTML, like Gecko)" group-title="General",1+1 International
            https://stream.example/one.m3u8
            """,
        ).single()

        assertThat(channel.name).isEqualTo("1+1 International")
        assertThat(channel.categoryId).isEqualTo("General")
        assertThat(channel.epgChannelId).isEqualTo("One.ua")
    }

    @Test
    fun `an address ending in a script name has no container, rather than a wrong one`() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,Script
            http://stream.example:88/georgia_play.php?id=1plus1international
            #EXTINF:-1,Playlist
            https://stream.example/live/one.m3u8
            """,
        )

        assertThat(channels[0].containerExtension).isNull()
        assertThat(channels[1].containerExtension).isEqualTo("m3u8")
    }

    @Test
    fun `the streaming protocols that are not HTTP are refused, and counted as such`() {
        val report = M3uParseReport()
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,Flash
            rtmp://stream.example/live/one
            #EXTINF:-1,Windows Media
            mmsh://stream.example/one
            #EXTINF:-1,Reliable transport
            srt://stream.example:9000
            #EXTINF:-1,Web
            https://stream.example/one.m3u8
            """,
            report,
        )

        assertThat(channels.map(LiveChannel::name)).containsExactly("Web")
        assertThat(report.skippedRefusedUrls).isEqualTo(3)
    }

    @Test
    fun `the playback hints are read from the entry line`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 http-user-agent="Mozilla/5.0 (Windows NT 10.0)" http-referrer="https://portal.example/",One
            https://stream.example/one.m3u8
            """,
        ).single()

        assertThat(channel.streamHeaders?.userAgent).isEqualTo("Mozilla/5.0 (Windows NT 10.0)")
        assertThat(channel.streamHeaders?.referrer).isEqualTo("https://portal.example/")
    }

    @Test
    fun `the same hints are read from the older directive spelling`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1,One
            #EXTVLCOPT:http-user-agent=VLC/3.0.20 LibVLC/3.0.20
            #EXTVLCOPT:http-referrer=https://portal.example/
            https://stream.example/one.m3u8
            """,
        ).single()

        assertThat(channel.streamHeaders?.userAgent).isEqualTo("VLC/3.0.20 LibVLC/3.0.20")
        assertThat(channel.streamHeaders?.referrer).isEqualTo("https://portal.example/")
    }

    @Test
    fun `where both spellings disagree the entry line wins`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1 http-user-agent="FromAttribute",One
            #EXTVLCOPT:http-user-agent=FromDirective
            #EXTVLCOPT:http-referrer=https://only.example/
            https://stream.example/one.m3u8
            """,
        ).single()

        assertThat(channel.streamHeaders?.userAgent).isEqualTo("FromAttribute")
        // The directive still fills what the attribute never said.
        assertThat(channel.streamHeaders?.referrer).isEqualTo("https://only.example/")
    }

    @Test
    fun `a channel that names no hints carries none, rather than an empty one`() {
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1,One
            https://stream.example/one.m3u8
            """,
        ).single()

        assertThat(channel.streamHeaders).isNull()
    }

    @Test
    fun `a directive belonging to no entry is ignored rather than attached to the next`() {
        val channels = parse(
            """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=Orphan
            #EXTINF:-1,One
            https://stream.example/one.m3u8
            """,
        )

        assertThat(channels.single().streamHeaders).isNull()
    }

    @Test
    fun `a hint out of an untrusted file is bounded and stripped like every other text`() {
        val bell = Char(7)
        val noisy = "A" + bell + "B" + "x".repeat(900)
        val channel = parse(
            """
            #EXTM3U
            #EXTINF:-1,One
            #EXTVLCOPT:http-user-agent=$noisy
            https://stream.example/one.m3u8
            """,
        ).single()

        val agent = channel.streamHeaders?.userAgent
        assertThat(agent).isNotNull()
        assertThat(agent).doesNotContain(bell.toString())
        assertThat(agent).hasLength(512)
    }

    private fun parse(text: String, report: M3uParseReport = M3uParseReport()): List<LiveChannel> =
        M3uPlaylistParser.parse(text.trimIndent().lineSequence(), report).toList()
}
