package app.leopard.cockpit.nav

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Turn arrows read from their pictures, as Waze draws them: white on its black card. */
class ArrowReaderTest {
    private val size = 140

    private class Canvas(val n: Int) {
        val px = IntArray(n * n) { BLACK }

        fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float = 14f, color: Int = WHITE) {
            for (y in 0 until n) for (x in 0 until n) {
                val vx = x1 - x0
                val vy = y1 - y0
                val t = (((x - x0) * vx + (y - y0) * vy) / (vx * vx + vy * vy)).coerceIn(0f, 1f)
                if (hypot(x - (x0 + t * vx), y - (y0 + t * vy)) <= width / 2) px[y * n + x] = color
            }
        }

        /** Counter-clockwise from [from] degrees to [to], 0 pointing right. */
        fun arc(cx: Float, cy: Float, r: Float, from: Float, to: Float, color: Int = WHITE) {
            val steps = 24
            for (k in 0 until steps) {
                val a = (from + (to - from) * k / steps) * PI.toFloat() / 180
                val b = (from + (to - from) * (k + 1) / steps) * PI.toFloat() / 180
                line(cx + r * cos(a), cy - r * sin(a), cx + r * cos(b), cy - r * sin(b), color = color)
            }
        }

        /** A head with its tip at (x, y), pointing along (dx, dy). */
        fun head(x: Float, y: Float, dx: Float, dy: Float) {
            val ax = x - dx * 26 - dy * 22
            val ay = y - dy * 26 + dx * 22
            val bx = x - dx * 26 + dy * 22
            val by = y - dy * 26 - dx * 22
            for (j in 0 until n) for (i in 0 until n) {
                fun side(px0: Float, py0: Float, px1: Float, py1: Float) = (px1 - px0) * (j - py0) - (py1 - py0) * (i - px0)
                val s1 = side(x, y, ax, ay)
                val s2 = side(ax, ay, bx, by)
                val s3 = side(bx, by, x, y)
                if ((s1 >= 0 && s2 >= 0 && s3 >= 0) || (s1 <= 0 && s2 <= 0 && s3 <= 0)) px[j * n + i] = WHITE
            }
        }

        fun disc(cx: Float, cy: Float, r: Float, color: Int) {
            for (y in 0 until n) for (x in 0 until n) if (hypot(x - cx, y - cy) <= r) px[y * n + x] = color
        }

        fun mirrored(): IntArray = IntArray(n * n) { i -> px[(i / n) * n + (n - 1 - i % n)] }
    }

    private fun read(px: IntArray) = ArrowReader.read(px, size, size)

    @Test fun straight() {
        val c = Canvas(size).apply { line(70f, 125f, 70f, 40f); head(70f, 12f, 0f, -1f) }
        assertEquals(Maneuver.STRAIGHT, read(c.px))
    }

    @Test fun rightAndLeft() {
        val c = Canvas(size).apply {
            line(40f, 125f, 40f, 80f)
            arc(80f, 80f, 40f, 180f, 90f)
            line(80f, 40f, 100f, 40f)
            head(126f, 40f, 1f, 0f)
        }
        assertEquals(Maneuver.RIGHT, read(c.px))
        assertEquals(Maneuver.LEFT, read(c.mirrored()))
    }

    @Test fun keepRightAndLeft() {
        val c = Canvas(size).apply { line(55f, 125f, 55f, 85f); line(55f, 85f, 95f, 40f); head(114f, 19f, 0.7f, -0.7f) }
        assertEquals(Maneuver.KEEP_RIGHT, read(c.px))
        assertEquals(Maneuver.KEEP_LEFT, read(c.mirrored()))
    }

    @Test fun uTurn() {
        val c = Canvas(size).apply {
            line(100f, 125f, 100f, 60f)
            arc(70f, 60f, 30f, 0f, 180f)
            line(40f, 60f, 40f, 100f)
            head(40f, 130f, 0f, 1f)
        }
        assertEquals(Maneuver.U_TURN, read(c.px))
        assertEquals(Maneuver.U_TURN_RIGHT, read(c.mirrored()))
    }

    /** A ring with the road coming in from below, round to [exit] degrees, then off it. */
    private fun ring(exit: Float): IntArray {
        val c = Canvas(size).apply {
            val cx = 70f
            val cy = 70f
            arc(cx, cy, 20f, 0f, 360f, color = GREY)
            line(cx, 132f, cx, cy + 20f)
            arc(cx, cy, 20f, 270f, if (exit <= 0f) 360f else 270f + (exit + 90f) % 360f)
            val a = Math.toRadians(exit.toDouble())
            val dx = Math.cos(a).toFloat()
            val dy = -Math.sin(a).toFloat()
            line(cx + 20f * dx, cy + 20f * dy, cx + 40f * dx, cy + 40f * dy)
            head(cx + 64f * dx, cy + 64f * dy, dx, dy)
        }
        return c.px
    }

    @Test fun roundaboutExits() {
        assertEquals(Maneuver.ROUNDABOUT_RIGHT, read(ring(0f)))
        assertEquals(Maneuver.ROUNDABOUT_STRAIGHT, read(ring(90f)))
        assertEquals(Maneuver.ROUNDABOUT_LEFT, read(ring(180f)))
        // Only the ring: which exit is in the words beside it, if anywhere.
        val plain = Canvas(size).apply { arc(70f, 70f, 30f, 0f, 360f, color = GREY); arc(70f, 70f, 30f, 200f, 320f) }
        assertEquals(Maneuver.ROUNDABOUT, read(plain.px))
    }

    @Test fun destination() {
        val flag = Canvas(size).apply {
            arc(65f, 65f, 45f, 0f, 360f)
            disc(105f, 100f, 20f, PINK)
        }
        assertEquals(Maneuver.ARRIVE, read(flag.px))
    }

    @Test fun nothingToRead() {
        assertEquals(Maneuver.UNKNOWN, read(Canvas(size).px))
    }

    /**
     * Against the map app's own pictures, when there's a folder of them in
     * build/arrow-pictures: raw square pictures (big-endian ARGB, row by row), named
     * as Waze names its pictures. Not part of the project.
     */
    @Test fun wazePictures() {
        val dir = File("build/arrow-pictures")
        assumeTrue(dir.isDirectory)
        val expected = listOf(
            "end" to Maneuver.ARRIVE, "exit_left" to Maneuver.KEEP_LEFT, "exit_right" to Maneuver.KEEP_RIGHT,
            "forward" to Maneuver.STRAIGHT, "direction_left" to Maneuver.LEFT, "direction_right" to Maneuver.RIGHT,
            "stop" to Maneuver.WAYPOINT,
            // Roundabouts by their exit; "uk" is where traffic keeps left (the ring goes the other way).
            "roundabout_r" to Maneuver.ROUNDABOUT_RIGHT, "roundabout_uk_l" to Maneuver.ROUNDABOUT_LEFT,
            "roundabout_l" to Maneuver.ROUNDABOUT_LEFT, "roundabout_s" to Maneuver.ROUNDABOUT_STRAIGHT,
            "roundabout_uk_s" to Maneuver.ROUNDABOUT_STRAIGHT,
            // The plain ring (no arm off it), before "roundabout_u" catches it.
            "roundabout_uk." to Maneuver.ROUNDABOUT, "roundabout_uk_72" to Maneuver.ROUNDABOUT,
            "roundabout_u" to Maneuver.ROUNDABOUT_BACK,
            "roundabout" to Maneuver.ROUNDABOUT,
            "uturn_uk" to Maneuver.U_TURN_RIGHT, "uturn" to Maneuver.U_TURN,
            "live_right" to Maneuver.RIGHT,
        )
        val wrong = ArrayList<String>()
        for (f in dir.listFiles { f -> f.extension == "argb" }!!.sortedBy { it.name }) {
            val want = expected.firstOrNull { f.name.contains(it.first) }?.second ?: continue
            // Square pictures of any size (Otility reads the arrow at 72 px).
            val n = Math.round(Math.sqrt(f.length() / 4.0)).toInt()
            val px = DataInputStream(f.inputStream().buffered()).use { s -> IntArray(n * n) { s.readInt() } }
            val got = ArrowReader.read(px, n, n)
            println("${f.name}: $got (want $want) " + ArrowReader.scores(px, n, n).joinToString { "${it.first}=${"%.2f".format(it.second)}" })
            if (got != want) wrong += "${f.name}: $got, not $want"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF5A6268.toInt()
        const val PINK = 0xFFFF6E6E.toInt()
    }
}
