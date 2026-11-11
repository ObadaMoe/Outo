package app.leopard.cockpit.helper

import android.os.SystemClock
import android.view.MotionEvent

/**
 * A cancel that ends the touch this event belongs to, as Android sends when something
 * takes a touch away: the same fingers, device and source, stamped now (Android drops
 * an event more than ten seconds old, and a finger can rest that long). An app only
 * takes a cancel as the end of a touch when it comes from the touch screen, as this
 * one does; until then a finger stays down for it (YouTube's video at 2x, say).
 */
fun MotionEvent.toCancel(): MotionEvent {
    val n = pointerCount
    val props = Array(n) { i -> MotionEvent.PointerProperties().also { getPointerProperties(i, it) } }
    val coords = Array(n) { i -> MotionEvent.PointerCoords().also { getPointerCoords(i, it) } }
    return MotionEvent.obtain(
        downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, n, props, coords,
        metaState, buttonState, xPrecision, yPrecision, deviceId, edgeFlags, source, flags,
    )
}
