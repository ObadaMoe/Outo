package app.leopard.cockpit.data

/**
 * Tells a finished icon (a logo already on its own square or rounded-square plate,
 * as the car's themed icons are) from a bare logo with see-through space around it.
 * A finished icon fills Otility's icon shape as it is; a bare logo goes on a white
 * tile, as on iPhone. Works on ARGB pixels so it can be tested without Android.
 */
object IconPlate {
    /**
     * The plate's box as left, top, right, bottom (right and bottom exclusive), or
     * null for a bare logo. A plate is nearly square, takes up most of the picture,
     * and is solid across nearly all of its box: a rounded square is, a circle
     * (about 79%) or a loose logo isn't.
     */
    fun find(argb: IntArray, size: Int): IntArray? {
        var left = size
        var top = size
        var right = -1
        var bottom = -1
        for (y in 0 until size) for (x in 0 until size) {
            if (alpha(argb[y * size + x]) > 32) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return null
        val w = right - left + 1
        val h = bottom - top + 1
        if (w < size * 0.6f || h < size * 0.6f) return null
        if (w.toFloat() / h !in 0.9f..1.1f) return null
        var solid = 0
        for (y in top..bottom) for (x in left..right) if (alpha(argb[y * size + x]) > 200) solid++
        if (solid.toFloat() / (w * h) < 0.88f) return null
        return intArrayOf(left, top, right + 1, bottom + 1)
    }

    /**
     * The plate's colour along its edges: the middle of each side, a little way in,
     * averaged. It fills the corners that Otility's shape keeps but the plate's own
     * rounder corners leave see-through.
     */
    fun edgeColor(argb: IntArray, size: Int, box: IntArray): Int {
        val (l, t, r, b) = box.toList()
        val inset = maxOf(2, (r - l) / 20)
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val points = listOf(cx to t + inset, cx to b - 1 - inset, l + inset to cy, r - 1 - inset to cy)
        var red = 0
        var green = 0
        var blue = 0
        for ((x, y) in points) {
            val c = argb[y * size + x]
            red += c shr 16 and 255
            green += c shr 8 and 255
            blue += c and 255
        }
        val n = points.size
        return (0xFF shl 24) or (red / n shl 16) or (green / n shl 8) or (blue / n)
    }

    private fun alpha(c: Int) = c ushr 24
}
