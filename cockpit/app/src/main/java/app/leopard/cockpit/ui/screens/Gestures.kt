package app.leopard.cockpit.ui.screens

import android.graphics.Matrix
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import app.leopard.cockpit.car.CarBridge
import kotlinx.coroutines.CancellationException
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
 * it (and shows its menu), and moving while held drags it.
 */
@Composable
fun Modifier.liftable(key: Any?, callbacks: LiftCallbacks): Modifier {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val cb by rememberUpdatedState(callbacks)
    val haptic = LocalHapticFeedback.current
    return this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(key) {
            fun root(p: Offset): Offset = coords[0]?.takeIf { it.isAttached }?.localToRoot(p) ?: p
            awaitEachGesture {
                val down = awaitFirstDown()
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
}

/**
 * Sends this area's touches through [router]. Android normally saves a finger's moves
 * up and hands them over with Otility's next frame; here each one goes on to the app
 * the moment it comes, up to a frame sooner.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.routeTouches(router: TouchRouter): Modifier {
    val view = LocalView.current
    return pointerInteropFilter { ev ->
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(ev)
        router.onTouch(ev)
    }
}

/**
 * Where touches on a zoomed screen go: a rectangle of the window and the display behind
 * it. [left] and [top] are where the rectangle's picture starts on that display (a map
 * on the cluster shows only part of its own).
 */
class TouchTarget(
    val rect: Rect, val displayId: () -> Int?, val width: () -> Int, val height: () -> Int,
    val left: Float = 0f, val top: Float = 0f,
)

/**
 * Passes touches on a zoomed-in screen through to the app, in that app's own
 * pixels, and keeps a few gestures for Otility:
 *
 *  - two fingers up: show the controls and the dock
 *  - two fingers left: zoom back out
 *  - three fingers left or right: the next screen
 *  - one finger in from the left edge: Back, inside the app
 *
 * Once a gesture is Otility's, the app gets a cancel, like when a system
 * gesture takes over.
 */
class TouchRouter(
    private val bridge: CarBridge,
    private val dp: Float,
    private val onTwoUp: () -> Unit,
    private val onTwoLeft: () -> Unit,
    private val onThree: (Int) -> Unit,
    private val onTouchedPane: (Int) -> Unit,
) {
    var targets: List<TouchTarget> = emptyList()
    var gestures: Boolean = true

    private var target: TouchTarget? = null
    private var ours = false
    private var multi = 0
    private var startX = 0f
    private var startY = 0f
    private var startSpan = 0f
    private var startTime = 0L
    private var edge: MotionEvent? = null

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ours = false
                multi = 0
                val i = targets.indexOfFirst { it.rect.contains(Offset(ev.x, ev.y)) }
                target = targets.getOrNull(i) ?: targets.minByOrNull { (it.rect.center - Offset(ev.x, ev.y)).getDistance() }
                if (i >= 0) onTouchedPane(i)
                if (gestures && ev.x < 22 * dp) {
                    edge = MotionEvent.obtain(ev)
                    return true
                }
                forward(ev)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                flushEdge()
                if (gestures && ev.pointerCount in 2..3) {
                    multi = ev.pointerCount
                    startX = cx(ev); startY = cy(ev); startSpan = span(ev)
                    startTime = ev.eventTime
                }
                if (!ours) forward(ev)
            }
            MotionEvent.ACTION_MOVE -> {
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
                    if (multi == 2 && spanChange < 60 * dp) {
                        if (-dy > far && abs(dy) > abs(dx) * 1.4f) { takeOver(ev); onTwoUp(); return true }
                        if (-dx > far && abs(dx) > abs(dy) * 1.4f) { takeOver(ev); onTwoLeft(); return true }
                    }
                    if (multi == 3 && abs(dx) > far && abs(dx) > abs(dy) * 1.4f) {
                        takeOver(ev); onThree(if (dx < 0) 1 else -1); return true
                    }
                }
                forward(ev)
            }
            MotionEvent.ACTION_POINTER_UP -> if (!ours) forward(ev)
            MotionEvent.ACTION_UP -> {
                if (edge != null) { flushEdge(); forward(ev) } else if (!ours) forward(ev)
                ours = false; multi = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                edge?.recycle(); edge = null
                if (!ours) forward(ev)
                ours = false; multi = 0
            }
            else -> if (!ours) forward(ev)
        }
        return true
    }

    private fun takeOver(ev: MotionEvent) {
        ours = true
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        forward(cancel)
        cancel.recycle()
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
        bridge.inject(e, id)
        e.recycle()
    }

    private fun cx(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getX(i); return s / ev.pointerCount }
    private fun cy(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getY(i); return s / ev.pointerCount }
    private fun span(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        return hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
    }
}
