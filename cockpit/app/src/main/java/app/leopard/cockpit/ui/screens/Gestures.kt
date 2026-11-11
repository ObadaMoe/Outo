package app.leopard.cockpit.ui.screens

import android.graphics.Matrix
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import app.leopard.cockpit.car.CarBridge
import app.leopard.cockpit.helper.toCancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** The touches of an app's icon, for each app (the sidebar's, say). */
typealias LiftCallbacksFactory = (app.leopard.cockpit.data.AppInfo) -> LiftCallbacks

/** What the hold-and-drag gesture on an app icon reports. Positions are in root pixels. */
class LiftCallbacks(
    val onTap: () -> Unit,
    val onHold: (bounds: Rect) -> Unit = {},
    val onDragStart: (Offset) -> Unit = {},
    val onDrag: (Offset) -> Unit = {},
    val onDrop: (Offset) -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * Apple's way of handling an app icon: a quick tap opens it, holding it lifts
 * it (and shows its menu), and moving while held drags it. While the finger is
 * on it, it sinks a little and dims, as with every tap in Otility ([app.leopard.cockpit.ui.PressIndication]).
 */
@Composable
fun Modifier.liftable(key: Any?, callbacks: LiftCallbacks): Modifier {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val cb by rememberUpdatedState(callbacks)
    val haptic = LocalHapticFeedback.current
    val press = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun pressed(on: Boolean) {
        scope.launch {
            if (on) press.animateTo(1f, tween(90))
            else {
                if (press.value < 0.7f) press.animateTo(1f, tween(60))
                press.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f))
            }
        }
    }
    return this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(key) {
            fun root(p: Offset): Offset = coords[0]?.takeIf { it.isAttached }?.localToRoot(p) ?: p
            awaitEachGesture {
                val down = awaitFirstDown()
                pressed(true)
                val slop = viewConfiguration.touchSlop
                // 0 = tap, 1 = moved (a scroll, not ours), 2 = cancelled; null = held long enough.
                val early: Int? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var r = -1
                    while (r < 0) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id }
                        r = when {
                            c == null -> 2
                            c.changedToUp() -> if (c.isConsumed) 2 else 0
                            c.isConsumed -> 2
                            (c.position - down.position).getDistance() > slop -> 1
                            else -> -1
                        }
                    }
                    r
                }.also { r ->
                    // A tap shows its press; a scroll or a hold (which lifts it instead) lets go.
                    if (r == 0) pressed(false) else scope.launch { press.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f)) }
                }
                when (early) {
                    0 -> cb.onTap()
                    1, 2 -> Unit
                    null -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val bounds = coords[0]?.takeIf { it.isAttached }?.boundsInRoot() ?: Rect.Zero
                        cb.onHold(bounds)
                        var dragging = false
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                if (c == null) {
                                    if (dragging) cb.onCancel()
                                    break
                                }
                                if (c.changedToUp()) {
                                    if (dragging) cb.onDrop(root(c.position))
                                    break
                                }
                                if (!dragging && (c.position - down.position).getDistance() > slop) {
                                    dragging = true
                                    cb.onDragStart(root(c.position))
                                }
                                if (dragging) {
                                    cb.onDrag(root(c.position))
                                    c.consume()
                                }
                            }
                        } catch (e: CancellationException) {
                            if (dragging) cb.onCancel()
                            throw e
                        }
                    }
                }
            }
        }
        .graphicsLayer {
            val p = press.value.coerceIn(0f, 1f)
            val s = 1f - 0.08f * p
            scaleX = s; scaleY = s
            alpha = 1f - 0.3f * p
        }
}

/** How far in from the left or right edge a touch counts as one from the edge, in dp. */
const val EDGE_ZONE = 26f

/**
 * Sends this area's touches through [router]. Android normally saves a finger's moves
 * up and hands them over with Otility's next frame; here each one goes on to the app
 * the moment it comes, up to a frame sooner.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.routeTouches(router: TouchRouter): Modifier {
    val view = LocalView.current
    // Gone in the middle of a touch (zoomed out, say): the app has the touch ended too.
    DisposableEffect(router) { onDispose { router.cancelPress() } }
    return onSizeChanged { router.areaWidth = it.width.toFloat() }.pointerInteropFilter { ev ->
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(ev)
        router.onTouch(ev)
    }
}

/**
 * Where touches on a zoomed screen go: a rectangle of the window and the display behind
 * it. [left] and [top] are where the rectangle's picture starts on that display (a map
 * on the cluster shows only part of its own).
 */
/**
 * One finger pulled in from the left or right edge of a zoomed-in screen, followed:
 * [pulled] as it comes in ([side] -1 from the left, 1 from the right; [inward] how far in
 * and [y] where, in the area's pixels), then [released] (an [inward] of 0 when the touch
 * was taken away). [released] says whether the app under the finger gets Back.
 */
class EdgePull(
    val pulled: (side: Int, inward: Float, y: Float) -> Unit,
    val released: (side: Int, inward: Float) -> Boolean,
)

class TouchTarget(
    val rect: Rect, val displayId: () -> Int?, val width: () -> Int, val height: () -> Int,
    val left: Float = 0f, val top: Float = 0f,
)

/**
 * Passes touches on a zoomed-in screen through to the app, in that app's own
 * pixels, and keeps a few gestures for Otility:
 *
 *  - two fingers up: show the controls and the sidebar
 *  - two fingers down: back out (to all three screens)
 *  - two fingers left or right: the next screen, the way pages follow the fingers
 *  - one finger in from the left edge: Back, inside the app; with [edgePull], from
 *    either edge, followed as it comes in (a short pull is Back, a long one is the
 *    tab's: room for a second app)
 *
 * Once a gesture is Otility's, the app gets a cancel, like when a system
 * gesture takes over. So it does when Android takes the touch away from Otility
 * (a swipe for BYD's bars, a pop-up over Otility), or the area goes mid-touch:
 * otherwise a finger would stay down for the app, held (YouTube's video at 2x).
 */
class TouchRouter(
    private val bridge: CarBridge,
    private val dp: Float,
    private val onTwoUp: () -> Unit,
    private val onTwoDown: () -> Unit,
    /** -1: two fingers to the left; 1: to the right. */
    private val onTwoSide: (Int) -> Unit,
    private val onTouchedPane: (Int) -> Unit,
) {
    var targets: List<TouchTarget> = emptyList()
    var gestures: Boolean = true
    /** Pulls in from either edge, followed (see [EdgePull]); without it, only the left edge's Back. */
    var edgePull: EdgePull? = null
    /** The area's width: where its right edge is. */
    var areaWidth: Float = 0f

    private var target: TouchTarget? = null
    private var ours = false
    private var multi = 0
    private var startX = 0f
    private var startY = 0f
    private var startSpan = 0f
    private var startTime = 0L
    private var edge: MotionEvent? = null
    /** The edge [edge] is at: -1 the left, 1 the right. */
    private var edgeSide = -1
    /** A pull in from an edge is under way, from [pullFrom] (x). */
    private var pulling = false
    private var pullFrom = 0f
    /** The app's side of the touch going on: the last event it was sent, and where, while a finger is down for it. */
    private var pressed: MotionEvent? = null
    private var pressedOn = 0

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The last touch never came to an end for the app: end it before this one starts.
                edge?.recycle(); edge = null
                if (pulling) endPull(0f)
                cancelPress()
                ours = false
                multi = 0
                val i = targets.indexOfFirst { it.rect.contains(Offset(ev.x, ev.y)) }
                target = targets.getOrNull(i) ?: targets.minByOrNull { (it.rect.center - Offset(ev.x, ev.y)).getDistance() }
                if (i >= 0) onTouchedPane(i)
                val zone = EDGE_ZONE * dp
                val side = when {
                    !gestures -> 0
                    ev.x < zone -> -1
                    edgePull != null && areaWidth > 0f && ev.x > areaWidth - zone -> 1
                    else -> 0
                }
                if (side != 0) {
                    edge = MotionEvent.obtain(ev)
                    edgeSide = side
                    return true
                }
                forward(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger ends a pull (and the touch stays Otility's).
                if (pulling) { endPull(0f); return true }
                flushEdge()
                if (gestures && ev.pointerCount == 2) {
                    multi = ev.pointerCount
                    startX = cx(ev); startY = cy(ev); startSpan = span(ev)
                    startTime = ev.eventTime
                }
                if (!ours) forward(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pulling) {
                    edgePull?.pulled(edgeSide, (ev.x - pullFrom) * -edgeSide, ev.y)
                    return true
                }
                val pull = edgePull
                if (pull != null) edge?.let { down ->
                    val inward = (ev.x - down.x) * -edgeSide
                    val dy = ev.y - down.y
                    if (inward > 12 * dp && abs(dy) < inward) {
                        // In from the edge: the tab follows it; the app has none of it.
                        pullFrom = down.x
                        down.recycle(); edge = null
                        ours = true
                        pulling = true
                        pull.pulled(edgeSide, inward, ev.y)
                    } else if (hypot(ev.x - down.x, dy) > 12 * dp) {
                        flushEdge()
                        forward(ev)
                    }
                    return true
                }
                edge?.let { down ->
                    val dx = ev.x - down.x
                    val dy = ev.y - down.y
                    if (dx > 64 * dp && abs(dy) < 48 * dp) {
                        down.recycle(); edge = null
                        ours = true
                        target?.displayId?.invoke()?.let { bridge.key(KeyEvent.KEYCODE_BACK, it) }
                    } else if (hypot(dx, dy) > 12 * dp) {
                        flushEdge()
                        forward(ev)
                    }
                    return true
                }
                if (ours) return true
                if (multi >= 2 && ev.pointerCount == multi && ev.eventTime - startTime < 900) {
                    val dx = cx(ev) - startX
                    val dy = cy(ev) - startY
                    val spanChange = abs(span(ev) - startSpan)
                    val far = 70 * dp
                    if (spanChange < 60 * dp) {
                        if (abs(dy) > far && abs(dy) > abs(dx) * 1.4f) {
                            takeOver(); if (dy < 0) onTwoUp() else onTwoDown(); return true
                        }
                        if (abs(dx) > far && abs(dx) > abs(dy) * 1.4f) {
                            takeOver(); onTwoSide(if (dx < 0) -1 else 1); return true
                        }
                    }
                }
                forward(ev)
            }
            MotionEvent.ACTION_POINTER_UP -> if (!ours) forward(ev)
            MotionEvent.ACTION_UP -> {
                if (pulling) endPull((ev.x - pullFrom) * -edgeSide)
                else if (edge != null) { flushEdge(); forward(ev) } else if (!ours) forward(ev)
                ours = false; multi = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                // Not passed on as it is: Compose's own cancel (when Android takes the touch
                // away) names no device or touch screen, and an app doesn't take it as one.
                edge?.recycle(); edge = null
                if (pulling) endPull(0f)
                cancelPress()
                ours = false; multi = 0
            }
            else -> if (!ours) forward(ev)
        }
        return true
    }

    /** The pull is over, [inward] in (0: taken away): Back for the app under it, if the tab says so. */
    private fun endPull(inward: Float) {
        pulling = false
        val back = edgePull?.released(edgeSide, inward) ?: false
        if (back) target?.displayId?.invoke()?.let { bridge.key(KeyEvent.KEYCODE_BACK, it) }
    }

    private fun takeOver() {
        ours = true
        cancelPress()
    }

    /** Ends, for the app, the touch it has going on (if any) with a cancel, as from the system. */
    fun cancelPress() {
        val last = pressed ?: return
        val cancel = last.toCancel()
        send(cancel, pressedOn)
        cancel.recycle()
    }

    /** Passes an event in the app's pixels to its display, and keeps track of the touch. */
    private fun send(e: MotionEvent, display: Int) {
        bridge.inject(e, display)
        pressed?.recycle()
        pressed = when (e.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> null
            else -> MotionEvent.obtain(e).also { pressedOn = display }
        }
    }

    private fun flushEdge() {
        val down = edge ?: return
        edge = null
        forward(down)
        down.recycle()
    }

    private fun forward(ev: MotionEvent) {
        val t = target ?: return
        val id = t.displayId() ?: return
        val r = t.rect
        if (r.width <= 0f || r.height <= 0f) return
        val m = Matrix()
        m.setTranslate(-r.left, -r.top)
        m.postScale(t.width() / r.width, t.height() / r.height)
        m.postTranslate(t.left, t.top)
        val e = MotionEvent.obtain(ev)
        e.transform(m)
        send(e, id)
        e.recycle()
    }

    private fun cx(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getX(i); return s / ev.pointerCount }
    private fun cy(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getY(i); return s / ev.pointerCount }
    private fun span(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        return hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
    }
}
