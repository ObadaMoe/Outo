package app.leopard.cockpit.helper

/**
 * The driver cluster's navigation values (BYD's instrument device in the
 * "autoservice" system service). The helper writes only these, so Otility can
 * never change anything else in the car through it.
 */
object Instrument {
    const val DEVICE = 1007

    /** Navigation mode: write 2 while guiding, 4 when done. The cluster reports 2 or 621 while in it. */
    const val NAV_STATUS = 1138753594
    /** The turn arrow, in the cluster's own codes ([arrow]), and its second copy. */
    const val TURN_ICON = 1139806224
    const val TURN_ICON_2 = 1139806256
    /** Metres to the turn; -1 = none. */
    const val TURN_METRES = 1139806232
    /** Metres left on the route (0–9 999 000); -1 = none. */
    const val LEFT_METRES = 1139810344
    const val LEFT_HOURS = 1139810320
    const val LEFT_MINUTES = 1139810328
    /** Alongside the time left: 1 in its last 30 seconds, 2 before (as BYD's map). */
    const val LEFT_EXTRA = 1139810334

    /**
     * The next street, as text. It can't be set with `service call` (it takes bytes),
     * only through the car's own instrument API, in UTF-16 as BYD's map sends it.
     */
    const val ROAD_NAME = 1140461576
    /** Longer street names are cut, so a long one can't fill the cluster's line. */
    const val ROAD_MAX = 40

    /** The highest speed limit Otility may set, in km/h. */
    const val SPEED_LIMIT_MAX = 200

    const val GUIDING = 2
    const val DONE = 4
    val IN_NAV = setOf(2, 621)

    private val VALUES = setOf(TURN_ICON, TURN_ICON_2, TURN_METRES, LEFT_METRES, LEFT_HOURS, LEFT_MINUTES, LEFT_EXTRA)

    /** Straight on: the arrow with no turn known (0 would be the flag). */
    const val NO_TURN = 1001

    /**
     * What clears the cluster's navigation (the same steps Otility takes when guiding
     * ends): out of navigation mode first, then the values, so none flashes up.
     */
    val CLEAR = listOf(
        NAV_STATUS to DONE, TURN_ICON to NO_TURN, TURN_ICON_2 to NO_TURN, TURN_METRES to -1, LEFT_METRES to -1,
        LEFT_HOURS to 0, LEFT_MINUTES to 0, LEFT_EXTRA to 0,
    )

    fun mayWrite(device: Int, feature: Int, value: Int) = device == DEVICE &&
        (feature in VALUES || (feature == NAV_STATUS && (value == GUIDING || value == DONE)))

    fun mayRead(device: Int, feature: Int) = device == DEVICE && (feature == NAV_STATUS || feature in VALUES)

    /**
     * A write went through. The car's autoservice answers a write with one word, its
     * result ("Result: Parcel(00000001 '....')"); a failed call starts with a non-zero
     * status followed by its message.
     */
    fun wrote(out: String): Boolean {
        val m = REPLY.find(out) ?: return false
        return m.groupValues[2].isEmpty() || m.groupValues[1].toLong(16) == 0L
    }

    private val REPLY = Regex("""Parcel\(\s*([0-9a-fA-F]{8})(?:\s+([0-9a-fA-F]{8}))?""")

    private val PARCEL = Regex("""Parcel\(\s*([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})""")

    /** The int in `service call`'s reply ("Result: Parcel(00000000 00000002 '........')"), after its status word. */
    fun parseInt(out: String): Int? {
        val m = PARCEL.find(out) ?: return null
        if (m.groupValues[1].toLong(16) != 0L) return null
        return m.groupValues[2].toLong(16).toInt()
    }
}
