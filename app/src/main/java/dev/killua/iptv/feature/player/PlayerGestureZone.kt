package dev.killua.iptv.feature.player

internal enum class PlayerGestureZone {
    Left,
    Center,
    Right,
}

internal fun resolvePlayerGestureZone(x: Float, width: Float): PlayerGestureZone {
    if (width <= 0f) return PlayerGestureZone.Center
    return when {
        x < width / 3f -> PlayerGestureZone.Left
        x > width * 2f / 3f -> PlayerGestureZone.Right
        else -> PlayerGestureZone.Center
    }
}
