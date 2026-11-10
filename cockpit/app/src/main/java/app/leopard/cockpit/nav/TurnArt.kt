package app.leopard.cockpit.nav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.PathParser
import java.io.ByteArrayOutputStream

/**
 * Otility's turn arrows, drawn on a 24-unit grid. The Overview shows them as
 * icons; the HUD gets the same drawing as a small picture.
 */
object TurnArt {
    const val STRAIGHT = "M12 20V5M7 10l5-5 5 5"
    const val LEFT = "M17 20v-7a4 4 0 0 0-4-4H5M9 5 5 9l4 4"
    const val RIGHT = "M7 20v-7a4 4 0 0 1 4-4h8M15 5l4 4-4 4"
    const val SLIGHT_LEFT = "M15 20v-6.5L8 6.5M8 12.5v-6h6"
    const val SLIGHT_RIGHT = "M9 20v-6.5l7-7M16 12.5v-6h-6"
    const val U_TURN = "M16 20V9.5a4 4 0 0 0-8 0V17M5 14l3 3 3-3"
    const val ROUNDABOUT = "M10 21v-5M10 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8ZM14 12h6.5M18 9.5l2.5 2.5-2.5 2.5"
    const val ARRIVE = "M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11ZM12 7.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5Z"
    const val SHARP_LEFT = "M16 20V9a3.5 3.5 0 0 0-6-2.5L5.5 12M5.5 6.5V12H11"
    const val SHARP_RIGHT = "M8 20V9a3.5 3.5 0 0 1 6-2.5l4.5 5.5M18.5 6.5V12H13"
    const val U_TURN_RIGHT = "M8 20V9.5a4 4 0 0 1 8 0V17M13 14l3 3 3-3"
    // Roundabouts by their exit: the ring, the road in from below, the way out.
    const val ROUNDABOUT_STRAIGHT = "M12 22v-4.5M12 9.5a4 4 0 1 0 0 8a4 4 0 1 0 0-8ZM12 9.5V3M9.5 5.5 12 3l2.5 2.5"
    const val ROUNDABOUT_LEFT = "M14 21v-5M14 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8ZM10 12H3.5M6 9.5 3.5 12 6 14.5"
    const val ROUNDABOUT_BACK = "M14 21v-8M14 5a4 4 0 1 0 0 8a4 4 0 1 0 0-8ZM10 9v11M7.5 17.5 10 20l2.5-2.5"
    const val ROUNDABOUT_EXIT = "M12 21v-6M12 3a4 4 0 1 0 0 8M14.8 9.8 18 13M18 9v4h-4"
    const val TOLL = "M5 20V9h14v11M3.5 9h17M8 9V5.5h8V9M12 13v4"
    const val FERRY = "M4 15.5 6 19h12l2-3.5ZM6.5 15.5V11h11v4.5M12 11V5M3 21.5c1.5 0 1.5-1 3-1s1.5 1 3 1 1.5-1 3-1 1.5 1 3 1 1.5-1 3-1 1.5 1 3 1"

    fun path(m: Maneuver): String = when (m) {
        Maneuver.LEFT -> LEFT
        Maneuver.RIGHT -> RIGHT
        Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT -> SLIGHT_LEFT
        Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> SLIGHT_RIGHT
        Maneuver.SHARP_LEFT -> SHARP_LEFT
        Maneuver.SHARP_RIGHT -> SHARP_RIGHT
        Maneuver.U_TURN -> U_TURN
        Maneuver.U_TURN_RIGHT -> U_TURN_RIGHT
        Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_RIGHT -> ROUNDABOUT
        Maneuver.ROUNDABOUT_STRAIGHT -> ROUNDABOUT_STRAIGHT
        Maneuver.ROUNDABOUT_LEFT -> ROUNDABOUT_LEFT
        Maneuver.ROUNDABOUT_BACK -> ROUNDABOUT_BACK
        Maneuver.ROUNDABOUT_EXIT -> ROUNDABOUT_EXIT
        Maneuver.TOLL -> TOLL
        Maneuver.FERRY -> FERRY
        Maneuver.ARRIVE, Maneuver.WAYPOINT -> ARRIVE
        Maneuver.STRAIGHT, Maneuver.UNKNOWN -> STRAIGHT
    }

    /** The ring's drawings use a thinner line, so the ring stays open in the middle. */
    fun width(m: Maneuver): Float = when {
        m.isRoundabout -> 2.4f
        m == Maneuver.ARRIVE || m == Maneuver.WAYPOINT || m == Maneuver.TOLL || m == Maneuver.FERRY -> 2f
        else -> 2.6f
    }

    private val pngs = HashMap<Pair<Maneuver, Int>, ByteArray>()

    /** The arrow as a PNG: white strokes on a clear background, [size] pixels square. */
    fun png(m: Maneuver, size: Int): ByteArray = synchronized(pngs) {
        pngs.getOrPut(m to size) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(size / 24f, size / 24f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = width(m)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(PathParser.createPathFromPathData(path(m)), paint)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }
}
