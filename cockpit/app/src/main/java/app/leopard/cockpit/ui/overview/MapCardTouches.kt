package app.leopard.cockpit.ui.overview

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import app.leopard.cockpit.ui.screens.TouchRouter
import kotlin.math.hypot

/**
 * Touches on the map card, small, as on the map's own screen: a drag pans the map and
 * two fingers zoom it, a tap goes to its buttons. Two taps open it up ([onDoubleTap]), and
 * a finger held still brings the widgets' menu ([onHold]), as on the other widgets.
 *
 * So a finger's touch waits a moment before the map has it: until it moves (then the map
 * has all of it, from where it began), a second finger comes, or it lets go. A tap goes
 * on once no second one follows it, as Apple's maps wait to tell one tap from two.
 * [router] takes what goes to the map ([TouchRouter.gestures] off: Otility's own two-finger
 * gestures and Back from the edge are for the opened-up map, not the card).
 */
internal class MapCardTouches(
    private val router: TouchRouter,
    private val slop: Float,
    private val doubleTapSlop: Float,
    private val onDoubleTap: () -> Unit,
    private val onHold: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    /** The finger's first touch, held back until it moves, a second one comes, or it lets go. */
    private var down: MotionEvent? = null
    /** A tap held back while a second may follow it: its down and its up. */
    private var tapDown: MotionEvent? = null
    private var tapUp: MotionEvent? = null
    /** This touch is the map's. */
    private var passing = false
    /** This touch was Otility's (two taps, or a hold): nothing more of it goes on. */
    private var ours = false

    private val hold = Runnable {
        if (down != null && !passing) {
            drop()
            ours = true
            onHold()
        }
    }
    private val sendTap = Runnable { flushTap() }

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(hold)
                drop()
                passing = false
                ours = false
                val first = tapDown
                if (first != null) {
                    if (hypot(ev.x - first.x, ev.y - first.y) < doubleTapSlop) {
                        // The second tap: the map opens up, and has neither.
                        handler.removeCallbacks(sendTap)
                        dropTap()
                        ours = true
                        onDoubleTap()
                        return true
                    }
                    // Somewhere else: the first was a tap of its own.
                    handler.removeCallbacks(sendTap)
                    flushTap()
                }
                down = MotionEvent.obtain(ev)
                handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> when {
                ours -> {}
                passing -> router.onTouch(ev)
                else -> down?.let { d ->
                    if (hypot(ev.x - d.x, ev.y - d.y) > slop) {
                        pass()
                        router.onTouch(ev)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!ours) {
                // Two fingers: a zoom, the map's.
                if (!passing) pass()
                router.onTouch(ev)
            }
            MotionEvent.ACTION_POINTER_UP -> if (passing) router.onTouch(ev)
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                when {
                    ours -> {}
                    passing -> router.onTouch(ev)
                    else -> down?.let { d ->
                        // A tap: kept a moment, in case a second follows.
                        tapDown = d
                        tapUp = MotionEvent.obtain(ev)
                        down = null
                        handler.postDelayed(sendTap, ViewConfiguration.getDoubleTapTimeout().toLong())
                    }
                }
                passing = false
                ours = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(hold)
                if (passing) router.onTouch(ev)
                drop()
                passing = false
                ours = false
            }
            else -> if (passing) router.onTouch(ev)
        }
        return true
    }

    /** The card stops taking touches (opened up, the widgets shaking, gone): nothing waits any more. */
    fun dispose() {
        handler.removeCallbacks(hold)
        handler.removeCallbacks(sendTap)
        drop()
        dropTap()
        router.cancelPress()
        passing = false
        ours = false
    }

    /** From here on the touch is the map's, from where it began. */
    private fun pass() {
        handler.removeCallbacks(hold)
        passing = true
        down?.let { router.onTouch(it); it.recycle() }
        down = null
    }

    private fun flushTap() {
        val d = tapDown ?: return
        val u = tapUp
        tapDown = null
        tapUp = null
        router.onTouch(d)
        d.recycle()
        if (u != null) {
            router.onTouch(u)
            u.recycle()
        }
    }

    private fun drop() {
        down?.recycle()
        down = null
    }

    private fun dropTap() {
        tapDown?.recycle(); tapDown = null
        tapUp?.recycle(); tapUp = null
    }
}
