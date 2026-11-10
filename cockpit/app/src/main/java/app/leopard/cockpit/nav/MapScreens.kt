package app.leopard.cockpit.nav

import kotlin.math.roundToInt

/** One view on the map's screen, as Android's accessibility reports it. */
data class ScreenNode(
    /** "com.waze:id/navBarDistance", when the app names its views. */
    val id: String?,
    val text: String?,
    val desc: String?,
    val top: Int = 0,
    val left: Int = 0,
    val bottom: Int = 0,
    val right: Int = 0,
    /** The screen it's on (bounds are in that screen's pixels). */
    val display: Int = 0,
)

/**
 * Reads the next turn from what Waze or Google Maps shows on screen while it
 * guides. These are pure functions over the screen's views, so they can be
 * tested without the apps.
 */
object MapScreens {
    val WAZE = setOf("com.waze", "com.waze.preview")
    val GOOGLE_MAPS = setOf("com.google.android.apps.maps", "app.revanced.android.apps.maps")
    val PACKAGES = WAZE + GOOGLE_MAPS

    fun parse(pkg: String, nodes: List<ScreenNode>): NextTurn? = when (pkg) {
        in WAZE -> waze(pkg, nodes)
        in GOOGLE_MAPS -> googleMaps(pkg, nodes)
        else -> null
    }?.copy(speedLimit = speedLimit(nodes))

    private val LIMIT_WORDS = Regex("""speed\s*limit|حد السرعة|الحد الأقصى للسرعة|السرعة القصوى|السرعة المسموح""")
    private val LIMIT_NUMBER = Regex("""(?<!\d)(\d{2,3})(?!\d)""")

    /**
     * The speed limit sign both apps show while driving, in km/h: a view that says
     * so ("Speed limit 80 km/h", "حد السرعة ٨٠"), or one named for it that holds just
     * the number. Not the car's current speed, which sits next to it.
     */
    fun speedLimit(nodes: List<ScreenNode>): Int? {
        for (n in nodes) {
            val named = n.id?.substringAfter(":id/")?.lowercase()?.replace("_", "")?.contains("speedlimit") == true
            for (s in listOfNotNull(n.desc.clean(), n.text.clean())) {
                val t = Units.normalize(s)
                val label = LIMIT_WORDS.find(t)
                if (label == null && !(named && t.length <= 12)) continue
                val number = LIMIT_NUMBER.find(t, label?.range?.last?.plus(1) ?: 0)?.groupValues?.get(1)?.toInt() ?: continue
                val kmh = if ("mph" in t || "mile" in t) (number * 1.609344).roundToInt() else number
                if (kmh in 5..160) return kmh
            }
        }
        return null
    }

    private fun String?.clean() = this?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** Waze names the views of its top bar and its trip box. */
    fun waze(pkg: String, nodes: List<ScreenNode>): NextTurn? {
        fun view(name: String) = nodes.firstOrNull { it.id?.endsWith(":id/$name") == true }
        val distance = view("navBarDistance")?.text.clean() ?: return null
        val street = view("navBarStreetLine")?.text.clean()
        val maneuver = view("navBarDirection")?.desc.clean()?.let(Directions::maneuver) ?: Maneuver.UNKNOWN
        val time = view("lblTimeToDestination")?.text.clean()
        val left = view("lblDistanceToDestination")?.text.clean()
        return turn(
            pkg, distance, street, maneuver,
            // Waze can show the arrival time there instead of the time left.
            seconds = Units.seconds(time), metres = Units.metres(left), eta = time?.takeIf { Units.seconds(it) == null }?.let(Units::clock),
        )
    }

    /** A line that is only a distance: "600 m", "1.2 km", "٦٠٠ م". */
    private fun bareDistance(s: String) = s.length <= 14 && Units.metres(s) != null &&
        Units.normalize(s).replace(Regex("""[\d.,\s]"""), "").length <= 6

    private val TIME_LEFT = Regex("""(?i)^\s*(\d+\s*(h|hr|min)\b.*|.*\b\d+\s*(min|mins)\b.*|[\d٠-٩]+\s*(دقيقة|دقائق|د|ساعة|س)\b.*)$""")

    /**
     * Google Maps' guidance views: the banner's distance ("distance_text") and
     * street ("top_cue_text"), the step card that says the whole instruction
     * ("0 meters, Sharp right onto Al Jasra Street"), the time left, and the line
     * with the distance left and the arrival time ("5.7 km • 9:44 PM").
     */
    fun googleMaps(pkg: String, nodes: List<ScreenNode>): NextTurn? {
        fun views(name: String) = nodes.filter { it.id?.endsWith(":id/$name") == true }
        val distance = views("distance_text").firstNotNullOfOrNull { it.text.clean() }
        val street = views("top_cue_text").firstNotNullOfOrNull { it.text.clean() }
        // The current step's card; the next one waits off screen, a pixel wide.
        val sentence = views("step_instruction_container")
            .filter { it.right - it.left > 20 }
            .firstNotNullOfOrNull { it.desc.clean() }
            ?.let(::withoutDistance)
        // The first step ("Head west") has no distance; any of the three means it's guiding.
        if (distance == null && sentence == null && street == null) return googleMapsByLayout(pkg, nodes)
        val time = views("navigation_time_remaining_label").firstNotNullOfOrNull { it.text.clean() }
        val trip = nodes.firstNotNullOfOrNull { n ->
            listOfNotNull(n.text.clean(), n.desc.clean()).firstOrNull { Units.clock(it) != null && Units.metres(it) != null }
        }
        // The banner repeats the whole step ("Head west") when there's no street to name.
        val road = street?.takeIf { it != sentence } ?: sentence?.let(Directions::street)
        return turn(
            pkg, distance, road,
            sentence?.let { Directions.maneuver(Directions.head(it)) } ?: Maneuver.UNKNOWN,
            Units.seconds(time), Units.metres(trip), Units.clock(trip),
            instruction = sentence,
        )
    }

    /** "Sharp right onto Al Jasra Street" from "0 meters, Sharp right onto Al Jasra Street". */
    private fun withoutDistance(s: String): String {
        val parts = s.split(Regex("""[,،]\s*"""), limit = 2)
        return if (parts.size == 2 && Units.metres(parts[0]) != null) parts[1] else s
    }

    /**
     * For a Google Maps version whose views have other names: goes by what they
     * say and where they are. The banner at the top holds the distance and the
     * street; the box at the bottom holds the time left, the distance left and
     * the arrival time.
     */
    private fun googleMapsByLayout(pkg: String, nodes: List<ScreenNode>): NextTurn? {
        val lines = nodes.mapNotNull { n -> (n.text.clean() ?: n.desc.clean())?.let { it to n } }
        // The banner's distance: the highest bare distance on screen.
        val (distance, dNode) = lines.filter { bareDistance(it.first) }.minByOrNull { it.second.top } ?: return null
        // The street: the next line in the banner (just below the distance, same column).
        val street = lines.asSequence()
            .filter { (s, n) ->
                n !== dNode && n.top >= dNode.top && n.top <= dNode.bottom + (dNode.bottom - dNode.top) * 3 &&
                    !bareDistance(s) && Units.clock(s) == null && Units.seconds(s) == null && s.length in 2..80 &&
                    Directions.maneuver(s).let { it == Maneuver.UNKNOWN || it == Maneuver.ARRIVE || " onto " in s || " toward" in s }
            }
            .minByOrNull { it.second.top }?.first
        // The arrow says what to do in its description ("Turn left"), when Maps gives one.
        val maneuver = lines.asSequence()
            .filter { (s, n) -> n.desc.clean() == s && n.top <= dNode.bottom + 40 && s.length <= 60 }
            .map { Directions.maneuver(it.first) }.firstOrNull { it != Maneuver.UNKNOWN }
            ?: Maneuver.UNKNOWN
        // The trip box: below the banner.
        val below = lines.filter { it.second.top > dNode.bottom }
        val time = below.firstOrNull { TIME_LEFT.matches(it.first) && Units.seconds(it.first) != null }?.first
        val tripLine = below.firstOrNull { !bareDistance(it.first) && Units.metres(it.first) != null && Units.clock(it.first) != null }?.first
            ?: below.firstOrNull { Units.clock(it.first) != null }?.first
        // Guiding always shows the time left and the arrival time below the banner; a
        // route preview, a place card or the map's scale bar doesn't.
        if (time == null || tripLine == null) return null
        val left = Units.metres(tripLine)
            ?: below.filter { bareDistance(it.first) }.maxByOrNull { it.second.top }?.first?.let(Units::metres)
        return turn(pkg, distance, street?.let(::streetOf), maneuver, Units.seconds(time), left, Units.clock(tripLine), guessed = true)
    }

    /** "Al Corniche" from "Turn left onto Al Corniche". */
    private fun streetOf(line: String) = Directions.street(line) ?: line

    private fun turn(
        pkg: String, distance: String?, street: String?, maneuver: Maneuver,
        seconds: Int?, metres: Int?, eta: String?, instruction: String? = null, guessed: Boolean = false,
    ) = NextTurn(
        pkg = pkg,
        instruction = instruction ?: Directions.sentence(maneuver, street) ?: "Continue",
        distance = distance,
        detail = null,
        maneuver = maneuver,
        arrow = null,
        street = street,
        remainingSeconds = seconds,
        remainingMetres = metres,
        eta = eta,
        fromScreen = true,
        guessed = guessed,
    )
}
