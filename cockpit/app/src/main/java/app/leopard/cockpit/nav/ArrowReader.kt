package app.leopard.cockpit.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tells which way a map app's turn arrow points, from its picture. Waze labels
 * its arrow with nothing, neither on its screen nor in its notification, so the
 * picture is all there is.
 *
 * Waze draws its arrows white on its black card. A blue-grey ring means a
 * roundabout (the part of it you don't drive), and the way its arrow leaves the
 * ring is the exit; a red pin is the destination, a blue disc a stop on the way.
 * Any other arrow is compared with a few plain arrows drawn here (straight on,
 * left, right, keeping left or right, a U-turn either way), and the closest one wins.
 */
object ArrowReader {

    /** [argb] is [w] × [h] pixels, row by row. */
    fun read(argb: IntArray, w: Int, h: Int): Maneuver {
        if (w < 8 || h < 8 || argb.size < w * h) return Maneuver.UNKNOWN
        val white = BooleanArray(w * h)
        var whites = 0
        var rings = 0
        var reds = 0
        var blues = 0
        for (i in 0 until w * h) {
            val c = argb[i]
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            when {
                lo >= 190 -> { white[i] = true; whites++ }
                r >= 190 && g <= 150 && b <= 160 -> reds++
                b >= 170 && r <= 170 && b - r >= 60 -> blues++
                // The ring's grey leans blue (about 72, 84, 88); the soft edges of a
                // white arrow on black are plain grey, so they don't count.
                r in 40..140 && b - r in 8..30 && g >= r && b >= g && b <= 170 -> rings++
            }
        }
        val area = w * h
        return when {
            whites < area / 100 -> Maneuver.UNKNOWN
            reds >= area / 60 -> Maneuver.ARRIVE
            // Waze's stop on the way is a blue disc.
            blues >= area / 8 -> Maneuver.WAYPOINT
            rings * 12 >= whites -> ringExit(argb, white, w, h)
            else -> closest(grid(white, w, h))
        }
    }

    // ---- Roundabouts ---------------------------------------------------------

    /**
     * Which way the roundabout's exit leaves. The ring closes in a dark middle (dark
     * that can't be reached from the picture's edge): its centre is the ring's. The
     * arrow's tip is the white farthest from it, leaving out the road coming in from
     * below. Pointing right, up or left is that exit; pointing down (beside the road
     * in) is going back round. A ring with no arm off it says no exit.
     */
    private fun ringExit(argb: IntArray, white: BooleanArray, w: Int, h: Int): Maneuver {
        val dark = BooleanArray(w * h) { i ->
            val c = argb[i]
            max(c shr 16 and 0xFF, max(c shr 8 and 0xFF, c and 0xFF)) < 40
        }
        // Everything dark that the edge reaches.
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun reach(i: Int) {
            if (dark[i] && !outside[i]) { outside[i] = true; queue[tail++] = i }
        }
        for (x in 0 until w) { reach(x); reach((h - 1) * w + x) }
        for (y in 0 until h) { reach(y * w); reach(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) reach(i - 1)
            if (x < w - 1) reach(i + 1)
            if (y > 0) reach(i - w)
            if (y < h - 1) reach(i + w)
        }
        var n = 0
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until w * h) if (dark[i] && !outside[i]) { n++; sx += i % w + 0.5; sy += i / w + 0.5 }
        if (n < w * h / 400) return Maneuver.ROUNDABOUT
        val cx = sx / n
        val cy = sy / n
        val r = sqrt(n / PI)
        var far = 0.0
        var tx = 0.0
        var ty = 0.0
        for (i in 0 until w * h) {
            if (!white[i]) continue
            val x = i % w + 0.5
            val y = i / w + 0.5
            // The road coming in, below the ring.
            if (abs(x - cx) < r * 0.9 && y > cy + r) continue
            val d = hypot(x - cx, y - cy)
            if (d > far) { far = d; tx = x; ty = y }
        }
        if (far < r * 2.2) return Maneuver.ROUNDABOUT
        val angle = (Math.toDegrees(atan2(cy - ty, tx - cx)) + 360) % 360
        return when {
            angle < 45 || angle >= 315 -> Maneuver.ROUNDABOUT_RIGHT
            angle < 135 -> Maneuver.ROUNDABOUT_STRAIGHT
            angle < 215 -> Maneuver.ROUNDABOUT_LEFT
            else -> Maneuver.ROUNDABOUT_BACK
        }
    }

    // ---- The plain arrows ----------------------------------------------------

    private const val N = 24
    private const val DRAW = 96

    private class Shape(val maneuver: Maneuver, val grid: FloatArray)

    /** Points from (0, 0) at the top left to (1, 1) at the bottom right. */
    private class Arrow(val line: List<Pair<Float, Float>>)

    private fun arc(cx: Float, cy: Float, r: Float, from: Float, to: Float): List<Pair<Float, Float>> =
        (0..12).map { k ->
            val a = (from + (to - from) * k / 12) * PI.toFloat() / 180
            cx + r * cos(a) to cy - r * sin(a)
        }

    private val shapes: List<Shape> by lazy {
        fun right(): List<Pair<Float, Float>> =
            listOf(0.3f to 0.95f, 0.3f to 0.55f) + arc(0.6f, 0.55f, 0.3f, 180f, 90f) + listOf(0.85f to 0.25f)
        fun keepRight(): List<Pair<Float, Float>> =
            listOf(0.4f to 0.95f, 0.4f to 0.6f) + arc(0.75f, 0.6f, 0.35f, 180f, 135f) + listOf(0.82f to 0.08f)
        fun uTurn(): List<Pair<Float, Float>> =
            listOf(0.75f to 0.95f, 0.75f to 0.4f) + arc(0.5f, 0.4f, 0.25f, 0f, 180f) + listOf(0.25f to 0.92f)
        fun mirror(l: List<Pair<Float, Float>>) = l.map { (x, y) -> 1 - x to y }
        listOf(
            Maneuver.STRAIGHT to listOf(0.5f to 0.95f, 0.5f to 0.05f),
            Maneuver.RIGHT to right(),
            Maneuver.LEFT to mirror(right()),
            // Waze has no slight turns: this is its exit (or fork) to one side.
            Maneuver.KEEP_RIGHT to keepRight(),
            Maneuver.KEEP_LEFT to mirror(keepRight()),
            Maneuver.U_TURN to uTurn(),
            // Where traffic keeps left, the U-turn swings the other way.
            Maneuver.U_TURN_RIGHT to mirror(uTurn()),
        ).map { (m, line) -> Shape(m, grid(draw(Arrow(line)), DRAW, DRAW)) }
    }

    /** The arrow as Waze draws it: a round stroke with a head at the end. */
    private fun draw(a: Arrow): BooleanArray {
        val out = BooleanArray(DRAW * DRAW)
        val half = 0.055f
        val pts = a.line
        val (ex, ey) = pts.last()
        val (px, py) = pts[pts.size - 2]
        val len = hypot(ex - px, ey - py)
        val dx = (ex - px) / len
        val dy = (ey - py) / len
        // The head: a triangle around the line's end.
        val tip = ex + dx * 0.06f to ey + dy * 0.06f
        val baseL = ex - dx * 0.12f - dy * 0.14f to ey - dy * 0.12f + dx * 0.14f
        val baseR = ex - dx * 0.12f + dy * 0.14f to ey - dy * 0.12f - dx * 0.14f
        for (j in 0 until DRAW) for (i in 0 until DRAW) {
            val x = (i + 0.5f) / DRAW
            val y = (j + 0.5f) / DRAW
            var near = false
            for (k in 0 until pts.size - 1) {
                if (distance(x, y, pts[k], pts[k + 1]) <= half) { near = true; break }
            }
            if (near || inside(x, y, tip, baseL, baseR)) out[j * DRAW + i] = true
        }
        return out
    }

    private fun distance(x: Float, y: Float, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val vx = b.first - a.first
        val vy = b.second - a.second
        val l2 = vx * vx + vy * vy
        val t = if (l2 == 0f) 0f else (((x - a.first) * vx + (y - a.second) * vy) / l2).coerceIn(0f, 1f)
        return hypot(x - (a.first + t * vx), y - (a.second + t * vy))
    }

    private fun inside(x: Float, y: Float, a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>): Boolean {
        fun side(p: Pair<Float, Float>, q: Pair<Float, Float>) =
            (q.first - p.first) * (y - p.second) - (q.second - p.second) * (x - p.first)
        val s1 = side(a, b)
        val s2 = side(b, c)
        val s3 = side(c, a)
        return (s1 >= 0 && s2 >= 0 && s3 >= 0) || (s1 <= 0 && s2 <= 0 && s3 <= 0)
    }

    // ---- Comparing ------------------------------------------------------------

    /**
     * The white part in an [N] × [N] grid of how much of each cell is white, in a
     * square around it (so a tall straight arrow stays thin).
     */
    private fun grid(mask: BooleanArray, w: Int, h: Int): FloatArray {
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) {
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        val out = FloatArray(N * N)
        if (x1 < 0) return out
        val side = max(x1 - x0 + 1, y1 - y0 + 1) * 1.08f
        val cx = (x0 + x1 + 1) / 2f
        val cy = (y0 + y1 + 1) / 2f
        val left = cx - side / 2
        val top = cy - side / 2
        val counts = IntArray(N * N)
        val totals = IntArray(N * N)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = ((x + 0.5f - left) / side * N).toInt()
            val gy = ((y + 0.5f - top) / side * N).toInt()
            if (gx !in 0 until N || gy !in 0 until N) continue
            val k = gy * N + gx
            totals[k]++
            if (mask[y * w + x]) counts[k]++
        }
        for (k in 0 until N * N) out[k] = if (totals[k] == 0) 0f else counts[k].toFloat() / totals[k]
        return out
    }

    /** How alike two grids are, from 0 to 1. */
    private fun alike(a: FloatArray, b: FloatArray): Float {
        var ab = 0f
        var aa = 0f
        var bb = 0f
        for (k in a.indices) {
            ab += a[k] * b[k]
            aa += a[k] * a[k]
            bb += b[k] * b[k]
        }
        return if (aa == 0f || bb == 0f) 0f else ab / sqrt(aa * bb)
    }

    /** Below this, the picture isn't any of the arrows (a lane picture, a sign). */
    private const val ENOUGH = 0.55f

    private fun closest(g: FloatArray): Maneuver {
        var best = Maneuver.UNKNOWN
        var score = ENOUGH
        for (s in shapes) {
            val a = alike(g, s.grid)
            if (a > score) { score = a; best = s.maneuver }
        }
        return best
    }

    /** For tests and tuning: how alike the picture is to each plain arrow. */
    internal fun scores(argb: IntArray, w: Int, h: Int): List<Pair<Maneuver, Float>> {
        val white = BooleanArray(w * h) { i ->
            val c = argb[i]
            min(c shr 16 and 0xFF, min(c shr 8 and 0xFF, c and 0xFF)) >= 190
        }
        val g = grid(white, w, h)
        return shapes.map { it.maneuver to alike(g, it.grid) }
    }
}
