package app.leopard.cockpit.ui.carplay

import android.os.SystemClock
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.geometry.Rect
import app.leopard.cockpit.data.Tab

/**
 * CarPlay mode's motion, one feel throughout, as Apple's: springs, which carry on from
 * wherever a finger or an earlier move left things; none that bounce on the way into
 * something (an icon), a touch of give where something lands.
 */
internal object Motion {
    /** A window growing out of its icon or card. */
    fun <T> grow(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 230f)
    /** Back into its icon: no bounce, so it doesn't swing past it. */
    fun <T> shrink(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 260f)
    /** Windows moving to new places: a split opening, the handle let go. */
    fun <T> move(): SpringSpec<T> = spring(dampingRatio = 0.88f, stiffness = 320f)
    /** A window going (squeezed out, replaced, closed): quick, out of the way. */
    fun <T> leave(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 420f)
    /** Small things: dots, pills, cards settling. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    /** How much a page falls back (or comes forward from) as an app opens over it. */
    const val DEPTH = 1.05f
}

/**
 * What a window grows out of, or shrinks back into: an icon, an app switcher card, the
 * dashboard's map. In the window's pixels, with that shape's corner radius.
 */
internal data class Origin(val rect: Rect, val corner: Float, val kind: Kind, val at: Long = SystemClock.uptimeMillis()) {
    enum class Kind {
        /** An app icon: the window is the icon at first, its picture comes in as it grows. */
        ICON,
        /** An app switcher card: its poster, as the card showed it. */
        CARD,
        /** The dashboard's map: the picture throughout, fading out as it lands on the card. */
        LIVE,
        /** Nothing to grow out of: it fades in (or out), a little smaller. */
        FADE,
    }

    /** Still the app just tapped (its question answered), not one from long ago. */
    val fresh get() = SystemClock.uptimeMillis() - at < 15_000

    companion object {
        /** An app icon, in Apple's rounded-square shape. */
        fun icon(r: Rect) = Origin(r, r.width * 0.225f, Kind.ICON)

        /** [r] a little smaller, about its middle: fading in or out there. */
        fun fade(r: Rect, corner: Float, scale: Float = 0.94f): Origin {
            val c = r.center
            val hw = r.width * scale / 2f
            val hh = r.height * scale / 2f
            return Origin(Rect(c.x - hw, c.y - hh, c.x + hw, c.y + hh), corner, Kind.FADE)
        }
    }
}

/**
 * How CarPlay mode's pages change. Into the apps: they grow out of their icons (see
 * [CarPlayApps]) over Home, which falls back a little as it fades. Out of them: they
 * shrink back into their icons over Home coming forward. Home to one of its own pages
 * (Settings, all three screens) opens out of Home, and back. Screen mode crossfades.
 */
internal fun AnimatedContentTransitionScope<Pair<Tab, Boolean>>.tabMotion(carPlay: Boolean): ContentTransform {
    val fade = ContentTransform(fadeIn(tween(220)), fadeOut(tween(220)), sizeTransform = null)
    if (!carPlay) return fade
    val (from, fromApps) = initialState
    val (to, toApps) = targetState
    val intoApps = to == Tab.SCREENS && toApps
    val outOfApps = from == Tab.SCREENS && fromApps
    return when {
        intoApps -> ContentTransform(
            EnterTransition.None,
            fadeOut(tween(320)) + scaleOut(tween(420, easing = FastOutSlowInEasing), targetScale = Motion.DEPTH),
            targetContentZIndex = 1f,
            sizeTransform = null,
        )
        outOfApps -> ContentTransform(
            fadeIn(tween(280)) + scaleIn(spring(1f, 200f), initialScale = Motion.DEPTH),
            ExitTransition.None,
            targetContentZIndex = -1f,
            sizeTransform = null,
        )
        from == Tab.OVERVIEW -> ContentTransform(
            fadeIn(tween(240, delayMillis = 40)) + scaleIn(spring(0.9f, 280f), initialScale = 0.92f),
            fadeOut(tween(220)) + scaleOut(tween(320, easing = FastOutSlowInEasing), targetScale = Motion.DEPTH),
            targetContentZIndex = 1f,
            sizeTransform = null,
        )
        to == Tab.OVERVIEW -> ContentTransform(
            fadeIn(tween(260)) + scaleIn(spring(1f, 220f), initialScale = Motion.DEPTH),
            fadeOut(tween(200)) + scaleOut(tween(260, easing = FastOutSlowInEasing), targetScale = 0.92f),
            targetContentZIndex = -1f,
            sizeTransform = null,
        )
        else -> fade
    }
}
