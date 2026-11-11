package app.leopard.cockpit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedGlideTest {

    /** The speedometer's next whole km/h is eased into, not jumped to. */
    @Test
    fun aWholeKmhIsAGlideNotAStep() {
        val g = SpeedGlide()
        assertEquals(15f, g.next(15f, 0, SpeedGlide.WHOLE), 0f)
        val soon = g.next(16f, 100, SpeedGlide.WHOLE)
        assertTrue("after 0.1 s: $soon", soon in 15.05f..15.3f)
        var t = 100L
        var v = soon
        while (t < 2_500) {
            t += 40
            v = g.next(16f, t, SpeedGlide.WHOLE)
        }
        assertEquals(16f, v, 0.05f)
    }

    /** The car's finer speed is followed more closely. */
    @Test
    fun theFineSpeedIsFollowedCloser() {
        val whole = SpeedGlide().apply { next(15f, 0, SpeedGlide.WHOLE) }.next(16f, 200, SpeedGlide.WHOLE)
        val fine = SpeedGlide().apply { next(15f, 0, SpeedGlide.FINE) }.next(16f, 200, SpeedGlide.FINE)
        assertTrue("fine $fine vs whole $whole", fine > whole + 0.2f)
    }

    /** A long gap (the sound off and on) doesn't swing past the reading. */
    @Test
    fun aLongGapLandsOnTheReading() {
        val g = SpeedGlide()
        g.next(0f, 0, SpeedGlide.WHOLE)
        assertEquals(50f, g.next(50f, 60_000, SpeedGlide.WHOLE), 0.5f)
        g.reset()
        assertEquals(20f, g.next(20f, 60_100, SpeedGlide.WHOLE), 0f)
    }
}
