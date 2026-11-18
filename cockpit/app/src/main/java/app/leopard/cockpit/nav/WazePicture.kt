package app.leopard.cockpit.nav

import kotlin.math.abs

/**
 * Reads Waze's turn from the words in its picture. Newer Waze (5.27 and on) draws
 * its turn card inside its Compose screen, where Android's accessibility can't see
 * it, so the card's picture is read instead: the distance in big letters, the
 * street under it, the arrow to their left, and the trip line at the bottom
 * ("11 min • 11 km").
 *
 * Coordinates are the picture's pixels. Only the words are worked out here; taking
 * the picture and finding the words is [MapScreenReader]'s.
 */
object WazePicture {

    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height get() = bottom - top
    }

    /** What the card says: the distance line, and the street under it when there is one. */
    data class Card(val distance: Line, val street: Line?)

    /** Short words Waze writes on its card that aren't streets. */
    private val NOT_STREETS = setOf("and then", "then", "and", "go", "report", "menu", "search")

    /**
     * The card's distance ("30 m", "1.2 km") and the street right under it. The
     * card sits in the top left; map labels elsewhere in the picture are ignored by
     * asking for the street to start where the distance does, a line lower.
     */
    fun card(lines: List<Line>, width: Int): Card? {
        val distance = lines
            .filter { it.left < width * 0.6 && isDistance(it.text) }
            // The card's distance is the biggest one there.
            .maxByOrNull { it.height } ?: return null
        val h = distance.height.coerceAtLeast(8)
        val street = lines
            .filter { it !== distance }
            .filter { it.top >= distance.bottom - h / 3 && it.top <= distance.bottom + h * 2 }
            .filter { abs(it.left - distance.left) <= h * 2 }
            .filter { isStreet(it.text) }
            .minByOrNull { it.top }
        return Card(distance, street)
    }

    /** Time and distance left from the trip line: (seconds, metres). */
    fun trip(lines: List<Line>): Pair<Int?, Int?> {
        var seconds: Int? = null
        var metres: Int? = null
        for (l in lines.sortedByDescending { it.bottom }) {
            // "11 min • 11 km" comes as one line or as two.
            for (part in l.text.split('•', '·', '|')) {
                if (seconds == null) Units.seconds(part)?.let { seconds = it }
                if (metres == null && Units.seconds(part) == null) Units.metres(part)?.let { metres = it }
            }
            if (seconds != null && metres != null) break
        }
        return seconds to metres
    }

    /** A line that is only a distance: "600 m", "1.2 km". */
    fun isDistance(s: String): Boolean {
        val t = s.trim()
        if (t.length > 12 || Units.metres(t) == null) return false
        return Units.normalize(t).replace(Regex("""[\d.,\s]"""), "").length <= 6
    }

    private fun isStreet(s: String): Boolean {
        val t = s.trim()
        if (t.length < 3 || t.lowercase() in NOT_STREETS) return false
        if (Units.metres(t) != null && isDistance(t)) return false
        if (Units.seconds(t) != null) return false
        return t.any { it.isLetter() }
    }
}
