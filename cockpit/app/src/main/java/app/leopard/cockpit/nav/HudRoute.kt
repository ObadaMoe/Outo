package app.leopard.cockpit.nav

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The route shape the windshield display gets with every turn. BYD's own map
 * always sends where the car is, the road ahead through the next turn and the
 * turn's point, and the head-up display seems to need them before it draws a
 * turn. Waze and Google Maps don't share their route, so Otility draws a simple
 * one that agrees with the turn: straight ahead for the distance to it, then off
 * in the turn's direction. It starts from a fixed point and heads north; only the
 * shape matters, not where on the map it is.
 */
object HudRoute {
    /** Where the drawn route starts: Doha. */
    const val START_LONGITUDE = 51.531
    const val START_LATITUDE = 25.286

    /** How far the line goes on past the turn, in metres. */
    private const val AFTER = 60.0
    /** Metres in one degree of latitude. */
    private const val METRES_PER_DEGREE = 111_320.0

    class Shape(val line: String, val point: String)

    fun shape(maneuver: Maneuver, metres: Int?): Shape {
        val d = (metres ?: 200).coerceIn(10, 5_000).toDouble()
        // Metres east (x) and north (y) of the car.
        val points = ArrayList<Pair<Double, Double>>()
        for (i in 0..4) points += 0.0 to d * i / 4
        when (maneuver) {
            // Right-hand traffic (Qatar): a U-turn swings left and comes back on the far side.
            Maneuver.U_TURN, Maneuver.ROUNDABOUT_BACK, Maneuver.U_TURN_RIGHT -> {
                val side = if (maneuver == Maneuver.U_TURN_RIGHT) 1.0 else -1.0
                points += side * 6 to d + 6
                points += side * 12 to d
                points += side * 12 to d - AFTER
            }
            Maneuver.ARRIVE, Maneuver.WAYPOINT -> {}
            else -> {
                val bearing = when (maneuver) {
                    Maneuver.LEFT, Maneuver.ROUNDABOUT_LEFT -> -90.0
                    Maneuver.RIGHT, Maneuver.ROUNDABOUT_RIGHT -> 90.0
                    Maneuver.SLIGHT_LEFT -> -45.0
                    Maneuver.SLIGHT_RIGHT -> 45.0
                    Maneuver.KEEP_LEFT -> -25.0
                    Maneuver.KEEP_RIGHT -> 25.0
                    Maneuver.SHARP_LEFT -> -135.0
                    Maneuver.SHARP_RIGHT -> 135.0
                    else -> 0.0
                } * PI / 180
                for (k in 1..3) {
                    val r = AFTER * k / 3
                    points += sin(bearing) * r to d + cos(bearing) * r
                }
            }
        }
        val line = points.joinToString(",", "[", "]") { (x, y) -> "[${lon(x)},${lat(y)},0]" }
        return Shape(line, "${lon(0.0)},${lat(d)},0")
    }

    private fun lat(north: Double) = degrees(START_LATITUDE + north / METRES_PER_DEGREE)

    private fun lon(east: Double) =
        degrees(START_LONGITUDE + east / (METRES_PER_DEGREE * cos(START_LATITUDE * PI / 180)))

    private fun degrees(v: Double) = String.format(Locale.ROOT, "%.7f", v)
}
