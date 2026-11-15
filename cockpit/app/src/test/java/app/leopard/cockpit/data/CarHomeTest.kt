package app.leopard.cockpit.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHomeTest {
    private val car = listOf("settings", "maps", "music", "radio", "phone")

    @Test
    fun arrangeKeepsTheCarsOrderUntilMoved() {
        assertEquals(car, CarHome().arrange(car))
    }

    @Test
    fun arrangePutsTheDriversOrderFirstAndNewAppsAfter() {
        val home = CarHome(order = listOf("radio", "settings", "gone"))
        // "gone" isn't in the car any more; the rest follow in the car's order.
        assertEquals(listOf("radio", "settings", "maps", "music", "phone"), home.arrange(car))
    }

    @Test
    fun hiddenAppsAreLeftOut() {
        val home = CarHome(order = listOf("music", "maps"), hidden = setOf("maps", "phone"))
        assertEquals(listOf("music", "settings", "radio"), home.arrange(car))
    }

    @Test
    fun moveTakesTheShownOrder() {
        val shown = CarHome().arrange(car)
        val moved = CarHome().move(shown, "phone", 1)
        assertEquals(listOf("settings", "phone", "maps", "music", "radio"), moved.arrange(car))
        // Past the end: last.
        assertEquals("maps", CarHome().move(shown, "maps", 99).arrange(car).last())
        // Not shown: nothing changes.
        val home = CarHome()
        assertSame(home, home.move(shown, "nope", 0))
    }

    @Test
    fun settingsCantBeHidden() {
        assertEquals(CarHome(), CarHome().hide("settings"))
        assertTrue("maps" in CarHome().hide("maps").hidden)
    }

    @Test
    fun shownAgainGoesAtTheEnd() {
        val home = CarHome(order = listOf("maps", "music", "radio"), hidden = setOf("maps"))
        val back = home.show("maps", car)
        assertFalse("maps" in back.hidden)
        assertEquals("maps", back.arrange(car).last())
    }

    @Test
    fun toggleAddsAndRemovesButNeverEmptiesAStack() {
        val home = CarHome(top = listOf(DashCard.NOW_PLAYING), bottom = listOf(DashCard.NAVIGATION))
        val added = home.toggle(true, DashCard.ENERGY)
        assertEquals(listOf(DashCard.NOW_PLAYING, DashCard.ENERGY), added.top)
        assertEquals(listOf(DashCard.NOW_PLAYING), added.toggle(true, DashCard.ENERGY).top)
        // The only card: stays.
        assertEquals(home, home.toggle(true, DashCard.NOW_PLAYING))
        assertFalse(home.canToggle(true, DashCard.NOW_PLAYING))
    }

    @Test
    fun aCardFromTheOtherStackMoves() {
        val home = CarHome(top = listOf(DashCard.NOW_PLAYING), bottom = listOf(DashCard.NAVIGATION, DashCard.CLIMATE))
        val moved = home.toggle(true, DashCard.CLIMATE)
        assertEquals(listOf(DashCard.NOW_PLAYING, DashCard.CLIMATE), moved.top)
        assertEquals(listOf(DashCard.NAVIGATION), moved.bottom)
        // The other stack's only card can't come over.
        assertFalse(moved.canToggle(true, DashCard.NAVIGATION))
    }

    @Test
    fun leadPutsACardFirst() {
        val home = CarHome(bottom = listOf(DashCard.NOTIFICATIONS, DashCard.NAVIGATION, DashCard.TYRES))
        assertEquals(listOf(DashCard.TYRES, DashCard.NOTIFICATIONS, DashCard.NAVIGATION), home.lead(false, DashCard.TYRES).bottom)
        assertEquals(home, home.lead(false, DashCard.ENERGY))
    }

    @Test
    fun jsonRoundTrips() {
        val home = CarHome(
            order = listOf("radio", "settings"),
            hidden = setOf("phone"),
            top = listOf(DashCard.NOW_PLAYING, DashCard.WEEK),
            bottom = listOf(DashCard.NAVIGATION),
        )
        assertEquals(home, CarHome.fromJson(JSONObject(home.toJson().toString())))
    }

    @Test
    fun badJsonFallsBackSafely() {
        val o = JSONObject("""{"hidden":["settings","maps"],"top":["NOPE"],"bottom":[]}""")
        val home = CarHome.fromJson(o)
        assertEquals(setOf("maps"), home.hidden)
        assertEquals(CarHome.DEFAULT_TOP, home.top)
        assertEquals(CarHome.DEFAULT_BOTTOM, home.bottom)
    }
}
