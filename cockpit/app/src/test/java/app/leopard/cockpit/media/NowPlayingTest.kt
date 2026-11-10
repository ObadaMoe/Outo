package app.leopard.cockpit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
    private data class S(val pkg: String, val playing: Boolean, val hasTrack: Boolean = true, val failed: Boolean = false)

    private fun pick(list: List<S>, current: S? = null) =
        MediaChoice.pick(list, current, { a, b -> a.pkg == b.pkg }, { it.playing }, { it.hasTrack && !it.failed }, { it.hasTrack })

    @Test
    fun aFailedPlayStaysOnlyIfAlreadyShown() {
        val failed = S("youtube", playing = false, failed = true)
        // Play pressed on the card and the app refused: the card keeps it.
        assertEquals(failed, pick(listOf(failed), current = failed))
        // But one that failed elsewhere doesn't turn up on its own.
        assertNull(pick(listOf(failed)))
    }

    @Test
    fun aPlayingAppWinsOverAPausedOne() {
        val paused = S("spotify", playing = false)
        val playing = S("ytmusic", playing = true)
        assertEquals(playing, pick(listOf(paused, playing)))
    }

    @Test
    fun pausedStaysShownSoItCanBeContinued() {
        val a = S("spotify", playing = false)
        val b = S("ytmusic", playing = false)
        // Android lists b first now, but a is on the card: it stays.
        assertEquals(a, pick(listOf(b, a), current = a))
        assertEquals(b, pick(listOf(b, a)))
    }

    @Test
    fun nothingWithoutATrack() {
        assertNull(pick(listOf(S("radio", playing = true, hasTrack = false))))
        assertNull(pick(emptyList()))
    }

    @Test
    fun positionMovesOnOnlyWhilePlaying() {
        val t = Track("p", "Song", null, null, null, playing = true, positionMs = 10_000, at = 1_000, speed = 1f,
            durationMs = 60_000, canPrevious = true, canNext = true)
        assertEquals(15_000, t.positionAt(6_000))
        assertEquals(10_000, t.copy(playing = false).positionAt(6_000))
        // Never past the end.
        assertEquals(60_000, t.positionAt(1_000_000))
        // Buffering shows as playing but stays put.
        assertEquals(10_000, t.copy(speed = 0f).positionAt(6_000))
    }

    @Test
    fun positionMovesAtTheAppsOwnSpeed() {
        // Playing: the app's speed; one that left it at 0 still plays at 1.
        assertEquals(1.5f, movingSpeed(STATE_PLAYING, 1.5f))
        assertEquals(1f, movingSpeed(STATE_PLAYING, 0f))
        assertEquals(1f, movingSpeed(STATE_PLAYING, null))
        // Buffering, connecting or skipping: still, whatever speed it reports.
        assertEquals(0f, movingSpeed(STATE_BUFFERING, 1f))
        assertEquals(0f, movingSpeed(STATE_CONNECTING, 0f))
        assertEquals(0f, movingSpeed(STATE_SKIPPING_TO_NEXT, 1f))
        // Rewinding goes back.
        assertEquals(-2f, movingSpeed(STATE_REWINDING, -2f))
        val rewinding = Track("p", "Song", null, null, null, playing = true, positionMs = 10_000, at = 1_000,
            speed = movingSpeed(STATE_REWINDING, -2f), durationMs = 60_000, canPrevious = true, canNext = true)
        assertEquals(0, rewinding.positionAt(6_000))
    }

    // PlaybackState's values (the unit tests run without Android's own classes).
    private companion object {
        const val STATE_CONNECTING = 8
        const val STATE_PLAYING = 3
        const val STATE_BUFFERING = 6
        const val STATE_REWINDING = 5
        const val STATE_SKIPPING_TO_NEXT = 10
    }

    @Test
    fun artworkColour() {
        val red = IntArray(100) { 0xFFE02020.toInt() }
        val c = MediaColor.accentOf(red)!!
        val r = c shr 16 and 255
        val g = c shr 8 and 255
        val b = c and 255
        assertTrue("reddish: $r $g $b", r > g * 2 && r > b * 2)
        // Kept to a middling brightness, so white text reads on it.
        assertTrue(r in 100..190)
        // Black, white and greys give no colour.
        val grey = IntArray(100) { i -> if (i % 2 == 0) 0xFF000000.toInt() else 0xFFCCCCCC.toInt() }
        assertNull(MediaColor.accentOf(grey))
    }
}
