package app.leopard.cockpit.nav

import app.leopard.cockpit.nav.WazePicture.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WazePictureTest {

    // Waze 5.27's card as seen in the car (1080 x 830 map, picture at 0.76).
    private val card = listOf(
        Line("30 m", 103, 15, 176, 58),
        Line("Industrial Area Service Rd", 103, 58, 400, 98),
        Line("and then", 11, 128, 82, 152),
        // A map label further right.
        Line("Industrial Area Service Rd", 470, 70, 640, 95),
        Line("Street 33", 420, 190, 520, 215),
    )

    @Test fun readsDistanceAndStreet() {
        val c = WazePicture.card(card, 648)!!
        assertEquals("30 m", c.distance.text)
        assertEquals("Industrial Area Service Rd", c.street?.text)
        assertEquals(103, c.street?.left)
    }

    @Test fun kilometresAndNoStreet() {
        val c = WazePicture.card(listOf(Line("1.2 km", 100, 10, 200, 60)), 648)!!
        assertEquals("1.2 km", c.distance.text)
        assertNull(c.street)
    }

    @Test fun noCardWithoutADistance() {
        assertNull(WazePicture.card(listOf(Line("Where to?", 20, 20, 200, 60), Line("Street 33", 30, 80, 150, 100)), 648))
    }

    @Test fun ignoresDistancesOnTheRight() {
        assertNull(WazePicture.card(listOf(Line("500 m", 500, 20, 600, 60)), 648))
    }

    @Test fun tripLine() {
        assertEquals(660 to 11_000, WazePicture.trip(listOf(Line("Re-center", 60, 20, 200, 60), Line("11 min • 11 km", 220, 20, 420, 60))))
        assertEquals(4500 to 2_500, WazePicture.trip(listOf(Line("1 h 15 min", 220, 20, 330, 60), Line("2.5 km", 350, 20, 420, 60))))
    }
}
