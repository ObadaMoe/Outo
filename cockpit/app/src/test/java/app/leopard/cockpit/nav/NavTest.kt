package app.leopard.cockpit.nav

import app.leopard.cockpit.helper.Instrument
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NavTest {

    // ---- Units ---------------------------------------------------------------

    @Test fun distances() {
        assertEquals(350, Units.metres("350 m"))
        assertEquals(600, Units.metres("600 m"))
        assertEquals(5200, Units.metres("5.2 km"))
        assertEquals(5200, Units.metres("5,2 km"))
        assertEquals(5500, Units.metres("5.5 km • 9:25 PM"))
        assertEquals(600, Units.metres("٦٠٠ م"))
        assertEquals(1500, Units.metres("١٫٥ كم"))
        assertEquals(1500, Units.metres("1.5 كم"))
        assertEquals(483, Units.metres("0.3 mi"))
        assertEquals(152, Units.metres("500 ft"))
        assertEquals(350, Units.metres("In 350 m"))
        // Google Maps spells units out for screen readers.
        assertEquals(1900, Units.metres("1.9 kilometers"))
        assertEquals(0, Units.metres("0 meters"))
        assertEquals(1, Units.metres("1 meter"))
        assertEquals(1609, Units.metres("1 mile"))
        assertNull(Units.metres("13 min"))
        assertNull(Units.metres("Al Corniche"))
    }

    @Test fun timesLeft() {
        assertEquals(780, Units.seconds("13 min"))
        assertEquals(4320, Units.seconds("1 h 12 min"))
        assertEquals(3600, Units.seconds("1 hr"))
        assertEquals(5100, Units.seconds("1:25 h"))
        assertEquals(780, Units.seconds("١٣ دقيقة"))
        assertEquals(4320, Units.seconds("١ ساعة ١٢ دقيقة"))
        assertNull(Units.seconds("8:42 PM"))
        assertNull(Units.seconds("600 m"))
    }

    @Test fun clocks() {
        assertEquals("9:25 PM", Units.clock("5.5 km • 9:25 PM"))
        assertEquals("9:26 PM", Units.clock("Arrive 9:26 PM"))
        assertEquals("9:25 PM", Units.clock("٩:٢٥ م"))
        assertEquals("21:25", Units.clock("21:25"))
        assertNull(Units.clock("13 min"))
        assertNull(Units.clock("1:25 h"))
        // م after a time is "evening", not metres.
        assertNull(Units.metres("الوصول ٩:٢٦ م"))
        assertEquals(5500, Units.metres("٥٫٥ كم • ٩:٢٥ م"))
        assertEquals("21:25", Units.clock24("9:25 PM"))
        assertEquals("00:05", Units.clock24("12:05 am"))
        assertEquals("12:30", Units.clock24("12:30 PM"))
        assertEquals("08:15", Units.clock24("8:15"))
    }

    @Test fun formats() {
        assertEquals("350 m", Units.distance(355))
        assertEquals("5.5 km", Units.distance(5500))
        assertEquals("12 km", Units.distance(12_400))
        assertEquals("13 min", Units.duration(780))
        assertEquals("1 h 12 min", Units.duration(4320))
    }

    // ---- HUD frame -----------------------------------------------------------

    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test fun hudWorkedExample() {
        // Turn right, 350 m, onto Al Corniche, 12 400 m and 1 260 s left, arriving 14:35.
        val frame = HudFrame.encode(
            HudFrame.Fields(
                remainingMetres = 12_400, remainingSeconds = 1260, metres = 350, road = "Al Corniche",
                eta = "14:35", code = HudFrame.code(Maneuver.RIGHT),
            ),
        )
        val expected = hex(
            "0A 24 18 F0 60 20 EC 09 48 DE 02 52 0B 41 6C 20 43 6F 72 6E 69 63 68 65 " +
                "80 01 02 D2 01 05 31 34 3A 33 35 E0 01 02",
        )
        assertArrayEquals(expected, frame)
    }

    @Test fun hudStopFrame() {
        assertArrayEquals(hex("0A 03 80 01 01"), HudFrame.stopped)
    }

    @Test fun hudPositionAndRoute() {
        val frame = HudFrame.encode(
            HudFrame.Fields(status = 0, longitude = 51.5, latitude = 25.25, heading = 90.0, guidePoint = "p"),
        )
        // 19 and 20 (99 01, A1 01) and 32 (81 02) are little-endian doubles; 31 (FA 01) a string.
        val expected = hex(
            "0A 22 99 01 00 00 00 00 00 C0 49 40 A1 01 00 00 00 00 00 40 39 40 " +
                "FA 01 01 70 81 02 00 00 00 00 00 80 56 40",
        )
        assertArrayEquals(expected, frame)
    }

    @Test fun hudRouteShape() {
        val right = HudRoute.shape(Maneuver.RIGHT, 100)
        // The turn is 100 m due north of the start.
        assertEquals("51.5310000,25.2868983,0", right.point)
        val points = Regex("""\[(-?[\d.]+),(-?[\d.]+),0]""").findAll(right.line).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        assertEquals(8, points.size)
        assertEquals(HudRoute.START_LONGITUDE to HudRoute.START_LATITUDE, points.first())
        // After the turn the line heads east, and the turn point is on it.
        assertTrue(points.last().first > HudRoute.START_LONGITUDE)
        assertEquals(25.2868983, points[4].second, 1e-7)
        // Straight on continues north; arriving ends at the turn point.
        assertTrue(HudRoute.shape(Maneuver.STRAIGHT, 100).line.count { it == '[' } == 9)
        assertTrue(HudRoute.shape(Maneuver.ARRIVE, 100).line.endsWith("[51.5310000,25.2868983,0]]"))
    }

    @Test fun clusterArrowCodes() {
        // BYD's own codes for the cluster (and with it the HUD), tried in the car: 1003 is
        // the right arrow, 1024 the flag.
        assertEquals(1003, ClusterTurns.arrow(Maneuver.RIGHT))
        assertEquals(1007, ClusterTurns.arrow(Maneuver.LEFT))
        assertEquals(1001, ClusterTurns.arrow(Maneuver.STRAIGHT))
        assertEquals(1005, ClusterTurns.arrow(Maneuver.U_TURN))
        assertEquals(1024, ClusterTurns.arrow(Maneuver.ARRIVE))
        // Never 0: the cluster keeps one byte of the code, and 0 is the flag's.
        assertEquals(1001, ClusterTurns.arrow(Maneuver.UNKNOWN))
        // BYD's roundabout exits, by the way out.
        assertEquals(1071, ClusterTurns.arrow(Maneuver.ROUNDABOUT_RIGHT))
        assertEquals(1069, ClusterTurns.arrow(Maneuver.ROUNDABOUT_STRAIGHT))
        assertEquals(1075, ClusterTurns.arrow(Maneuver.ROUNDABOUT_LEFT))
        assertEquals(1073, ClusterTurns.arrow(Maneuver.ROUNDABOUT_BACK))
        assertEquals(1009, ClusterTurns.arrow(Maneuver.ROUNDABOUT))
        // Every turn has a code of its own kind, none of them 0.
        for (m in Maneuver.entries) assertTrue("$m", ClusterTurns.arrow(m) in 1001..1113)
    }

    @Test fun everyKindOfTurnFromWords() {
        assertEquals(Maneuver.SHARP_RIGHT, Directions.maneuver("Sharp right onto Al Jasra Street"))
        assertEquals(Maneuver.SHARP_LEFT, Directions.maneuver("Turn sharp left"))
        assertEquals(Maneuver.SLIGHT_LEFT, Directions.maneuver("Slight left toward Lusail"))
        assertEquals(Maneuver.KEEP_RIGHT, Directions.maneuver("Keep right at the fork"))
        assertEquals(Maneuver.KEEP_LEFT, Directions.maneuver("Take exit 4 on the left"))
        assertEquals(Maneuver.KEEP_RIGHT, Directions.maneuver("Take the exit toward Al Wakra"))
        assertEquals(Maneuver.ROUNDABOUT, Directions.maneuver("At the roundabout, take the 2nd exit onto Al Rayyan Rd"))
        assertEquals(Maneuver.ROUNDABOUT_LEFT, Directions.maneuver("At the roundabout, turn left onto Right Street"))
        assertEquals(Maneuver.ROUNDABOUT_STRAIGHT, Directions.maneuver("At the roundabout, continue straight"))
        assertEquals(Maneuver.ROUNDABOUT_BACK, Directions.maneuver("At the roundabout, make a U-turn"))
        assertEquals(Maneuver.ROUNDABOUT_EXIT, Directions.maneuver("Exit the roundabout onto Salwa Road"))
        assertEquals(Maneuver.U_TURN, Directions.maneuver("Make a U-turn"))
        assertEquals(Maneuver.FERRY, Directions.maneuver("Take the ferry"))
    }

    @Test fun clusterStreet() {
        assertEquals("Street 964", ClusterTurns.street("Street 964, الدوحة, بلدية الدوحة"))
        assertEquals("الكورنيش", ClusterTurns.street("الكورنيش، الدوحة"))
        assertEquals("", ClusterTurns.street(null))
        assertEquals(Instrument.ROAD_MAX, ClusterTurns.street("x".repeat(80)).length)
    }

    @Test fun clusterWriteReplies() {
        // The car answers a write with one word; a failed call with a status and a message.
        assertTrue(Instrument.wrote("Result: Parcel(00000001    '....')"))
        assertTrue(Instrument.wrote("Result: Parcel(00000000 00000001   '........')"))
        assertFalse(Instrument.wrote("Result: Parcel(ffffffec 00000000 'S.e.c.u.r.i.t.y.')"))
        assertFalse(Instrument.wrote("service: Service autoservice does not exist"))
    }

    @Test fun hudCodes() {
        assertEquals(1, HudFrame.code(Maneuver.LEFT))
        assertEquals(5, HudFrame.code(Maneuver.SLIGHT_RIGHT))
        assertEquals(9, HudFrame.code(Maneuver.U_TURN))
        assertEquals(11, HudFrame.code(Maneuver.ROUNDABOUT))
        assertEquals(0, HudFrame.code(Maneuver.UNKNOWN))
    }

    @Test fun hudArabicRoad() {
        val frame = HudFrame.encode(HudFrame.Fields(metres = 200, road = "الكورنيش"))
        val road = "الكورنيش".toByteArray(Charsets.UTF_8)
        // 48 C8 01 (200 m), then 52, the length, the UTF-8 bytes.
        val inner = byteArrayOf(0x48, 0xC8.toByte(), 0x01, 0x52, road.size.toByte()) + road + byteArrayOf(0x80.toByte(), 0x01, 0x02)
        assertArrayEquals(byteArrayOf(0x0A, inner.size.toByte()) + inner, frame)
    }

    // ---- Screens ---------------------------------------------------------------

    private fun node(id: String?, text: String?, desc: String? = null, top: Int = 0, bottom: Int = top + 40) =
        ScreenNode(id, text, desc, top, 0, bottom, 500)

    @Test fun wazeScreen() {
        val nodes = listOf(
            node("com.waze:id/navBarDirection", null, "Turn left"),
            node("com.waze:id/navBarDistance", "600 m"),
            node("com.waze:id/navBarStreetLine", "  Al Corniche "),
            node("com.waze:id/lblTimeToDestination", "13 min"),
            node("com.waze:id/lblDistanceToDestination", "5.5 km"),
        )
        val t = MapScreens.parse("com.waze", nodes)!!
        assertEquals("600 m", t.distance)
        assertEquals(600, t.metres)
        assertEquals("Al Corniche", t.street)
        assertEquals(Maneuver.LEFT, t.maneuver)
        assertEquals("Turn left onto Al Corniche", t.instruction)
        assertEquals(780, t.remainingSeconds)
        assertEquals(5500, t.remainingMetres)
        assertTrue(t.fromScreen)
        assertEquals("13 min · 5.5 km", t.trip)
    }

    @Test fun wazeShowsArrivalTime() {
        val nodes = listOf(
            node("com.waze:id/navBarDistance", "200 m"),
            node("com.waze:id/lblTimeToDestination", "9:42 PM"),
        )
        val t = MapScreens.parse("com.waze", nodes)!!
        assertNull(t.remainingSeconds)
        assertEquals("9:42 PM", t.eta)
    }

    /** Google Maps 25.36 guiding in Doha, as the reader saw it on the emulator. */
    @Test fun googleMapsScreen() {
        val p = "com.google.android.apps.maps:id/"
        fun n(id: String?, text: String?, desc: String?, l: Int, t: Int, r: Int, b: Int) = ScreenNode(id?.let { p + it }, text, desc, t, l, b, r)
        val nodes = listOf(
            n("base_compass_button", null, "Map bearing 350 degrees.", 834, 612, 930, 708),
            n(null, null, "Current speed -- kilometer per hour", 24, 853, 107, 936),
            n(null, "--", null, 24, 853, 107, 936),
            n(null, "km/h", null, 47, 900, 84, 936),
            n(null, "5.7 km  •  9:44 PM", "Distance remaining is 5.7 km, estimated time of arrival is 9:44 PM", 126, 1030, 336, 1069),
            n("navigation_time_remaining_label", "14 min", "Time until arrival is 14 min", 152, 969, 274, 1030),
            n("step_instruction_container", null, "Head toward Al Jasra Street", 12, 12, 1, 144),
            n("step_instruction_container", null, "1.9 kilometers, Sharp right onto Al Jasra Street", 13, 12, 450, 149),
            n("next_step_instruction_container", null, "Then Turn left onto Al Jasra Street", 13, 149, 200, 221),
            n("next_step_instruction", "Then  ", null, 13, 149, 200, 221),
            n("distance_text", "350 m", null, 141, 24, 342, 78),
            n("top_cue_text", "Al Jasra St", null, 141, 78, 342, 137),
        )
        val t = MapScreens.parse("com.google.android.apps.maps", nodes)!!
        assertEquals("350 m", t.distance)
        assertEquals(350, t.metres)
        assertEquals("Al Jasra St", t.street)
        assertEquals(Maneuver.SHARP_RIGHT, t.maneuver)
        assertEquals("Sharp right onto Al Jasra Street", t.instruction)
        assertEquals(840, t.remainingSeconds)
        assertEquals(5700, t.remainingMetres)
        assertEquals("9:44 PM", t.eta)
        assertEquals("14 min · 5.7 km · 9:44 PM", t.trip)
    }

    /** The route's first step: Maps shows no distance and repeats the step in the banner. */
    @Test fun googleMapsFirstStep() {
        val p = "com.google.android.apps.maps:id/"
        fun n(id: String?, text: String?, desc: String?, l: Int, t: Int, r: Int, b: Int) = ScreenNode(id?.let { p + it }, text, desc, t, l, b, r)
        val nodes = listOf(
            n(null, "6.6 km  •  9:55 PM", "Distance remaining is 6.6 km, estimated time of arrival is 9:55 PM", 126, 1031, 336, 1070),
            n("navigation_time_remaining_label", "13 min", "Time until arrival is 13 min", 153, 970, 272, 1031),
            n("step_instruction_container", null, "Head west", 12, 12, 450, 144),
            n("next_step_instruction_container", null, "Then Turn right", 12, 144, 199, 216),
            n("top_cue_text", "Head west", null, 141, 48, 342, 107),
        )
        val t = MapScreens.parse("com.google.android.apps.maps", nodes)!!
        assertNull(t.distance)
        assertEquals("Head west", t.instruction)
        assertNull(t.street)
        assertEquals("13 min · 6.6 km · 9:55 PM", t.trip)
    }

    @Test fun speedLimitSign() {
        val maps = "com.google.android.apps.maps:id/"
        // The current speed beside the sign is not the limit.
        val speed = ScreenNode(maps + "speedometer", null, "Current speed 72 kilometer per hour")
        assertNull(MapScreens.speedLimit(listOf(speed)))
        assertEquals(80, MapScreens.speedLimit(listOf(speed, ScreenNode(null, null, "Speed limit 80 km/h"))))
        assertEquals(100, MapScreens.speedLimit(listOf(ScreenNode(maps + "speed_limit_view", "100", null))))
        assertEquals(60, MapScreens.speedLimit(listOf(ScreenNode(null, "حد السرعة ٦٠ كم/س", null))))
        assertEquals(105, MapScreens.speedLimit(listOf(ScreenNode(null, null, "Speed limit 65 mph"))))
        assertEquals(120, MapScreens.speedLimit(listOf(ScreenNode("com.waze:id/speedLimitText", "120", null))))
        // A named view with something else in it isn't read as a limit.
        assertNull(MapScreens.speedLimit(listOf(ScreenNode("com.waze:id/speedLimitText", "Report a speed limit change at 300 m", null))))
    }

    @Test fun hudSpeedLimitField() {
        // Field 11 (tag 0x58), varint 80, inside the outer field 1.
        val f = HudFrame.encode(HudFrame.Fields(speedLimit = 80, status = 2))
        assertEquals(listOf(0x0A, 0x05, 0x58, 0x50, 0x80, 0x01, 0x02), f.map { it.toInt() and 0xFF })
    }

    /** A Google Maps version whose views have other names: read by layout, only while guiding. */
    @Test fun googleMapsByLayout() {
        fun n(text: String, top: Int, bottom: Int = top + 40) = ScreenNode(null, text, null, top, 0, bottom, 500)
        val guiding = listOf(n("350 m", 24, 78), n("Al Jasra St", 78, 137), n("14 min", 969, 1030), n("5.7 km  •  9:44 PM", 1030, 1069))
        val t = MapScreens.parse(maps, guiding)!!
        assertEquals("350 m", t.distance)
        assertEquals("Al Jasra St", t.street)
        assertEquals(840, t.remainingSeconds)
        assertEquals(5700, t.remainingMetres)
        assertTrue(t.guessed)
        // A route preview ("15 min (12 km)") and the map's scale bar aren't guidance.
        assertNull(MapScreens.parse(maps, listOf(n("15 min", 900), n("(12 km)", 900))))
        assertNull(MapScreens.parse(maps, listOf(n("200 m", 600))))
    }

    @Test fun googleMapsNotGuiding() {
        val nodes = listOf(ScreenNode("com.google.android.apps.maps:id/search_omnibox_text_box", "Search here", null))
        assertNull(MapScreens.parse("com.google.android.apps.maps", nodes))
    }

    @Test fun wazeNotGuiding() {
        assertNull(MapScreens.parse("com.waze", listOf(node("com.waze:id/navBarStreetLine", "Al Corniche"))))
    }

    // ---- Merging ---------------------------------------------------------------

    private fun turn(
        instruction: String, distance: String?, maneuver: Maneuver, street: String? = null,
        screen: Boolean = false, seconds: Int? = null, eta: String? = null, pkg: String = "com.google.android.apps.maps",
    ) = NextTurn(pkg, instruction, distance, null, maneuver, null, street = street, fromScreen = screen, remainingSeconds = seconds, eta = eta)

    @Test fun screenWinsAndNotificationFillsIn() {
        val note = turn("Turn left onto Al Corniche", "700 m", Maneuver.LEFT, street = "Al Corniche", eta = "9:26 PM")
        val screen = turn("Al Corniche", "600 m", Maneuver.UNKNOWN, street = "Al Corniche", screen = true, seconds = 780)
        val m = Directions.merge(note, screen)!!
        assertEquals("600 m", m.distance)
        assertEquals(Maneuver.LEFT, m.maneuver)
        assertEquals("Turn left onto Al Corniche", m.instruction)
        assertEquals(780, m.remainingSeconds)
        assertEquals("9:26 PM", m.eta)
    }

    @Test fun laggingNotificationDoesNotLend_itsTurn() {
        val note = turn("Head west", null, Maneuver.STRAIGHT)
        val screen = turn("Al Corniche", "600 m", Maneuver.UNKNOWN, street = "Al Corniche", screen = true)
        val m = Directions.merge(note, screen)!!
        assertEquals(Maneuver.UNKNOWN, m.maneuver)
        assertEquals("Al Corniche", m.instruction)
    }

    @Test fun eitherAlone() {
        val note = turn("Turn right", "300 m", Maneuver.RIGHT)
        assertEquals(note, Directions.merge(note, null))
        assertNull(Directions.merge(null, null))
        val waze = turn("Keep left", "1.2 km", Maneuver.SLIGHT_LEFT, screen = true, pkg = "com.waze")
        assertEquals(waze, Directions.merge(note, waze))
    }

    @Test fun streets() {
        assertEquals("Al Corniche", Directions.street("Turn right onto Al Corniche"))
        assertEquals("Al Corniche", Directions.street("Al Corniche"))
        assertNull(Directions.street("Turn right"))
    }

    @Test fun guessedScreenNeedsTheNotification() {
        val guessed = turn("Al Corniche", "600 m", Maneuver.UNKNOWN, screen = true).copy(guessed = true)
        assertNull(Directions.trusted(guessed, emptyList()))
        assertNull(Directions.trusted(guessed, listOf(turn("Turn left", "700 m", Maneuver.LEFT, pkg = "com.waze"))))
        assertEquals(guessed, Directions.trusted(guessed, listOf(turn("Turn left", "700 m", Maneuver.LEFT))))
        val named = turn("Al Corniche", "600 m", Maneuver.UNKNOWN, screen = true)
        assertEquals(named, Directions.trusted(named, emptyList()))
    }

    // ---- Notifications -----------------------------------------------------------

    private val maps = "com.google.android.apps.maps"

    @Test fun englishNotification() {
        val t = Directions.parse(maps, listOf("300 m", "Turn right onto Al Corniche", null), "12 min · 5.2 km · 8:42 PM")!!
        assertEquals("300 m", t.distance)
        assertEquals(300, t.metres)
        assertEquals(Maneuver.RIGHT, t.maneuver)
        assertEquals("Al Corniche", t.street)
        assertEquals(720, t.remainingSeconds)
        assertEquals(5200, t.remainingMetres)
        assertEquals("8:42 PM", t.eta)
        assertEquals("1.5 km", Directions.parse(maps, listOf("In 1.5 km", "Keep left"), null)!!.distance)
    }

    /** Arabic-Indic digits with the Arabic decimal mark: 1.5 km, not 5 km. */
    @Test fun arabicNotification() {
        val t = Directions.parse(maps, listOf("١٫٥ كم", "انعطف يمينًا إلى شارع الكورنيش"), "١٢ دقيقة · ٥٫٢ كم · ٩:٢٦ م")!!
        assertEquals("١٫٥ كم", t.distance)
        assertEquals(1500, t.metres)
        assertEquals(Maneuver.RIGHT, t.maneuver)
        assertEquals("شارع الكورنيش", t.street)
        assertEquals(720, t.remainingSeconds)
        assertEquals(5200, t.remainingMetres)
        assertEquals("9:26 PM", t.eta)
    }

    @Test fun thenLineIsNotThisTurn() {
        val t = Directions.parse(maps, listOf("300 m", "Al Corniche", "Then turn left"), null)!!
        assertEquals("Al Corniche", t.instruction)
        assertEquals(Maneuver.UNKNOWN, t.maneuver)
        assertEquals("300 m", t.distance)
    }

    @Test fun headToward() {
        val t = Directions.parse(maps, listOf("350 m", "Head toward Al Jasra Street"), null)!!
        assertEquals(Maneuver.STRAIGHT, t.maneuver)
        assertEquals("Al Jasra Street", t.street)
        assertEquals(Maneuver.UNKNOWN, Directions.maneuver("Traffic ahead"))
    }

    // ---- Places ------------------------------------------------------------------

    @Test fun wazeWithAnotherMapsLink() {
        val short = "https://maps.app.goo.gl/abc123"
        // The car couldn't follow it, but the shared text named the place: Waze searches for it.
        assertEquals(
            "https://waze.com/ul?q=Souq%20Waqif&navigate=yes",
            Navigator.uris("com.waze", Place(name = "Souq Waqif", link = short)).first(),
        )
        // Nothing to search for: only the link is left.
        assertEquals(listOf(short), Navigator.uris("com.waze", Place(name = "Place from Google Maps", link = short)))
        // Waze's own link opens as it is.
        assertEquals(
            "https://waze.com/ul/hsv8tx?navigate=yes",
            Navigator.uris("com.waze", Place(name = "Place from Waze", link = "https://waze.com/ul/hsv8tx")).first(),
        )
        assertTrue(Navigator.wazeLink("waze://?ll=25.28,51.53"))
        assertTrue(Navigator.wazeLink("https://www.waze.com/ul?q=x"))
        assertFalse(Navigator.wazeLink("https://notwaze.com/ul"))
    }

    /** The HUD gets Western digits even when the car is in Arabic. */
    @Test fun westernDigitsInArabic() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-QA-u-nu-arab"))
            assertEquals("21:26", Units.clock24("٩:٢٦ م"))
            assertTrue(Units.arrival(600).matches(Regex("[0-9]{2}:[0-9]{2}")))
            assertEquals("1.2 km", Units.distance(1234))
        } finally {
            Locale.setDefault(was)
        }
    }

    // ---- Cluster ---------------------------------------------------------------

    @Test fun clusterReplies() {
        assertEquals(2, Instrument.parseInt("Result: Parcel(00000000 00000002   '........')"))
        assertEquals(621, Instrument.parseInt("Result: Parcel(00000000 0000026d   '....m...')"))
        assertEquals(-1, Instrument.parseInt("Result: Parcel(00000000 ffffffff   '........')"))
        assertNull(Instrument.parseInt("Result: Parcel(ffffffec 00000000 '....')"))
        assertNull(Instrument.parseInt("service: Service autoservice does not exist"))
    }

    @Test fun clusterAllowsOnlyNavigationValues() {
        assertTrue(Instrument.mayWrite(1007, Instrument.TURN_ICON, 2))
        assertTrue(Instrument.mayWrite(1007, Instrument.NAV_STATUS, 2))
        assertTrue(Instrument.mayWrite(1007, Instrument.NAV_STATUS, 4))
        assertFalse(Instrument.mayWrite(1007, Instrument.NAV_STATUS, 3))
        assertFalse(Instrument.mayWrite(1023, 1276174357, 3))
        assertFalse(Instrument.mayWrite(1007, 1234, 1))
    }
}
