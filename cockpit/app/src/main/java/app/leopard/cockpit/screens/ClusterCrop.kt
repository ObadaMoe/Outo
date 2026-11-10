package app.leopard.cockpit.screens

import kotlin.math.ceil

/**
 * A map app on the driver cluster, map only: the app runs on a hidden display
 * bigger than the cluster, laid out as on a wide screen, and the cluster shows
 * the part of it that is only map. [left] and [top] are where the cluster's
 * picture starts on that display.
 */
data class ClusterFrame(val width: Int, val height: Int, val left: Int, val top: Int, val dpi: Int)

/**
 * Where Waze's and Google Maps' own banners, cards and buttons sit, so they can be
 * kept outside the picture. Sizes are in the display's pixels.
 */
object ClusterCrop {

    /** The apps framed this way. Any other app shows on the cluster as it is. */
    val APPS = setOf("com.waze", "com.google.android.apps.maps", "app.revanced.android.apps.maps")

    /**
     * The frame for [pkg] on a cluster [w] × [h] at [dpi], or null for an app that
     * isn't framed.
     */
    fun frameFor(pkg: String, w: Int, h: Int, dpi: Int): ClusterFrame? {
        if (w <= 0 || h <= 0 || dpi <= 0) return null
        fun dp(v: Int) = (v * dpi + 80) / 160
        return when (pkg) {
            "com.google.android.apps.maps", "app.revanced.android.apps.maps" -> {
                // On a wide screen, Google Maps keeps its turn banner, its trip card and
                // its search bar in a column on the left, MAPS_PANEL of the width, and its
                // round buttons along the right edge. The map between them is the picture.
                val right = dp(MAPS_BUTTONS_DP)
                val gap = dp(MAPS_GAP_DP)
                val width = even(ceil((w + right + gap) / (1f - MAPS_PANEL)).toInt())
                val height = even(h + 2 * dp(MAPS_TOP_DP))
                ClusterFrame(width, height, left = width - right - w, top = (height - h) / 2, dpi = dpi)
            }
            "com.waze" -> {
                // Waze keeps its turn card at the top left, its trip card along the bottom
                // and its round buttons in the bottom corners. It draws the car low on the
                // map, just above the trip card, so the picture ends right there: below
                // the car, but above the card.
                val side = dp(WAZE_SIDE_DP)
                val width = even(w + 2 * side)
                val height = even(h + dp(WAZE_TOP_DP) + dp(WAZE_BOTTOM_DP))
                ClusterFrame(width, height, left = (width - w) / 2, top = height - dp(WAZE_BOTTOM_DP) - h, dpi = dpi)
            }
            else -> null
        }
    }

    private fun even(v: Int) = v + (v and 1)

    /** Google Maps' left column, as a share of the width (measured: 44.6%, with its shadow). */
    const val MAPS_PANEL = 0.45f
    /** Google Maps' buttons along the right edge (the last 80 dp), and room after them. */
    const val MAPS_BUTTONS_DP = 96
    /** Room between the left column's shadow and the picture. */
    const val MAPS_GAP_DP = 24
    /**
     * Above and below the picture: Google Maps' "Re-center" pill sits in the bottom
     * 76 dp, next to the left column, and its chips along the top.
     */
    const val MAPS_TOP_DP = 88
    /**
     * Waze, measured on the car's cluster while guiding: its round buttons (speed and
     * report) sit in the outer 82 dp on each side, more with a speed-limit sign.
     */
    const val WAZE_SIDE_DP = 120
    /** Its turn card reaches 123 dp down, more with lanes under it. */
    const val WAZE_TOP_DP = 150
    /** Its trip card fills the bottom 72 dp; the car sits about 65 dp above that. */
    const val WAZE_BOTTOM_DP = 72
}
