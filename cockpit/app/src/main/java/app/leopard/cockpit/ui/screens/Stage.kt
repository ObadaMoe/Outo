package app.leopard.cockpit.ui.screens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import app.leopard.cockpit.screens.ScreenId
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The dashboard photo and where its three screens are, in photo pixels. */
object Dash {
    const val W = 1790f
    const val H = 1007f

    fun hole(s: ScreenId): Rect = when (s) {
        ScreenId.DRIVER -> Rect(67f, 439f, 67f + 479f, 439f + 169f)
        ScreenId.MAIN -> Rect(607f, 379f, 607f + 557f, 379f + 333f)
        ScreenId.PASSENGER -> Rect(1238f, 440f, 1238f + 459f, 440f + 190f)
    }

    /** Middle of the band of screens. */
    const val BAND_CENTER = (379f + 712f) / 2f

    /** The band of screens from the driver's left edge to the passenger's right. */
    const val BAND_LEFT = 67f
    const val BAND_RIGHT = 1238f + 459f

    /** The cluster and passenger screens' shape when the car hasn't told us. */
    const val SIDE_ASPECT = 1920f / 720f
}

/**
 * Lays the photo out right of the sidebar ([photoLeft] on), as large as the three
 * screens fit across that room with [pad] to spare, and the screens in the middle
 * of the part between the car's bars. Above and below them the photo is mostly
 * sky and dashboard, so that's what gets cut. All values are window pixels at the
 * zoomed-out view.
 */
@Immutable
class StageGeometry(
    val width: Float,
    val height: Float,
    private val aspects: Map<ScreenId, Float>,
    /**
     * The car's own bars over the main screen (BYD's status bar at the top, its
     * buttons at the bottom), as parts of the screen's height. Apps there stay
     * between them, so nothing of theirs is hidden underneath.
     */
    private val mainBars: Pair<Float, Float> = 0f to 0f,
    /** Where the photo starts: the sidebar stands left of it, over nothing of it. */
    private val photoLeft: Float = 0f,
    /** Room between the outer screens and the photo's edges. */
    private val pad: Float = 0f,
) {
    val scale = ((width - photoLeft - 2f * pad) / (Dash.BAND_RIGHT - Dash.BAND_LEFT)).coerceAtLeast(0.01f)
    private val left = photoLeft + pad - Dash.BAND_LEFT * scale
    private val top: Float = run {
        val photoH = Dash.H * scale
        // The part of the window the car's bars don't cover.
        val shownTop = height * mainBars.first
        val shownBottom = height * (1f - mainBars.second)
        val wanted = (shownTop + shownBottom) / 2f - Dash.BAND_CENTER * scale
        // Cut evenly where it can, but keep photo all the way between the bars.
        if (photoH >= shownBottom - shownTop) wanted.coerceIn(shownBottom - photoH, shownTop)
        else (shownTop + shownBottom - photoH) / 2f
    }

    /** The whole photo, and the part of it that's shown (right of the sidebar). */
    val photo = Rect(left, top, left + Dash.W * scale, top + Dash.H * scale)
    val photoShown = Rect(maxOf(left, photoLeft), top, left + Dash.W * scale, top + Dash.H * scale)

    fun hole(s: ScreenId): Rect = Dash.hole(s).let {
        Rect(left + it.left * scale, top + it.top * scale, left + it.right * scale, top + it.bottom * scale)
    }

    /** The screen's picture inside its hole, at the screen's real shape. */
    fun content(s: ScreenId): Rect {
        val h = hole(s)
        val aspect = aspects[s] ?: Dash.SIDE_ASPECT
        val w: Float
        val ht: Float
        if (h.width / h.height > aspect) { ht = h.height; w = ht * aspect } else { w = h.width; ht = w / aspect }
        return Rect(Offset(h.center.x - w / 2f, h.center.y - ht / 2f), androidx.compose.ui.geometry.Size(w, ht))
    }

    /**
     * Where the screen's apps go: all of it, except on the main screen, where it's
     * the part between the car's bars. Zoomed in, that's exactly between them.
     */
    fun apps(s: ScreenId): Rect {
        val c = content(s)
        if (s != ScreenId.MAIN) return c
        val (top, bottom) = mainBars
        return Rect(c.left, c.top + c.height * top, c.right, c.bottom - c.height * bottom)
    }

    val identity get() = Camera(1f, width / 2f, height / 2f, 0f)

    /** The camera that fills the window with one screen. */
    fun zoomOn(s: ScreenId): Camera {
        val c = content(s)
        return Camera(min(width / c.width, height / c.height), c.center.x, c.center.y, 1f)
    }

    override fun equals(other: Any?) =
        other is StageGeometry && other.width == width && other.height == height && other.aspects == aspects &&
            other.mainBars == mainBars && other.photoLeft == photoLeft && other.pad == pad

    override fun hashCode() =
        ((((width.hashCode() * 31 + height.hashCode()) * 31 + aspects.hashCode()) * 31 + mainBars.hashCode()) * 31 +
            photoLeft.hashCode()) * 31 + pad.hashCode()
}

/**
 * Where the stage is looked at from: [k] is the zoom, ([cx], [cy]) the stage point
 * at the middle of the window, and [z] how "zoomed in" this counts as (0 to 1,
 * used to fade the photo).
 */
@Immutable
data class Camera(val k: Float, val cx: Float, val cy: Float, val z: Float) {
    fun map(p: Offset, w: Float, h: Float) = Offset((p.x - cx) * k + w / 2f, (p.y - cy) * k + h / 2f)
    fun map(r: Rect, w: Float, h: Float) = Rect(map(r.topLeft, w, h), map(r.bottomRight, w, h))
    fun unmap(p: Offset, w: Float, h: Float) = Offset((p.x - w / 2f) / k + cx, (p.y - h / 2f) / k + cy)
}

/**
 * A camera move. The zoom changes evenly (not linearly, which feels like it
 * rushes at the end) and the screen being zoomed into glides straight to the
 * middle. Moving from one zoomed screen to another pulls back a little on the
 * way, like a camera crane.
 */
fun between(a: Camera, b: Camera, p: Float): Camera {
    val dip = if (a.z > 0.5f && b.z > 0.5f) 1f - 0.4f * sin(PI.toFloat() * p.coerceIn(0f, 1f)) else 1f
    val k = a.k * (b.k / a.k).pow(p) * dip
    val ax = if (b.k >= a.k) b.cx else a.cx
    val ay = if (b.k >= a.k) b.cy else a.cy
    val sax = (ax - a.cx) * a.k
    val say = (ay - a.cy) * a.k
    val sbx = (ax - b.cx) * b.k
    val sby = (ay - b.cy) * b.k
    val sx = sax + (sbx - sax) * p
    val sy = say + (sby - say) * p
    return Camera(k, ax - sx / k, ay - sy / k, a.z + (b.z - a.z) * p)
}

fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun Float.px() = if (isFinite()) roundToInt() else 0

/** Places an element at a rectangle given in its parent's pixels. */
fun Modifier.at(r: Rect): Modifier = at { r }

/** Same, for a rectangle that animates (read during layout only, so no recomposition). */
fun Modifier.at(r: () -> Rect): Modifier = this.layout { m, _ ->
    val rect = r()
    // A screen with no size yet (0 by 0) can make it NaN for a frame: nothing to draw then.
    val w = rect.width.px().coerceAtLeast(0)
    val h = rect.height.px().coerceAtLeast(0)
    val p = m.measure(Constraints.fixed(w, h))
    layout(w, h) { p.place(IntOffset(rect.left.px(), rect.top.px())) }
}
