package app.leopard.cockpit.nav

/**
 * The speed limit Outo gives the HUD and the cluster while a map guides: the map's
 * (Waze's) for the road, until the car's camera reads a new sign; then the camera's,
 * until the map shows a limit again (a new one, or its sign back after a while hidden).
 * Whichever spoke last wins; at the same moment, the map.
 *
 * The car's own camera reading stays on the car either way; this only decides which
 * value Outo sends as the navigation's limit.
 */
class RoadLimit {
    private var map = 0
    private var mapAt = 0L
    private var mapShowing = false
    private var mapHiddenAt = 0L
    private var camera = 0
    private var cameraAt = 0L

    /** What [update] chose last (0: none, the car keeps its own reading). */
    @Volatile var current = 0
        private set
    /** [current] is the camera's, not the map's. */
    @Volatile var fromCamera = false
        private set

    /**
     * [seen]: the map's limit sign now (null while it's hidden); [held]: the map's limit
     * for this road, held while the sign is hidden (0: none, or no route); [cameraNow]:
     * the camera's reading now (null: none); [now]: milliseconds.
     */
    fun update(seen: Int?, held: Int, cameraNow: Int?, now: Long): Int {
        if (held <= 0) {
            reset()
            return 0
        }
        // The map speaks: a new limit, or its sign showing again after a while hidden.
        val back = seen != null && !mapShowing && mapHiddenAt != 0L && now - mapHiddenAt >= REAPPEAR_MS
        if (held != map || back) {
            map = held
            mapAt = now
        }
        if (seen != null) {
            mapShowing = true
        } else if (mapShowing) {
            mapShowing = false
            mapHiddenAt = now
        }
        // The camera speaks: a sign read (a new value, or one after none).
        val cam = cameraNow ?: 0
        if (cam != camera) {
            camera = cam
            if (cam > 0) cameraAt = now
        }
        fromCamera = camera > 0 && cameraAt > mapAt
        current = if (fromCamera) camera else map
        return current
    }

    fun reset() {
        map = 0; mapAt = 0L; mapShowing = false; mapHiddenAt = 0L
        camera = 0; cameraAt = 0L
        current = 0
        fromCamera = false
    }

    companion object {
        /** A sign hidden this long and shown again counts as the map speaking again. */
        const val REAPPEAR_MS = 3_000L
    }
}
