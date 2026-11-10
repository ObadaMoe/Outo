package app.leopard.cockpit.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cluster's map-only frames, on the car's cluster (1920 × 720 at 320 dpi). */
class ClusterCropTest {
    private val w = 1920
    private val h = 720
    private val dpi = 320

    private fun ClusterFrame.assertInside() {
        assertTrue("picture starts left of the display: $this", left >= 0 && top >= 0)
        assertTrue("picture runs past the display: $this", left + w <= width && top + h <= height)
        assertEquals("odd width: $this", 0, width % 2)
        assertEquals("odd height: $this", 0, height % 2)
    }

    @Test
    fun googleMapsLeavesItsColumnAndButtonsOut() {
        for (pkg in listOf("com.google.android.apps.maps", "app.revanced.android.apps.maps")) {
            val f = ClusterCrop.frameFor(pkg, w, h, dpi)!!
            f.assertInside()
            assertEquals(dpi, f.dpi)
            // Its left column ends at 44.6% of the width, plus its shadow (8 dp).
            assertTrue("left column shows: $f", f.left >= f.width * 0.446f + 16)
            // Its round buttons sit in the last 80 dp.
            assertTrue("buttons show: $f", f.left + w <= f.width - 160)
            // Its "Re-center" pill, in the bottom 76 dp, stays below the picture.
            assertTrue("re-center pill shows: $f", f.top + h <= f.height - 152)
        }
    }

    @Test
    fun wazeKeepsTheCarButNotItsCards() {
        val f = ClusterCrop.frameFor("com.waze", w, h, dpi)!!
        f.assertInside()
        assertEquals(f.width - w - f.left, f.left)
        // Measured on the car: the trip card is the bottom 144 px, the turn card the
        // top 246 px, the round buttons the outer 165 px; the car is 275 px above the
        // bottom.
        assertTrue("trip card shows: $f", f.top + h <= f.height - 144)
        assertTrue("turn card shows: $f", f.top >= 246)
        assertTrue("buttons show: $f", f.left >= 165 && f.left + w <= f.width - 165)
        val car = f.height - 275 - f.top
        assertTrue("car is out of the picture: $f (car at $car)", car in 360..640)
    }

    @Test
    fun otherAppsShowAsTheyAre() {
        assertNull(ClusterCrop.frameFor("com.google.android.youtube", w, h, dpi))
        assertNull(ClusterCrop.frameFor("com.google.android.apps.maps", 0, h, dpi))
    }
}
