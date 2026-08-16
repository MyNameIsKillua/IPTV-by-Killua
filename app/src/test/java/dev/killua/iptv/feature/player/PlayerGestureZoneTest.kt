package dev.killua.iptv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureZoneTest {
    @Test
    fun `left third resolves to left`() {
        assertEquals(PlayerGestureZone.Left, resolvePlayerGestureZone(x = 20f, width = 300f))
    }

    @Test
    fun `middle third resolves to center`() {
        assertEquals(PlayerGestureZone.Center, resolvePlayerGestureZone(x = 150f, width = 300f))
    }

    @Test
    fun `right third resolves to right`() {
        assertEquals(PlayerGestureZone.Right, resolvePlayerGestureZone(x = 280f, width = 300f))
    }

    @Test
    fun `invalid width safely resolves to center`() {
        assertEquals(PlayerGestureZone.Center, resolvePlayerGestureZone(x = 0f, width = 0f))
    }

    @Test
    fun `third boundaries belong to center`() {
        assertEquals(PlayerGestureZone.Center, resolvePlayerGestureZone(x = 100f, width = 300f))
        assertEquals(PlayerGestureZone.Center, resolvePlayerGestureZone(x = 200f, width = 300f))
    }
}
