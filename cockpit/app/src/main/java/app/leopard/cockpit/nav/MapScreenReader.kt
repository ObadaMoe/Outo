package app.leopard.cockpit.nav

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.leopard.cockpit.Otility
import app.leopard.cockpit.car.HelperConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads Waze's and Google Maps' own screen while they guide, on whichever car
 * screen they are on: the exact distance to the turn, the street, the time and
 * distance left. Android only sends it those two apps' screens (see
 * res/xml/map_screen_reader.xml), and it reads nothing while the setting is off.
 */
class MapScreenReader : AccessibilityService() {
    /**
     * Reads run on their own thread: each one asks the map app for its views, which
     * would stall Otility's own screen on the main thread. Everything below is used
     * only on this thread.
     */
    private val thread = HandlerThread("otility-map-reader", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    /** The map app whose screen changed last. */
    private var pending: String? = null
    private var scheduled = false
    private var lastRead = 0L
    private var lastFound = 0L
    private var shown: NextTurn? = null
    private var lastDump = 0L
    // Debug counters.
    private var events = 0
    private var lastSummary = 0L

    companion object {
        /** Android has the reader switched on. */
        val running = MutableStateFlow(false)
        private const val TAG = "OtilityScreen"
        /** At most four reads a second. */
        private const val THROTTLE = 250L
        /** While guiding, read again this often even if the screen sends nothing. */
        private const val KEEP = 1500L
        /** The turn has been gone this long: the route ended. */
        private const val GONE = 4000L
        /** Waze's arrow is looked at again this often, even on the same street. */
        private const val ARROW_EVERY = 3000L
        /** The arrow's picture is read at most this many pixels across. */
        private const val ARROW_PX = 72
    }

    override fun onServiceConnected() {
        running.value = true
        Log.i(TAG, "map reader on")
    }

    override fun onDestroy() {
        running.value = false
        // No more reads; then, after any read still running, take back what was shown.
        handler.removeCallbacksAndMessages(null)
        handler.post { show(null) }
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    private fun enabled() = Otility.instance.prefs.settings.value.let { it.showDirections && it.readMapScreen }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Android reuses the event after this returns: keep only what's needed.
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in MapScreens.PACKAGES) return
        val display = if (Build.VERSION.SDK_INT >= 30) event.displayId else 0
        handler.post { changed(pkg, display) }
    }

    private fun changed(pkg: String, display: Int) {
        if (++events == 1 && debuggable) Log.d(TAG, "first event from $pkg on display $display")
        if (!enabled()) {
            show(null)
            return
        }
        pending = pkg
        schedule((lastRead + THROTTLE - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun schedule(delay: Long) {
        if (scheduled) return
        scheduled = true
        handler.postDelayed(read, delay)
    }

    private val read = Runnable {
        scheduled = false
        lastRead = SystemClock.uptimeMillis()
        val pkg = pending ?: return@Runnable
        if (!enabled()) {
            show(null)
            return@Runnable
        }
        val roots = runCatching { rootsOf(pkg) }.onFailure { Log.w(TAG, "windows of $pkg", it) }.getOrDefault(emptyList())
        val nodes = runCatching { roots.flatMap { (root, display) -> collect(root, display) } }
            .onFailure { Log.w(TAG, "read $pkg", it) }.getOrDefault(emptyList())
        val turn = runCatching { MapScreens.parse(pkg, nodes)?.let { withArrow(it, nodes) } }
            .onFailure { Log.w(TAG, "parse $pkg", it) }.getOrNull()
        val now = SystemClock.uptimeMillis()
        if (turn != null) {
            lastFound = now
            show(turn)
        } else if (now - lastFound > GONE) {
            show(null)
        }
        if (debuggable && now - lastSummary > 10_000) {
            lastSummary = now
            Log.d(TAG, "$pkg: $events events, ${roots.size} windows, ${nodes.size} views; read: ${turn?.instruction}")
        }
        if (debuggable && nodes.isNotEmpty() && now - lastDump > 15_000) {
            lastDump = now
            dump(pkg, nodes, turn)
        }
        // A still screen sends no events; keep reading while there's a turn to show.
        if (shown != null) schedule(KEEP)
    }

    // ---- Waze's arrow ------------------------------------------------------------

    /** What Waze's arrow showed when last looked at, and on which street. */
    private var arrow = Maneuver.UNKNOWN
    private var arrowStreet: String? = null
    private var arrowAt = 0L

    /**
     * Waze's arrow has no words, on screen or in its notification, so its picture
     * is read (through the helper) when the street changes, and again every
     * [ARROW_EVERY] ms in case the turn changed on the same street.
     */
    private fun withArrow(turn: NextTurn, nodes: List<ScreenNode>): NextTurn {
        if (turn.pkg !in MapScreens.WAZE || turn.maneuver != Maneuver.UNKNOWN) return turn
        val box = nodes.firstOrNull { it.id?.endsWith(":id/navBarDirection") == true && it.right - it.left >= 16 && it.bottom - it.top >= 16 }
            ?: return turn
        val now = SystemClock.uptimeMillis()
        if (turn.street != arrowStreet || now - arrowAt > ARROW_EVERY) {
            val was = arrow
            arrowStreet = turn.street
            arrowAt = now
            arrow = readArrow(box)
            if (arrow != was) Log.i(TAG, "Waze's arrow: $arrow")
        }
        if (arrow == Maneuver.UNKNOWN) return turn
        return turn.copy(maneuver = arrow, instruction = Directions.sentence(arrow, turn.street) ?: turn.instruction)
    }

    private fun readArrow(box: ScreenNode): Maneuver {
        val helper = HelperConnection.current ?: return Maneuver.UNKNOWN
        val w = box.right - box.left
        val h = box.bottom - box.top
        val scale = minOf(1f, ARROW_PX.toFloat() / max(w, h))
        val pw = max(8, (w * scale).roundToInt())
        val ph = max(8, (h * scale).roundToInt())
        val px = runCatching { helper.capture(box.display, box.left, box.top, box.right, box.bottom, pw, ph) }
            .onFailure { Log.w(TAG, "arrow picture", it) }.getOrNull()
        if (px == null || px.size < pw * ph) return Maneuver.UNKNOWN
        return ArrowReader.read(px, pw, ph)
    }

    private fun show(turn: NextTurn?) {
        if (turn == null && shown == null) return
        if (turn != null && turn.sameAs(shown)) return
        shown = turn
        Directions.screen(turn)
    }

    /**
     * The app's windows on every car screen (the map can be on any of them). Only
     * app windows are asked for their views; the bars, keyboard and overlays aren't.
     */
    private fun rootsOf(pkg: String): List<Pair<AccessibilityNodeInfo, Int>> {
        val out = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        if (Build.VERSION.SDK_INT >= 30) {
            val all = windowsOnAllDisplays
            for (i in 0 until all.size()) {
                for (w in all.valueAt(i)) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = w.root ?: continue
                    if (root.packageName?.toString() == pkg) out += root to all.keyAt(i)
                }
            }
        } else {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNullTo(out) { w -> w.root?.takeIf { it.packageName?.toString() == pkg }?.let { it to 0 } }
        }
        if (out.isEmpty()) rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }?.let { out += it to 0 }
        return out
    }

    /** Every view that has a name, text or description, top to bottom as Android lists them. */
    private fun collect(root: AccessibilityNodeInfo, display: Int): List<ScreenNode> {
        val out = ArrayList<ScreenNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        val r = Rect()
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val n = queue.removeFirst()
            visited++
            if (!n.isVisibleToUser && n !== root) continue
            val id = n.viewIdResourceName
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            if (id != null || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
                n.getBoundsInScreen(r)
                out += ScreenNode(id, text, desc, r.top, r.left, r.bottom, r.right, display)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return out
    }

    private val debuggable by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    /** Debug builds log what the map shows, to fit the reader to new app versions. */
    private fun dump(pkg: String, nodes: List<ScreenNode>, turn: NextTurn?) {
        Log.d(TAG, "---- $pkg: ${nodes.size} views; read: ${turn?.let { "${it.distance} | ${it.instruction} | ${it.trip}" }}")
        for (n in nodes) {
            if (n.text.isNullOrBlank() && n.desc.isNullOrBlank()) continue
            Log.d(TAG, "${n.id?.substringAfter(":id/") ?: "-"} | text=${n.text} | desc=${n.desc} | ${n.left},${n.top},${n.right},${n.bottom}")
        }
    }
}
