package app.leopard.cockpit.car

import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import app.leopard.cockpit.CrashLog
import app.leopard.cockpit.helper.IOtilityHelper
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class DisplayRow(val id: Int, val name: String, val w: Int, val h: Int, val dpi: Int, val layerStack: Int)
/** [userId] 0 is the car's main user; the passenger screen runs its apps as another user (999). */
data class TaskRow(
    val taskId: Int, val displayId: Int, val pkg: String, val component: String, val visible: Boolean, val type: Int,
    val userId: Int = 0,
)

/** An app's window the helper put back into Otility from the car's main screen, [ago] ms before the look. */
data class PopupRow(val seq: Int, val pkg: String, val ago: Long)

data class CarSnapshot(
    val displays: List<DisplayRow>, val tasks: List<TaskRow>, val hosts: Set<Int>,
    val popups: List<PopupRow> = emptyList(),
) {

    /** The cluster's full-map channel, or (on a test device) the first extra display. */
    val driverDisplay: DisplayRow? get() =
        displays.firstOrNull { it.name.startsWith("shared_fission_bg") && it.name.endsWith("_0") }
            ?: extras.getOrNull(0)

    val passengerDisplay: DisplayRow? get() =
        displays.firstOrNull { it.name == "fse" } ?: extras.getOrNull(1)

    private val extras: List<DisplayRow> get() =
        if (displays.any { it.name.startsWith("shared_fission_bg") || it.name == "fse" }) emptyList()
        else displays.filter { it.id != 0 && it.id !in hosts && !it.name.startsWith("otility") }

    fun displayFor(screen: ScreenId): DisplayRow? = when (screen) {
        ScreenId.DRIVER -> driverDisplay
        ScreenId.PASSENGER -> passengerDisplay
        ScreenId.MAIN -> displays.firstOrNull { it.id == 0 }
    }

    /**
     * An app's standard task, if it's running as the car's main user (Otility's). The
     * passenger screen can run the same app as its own user: that copy is the
     * passenger's, never moved or closed from here ([tasks] still lists it).
     */
    fun task(pkg: String): TaskRow? = tasks.firstOrNull { it.pkg == pkg && it.type == 1 && it.userId == 0 }

    companion object {
        fun parse(json: String): CarSnapshot {
            val o = JSONObject(json)
            val d = o.getJSONArray("displays")
            val t = o.getJSONArray("tasks")
            val h = o.optJSONArray("hosts")
            val p = o.optJSONArray("popups")
            return CarSnapshot(
                displays = List(d.length()) { i ->
                    d.getJSONObject(i).run {
                        DisplayRow(getInt("id"), getString("name"), getInt("w"), getInt("h"), getInt("dpi"), getInt("layerStack"))
                    }
                },
                tasks = List(t.length()) { i ->
                    t.getJSONObject(i).run {
                        TaskRow(getInt("taskId"), getInt("displayId"), getString("pkg"), getString("component"),
                            getBoolean("visible"), getInt("type"), optInt("userId", 0))
                    }
                },
                hosts = h?.let { a -> List(a.length()) { a.getInt(it) }.toSet() } ?: emptySet(),
                popups = p?.let { a ->
                    List(a.length()) { i -> a.getJSONObject(i).run { PopupRow(getInt("seq"), getString("pkg"), getLong("ago")) } }
                } ?: emptyList(),
            )
        }
    }
}

/**
 * Everything Otility asks of the car, done through the helper on one
 * background lane so requests never overlap. When the helper isn't connected
 * (or on a phone or emulator without it) calls do nothing and the UI runs on
 * its own model, clearly marked as a preview.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarBridge {
    private val lane = Dispatchers.IO.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + lane + CrashLog.survive)

    val helper: IOtilityHelper? get() = HelperConnection.current
    val connected: Boolean get() = helper?.asBinder()?.isBinderAlive == true

    fun snapshot(): CarSnapshot? = helper?.let { h ->
        runCatching { CarSnapshot.parse(h.snapshot()) }.onFailure { Log.w(TAG, "snapshot", it) }.getOrNull()
    }

    fun launchOrMove(app: AppId, component: String, displayId: Int) = scope.launch {
        val h = helper ?: return@launch
        val task = snapshot()?.task(app.pkg)
        runCatching {
            if (task != null && task.displayId != displayId) h.moveTask(task.taskId, displayId)
            else if (task == null) h.launch(component, displayId)
            else h.launch(component, displayId) // already there: bring to front
        }.onFailure { Log.w(TAG, "launchOrMove", it) }
    }

    /** Opens one of Otility's own windows ([activity], a class name) on a display. */
    fun launchOwn(activity: String, displayId: Int) = scope.launch {
        val h = helper ?: return@launch
        runCatching { h.launch("app.leopard.cockpit/$activity", displayId) }.onFailure { Log.w(TAG, "launchOwn", it) }
    }

    fun close(app: AppId) = scope.launch {
        val h = helper ?: return@launch
        val task = snapshot()?.task(app.pkg) ?: return@launch
        runCatching { h.closeTask(task.taskId) }
    }

    /** One switch of the cluster's map channel at a time, in the order asked (each takes a second). */
    private val clusterLock = Mutex()

    fun cluster(on: Boolean, code: Int) = scope.launch {
        clusterLock.withLock {
            val h = helper ?: return@withLock
            runCatching {
                if (on) { h.cluster(code); delay(1000); h.cluster(16) } else { h.cluster(18); delay(1000); h.cluster(0) }
            }
        }
    }

    fun vehicle(): JSONObject? = helper?.let { h -> runCatching { JSONObject(h.vehicle()) }.getOrNull() }

    fun exteriorSpeaker(on: Boolean) = scope.launch {
        runCatching { helper?.exteriorSpeaker(on) }
    }

    /** Opens a link in an app on a display. Call on [scope]'s lane. */
    fun openUri(pkg: String, uris: List<String>, displayId: Int): Boolean =
        runCatching { helper?.openUri(pkg, uris.toTypedArray(), displayId) ?: false }
            .onFailure { Log.w(TAG, "openUri", it) }.getOrDefault(false)

    // ---- Touch -------------------------------------------------------------

    /** Sends a touch to a display. Called from the UI thread; the binder call is one-way. */
    fun inject(event: MotionEvent, displayId: Int) {
        val h = helper ?: return
        runCatching { h.injectMotion(event, displayId) }
    }

    fun key(keyCode: Int, displayId: Int) {
        val h = helper ?: return
        runCatching { h.injectKey(keyCode, displayId) }
    }

    // ---- Mirrors -----------------------------------------------------------

    fun startMirror(displayId: Int, surface: Surface, w: Int, h: Int, onId: (Int) -> Unit): Job = scope.launch {
        val id = helper?.let { runCatching { it.startMirror(displayId, surface, w, h) }.getOrDefault(-1) } ?: -1
        onId(id)
    }

    fun stopMirror(id: Int) = scope.launch {
        if (id >= 0) runCatching { helper?.stopMirror(id) }
    }

    companion object {
        const val TAG = "Otility"
    }
}

/**
 * Apps on the main screen live inside Otility: each one gets its own hidden
 * display whose picture is drawn into Otility's view, so we can resize, swap,
 * split and gesture over them. When an app leaves the main screen its display
 * is kept for a few minutes ("parked") so bringing it back is instant.
 */
class HostManager(private val bridge: CarBridge, private val store: android.content.SharedPreferences? = null) {

    private class Host(var displayId: Int = -1, var w: Int = 0, var h: Int = 0, var dpi: Int = 0) {
        var parkJob: Job? = null
        /** The helper that made [displayId]; a restarted helper means the display is gone. */
        var owner: IBinder? = null
        /** What the view last gave us, so the display can be made again after a restart. */
        var surface: Surface? = null
        var component: String = ""
        /**
         * Sets the view's buffer to the app's real size. Android's TextureView resets
         * it to the view's (much smaller) size now and then, and the hidden display
         * takes its size from the buffer when it connects, so this runs right before.
         */
        var fit: (Int, Int) -> Unit = { _, _ -> }
    }

    private val hosts = HashMap<AppId, Host>()

    /**
     * Every app with a hidden display is written down, so that after Otility restarts
     * (an update, a crash) the ones it had parked or floating can be put away again:
     * their displays went with the old Otility, and they landed on the car's main
     * screen over everything.
     */
    private var hostedBefore: Set<AppId> =
        store?.getStringSet(HOSTED, null).orEmpty().map { AppId(it) }.toSet()

    private fun saveHosted() {
        val now = synchronized(hosts) { hosts.keys.map { it.pkg }.toSet() }
        store?.edit()?.putStringSet(HOSTED, now)?.apply()
    }

    /** The apps the last Otility had hidden displays for; given out once. */
    fun takeHostedBefore(): Set<AppId> = hostedBefore.also { hostedBefore = emptySet() }

    init {
        // When the helper restarts (an update, a crash), its displays went with it and
        // their apps fell back to the main screen. Make new ones for the views on show.
        bridge.scope.launch {
            HelperConnection.helper.collect { helper ->
                val binder = helper?.asBinder() ?: return@collect
                // Also covers apps placed before the helper connected at all.
                val stale = synchronized(hosts) { hosts.entries.filter { it.value.owner != binder }.map { it.key to it.value } }
                for ((app, host) in stale) {
                    host.displayId = -1
                    host.owner = null
                    // Parked apps too: they fell onto the car's main screen over Otility, and
                    // their timer (which keeps running) closes them as before.
                    Log.i(CarBridge.TAG, "hosting ${app.pkg} on the current helper")
                    create(helper, app, host, host.surface)
                }
            }
        }
    }

    fun displayOf(app: AppId): Int? = synchronized(hosts) { hosts[app]?.displayId?.takeIf { it >= 0 } }

    /**
     * [create] off: only for an app still hosted. A view handing the picture back (a
     * SurfaceView going away as the app closes or leaves for another screen) mustn't
     * make it a new display, which would start the app again, unseen.
     */
    fun attach(
        app: AppId, component: String, surface: Surface, w: Int, h: Int, dpi: Int,
        create: Boolean = true,
        fit: (Int, Int) -> Unit,
    ) = bridge.scope.launch {
        val host = synchronized(hosts) { if (create) hosts.getOrPut(app) { Host() } else hosts[app] } ?: return@launch
        saveHosted()
        host.parkJob?.cancel()
        host.surface = surface
        host.component = component
        host.fit = fit
        val helper = bridge.helper
        if (helper == null) {
            // The display is made once the helper connects.
            host.w = w; host.h = h; host.dpi = dpi
            return@launch
        }
        runCatching {
            if (host.displayId < 0 || host.owner != helper.asBinder()) {
                host.w = w; host.h = h; host.dpi = dpi
                create(helper, app, host, surface)
            } else {
                host.fit(w, h)
                if (host.w != w || host.h != h || host.dpi != dpi) {
                    helper.resizeHostDisplay(host.displayId, w, h, dpi)
                    host.w = w; host.h = h; host.dpi = dpi
                }
                helper.setHostSurface(host.displayId, surface)
                pullIn(helper, app, host)
            }
        }.onFailure { Log.w(CarBridge.TAG, "host attach", it) }
    }

    /**
     * The app has left its hidden display for the main screen and sits there out of
     * sight, behind Otility (a splash screen handing over to a new window does that):
     * back into its display, or the view shows an empty display. One in front on the
     * main screen is the driver's (opened from the car's own home screen) and stays.
     */
    private fun pullIn(helper: IOtilityHelper, app: AppId, host: Host) {
        val task = bridge.snapshot()?.task(app.pkg) ?: return
        if (task.displayId == host.displayId || task.displayId != 0 || task.visible) return
        Log.i(CarBridge.TAG, "${app.pkg} is behind Otility on the main screen; back into display ${host.displayId}")
        helper.moveTask(task.taskId, host.displayId)
    }

    /**
     * The app is on the main screen but only its icon shows: start it in its
     * hidden display, with no picture until Otility zooms in on it. With [pull], an
     * app that already has a display but runs somewhere else (on the cluster itself,
     * say) is brought into it.
     */
    fun prepare(
        app: AppId, component: String, w: Int, h: Int, dpi: Int, parked: Boolean = false, pull: Boolean = false,
    ) = bridge.scope.launch {
        val host = synchronized(hosts) { hosts.getOrPut(app) { Host() } }
        saveHosted()
        host.parkJob?.cancel()
        host.component = component
        if (parked) parkNow(app)
        val helper = bridge.helper
        if (helper == null) {
            if (host.displayId < 0) { host.w = w; host.h = h; host.dpi = dpi }
            return@launch
        }
        runCatching {
            if (host.displayId < 0 || host.owner != helper.asBinder()) {
                host.w = w; host.h = h; host.dpi = dpi
                create(helper, app, host, host.surface)
            } else {
                if (host.w != w || host.h != h || host.dpi != dpi) {
                    host.fit(w, h)
                    helper.resizeHostDisplay(host.displayId, w, h, dpi)
                    host.w = w; host.h = h; host.dpi = dpi
                }
                if (pull) {
                    val task = bridge.snapshot()?.task(app.pkg)
                    if (task == null) helper.launch(host.component, host.displayId)
                    else if (task.displayId != host.displayId) helper.moveTask(task.taskId, host.displayId)
                }
            }
        }.onFailure { Log.w(CarBridge.TAG, "host prepare", it) }
    }

    /**
     * Keeps [app] running in a hidden display of its own, whether or not anything shows
     * it (the Overview's map, so it's live the moment you look): makes the display if
     * there's none, and starts the app again if it has gone (closed to free memory, say).
     * Leaves a display that a view is using as it is, and never parks it.
     */
    fun keepRunning(app: AppId, component: String, w: Int, h: Int, dpi: Int) = bridge.scope.launch {
        val helper = bridge.helper ?: return@launch
        val host = synchronized(hosts) { hosts.getOrPut(app) { Host() } }
        host.parkJob?.cancel()
        if (host.component.isEmpty()) host.component = component
        runCatching {
            if (host.displayId < 0 || host.owner != helper.asBinder()) {
                saveHosted()
                host.w = w; host.h = h; host.dpi = dpi
                Log.i(CarBridge.TAG, "keeping ${app.pkg} running in a hidden display")
                create(helper, app, host, host.surface)
            } else if (bridge.snapshot()?.task(app.pkg) == null) {
                Log.i(CarBridge.TAG, "${app.pkg} had stopped; starting it again in display ${host.displayId}")
                helper.launch(host.component, host.displayId)
            } else {
                pullIn(helper, app, host)
            }
        }.onFailure { Log.w(CarBridge.TAG, "keep running", it) }
    }

    /** Makes the app's hidden display and puts the app in it. Runs on the bridge's lane. */
    private companion object {
        const val HOSTED = "hosted_apps"
    }

    private fun create(helper: IOtilityHelper, app: AppId, host: Host, surface: Surface?) {
        runCatching {
            host.fit(host.w, host.h)
            host.displayId = helper.createHostDisplay(app.pkg, surface, host.w, host.h, host.dpi)
            host.owner = helper.asBinder()
            if (host.displayId >= 0) {
                val task = bridge.snapshot()?.task(app.pkg)
                if (task != null) helper.moveTask(task.taskId, host.displayId)
                else helper.launch(host.component, host.displayId)
            }
        }.onFailure { Log.w(CarBridge.TAG, "host create", it) }
    }

    /**
     * The view is on show but the app's display sends it no picture. Android 14 and
     * later can leave a display's layers hidden after its app moved there in the middle
     * of another change (the old helper's display closing, the app going into
     * picture-in-picture). A new display with the app moved onto it shows again; the
     * app keeps its page. Returns false when there's nothing to do yet.
     */
    fun revive(app: AppId, onDone: (Boolean) -> Unit = {}) = bridge.scope.launch {
        val host = synchronized(hosts) { hosts[app] }
        val helper = bridge.helper
        val surface = host?.surface
        if (host == null || helper == null || surface == null || host.displayId < 0 || host.owner != helper.asBinder()) {
            onDone(false)
            return@launch
        }
        val ok = runCatching {
            val old = host.displayId
            // The view's buffer can feed one display at a time, and a new display that
            // asks for it while the old one still holds it draws into nothing. So: the
            // new display starts with no picture, the app moves over, the old display
            // goes, and only then does the new one get the buffer (once).
            helper.setHostSurface(old, null)
            val id = helper.createHostDisplay(app.pkg, null, host.w, host.h, host.dpi)
            if (id < 0) {
                helper.setHostSurface(old, surface)
                return@runCatching false
            }
            host.displayId = id
            val task = bridge.snapshot()?.task(app.pkg)
            if (task != null) helper.moveTask(task.taskId, id) else helper.launch(host.component, id)
            helper.releaseHostDisplay(old)
            delay(150)
            // Unless the view went away meanwhile.
            if (host.surface === surface && host.displayId == id) {
                host.fit(host.w, host.h)
                helper.setHostSurface(id, surface)
            }
            Log.i(CarBridge.TAG, "revived ${app.pkg}: display $old -> $id")
            true
        }.onFailure { Log.w(CarBridge.TAG, "host revive", it) }.getOrDefault(false)
        onDone(ok)
    }

    fun resize(app: AppId, w: Int, h: Int, dpi: Int) = bridge.scope.launch {
        val host = synchronized(hosts) { hosts[app] } ?: return@launch
        if (host.displayId < 0 || (host.w == w && host.h == h && host.dpi == dpi)) return@launch
        host.fit(w, h)
        runCatching { bridge.helper?.resizeHostDisplay(host.displayId, w, h, dpi) }
        host.w = w; host.h = h; host.dpi = dpi
    }

    /**
     * The view went away (tab change, zoom, Otility in the background): keep the app
     * running. [surface] is the view's: if another view has the app by now (one tab
     * fading into another, both showing it), that one keeps the picture.
     */
    fun detach(app: AppId, surface: Surface? = null) = bridge.scope.launch {
        val host = synchronized(hosts) { hosts[app] } ?: return@launch
        if (surface != null && host.surface != null && host.surface !== surface) return@launch
        host.surface = null
        host.fit = { _, _ -> } // the view's buffer is gone
        if (host.displayId >= 0) runCatching { bridge.helper?.setHostSurface(host.displayId, null) }
    }

    /**
     * The app left the main screen: park it, and after a while close it and free its
     * display. Queued behind any [prepare] or [attach] still waiting for this app.
     */
    fun park(app: AppId, closeAfterMs: Long = 5 * 60_000L) {
        bridge.scope.launch { parkNow(app, closeAfterMs) }
    }

    private fun parkNow(app: AppId, closeAfterMs: Long = 5 * 60_000L) {
        val host = synchronized(hosts) { hosts[app] } ?: return
        host.parkJob?.cancel()
        host.parkJob = bridge.scope.launch {
            delay(closeAfterMs)
            release(app, closeApp = true)
        }
    }

    /** The app moved to another screen: hand its display back right away. */
    fun release(app: AppId, closeApp: Boolean) = bridge.scope.launch {
        val host = synchronized(hosts) { hosts.remove(app) } ?: return@launch
        saveHosted()
        host.parkJob?.cancel()
        val helper = bridge.helper ?: return@launch
        runCatching {
            if (closeApp) bridge.snapshot()?.task(app.pkg)?.let { helper.closeTask(it.taskId) }
            if (host.displayId >= 0) helper.releaseHostDisplay(host.displayId)
        }
    }
}
