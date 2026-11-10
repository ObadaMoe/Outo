package app.leopard.cockpit.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadLimitTest {
    @Test
    fun theMapsLimitWhileTheCameraHasNone() {
        val r = RoadLimit()
        assertEquals(80, r.update(80, 80, null, 1_000))
        assertFalse(r.fromCamera)
    }

    @Test
    fun atTheSameMomentTheMapWins() {
        val r = RoadLimit()
        // Navigation starts with the camera already holding a sign.
        assertEquals(80, r.update(80, 80, 60, 1_000))
    }

    @Test
    fun aNewCameraSignTakesOverUntilTheMapSpeaksAgain() {
        val r = RoadLimit()
        r.update(80, 80, null, 1_000)
        assertEquals(60, r.update(80, 80, 60, 2_000))
        assertTrue(r.fromCamera)
        // The map's sign stays up with the same limit: still the camera's.
        assertEquals(60, r.update(80, 80, 60, 10_000))
        // A new limit from the map: the map's.
        assertEquals(100, r.update(100, 100, 60, 11_000))
        assertFalse(r.fromCamera)
    }

    @Test
    fun theMapsSignBackAfterAWhileHiddenCountsAsSpeakingAgain() {
        val r = RoadLimit()
        r.update(80, 80, null, 1_000)
        r.update(80, 80, 60, 2_000)
        // Hidden (the limit is held), then shown again.
        assertEquals(60, r.update(null, 80, 60, 3_000))
        assertEquals(60, r.update(null, 80, 60, 5_000))
        assertEquals(80, r.update(80, 80, 60, 3_000 + RoadLimit.REAPPEAR_MS))
        assertFalse(r.fromCamera)
    }

    @Test
    fun aMomentaryFlickerOfTheMapsSignIsntIt() {
        val r = RoadLimit()
        r.update(80, 80, null, 1_000)
        r.update(80, 80, 60, 2_000)
        r.update(null, 80, 60, 3_000)
        assertEquals(60, r.update(80, 80, 60, 3_500))
    }

    @Test
    fun theCameraLosingItsSignGivesTheMapBack() {
        val r = RoadLimit()
        r.update(80, 80, null, 1_000)
        r.update(80, 80, 60, 2_000)
        assertEquals(80, r.update(80, 80, null, 3_000))
        // The same sign read again later is a new reading.
        assertEquals(60, r.update(80, 80, 60, 4_000))
    }

    @Test
    fun noMapLimitMeansNothingSent() {
        val r = RoadLimit()
        r.update(80, 80, 60, 1_000)
        assertEquals(0, r.update(null, 0, 60, 2_000))
        assertEquals(0, r.current)
    }
}
