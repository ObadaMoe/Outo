package app.leopard.cockpit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.nav.TurnArt

/** Line icons in the style of the design (24-unit grid, round caps), drawn in any color. */
object Icons {
    private fun stroke(d: String, width: Float = 1.8f) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        stroke = SolidColor(Color.Black), strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

    private fun fill(d: String) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)).build()

    val overview = stroke("M4 16.5a8 8 0 1 1 16 0M12 16.5l4-5")
    val screens = stroke("M2.5 9h4.5v6H2.5zM9.5 6h5v12h-5zM17 9h4.5v6H17z")
    val apps = stroke("M6 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM15 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2ZM6 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2ZM15 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2Z")
    val settings = stroke("M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4")
    val back = stroke("M15 5l-7 7 7 7", 2.4f)
    val chevronRight = stroke("M9 5l7 7-7 7", 2.6f)
    val chevronDown = stroke("M5 9l7 7 7-7", 2.4f)
    val plus = stroke("M12 5v14M5 12h14", 2.4f)
    val minus = stroke("M5 12h14", 2.6f)
    val close = stroke("M7 7l10 10M17 7 7 17", 2.2f)
    val swap = stroke("M4 8.5h14M14.5 5l3.5 3.5-3.5 3.5M20 15.5H6M9.5 12 6 15.5 9.5 19", 2f)
    val check = stroke("M5 12.5l4.5 4.5L19 7.5", 3f)
    val energy = stroke("M13.5 2 4.5 13.5h6.5L10 22l9.5-12h-6.5Z", 2f)
    val sound = stroke("M4 9.5h3.5L12 5.5v13l-4.5-4H4ZM16 9a4 4 0 0 1 0 6M18.5 6.5a7.5 7.5 0 0 1 0 11")
    val display = stroke("M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16ZM12 4v16")
    val unlink = stroke("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1M4 4l16 16")
    val link = stroke("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val info = stroke("M12 11v6M12 7.2v.1", 2.2f)
    val navigation = fill("M12 2.8 19.2 20.4 12 16.6 4.8 20.4Z")
    val power = stroke("M12 3v8M6.3 6.3a8 8 0 1 0 11.4 0", 2f)
    val layouts = stroke("M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5ZM12 4.5v15")
    val phone = stroke("M8.5 3h7A1.5 1.5 0 0 1 17 4.5v15a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 19.5v-15A1.5 1.5 0 0 1 8.5 3ZM11 18h2")
    val tablet = stroke("M4.5 5h15A1.5 1.5 0 0 1 21 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5v-11A1.5 1.5 0 0 1 4.5 5Z")
    val trash = stroke("M4.5 7h15M10 7V4.8h4V7M6.5 7l1 13h9l1-13M10 11v5M14 11v5")
    val climate = stroke("M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9")
    val search = stroke("M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13ZM15.5 15.5 20 20", 2f)
    val pause = fill("M7.5 5h3.2a.6.6 0 0 1 .6.6v12.8a.6.6 0 0 1-.6.6H7.5a.6.6 0 0 1-.6-.6V5.6a.6.6 0 0 1 .6-.6ZM13.3 5h3.2a.6.6 0 0 1 .6.6v12.8a.6.6 0 0 1-.6.6h-3.2a.6.6 0 0 1-.6-.6V5.6a.6.6 0 0 1 .6-.6Z")
    val next = fill("M3 7.1v9.8a.7.7 0 0 0 1.1.6l7-4.9a.7.7 0 0 0 0-1.2l-7-4.9A.7.7 0 0 0 3 7.1ZM12 7.1v9.8a.7.7 0 0 0 1.1.6l7-4.9a.7.7 0 0 0 0-1.2l-7-4.9a.7.7 0 0 0-1.1.6Z")
    val previous = fill("M21 7.1v9.8a.7.7 0 0 1-1.1.6l-7-4.9a.7.7 0 0 1 0-1.2l7-4.9a.7.7 0 0 1 1.1.6ZM12 7.1v9.8a.7.7 0 0 1-1.1.6l-7-4.9a.7.7 0 0 1 0-1.2l7-4.9a.7.7 0 0 1 1.1.6Z")
    val music = stroke("M9 18.5V6.2l10-2.2v12.3M9 18.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0ZM19 16.3a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0Z")
    val play = fill("M8 5.2v13.6a.8.8 0 0 0 1.2.7l10.6-6.8a.8.8 0 0 0 0-1.4L9.2 4.5A.8.8 0 0 0 8 5.2Z")
    val stop = fill("M7 6h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1Z")
    val sendTo = stroke("M12 15V4M8 8l4-4 4 4M5 13v5.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V13", 2f)
    val star = stroke("M12 3.5l2.6 5.3 5.9.9-4.2 4.1 1 5.8-5.3-2.8-5.3 2.8 1-5.8-4.2-4.1 5.9-.9Z")
    val tire = stroke("M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17ZM12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8Z")
    val trip = stroke("M5 19a2 2 0 1 0 0-4a2 2 0 1 0 0 4ZM19 9a2 2 0 1 0 0-4a2 2 0 1 0 0 4ZM6.5 15.5 17.5 8.5")
    val gauge = stroke("M4 16.5a8 8 0 1 1 16 0M12 16.5l-3.5-4")
    val wheel = stroke("M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17ZM3.8 11.2c2.8-1.4 5.4-2 8.2-2s5.4.6 8.2 2M12 14v6.4M9.5 13.5 4.6 16M14.5 13.5l4.9 2.5")
    val passenger = stroke("M12 4a3 3 0 1 0 0 6a3 3 0 1 0 0-6ZM5.5 20c.6-3.8 3.2-6 6.5-6s5.9 2.2 6.5 6")
    val handoff = stroke("M7.5 2.8h5A1.5 1.5 0 0 1 14 4.3v15.4a1.5 1.5 0 0 1-1.5 1.5h-5A1.5 1.5 0 0 1 6 19.7V4.3A1.5 1.5 0 0 1 7.5 2.8ZM17 9l3 3-3 3M14 12h6")
    val hud = stroke("M3 14c3-5 15-5 18 0M12 7.5v4M10 9.5l2-2 2 2")
    val startup = stroke("M12 3v6M6.3 6.3a8 8 0 1 0 11.4 0M12 13l3 2", 2f)
    val keyboard = stroke("M4.5 6h15A1.5 1.5 0 0 1 21 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 16.5v-9A1.5 1.5 0 0 1 4.5 6ZM7 14.5h10M7 10h.1M10.3 10h.1M13.6 10h.1M17 10h.1")
    val home = stroke("M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1Z")
    val mirror = stroke("M3.5 7h7v10h-7zM13.5 7h7v10h-7zM12 4v16", 1.8f)
    val hand = stroke("M8 12V6.5a1.5 1.5 0 0 1 3 0V11M11 10.5V5a1.5 1.5 0 0 1 3 0v5.5M14 10.5V6.5a1.5 1.5 0 0 1 3 0V14a6 6 0 0 1-6 6h-.5a5.5 5.5 0 0 1-4.6-2.5L4 14.5a1.5 1.5 0 0 1 2.4-1.8L8 14.5")

    // Turn arrows for the next instruction (the HUD gets the same drawings).
    val turnStraight = stroke(TurnArt.STRAIGHT, 2.6f)
    val turnLeft = stroke(TurnArt.LEFT, 2.6f)
    val turnRight = stroke(TurnArt.RIGHT, 2.6f)
    val turnSlightLeft = stroke(TurnArt.SLIGHT_LEFT, 2.6f)
    val turnSlightRight = stroke(TurnArt.SLIGHT_RIGHT, 2.6f)
    val uTurn = stroke(TurnArt.U_TURN, 2.6f)
    // The ring with the road coming in below and leaving to the side (a diagonal
    // arrow off the ring read as the ♂ sign).
    val roundabout = stroke(TurnArt.ROUNDABOUT, 2.4f)
    val pin = stroke(TurnArt.ARRIVE, 2f)

    private val turns = HashMap<app.leopard.cockpit.nav.Maneuver, ImageVector>()

    /** The arrow for any turn (the HUD gets the same drawing). */
    fun turn(m: app.leopard.cockpit.nav.Maneuver): ImageVector =
        turns.getOrPut(m) { stroke(TurnArt.path(m), TurnArt.width(m)) }

    /** An outline plus a filled part, for the split-layout pictures. */
    private fun duo(outline: String, solid: String) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(outline).toNodes(),
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).addPath(pathData = PathParser().parsePathString(solid).toNodes(), fill = SolidColor(Color.Black)).build()

    private const val FRAME = "M4.5 5h15A1.5 1.5 0 0 1 21 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5v-11A1.5 1.5 0 0 1 4.5 5Z"
    val full = duo(FRAME, "M6 8h12v8H6Z")
    val leftHalf = duo(FRAME, "M6 8h5v8H6Z")
    val rightHalf = duo(FRAME, "M13 8h5v8h-5Z")
    val pip = duo(FRAME, "M12.5 11.5h5v4h-5Z")

    /** A widget's size on the Overview's grid (6 columns by 2 rows), for size menus. */
    fun widgetSize(w: Int, h: Int): ImageVector {
        val right = 6f + 12f * w.coerceIn(1, 6) / 6f
        val bottom = 8f + 8f * h.coerceIn(1, 2) / 2f
        return duo(FRAME, "M6 8H${right}V${bottom}H6Z")
    }
    val expand = stroke("M14 4h6v6M10 20H4v-6M20 4l-6.5 6.5M4 20l6.5-6.5", 2f)

    /** Home, as on CarPlay's sidebar: a rounded square. For the Overview. */
    val homeSquare = stroke("M8.4 4.6h7.2a3.8 3.8 0 0 1 3.8 3.8v7.2a3.8 3.8 0 0 1-3.8 3.8H8.4a3.8 3.8 0 0 1-3.8-3.8V8.4a3.8 3.8 0 0 1 3.8-3.8Z", 2.3f)

    /** A gear, for Settings. */
    val gear = stroke(gearPath(), 1.7f)

    /** An eight-tooth gear around a round hole, centred in the 24-unit grid. */
    private fun gearPath(teeth: Int = 8, outer: Float = 9.7f, inner: Float = 7.5f, hole: Float = 3.1f): String {
        val step = 2.0 * Math.PI / teeth
        fun pt(a: Double, r: Float) = String.format(java.util.Locale.US, "%.2f %.2f", 12 + r * kotlin.math.cos(a), 12 + r * kotlin.math.sin(a))
        val d = StringBuilder()
        for (i in 0 until teeth) {
            val a = i * step - Math.PI / 2
            d.append(if (i == 0) "M" else "L").append(pt(a - step * 0.27, inner))
            d.append("L").append(pt(a - step * 0.15, outer))
            d.append("A$outer $outer 0 0 1 ").append(pt(a + step * 0.15, outer))
            d.append("L").append(pt(a + step * 0.27, inner))
            d.append("A$inner $inner 0 0 1 ").append(pt(a + step * 0.73, inner))
        }
        d.append("Z")
        d.append("M${12 + hole} 12a$hole $hole 0 1 0 ${-2 * hole} 0a$hole $hole 0 1 0 ${2 * hole} 0Z")
        return d.toString()
    }
    val more = fill("M6 10.5a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3ZM12 10.5a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3ZM18 10.5a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3Z")
}

@Composable
fun Glyph(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}
