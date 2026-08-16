package dev.killua.iptv.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The drag-to-level arithmetic, which is the part of the brightness/volume gesture that can be
 * checked without a touch stream. `GestureAwarePlayerView` itself is an Android `View` and cannot
 * run on the JVM.
 */
class PlayerLevelGestureTest {
    // 2160 wide with 300-wide bands: brightness is 0..300, volume is 1860..2160.
    @Test
    fun `a touch inside the left band dims and the right band is volume`() {
        assertThat(target(x = 10f, y = 540f)).isEqualTo(PlayerLevelTarget.Brightness)
        assertThat(target(x = 300f, y = 540f)).isEqualTo(PlayerLevelTarget.Brightness)
        assertThat(target(x = 1860f, y = 540f)).isEqualTo(PlayerLevelTarget.Volume)
        assertThat(target(x = 2150f, y = 540f)).isEqualTo(PlayerLevelTarget.Volume)
    }

    @Test
    fun `everything between the two bands is not a slider any more`() {
        // The gesture used to own each whole half, which made it far too easy to hit by accident.
        assertThat(target(x = 301f, y = 540f)).isNull()
        assertThat(target(x = 1040f, y = 540f)).isNull()
        assertThat(target(x = 1859f, y = 540f)).isNull()
    }

    @Test
    fun `a touch above or below the band is not a slider either`() {
        // The band is centred on the slider, so the corners of the picture stay free.
        assertThat(target(x = 10f, y = 20f)).isNull()
        assertThat(target(x = 10f, y = 1060f)).isNull()
        assertThat(target(x = 2070f, y = 20f)).isNull()
    }

    @Test
    fun `an unmeasured surface claims nothing rather than guessing`() {
        assertThat(resolvePlayerLevelTarget(0f, 0f, 0f, 0f, BANDS)).isNull()
        assertThat(target(x = 10f, y = 540f, bands = PlayerLevelBands(0f, 0f))).isNull()
    }

    @Test
    fun `dragging the length of the visible track covers the whole scale`() {
        // The range is the slider's own track, not the screen, so what is drawn is what is dragged.
        assertThat(playerLevelAfterDrag(0f, 500f, 500f)).isWithin(TOLERANCE).of(1f)
        assertThat(playerLevelAfterDrag(1f, -500f, 500f)).isWithin(TOLERANCE).of(0f)
    }

    @Test
    fun `a drag continues from the current level instead of jumping`() {
        // Half the track up from 40% lands at 90%, not at wherever the finger is.
        assertThat(playerLevelAfterDrag(0.4f, 250f, 500f)).isWithin(TOLERANCE).of(0.9f)
        assertThat(playerLevelAfterDrag(0.4f, -100f, 500f)).isWithin(TOLERANCE).of(0.2f)
    }

    @Test
    fun `dragging past either end clamps rather than wrapping`() {
        assertThat(playerLevelAfterDrag(0.9f, 4_000f, 500f)).isEqualTo(1f)
        assertThat(playerLevelAfterDrag(0.1f, -4_000f, 500f)).isEqualTo(0f)
    }

    @Test
    fun `an unmeasured track leaves the level alone rather than dividing by zero`() {
        assertThat(playerLevelAfterDrag(0.7f, 300f, 0f)).isWithin(TOLERANCE).of(0.7f)
    }

    @Test
    fun `the track scales with the picture instead of filling a short screen`() {
        // A landscape phone is around 340dp tall; a fixed 180dp track took most of that.
        assertThat(playerLevelTrackHeightDp(340f)).isWithin(TOLERANCE).of(108.8f)
        assertThat(playerLevelTrackHeightDp(340f)).isLessThan(340f / 2f)
    }

    @Test
    fun `the track stays usable in a small window and sane on a large one`() {
        assertThat(playerLevelTrackHeightDp(120f)).isEqualTo(76f)
        assertThat(playerLevelTrackHeightDp(0f)).isEqualTo(76f)
        assertThat(playerLevelTrackHeightDp(1_200f)).isEqualTo(132f)
    }

    @Test
    fun `the cue reports whole percent`() {
        assertThat(playerLevelPercent(0f)).isEqualTo(0)
        assertThat(playerLevelPercent(0.456f)).isEqualTo(46)
        assertThat(playerLevelPercent(1f)).isEqualTo(100)
        assertThat(playerLevelPercent(4f)).isEqualTo(100)
    }

    @Test
    fun `volume rounds to the nearest step so the top of a drag reaches maximum`() {
        assertThat(streamVolumeIndexFor(1f, 15)).isEqualTo(15)
        assertThat(streamVolumeIndexFor(0f, 15)).isEqualTo(0)
        // Truncating would leave this at 9 and make the last step unreachable by dragging.
        assertThat(streamVolumeIndexFor(0.66f, 15)).isEqualTo(10)
    }

    @Test
    fun `a device reporting no volume steps is handled rather than divided by`() {
        assertThat(streamVolumeIndexFor(0.5f, 0)).isEqualTo(0)
        assertThat(streamVolumeLevelOf(3, 0)).isEqualTo(0f)
    }

    @Test
    fun `the current volume index maps back to the level a drag starts from`() {
        assertThat(streamVolumeLevelOf(0, 15)).isEqualTo(0f)
        assertThat(streamVolumeLevelOf(15, 15)).isEqualTo(1f)
        assertThat(streamVolumeLevelOf(6, 15)).isWithin(TOLERANCE).of(0.4f)
    }

    @Test
    fun `each target names itself for the slider`() {
        assertThat(PlayerLevelTarget.Brightness.cueLabel).isEqualTo("Brightness")
        assertThat(PlayerLevelTarget.Volume.cueLabel).isEqualTo("Volume")
    }

    @Test
    fun `the indicator carries the side it belongs on and the level to draw`() {
        val indicator = PlayerLevelIndicator(PlayerLevelTarget.Volume, 0.42f)

        assertThat(indicator.target).isEqualTo(PlayerLevelTarget.Volume)
        assertThat(playerLevelPercent(indicator.level)).isEqualTo(42)
    }

    /** A landscape 2160x1080 surface with bands roughly the size the screen actually draws. */
    private fun target(
        x: Float,
        y: Float,
        bands: PlayerLevelBands = BANDS,
    ): PlayerLevelTarget? = resolvePlayerLevelTarget(
        x = x,
        y = y,
        width = 2160f,
        height = 1080f,
        bands = bands,
    )

    private companion object {
        const val TOLERANCE = 0.0001f
        val BANDS = PlayerLevelBands(widthPx = 300f, heightPx = 760f)
    }
}
