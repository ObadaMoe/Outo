package app.leopard.cockpit.nav

import android.os.SystemClock
import android.util.Log
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.data.Prefs
import app.leopard.cockpit.data.VehicleFeed
import app.leopard.cockpit.helper.IOtilityHelper
import app.leopard.cockpit.helper.Instrument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Shows the next turn, its distance and the time and distance left on the
 * driver cluster, through the helper (which may set only these navigation
 * values). It writes a value only when it changes, checks every 3 s that the
 * cluster is still in navigation mode, and puts everything back when the
 * route ends.
 */
class ClusterTurns(private val prefs: Prefs, private val vehicle: VehicleFeed, private val roadLimit: RoadLimit) {
    companion object {
        private const val TAG = "OtilityCluster"
        private const val CHECK = 3000L

        /**
         * The street for the cluster's line: Waze adds the area after a comma ("Street 964,
         * Doha"), which the line has no room for.
         */
        fun street(s: String?): String =
            s?.split(", ", "، ")?.firstOrNull()?.trim()?.take(Instrument.ROAD_MAX).orEmpty()

        /**
         * The cluster's own arrow codes, as BYD's map writes them (its Baidu set, 1001 and
         * up). They aren't the HUD frame's codes: the cluster (and with it the HUD) draws
         * nothing for those. Tried in the car: 1003 shows the right arrow, 1024 the flag.
         * Never 0: the cluster keeps one byte of the code, and 0 is the flag's (1024's).
         * With no turn known it's straight on, as BYD's map does.
         */
        fun arrow(m: Maneuver): Int = when (m) {
            Maneuver.STRAIGHT, Maneuver.UNKNOWN -> 1001
            Maneuver.SLIGHT_RIGHT -> 1002
            Maneuver.RIGHT -> 1003
            Maneuver.SHARP_RIGHT -> 1004
            Maneuver.U_TURN -> 1005
            Maneuver.SHARP_LEFT -> 1006
            Maneuver.LEFT -> 1007
            Maneuver.SLIGHT_LEFT -> 1008
            Maneuver.ROUNDABOUT -> 1009
            Maneuver.ROUNDABOUT_EXIT -> 1010
            Maneuver.KEEP_LEFT -> 1011
            Maneuver.KEEP_RIGHT -> 1012
            Maneuver.ARRIVE -> 1024
            Maneuver.WAYPOINT -> 1025
            Maneuver.FERRY -> 1029
            Maneuver.TOLL -> 1031
            Maneuver.ROUNDABOUT_STRAIGHT -> 1069
            Maneuver.ROUNDABOUT_RIGHT -> 1071
            Maneuver.ROUNDABOUT_BACK -> 1073
            Maneuver.ROUNDABOUT_LEFT -> 1075
            Maneuver.U_TURN_RIGHT -> 1100
        }
    }

    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status.asStateFlow()

    private val lane = Executors.newSingleThreadExecutor { r -> Thread(r, "otility-cluster").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + lane)

    /** What the cluster has now, as Otility last set it. */
    private val written = HashMap<Int, Int>()
    /** The street the cluster has now, as Otility last set it (null: not known). */
    private var road: String? = null
    private var roadFailed = false
    /** The speed limit the car has now, as Otility last set it (null: none). */
    private var limit: Int? = null
    private var limitFailed = false
    /** The map's limit, held while its sign is hidden, until the next turn's street. */
    private var heldLimit = 0
    private var heldFor: String? = null
    /** Otility put the cluster in navigation mode and hasn't ended it yet. */
    private var armed = false
    /**
     * A helper just connected, so the cluster may still hold a route from before
     * (Otility restarted, or the route ended while the helper was away).
     */
    private var unsure = false
    /** When navigation mode was last checked. */
    private var checkedAt = 0L
    private var helper: IOtilityHelper? = null

    init {
        scope.launch {
            combine(
                // The HUD shows the turn the cluster has, so its arrows need these too.
                prefs.settings.map { it.showDirections && (it.clusterTurns || it.hudTurns) }.distinctUntilChanged(),
                HelperConnection.helper,
                Directions.turn,
            ) { on, h, t -> Triple(on, h, t) }
                .collectLatest { (on, h, turn) ->
                    if (h !== helper) {
                        // A new helper: the old one's writes are still on the cluster, so
                        // stay armed (to end them later) but write every value again.
                        helper = h
                        written.clear()
                        road = null
                        limit = null
                        if (h != null) unsure = true
                    }
                    when {
                        !on -> {
                            exit()
                            _status.value = "Off"
                        }
                        h == null -> _status.value = "Needs the in-car helper"
                        turn == null -> {
                            if (!armed) _status.value = "On. Waiting for a route"
                            delay(3000)
                            exit(force = unsure)
                            _status.value = "On. Waiting for a route"
                        }
                        else -> {
                            // New turns can come more often than every 3 s, so the check
                            // runs before each one too, not only in the quiet loop below.
                            keepInNav()
                            show(turn)
                            while (true) {
                                delay(CHECK)
                                keepInNav()
                                show(turn)
                            }
                        }
                    }
                }
        }
    }

    private fun show(t: NextTurn) {
        // Navigation mode first, and again after a new helper or after the cluster left it.
        if (!armed || written[Instrument.NAV_STATUS] != Instrument.GUIDING) {
            written.remove(Instrument.NAV_STATUS)
            if (!write(Instrument.NAV_STATUS, Instrument.GUIDING)) return
            armed = true
            unsure = false
            checkedAt = SystemClock.uptimeMillis()
        }
        val code = arrow(t.maneuver)
        var ok = write(Instrument.TURN_ICON, code) and
            write(Instrument.TURN_ICON_2, code) and
            write(Instrument.TURN_METRES, t.metres ?: -1)
        // What this turn doesn't know is cleared (as on exit), not left from an earlier turn.
        ok = ok and write(Instrument.LEFT_METRES, t.remainingMetres?.coerceIn(0, 9_999_000) ?: -1)
        val s = t.remainingSeconds
        ok = ok and write(Instrument.LEFT_HOURS, s?.let { (it / 3600).coerceIn(0, 254) } ?: 0) and
            write(Instrument.LEFT_MINUTES, s?.let { it / 60 % 60 } ?: 0) and
            // BYD's map: 1 in the last 30 seconds, 2 before.
            write(Instrument.LEFT_EXTRA, s?.let { if (it < 30) 1 else 2 } ?: 0)
        // The street isn't needed for the arrow: a car that won't take it still gets the rest.
        writeRoad(street(t.street))
        writeLimit(limitFor(t))
        if (ok) _status.value = "Sending turns"
    }

    /**
     * The speed limit to send, while "Speed limit from the map" is on: the map's for the
     * road, until the car's camera reads a new sign, then the camera's until the map
     * shows a limit again (see [RoadLimit]). Waze shows its sign only now and then, so
     * the last one seen holds until the next turn's street changes; then none until the
     * map shows one again.
     */
    private fun limitFor(t: NextTurn): Int {
        if (!prefs.settings.value.hudSpeedLimit) {
            roadLimit.reset()
            return 0
        }
        val seen = t.speedLimit?.takeIf { it in 5..Instrument.SPEED_LIMIT_MAX }
        if (seen != null) {
            heldLimit = seen
            heldFor = t.street
        } else if (t.street != heldFor) {
            heldLimit = 0
        }
        val camera = vehicle.data.value.cameraLimitKmh?.takeIf { it <= Instrument.SPEED_LIMIT_MAX }
        val before = roadLimit.current to roadLimit.fromCamera
        val kmh = roadLimit.update(seen, heldLimit, camera, SystemClock.uptimeMillis())
        if (before != (kmh to roadLimit.fromCamera)) {
            Log.i(TAG, "speed limit $kmh km/h from the ${if (roadLimit.fromCamera) "camera" else "map"} (map $heldLimit, camera ${camera ?: "none"})")
        }
        return kmh
    }

    private fun writeLimit(kmh: Int) {
        // Nothing set and nothing to set: the car keeps its own reading.
        if (limit == kmh || (kmh == 0 && limit == null)) return
        val h = helper ?: return
        val out = runCatching { h.setSpeedLimit(kmh) }.getOrElse { "error: ${it.message}" }
        if (out.startsWith("error")) {
            if (!limitFailed) Log.w(TAG, "speed limit $kmh: $out")
            limitFailed = true
            return
        }
        if (limitFailed || limit == null) Log.i(TAG, "speed limit $kmh km/h: $out")
        limitFailed = false
        limit = kmh.takeIf { it > 0 }
    }

    private fun writeRoad(text: String) {
        if (road == text) return
        val h = helper ?: return
        val out = runCatching { h.instrumentSetRoad(text) }.getOrElse { "error: ${it.message}" }
        if (out.startsWith("error")) {
            if (!roadFailed) Log.w(TAG, "street: $out")
            roadFailed = true
            return
        }
        roadFailed = false
        road = text
    }

    /**
     * At most every [CHECK] ms: if the cluster left navigation mode, forgets what
     * was written, so the next [show] sets the mode and every value again.
     */
    private fun keepInNav() {
        val now = SystemClock.uptimeMillis()
        if (!armed || now - checkedAt < CHECK) return
        checkedAt = now
        if (!stillInNav()) {
            Log.i(TAG, "the cluster left navigation mode; setting it again")
            armed = false
            written.clear()
            road = null
        }
    }

    /** The cluster still shows navigation (or can't be asked). */
    private fun stillInNav(): Boolean {
        val h = helper ?: return true
        val v = runCatching { h.instrumentGet(Instrument.DEVICE, Instrument.NAV_STATUS) }.getOrDefault(Int.MIN_VALUE)
        return v == Int.MIN_VALUE || v in Instrument.IN_NAV
    }

    /**
     * Clears the turn and the trip, then ends navigation mode; with [force], even
     * when this process didn't set it (a route may be left from before).
     */
    private fun exit(force: Boolean = false) {
        if (!force && !armed && written.isEmpty()) return
        // Without the helper nothing can be cleared: stay armed and clear once it's back.
        if (helper == null) return
        // Out of navigation mode first, so nothing cleared below flashes up (the
        // cluster's flag, say). Not ended: try again the next time the route is gone.
        if (!write(Instrument.NAV_STATUS, Instrument.DONE)) return
        writeRoad("")
        writeLimit(0)
        heldLimit = 0
        heldFor = null
        roadLimit.reset()
        for ((feature, value) in Instrument.CLEAR) if (feature != Instrument.NAV_STATUS) write(feature, value)
        Log.i(TAG, "cluster cleared")
        armed = false
        unsure = false
        written.clear()
        road = null
    }

    private fun write(feature: Int, value: Int): Boolean {
        if (written[feature] == value) return true
        val h = helper ?: return false
        val out = runCatching { h.instrumentSet(Instrument.DEVICE, feature, value) }.getOrElse { "error: ${it.message}" }
        if (Instrument.wrote(out)) {
            written[feature] = value
            return true
        }
        val why = if (out.contains("does not exist") || out.contains("Can't find")) "The car's cluster service isn't here"
        else "The cluster refused a value"
        if (_status.value != why) Log.w(TAG, "$feature=$value: $out")
        _status.value = why
        return false
    }
}
