package app.leopard.cockpit.screens

import android.os.SystemClock
import android.util.Log
import app.leopard.cockpit.car.CarBridge
import app.leopard.cockpit.car.CarSnapshot
import app.leopard.cockpit.car.DisplayRow
import app.leopard.cockpit.car.HostManager
import app.leopard.cockpit.car.TaskRow
import app.leopard.cockpit.data.AppCatalog
import app.leopard.cockpit.data.Layout
import app.leopard.cockpit.data.Prefs
import app.leopard.cockpit.ui.Alert
import app.leopard.cockpit.ui.AlertButton
import app.leopard.cockpit.ui.Alerts
import app.leopard.cockpit.ui.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Screens tab's brain: the current layout, the rules for changing it
 * (with an Apple-style confirmation before anything gets replaced), and
 * keeping the car in step.
 */
class ScreensController(
    private val prefs: Prefs,
    private val catalog: AppCatalog,
    val bridge: CarBridge,
    val hosts: HostManager,
) {
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The apps chosen to open when Otility starts (read once: this is the start). */
    private val startup = prefs.startup.value.takeUnless { it.isEmpty }

    private val _state = MutableStateFlow(
        (if (prefs.settings.value.restoreLayout) prefs.lastLayout() ?: ScreensState() else ScreensState())
            // The main screen is Otility's own, so its startup apps are in place from the start.
            .let { startup?.applyTo(it, listOf(ScreenId.MAIN)) ?: it }
            // The cluster shows a map only while the switch says so.
            .let { if (prefs.settings.value.mapOnCluster) it else it.with(ScreenId.DRIVER, ScreenContent.Idle) },
    )
    val state: StateFlow<ScreensState> = _state.asStateFlow()

    /** The side screens' startup apps open on the first look at the car. */
    private var startupPending = startup != null

    /** The main screen has a startup app: Otility opens zoomed in on it (asked once). */
    private var startupZoom = startup?.content(ScreenId.MAIN) != null

    fun takeStartupZoom(): ScreenId? = if (startupZoom) ScreenId.MAIN.also { startupZoom = false } else null

    private val _snapshot = MutableStateFlow<CarSnapshot?>(null)
    val snapshot: StateFlow<CarSnapshot?> = _snapshot.asStateFlow()

    private var lastLocalChange = 0L

    // ---- The cluster's map, map only --------------------------------------------

    private val _clusterMap = MutableStateFlow<ClusterMap?>(null)
    /** Waze or Google Maps on the driver cluster, map only (ClusterMapActivity shows it). */
    val clusterMap: StateFlow<ClusterMap?> = _clusterMap.asStateFlow()

    /** When the cluster's window was last opened, so a missing one isn't opened again and again. */
    private var clusterOpenedAt = 0L

    // ---- BYD's own view on the cluster, for a while ------------------------------

    /** The cluster's map is on the main screen: on the Overview, or opened up over Screens (see [lendClusterMap]). */
    var clusterMapLent = false
        private set
    /** Who has the cluster's map now (see [lendClusterMap]). */
    private val lenders = mutableSetOf<Any>()
    /** A turn signal is on: BYD shows its blind-spot camera on the cluster (see [setSignalling]). */
    private var signalling = false
    /** The cluster shows BYD's own view now, for one of those, instead of the map. */
    private var held = false

    /**
     * The cluster's map, opened up on the main screen ([clusterMapLent]) or given back. Its app
     * can be drawn at one size at a time: the main screen's view takes it at the main
     * screen's size, all of it (Waze's search and buttons too), and the cluster shows
     * BYD's own view meanwhile rather than a still picture. Given back, the cluster's
     * window takes it again at the cluster's size, and the cluster shows it.
     *
     * More than one view can borrow it at once (the Overview's map fading out as the
     * cluster's map opens over Screens, say): it goes back once the last one ([by])
     * gives it back.
     */
    fun lendClusterMap(by: Any, take: Boolean) {
        val changed = if (take) lenders.add(by) else lenders.remove(by)
        val lent = lenders.isNotEmpty()
        if (!changed || clusterMapLent == lent) return
        clusterMapLent = lent
        Log.i(CarBridge.TAG, if (lent) "the cluster's map is on the main screen" else "the cluster's map is back")
        // A new [ClusterMap.epoch]: its window makes a new view, which takes the app back.
        if (!lent) _clusterMap.value?.let { _clusterMap.value = it.copy(epoch = it.epoch + 1) }
        holdChannel()
    }

    /** A turn signal is on or off (with a moment's grace after it ends; see Otility). */
    fun setSignalling(on: Boolean) {
        if (signalling == on) return
        signalling = on
        holdChannel()
    }

    /** The map channel off while the map is lent or a signal is on, and back on after, if the cluster has something of ours. */
    private fun holdChannel() {
        // Nothing of ours there: the channel is off anyway.
        if (_state.value[ScreenId.DRIVER] == ScreenContent.Idle) { held = false; return }
        val hold = clusterMapLent || signalling
        if (hold == held) return
        held = hold
        bridge.cluster(!hold, prefs.settings.value.clusterCode)
    }

    /** Makes sure the cluster shows the map channel, unless it's on BYD's own view for now. */
    fun showClusterChannel() {
        if (!held) bridge.cluster(true, prefs.settings.value.clusterCode)
    }

    // ---- The cluster's map, opened up over the main screen for a moment ------------

    private val _peek = MutableStateFlow<AppId?>(null)
    /** The cluster's map, opened up over everything for now (see [peekClusterMap]), or null. */
    val peek: StateFlow<AppId?> = _peek.asStateFlow()

    /**
     * Opens the cluster's map up over the main screen, all of it, to pick a place, without
     * touching what the main screen has (ClusterPeek shows it, lent: see [lendClusterMap]).
     * One that is on the main screen already is shown there instead ([show]). False if
     * [app] isn't the cluster's map.
     */
    fun peekClusterMap(app: AppId, show: (ScreenId) -> Unit): Boolean {
        if (!keepsOnCluster(app)) return false
        if (_state.value.screenOf(app) == ScreenId.MAIN) {
            show(ScreenId.MAIN)
            return true
        }
        Log.i(CarBridge.TAG, "${app.pkg} opened up over the main screen")
        _peek.value = app
        return true
    }

    /** The cluster's map goes back to the cluster (see [peekClusterMap]). */
    fun closePeek() {
        if (_peek.value == null) return
        Log.i(CarBridge.TAG, "${_peek.value?.pkg} back from over the main screen")
        _peek.value = null
    }

    init {
        // The switch above the Driver screen, flipped.
        ui.launch {
            // (A map picked there is the navigation app too.)
            prefs.settings.map { it.mapOnCluster to it.navApp }.distinctUntilChanged().drop(1).collect {
                followSwitch(flipped = true)
                // Another map (or BYD's view) picked: the opened-up one closes.
                _peek.value?.let { if (!keepsOnCluster(it)) closePeek() }
            }
        }
    }

    // ---- The switch: BYD's own cluster, or the map -------------------------------

    /** The map the cluster shows while the switch is on: the chosen navigation app. */
    var clusterApp: () -> String? = { null }

    /** The switch is on and [app] is the map it keeps on the cluster. */
    fun keepsOnCluster(app: AppId): Boolean = prefs.settings.value.mapOnCluster && clusterApp() == app.pkg

    // ---- Apps' own pop-ups -------------------------------------------------------

    /** Shows a screen in Otility, zoomed in (set by the navigator, which the window follows). */
    var showScreen: (ScreenId) -> Unit = {}

    /** The last pop-up handled (the helper numbers them). */
    private var popupSeq = -1
    private val poppedAt = HashMap<AppId, Long>()

    /** Pop-ups the helper put back into Otility since the last look: the newest comes forward. */
    private fun takePopups(snap: CarSnapshot) {
        val fresh = snap.popups.filter { it.seq > popupSeq }
        if (fresh.isEmpty()) return
        popupSeq = fresh.maxOf { it.seq }
        // Not old ones (from before this Otility started, say).
        val p = fresh.filter { it.ago < POPUP_FRESH_MS }.maxByOrNull { it.seq } ?: return
        val app = AppId(p.pkg)
        val now = SystemClock.uptimeMillis()
        if (now - (poppedAt[app] ?: 0L) < POPUP_AGAIN_MS) return
        poppedAt[app] = now
        poppedUp(app)
    }

    /**
     * [app] opened a window of its own (Waze's "want to go there?", a settings page)
     * that Android put on the car's main screen, and the helper put back into Otility.
     * It comes forward as if tapped, so it can be answered: zoomed in on the main
     * screen if it's there; the cluster's map comes to the main screen (Back takes it
     * back); a hidden one (the Overview's map, a parked app) opens there full screen.
     * Never a question: the app asked for it, not the driver. Floating, it shows already.
     */
    fun poppedUp(app: AppId) {
        val at = _state.value.screenOf(app)
        Log.i(CarBridge.TAG, "${app.pkg} opened a window of its own; showing it (it's on ${at ?: "no screen"})")
        when {
            at == ScreenId.MAIN -> showScreen(ScreenId.MAIN)
            at == ScreenId.DRIVER && keepsOnCluster(app) -> {
                place(app, DropTarget(ScreenId.MAIN, Slot.FULL), confirm = false)
                showScreen(ScreenId.MAIN)
            }
            at != null || _pip.value == app -> Unit
            else -> {
                place(app, DropTarget(ScreenId.MAIN, Slot.FULL), confirm = false)
                showScreen(ScreenId.MAIN)
            }
        }
    }

    /** The Screens tab is zoomed in on the main screen (it says so). */
    private var mainZoomed = false
    /**
     * Until then, the cluster's map stays on the main screen where it was just put,
     * though nothing is zoomed in on it yet (the Screens tab is about to).
     */
    private var mainGraceUntil = 0L

    /** Zoomed in on the main screen, or out again (Back, another screen, another tab). */
    fun setMainZoomed(on: Boolean) {
        if (mainZoomed == on) return
        mainZoomed = on
        if (!on) {
            mainGraceUntil = 0L
            // After the Screens tab's change is done (this comes from its composition).
            ui.launch { followSwitch() }
        }
    }

    /**
     * The cluster's map, opened on the main screen, is used there: zoomed in on it, or
     * beside another app (a split). Then the cluster shows BYD's own view. Alone there
     * and zoomed out of (Back), it goes back to the cluster.
     */
    private fun keptOnMain(app: AppId, s: ScreensState): Boolean {
        val main = s[ScreenId.MAIN]
        if (app !in main.apps) return false
        return main is ScreenContent.Split || mainZoomed || SystemClock.uptimeMillis() < mainGraceUntil
    }

    /**
     * The switch: [pkg] on the cluster (it becomes the navigation app too, so places
     * open where the driver sees them), or null for BYD's own view.
     */
    fun setCluster(pkg: String?) = prefs.update { if (pkg == null) it.copy(mapOnCluster = false) else it.copy(mapOnCluster = true, navApp = pkg) }

    /**
     * Keeps the Driver screen as the switch says: the chosen map while it's on (started
     * if it isn't running, and back if it stopped), and BYD's own cluster while it's
     * off. Nothing else goes there. A map that leaves keeps running: it may be guiding,
     * and the Overview shows it. [flipped]: the switch was just turned.
     */
    private fun followSwitch(flipped: Boolean = false) {
        val s = _state.value
        val want = clusterApp()?.let(::AppId)?.takeIf { prefs.settings.value.mapOnCluster && catalog[it] != null }
        val now = s[ScreenId.DRIVER].apps.firstOrNull()
        if (want == now) return
        // In use on the main screen; it comes back once it isn't (not if the switch was
        // just turned to it: then it goes to the cluster).
        if (want != null && !flipped && keptOnMain(want, s)) {
            if (now != null) commit(s.with(ScreenId.DRIVER, ScreenContent.Idle))
            return
        }
        if (want == null) {
            // Switched to BYD: the cluster is the car's again. Later, only Otility's own
            // map is taken off; something the car put there itself stays.
            if (now == null || (!flipped && _clusterMap.value == null)) return
            commit(s.with(ScreenId.DRIVER, ScreenContent.Idle))
            return
        }
        if (!bridge.connected) return
        if (now != null) {
            // Another map there: BYD's view for a moment, so the cluster's window closes
            // cleanly before the new map gets one.
            commit(s.with(ScreenId.DRIVER, ScreenContent.Idle))
            ui.launch { delay(1500); followSwitch() }
            return
        }
        // The map leaves the main screen or the floating window for the cluster.
        if (_pip.value == want) _pip.value = null
        commit(Screens.close(s, want).with(ScreenId.DRIVER, ScreenContent.Single(want)))
    }

    private fun componentOf(app: AppId) = catalog[app]?.component?.flattenToShortString() ?: app.pkg

    /** The frame [app] gets on the driver cluster [d], map only (null: not a map the cluster can frame). */
    private fun clusterFrame(app: AppId, d: DisplayRow): ClusterFrame? = ClusterCrop.frameFor(app.pkg, d.w, d.h, d.dpi)

    /** Whether [app] would show on the driver cluster map only. */
    private fun framedOnCluster(app: AppId): Boolean =
        _snapshot.value?.driverDisplay?.let { clusterFrame(app, it) } != null

    /**
     * Puts [app] on the driver cluster map only, if it's Waze or Google Maps (and that's
     * switched on): it runs in a hidden display of its own, bigger than the cluster, and
     * Otility's window on the cluster shows the part that is only map.
     */
    private fun startClusterMap(app: AppId, d: DisplayRow): Boolean {
        val frame = clusterFrame(app, d) ?: return false
        val component = componentOf(app)
        Log.i(CarBridge.TAG, "${app.pkg} on the cluster, map only: ${frame.width}x${frame.height} from ${frame.left},${frame.top}")
        _clusterMap.value = ClusterMap(app, component, frame, d.id)
        lastLocalChange = System.currentTimeMillis()
        hosts.prepare(app, component, frame.width, frame.height, frame.dpi, pull = true)
        openClusterWindow(d.id)
        return true
    }

    private fun openClusterWindow(displayId: Int) {
        clusterOpenedAt = System.currentTimeMillis()
        bridge.launchOwn(CLUSTER_WINDOW, displayId)
    }

    /**
     * The cluster's map-only window closes (by itself) when its app leaves the driver
     * screen. The app keeps its hidden display and runs on: it may be guiding, and the
     * Overview's map (or the main screen, whose view takes the display over) shows it.
     * (One moved to the passenger screen has given its display back already.)
     */
    private fun keepClusterMap(s: ScreensState) {
        val map = _clusterMap.value ?: return
        if (map.app in s[ScreenId.DRIVER].apps) return
        Log.i(CarBridge.TAG, "${map.app.pkg} left the cluster")
        _clusterMap.value = null
    }

    /**
     * The map the cluster's map-only window shows: the one Otility put there or, after
     * Otility restarted ([first] look, or the window still there), the driver screen's
     * app from before, framed again. Opens the window again if it has gone. Null once
     * that app has stopped (or is on the passenger screen now).
     */
    private fun clusterMapApp(
        s: ScreensState, snap: CarSnapshot, d: DisplayRow, windowThere: Boolean, first: Boolean,
    ): AppId? {
        val map = _clusterMap.value?.takeIf { it.displayId == d.id }
        val app = map?.app
            ?: (s[ScreenId.DRIVER] as? ScreenContent.Single)?.app?.takeIf { windowThere || first }
            ?: return null
        val passenger = snap.passengerDisplay?.id
        if (passenger != null && snap.task(app.pkg)?.displayId == passenger) return null
        if (snap.task(app.pkg) == null) {
            // Give a starting (or restarting) app a few checks.
            val n = (missing[app] ?: 0) + 1
            missing[app] = n
            if (n < 3 && map != null) return app
            missing.remove(app)
            return null
        }
        missing.remove(app)
        if (map == null) return app.takeIf { startClusterMap(it, d) }
        if (!windowThere && System.currentTimeMillis() - clusterOpenedAt > CLUSTER_RETRY_MS) {
            Log.i(CarBridge.TAG, "the cluster's map window has gone; opening it again")
            openClusterWindow(d.id)
        }
        return app
    }

    private fun label(app: AppId) = catalog[app]?.label ?: app.pkg

    // ---- Changes -------------------------------------------------------------

    /**
     * Puts an app somewhere. Asks first if that would replace an app, unless
     * [confirm] is off (dragging from the dock while zoomed in) or the driver
     * switched confirmations off. Returns the question when one was asked;
     * [onCancel] runs if the driver says no.
     */
    fun place(app: AppId, target: DropTarget, confirm: Boolean = true, onCancel: () -> Unit = {}): Alert? {
        // Only the switch puts anything on the cluster. The map it keeps there may come to
        // the main screen for a while (see keptOnMain), not to the passenger screen.
        val clusterMap = keepsOnCluster(app)
        if (target.screen == ScreenId.DRIVER || (clusterMap && target.screen != ScreenId.MAIN)) return null
        val change = Screens.place(_state.value, app, target)
        val floating = _pip.value == app
        if (change.state == _state.value && !floating) return null
        val apply = {
            if (_pip.value == app) {
                // Leaving picture in picture for a screen.
                _pip.value = null
                val framed = target.screen == ScreenId.DRIVER && framedOnCluster(app)
                if (target.screen != ScreenId.MAIN && !framed) hosts.release(app, closeApp = false)
            }
            // Before the Screens tab zooms in on it: not straight back to the cluster.
            if (clusterMap) mainGraceUntil = SystemClock.uptimeMillis() + MAIN_GRACE_MS
            // Worked out again: the layout may have changed while the question was up.
            commit(Screens.place(_state.value, app, target).state)
        }
        if (confirm && change.replacesSomething && prefs.settings.value.confirmReplace) {
            val names = change.closes.joinToString(" and ") { label(it.first) }
            val where = target.screen.label
            return Alerts.confirm(
                title = "Replace $names?",
                message = "${label(app)} opens on the $where screen and $names closes.",
                action = "Replace",
                onCancel = onCancel,
                onConfirm = apply,
            )
        }
        apply()
        return null
    }

    /**
     * Tapping an app (the sidebar, the Apps tab) to have it on [on], full screen,
     * and then [show] that screen (zoomed in):
     *  - already there: show that screen;
     *  - floating: it lands on [on];
     *  - on another screen: ask whether to move it here (next to or instead of
     *    what's here) or show it where it is;
     *  - not open, and [on] already has an app: ask whether it goes next to that
     *    app or replaces it (with confirmations on, also while zoomed in: a tap is
     *    easy to make by accident, and it would close both halves of a split).
     */
    fun tap(app: AppId, on: ScreenId, show: (ScreenId) -> Unit) {
        if (on == ScreenId.DRIVER) return tap(app, ScreenId.MAIN, show)
        // The cluster's map comes to the main screen, all of it, as any app does, and the
        // cluster shows BYD's own view meanwhile (see keptOnMain). Never the passenger's.
        val clusterMap = keepsOnCluster(app)
        if (clusterMap && on != ScreenId.MAIN) return tap(app, ScreenId.MAIN, show)
        val state = _state.value
        // On the cluster it isn't open anywhere the driver uses it: no question about moving it.
        val from = state.screenOf(app)?.takeUnless { clusterMap && it == ScreenId.DRIVER }
        val name = label(app)
        when {
            _pip.value == app -> { place(app, DropTarget(on, Slot.FULL)); show(on) }
            from == on -> show(on)
            // The cluster's map, while the main screen has something else (YouTube, say):
            // first of all, only to use it for a moment (opened up over the main screen,
            // which keeps its app), besides bringing it there for good. Asked even with
            // "ask before replacing" off: these are different things, not a replace.
            clusterMap && on == ScreenId.MAIN && state.screenOf(app) == ScreenId.DRIVER && state[on] != ScreenContent.Idle -> Alerts.show(
                Alert(
                    title = "$name is on the Driver cluster",
                    message = "Control it here for a moment (${state[on].apps.joinToString(" and ") { label(it) }} stays on the Main screen), or bring it to the Main screen.",
                    buttons = listOf(AlertButton("Control Only") { peekClusterMap(app, show) }) +
                        hereChoices(app, on, verb = "Open") { show(on) } +
                        AlertButton("Cancel", Role.CANCEL),
                ),
            )
            from != null -> Alerts.show(
                Alert(
                    title = "$name is on the ${from.label} screen",
                    message = "Move it to the ${on.label} screen, or go to it where it is.",
                    buttons = hereChoices(app, on, verb = "Move") { show(on) } +
                        AlertButton("Show on ${from.label}") { show(from) } +
                        AlertButton("Cancel", Role.CANCEL),
                ),
            )
            state[on] == ScreenContent.Idle || !prefs.settings.value.confirmReplace -> {
                place(app, DropTarget(on, Slot.FULL), confirm = false)
                show(on)
            }
            else -> Alerts.show(
                Alert(
                    title = "Open $name",
                    message = "${state[on].apps.joinToString(" and ") { label(it) }} ${if (state[on].apps.size > 1) "are" else "is"} on the ${on.label} screen.",
                    buttons = hereChoices(app, on, verb = "Open") { show(on) } + AlertButton("Cancel", Role.CANCEL),
                ),
            )
        }
    }

    /** Ways to put [app] on [on], given what's there now; [then] after it's there. */
    private fun hereChoices(app: AppId, on: ScreenId, verb: String, then: () -> Unit): List<AlertButton> {
        fun go(slot: Slot): () -> Unit = { place(app, DropTarget(on, slot), confirm = false); then() }
        return when (val c = _state.value[on]) {
            ScreenContent.Idle -> listOf(AlertButton("$verb to ${on.label}", action = go(Slot.FULL)))
            is ScreenContent.Single ->
                if (on.canSplit) listOf(
                    AlertButton("Next to ${label(c.app)}", action = go(Slot.RIGHT)),
                    AlertButton("Replace ${label(c.app)}", Role.DESTRUCTIVE, go(Slot.FULL)),
                )
                else listOf(AlertButton("Replace ${label(c.app)}", Role.DESTRUCTIVE, go(Slot.FULL)))
            is ScreenContent.Split -> listOf(
                AlertButton("Replace ${label(c.left)}", Role.DESTRUCTIVE, go(Slot.LEFT)),
                AlertButton("Replace ${label(c.right)}", Role.DESTRUCTIVE, go(Slot.RIGHT)),
            )
        }
    }

    /** Tapping an app's icon above a screen: ask, then close it. */
    fun requestClose(app: AppId) {
        val on = _state.value.screenOf(app) ?: return
        Alerts.confirm(
            title = "Close ${label(app)}?",
            message = "It closes on the ${on.label} screen.",
            action = "Close",
        ) { commitClosing(Screens.close(_state.value, app)) }
    }

    /** Closes [app] at once: its button above the screen already asked, by turning red. Undo puts it back. */
    fun close(app: AppId) {
        val before = _state.value
        val next = Screens.close(before, app)
        if (next == before) return
        commitClosing(next)
        _closed.value = Closed(app, before, _state.value, System.currentTimeMillis())
    }

    /** An app just closed with its red button, for a moment, to put back (see [undoClose]). */
    class Closed(val app: AppId, val before: ScreensState, val after: ScreensState, val at: Long)

    private val _closed = MutableStateFlow<Closed?>(null)
    val closed: StateFlow<Closed?> = _closed.asStateFlow()

    fun forgetClosed(c: Closed) { _closed.compareAndSet(c, null) }

    /**
     * Opens the app just closed again where it was. The layout goes back as it was if
     * nothing else changed meanwhile; otherwise the app goes back on its screen (its
     * half, if the other app is still there). It starts afresh: closing closed it.
     */
    fun undoClose() {
        val c = _closed.value ?: return
        _closed.value = null
        if (c.app in _state.value.running) return
        if (_state.value == c.after) { commit(c.before); return }
        val screen = c.before.screenOf(c.app) ?: return
        val slot = when (val was = c.before[screen]) {
            is ScreenContent.Split -> {
                val other = if (was.left == c.app) was.right else was.left
                when {
                    other !in _state.value[screen].apps -> Slot.FULL
                    was.left == c.app -> Slot.LEFT
                    else -> Slot.RIGHT
                }
            }
            else -> Slot.FULL
        }
        place(c.app, DropTarget(screen, slot), confirm = false)
    }

    fun swap(screen: ScreenId) = commit(Screens.swap(_state.value, screen))

    /** Where the divider was let go (the Screens tab moves the picture while it's held). */
    fun releaseDivider(screen: ScreenId, ratio: Float) {
        // The split may have gone meanwhile (a half closed or crashed): nothing to do.
        if (_state.value[screen] !is ScreenContent.Split) return
        val (next, _) = Screens.releaseDivider(_state.value, screen, ratio)
        commit(next)
    }

    fun clearAll() {
        if (_state.value.running.isEmpty()) return
        Alerts.confirm("Close every app?", "The main and passenger screens go back to idle.", "Close All") {
            // The cluster keeps what the switch says.
            commitClosing(ScreensState().with(ScreenId.DRIVER, _state.value[ScreenId.DRIVER]))
        }
    }

    /**
     * [commit], for apps the driver closed: main-screen apps really close. Otherwise
     * they'd only be parked, and a music or video app would keep playing, unseen,
     * for minutes. (Side-screen apps are closed by [reconcile].)
     */
    private fun commitClosing(closing: ScreensState) {
        var next = closing
        // The cluster's map, closed on the main screen, goes back to the cluster instead
        // (it may be guiding): it isn't closed.
        val map = _state.value[ScreenId.MAIN].apps.firstOrNull { keepsOnCluster(it) && it !in next.running }
        if (map != null && next[ScreenId.DRIVER] == ScreenContent.Idle) {
            mainGraceUntil = 0L
            next = next.with(ScreenId.DRIVER, ScreenContent.Single(map))
        }
        val closed = _state.value[ScreenId.MAIN].apps.filter { it !in next.running }
        commit(next)
        for (app in closed) if (app != _pip.value) hosts.release(app, closeApp = true)
    }

    // ---- Picture in picture ----------------------------------------------------

    private val _pip = MutableStateFlow<AppId?>(null)
    /** An app floating in a small window over Otility, off every screen. */
    val pip: StateFlow<AppId?> = _pip.asStateFlow()

    fun pictureInPicture(app: AppId) {
        val old = _pip.value
        if (old == app) return
        _pip.value = app
        if (old != null) hosts.park(old)
        val next = Screens.close(_state.value, app)
        if (next != _state.value) commit(next)
    }

    /** Expands the floating app onto the main screen (asks first if that replaces something). */
    fun expandPip() {
        val app = _pip.value ?: return
        place(app, DropTarget(ScreenId.MAIN, Slot.FULL))
    }

    fun closePip() {
        val app = _pip.value ?: return
        _pip.value = null
        hosts.park(app)
    }

    fun applyLayout(layout: Layout) {
        // An app floats or is on a screen, never both: the layout's place wins.
        // The cluster keeps what the switch says, whatever a layout saved before had there.
        val wanted = layout.state.with(ScreenId.DRIVER, _state.value[ScreenId.DRIVER])
        if (_pip.value?.let { it in wanted.running } == true) _pip.value = null
        commit(wanted)
    }

    fun saveLayout(name: String) = prefs.saveLayout(Layout(name, _state.value))

    private fun commit(wanted: ScreensState) {
        val next = Screens.clamped(wanted)
        val prev = _state.value
        _state.value = next
        // Apps that came onto the main or passenger screen: used lately.
        val shown = { s: ScreensState -> s[ScreenId.MAIN].apps + s[ScreenId.PASSENGER].apps }
        val before = shown(prev).toSet()
        shown(next).filter { it !in before }.asReversed().forEach(prefs::noteOpened)
        lastLocalChange = System.currentTimeMillis()
        prefs.saveLastLayout(next)
        reconcile(prev, next)
        keepClusterMap(next)
        // The cluster's map gone from the main screen (another app took its place): back
        // to the cluster now, not at the next look.
        if (prev[ScreenId.MAIN].apps.any { keepsOnCluster(it) && it !in next.running }) ui.launch { followSwitch() }
    }

    /** Makes the car match the new layout (the main screen's apps attach as their views appear). */
    private fun reconcile(prev: ScreensState, next: ScreensState) {
        // Apps that are gone from every screen. Main-screen apps are parked even
        // while the helper is away, so a reconnect doesn't bring them back.
        val gone = prev.running - next.running
        for (app in gone) {
            if (app == _pip.value) continue // it moved into the floating window
            // The cluster's map runs on (see keepClusterMap).
            if (app in prev[ScreenId.DRIVER].apps) continue
            if (app in prev[ScreenId.MAIN].apps) hosts.park(app)
            else if (bridge.connected) bridge.close(app)
        }
        if (!bridge.connected) {
            // Nothing to launch yet, but apps that left the main screen give up their
            // hidden displays, so the helper doesn't remake them when it connects.
            for (screen in listOf(ScreenId.DRIVER, ScreenId.PASSENGER)) {
                for (app in next[screen].apps - prev[screen].apps.toSet()) hosts.release(app, closeApp = false)
            }
            return
        }
        val snap = _snapshot.value
        if (snap == null) {
            // Just connected, before the first look at the car: get one now, or apps
            // put on the side screens wouldn't open (and the next look would undo them).
            ui.launch {
                val fresh = withContext(bridge.scope.coroutineContext) { bridge.snapshot() } ?: return@launch
                _snapshot.value = fresh
                openOnSides(prev, next, fresh)
            }
            return
        }
        openOnSides(prev, next, snap)
    }

    private fun openOnSides(prev: ScreensState, next: ScreensState, snap: CarSnapshot) {
        val settings = prefs.settings.value
        for (screen in listOf(ScreenId.DRIVER, ScreenId.PASSENGER)) {
            val display = snap.displayFor(screen) ?: continue
            val added = next[screen].apps - prev[screen].apps.toSet()
            for (app in added) {
                // Waze or Google Maps on the cluster: map only.
                if (screen == ScreenId.DRIVER && startClusterMap(app, display)) continue
                val component = catalog[app]?.component?.flattenToShortString() ?: app.pkg
                bridge.launchOrMove(app, component, display.id)
                // Give back its hidden display, if it had one (it was on the main screen,
                // or parked there, whose timer would otherwise close it here later).
                hosts.release(app, closeApp = false)
            }
            // The cluster shows the map channel while the map is there, and its own
            // view again when it goes.
            if (screen == ScreenId.DRIVER) {
                val was = prev[screen] != ScreenContent.Idle
                val now = next[screen] != ScreenContent.Idle
                if (!was && now) {
                    // Not while the main screen has the map, or a signal is on: after.
                    held = clusterMapLent || signalling
                    if (!held) bridge.cluster(true, settings.clusterCode)
                }
                if (was && !now) {
                    held = false
                    bridge.cluster(false, settings.clusterCode)
                }
            }
        }
    }

    // ---- Keeping in step with the car -----------------------------------------

    private var started = false

    /** How many checks in a row each main-screen app's window has been missing. */
    private val missing = HashMap<AppId, Int>()

    /** Polls the car while Otility is open, so the screens show what's really there. */
    fun startSync() {
        if (started) return
        started = true
        ui.launch {
            var first = true
            var tick = 0
            while (isActive) {
                if (bridge.connected) {
                    val snap = withContext(bridge.scope.coroutineContext) { bridge.snapshot() }
                    if (snap != null) {
                        _snapshot.value = snap
                        if (System.currentTimeMillis() - lastLocalChange > 3000) adopt(snap, first)
                        first = false
                        if (startupPending) openStartup(snap)
                        followSwitch()
                        takePopups(snap)
                        // Every ten seconds, from the second look on (after the startup apps).
                        if (tick++ % 5 == 1) keepMapRunning(snap)
                    }
                }
                delay(2000)
            }
        }
    }

    /** The map app the Overview shows (the one used last, or the navigation app). */
    var mapApp: () -> String? = { null }

    /** The size the Overview's map card gives its app; told by the card once it's laid out. */
    @Volatile var mapSize: Pair<Int, Int>? = null

    /**
     * With the map on the Overview, its app runs all the time in a hidden display, so
     * the map is live (not its icon) the moment you look. Not while it's on one of the
     * screens (the main screen keeps it anyway), or running anywhere else (opened
     * from the car's own home screen, say): that's the driver's. Except when it has
     * landed behind Otility on the main screen, out of sight: that one comes back.
     */
    private fun keepMapRunning(snap: CarSnapshot) {
        if (prefs.widgets.value.map == null) return
        val app = mapApp()?.let { AppId(it) } ?: return
        if (_state.value.screenOf(app) != null) return
        val task = snap.task(app.pkg)
        val behindOtility = task != null && task.displayId == 0 && !task.visible && snap.task(OTILITY)?.visible == true
        if (task != null && task.displayId != hosts.displayOf(app) && !behindOtility) return
        val screen0 = snap.displays.firstOrNull { it.id == 0 } ?: return
        val component = catalog[app]?.component?.flattenToShortString() ?: return
        val (top, bottom) = mainBars
        val (w, h) = mapSize ?: (screen0.w to (screen0.h - top - bottom).coerceAtLeast(screen0.h / 2))
        hosts.keepRunning(app, component, w and 1.inv(), h and 1.inv(), screen0.dpi)
    }

    /**
     * Opens the startup apps: on the passenger screen (replacing what the car shows
     * there), and starts the main screen's in their hidden displays
     * so they're running before Otility zooms in on them.
     */
    private fun openStartup(snap: CarSnapshot) {
        startupPending = false
        val apps = startup ?: return
        // The main screen's again too: the first look may have found its app on a side
        // screen and moved it there. Not if the driver has already changed things.
        // (Not the cluster's: the switch above it says what it shows.)
        val screens = listOf(ScreenId.PASSENGER) + if (lastLocalChange == 0L) listOf(ScreenId.MAIN) else emptyList()
        val next = apps.applyTo(_state.value, screens)
        if (next != _state.value) commit(next)
        val screen0 = snap.displays.firstOrNull { it.id == 0 } ?: return
        val s = _state.value
        for (app in s[ScreenId.MAIN].apps) {
            if (hosts.displayOf(app) != null) continue
            val (w, h) = hostSizeGuess(s, app, screen0.w, screen0.h)
            val component = catalog[app]?.component?.flattenToShortString() ?: app.pkg
            hosts.prepare(app, component, w, h, screen0.dpi)
        }
    }

    /** Reads the driver and passenger screens from the car (the main screen is ours). */
    private fun adopt(snap: CarSnapshot, first: Boolean) {
        var s = _state.value
        var toFrame: Pair<AppId, DisplayRow>? = null
        for (screen in listOf(ScreenId.DRIVER, ScreenId.PASSENGER)) {
            val d = snap.displayFor(screen) ?: continue
            val top = snap.tasks.firstOrNull {
                it.displayId == d.id && it.type == 1 && (catalog[AppId(it.pkg)] != null || it.isClusterWindow())
            }
            if (screen == ScreenId.DRIVER && (top == null || top.isClusterWindow())) {
                // Otility's own window, showing a map (or about to).
                val app = clusterMapApp(s, snap, d, windowThere = top != null, first = first)
                if (app != null) {
                    if (s[screen] != ScreenContent.Single(app)) s = s.with(screen, ScreenContent.Single(app))
                    continue
                }
                if (top != null) {
                    // Its map has stopped; the window closes by itself.
                    if (s[screen] != ScreenContent.Idle) s = s.with(screen, ScreenContent.Idle)
                    continue
                }
            }
            val content = if (top == null) ScreenContent.Idle else ScreenContent.Single(AppId(top.pkg))
            // Waze or Google Maps right on the cluster (opened there by the car, or before
            // "Map only" was on): framed like the ones Otility puts there.
            if (screen == ScreenId.DRIVER && top != null && top.userId == 0 && prefs.settings.value.mapOnCluster &&
                clusterFrame(AppId(top.pkg), d) != null
            ) {
                toFrame = AppId(top.pkg) to d
            }
            if (s[screen] != content) {
                // The passenger's own copy (BYD runs that screen as a second user): the
                // driver's copy of the same app stays where it is, so leave this screen.
                if (top != null && top.userId != 0 && s.screenOf(AppId(top.pkg)).let { it != null && it != screen }) continue
                if (top != null && top.userId == 0) {
                    val app = AppId(top.pkg)
                    s = Screens.close(s, app)
                    // Opened there from the car itself: it's not on the main screen or
                    // floating any more, and a parked display's timer mustn't close it.
                    if (_pip.value == app) _pip.value = null
                    hosts.release(app, closeApp = false)
                }
                s = s.with(screen, content)
            }
        }
        // A main-screen app whose hidden display went away (Otility or the helper
        // restarted) lands on the car's real main screen, over Otility, and Otility's
        // own view, which would host it again, isn't seen. Take it back now.
        // The helper keeps the last Otility's displays for a while, with the apps still
        // in them (hidden): those are taken back the same way, display and all.
        fun stray(app: AppId): Boolean {
            val d = snap.task(app.pkg)?.displayId ?: return false
            return d == 0 || (d !in snap.hosts && snap.displays.any { it.id == d && it.name == "otility_${app.pkg}" })
        }
        val screen0 = snap.displays.firstOrNull { it.id == 0 }
        val reparked = HashSet<AppId>()
        if (screen0 != null) {
            for (app in s[ScreenId.MAIN].apps) {
                if (hosts.displayOf(app) != null || !stray(app)) continue
                val (w, h) = hostSizeGuess(s, app, screen0.w, screen0.h)
                val component = catalog[app]?.component?.flattenToShortString() ?: app.pkg
                hosts.prepare(app, component, w, h, screen0.dpi)
            }
            // Apps the last Otility had parked or floating fell onto the car's main screen
            // too, over Otility. Park them again (they close after the usual few minutes).
            // Only once, right after Otility starts: later, an app on the car's main
            // screen was opened there on purpose.
            for (app in hosts.takeHostedBefore()) {
                if (s.screenOf(app) != null || app == _pip.value || hosts.displayOf(app) != null) continue
                if (!stray(app)) continue
                val component = catalog[app]?.component?.flattenToShortString() ?: app.pkg
                // The Overview's map is put away but never closed: it may be guiding.
                val map = app.pkg == prefs.lastMap.value
                hosts.prepare(app, component, screen0.w and 1.inv(), screen0.h and 1.inv(), screen0.dpi, parked = !map)
                reparked += app
            }
        }
        // Apps on the main screen that closed or crashed on their own: take them off
        // (after a few checks, so an app that's still starting isn't caught).
        for (app in s[ScreenId.MAIN].apps) {
            val display = hosts.displayOf(app)
            if (display == null || snap.task(app.pkg) != null || snap.tasks.any { it.displayId == display }) {
                missing.remove(app)
                continue
            }
            val n = (missing[app] ?: 0) + 1
            missing[app] = n
            if (n >= 3) {
                missing.remove(app)
                s = Screens.close(s, app)
                hosts.release(app, closeApp = false)
            }
        }
        // The app that was on the main screen when Otility opened.
        if (first && s[ScreenId.MAIN] == ScreenContent.Idle) {
            val behind = snap.tasks.firstOrNull {
                it.displayId == 0 && it.type == 1 && it.pkg != OTILITY &&
                    catalog[AppId(it.pkg)] != null && s.screenOf(AppId(it.pkg)) == null && AppId(it.pkg) !in reparked
            }
            if (behind != null) s = s.with(ScreenId.MAIN, ScreenContent.Single(AppId(behind.pkg)))
        }
        if (s != _state.value) {
            _state.value = s
            prefs.saveLastLayout(s)
        }
        keepClusterMap(s)
        toFrame?.let { (app, d) ->
            if (System.currentTimeMillis() - clusterOpenedAt > CLUSTER_RETRY_MS) startClusterMap(app, d)
        }
    }

    /**
     * The car's bars over the main screen, top and bottom, in pixels: apps there are
     * sized to the part between them. Told by Otility's window once it's laid out.
     */
    @Volatile var mainBars: Pair<Int, Int> = 0 to 0

    /** About the size the Screens tab gives [app] on the main screen; the view corrects it when it appears. */
    private fun hostSizeGuess(s: ScreensState, app: AppId, screenW: Int, screenH: Int): Pair<Int, Int> {
        val (top, bottom) = mainBars
        val appsH = (screenH - top - bottom).coerceAtLeast(screenH / 2) and 1.inv()
        val split = s[ScreenId.MAIN] as? ScreenContent.Split ?: return (screenW and 1.inv()) to appsH
        val gap = Screens.splitGap(screenW.toFloat())
        val ratio = split.ratio.coerceIn(Screens.MIN_RATIO, Screens.MAX_RATIO)
        val share = if (split.left == app) ratio else 1f - ratio
        return (((screenW - gap) * share).toInt() and 1.inv()) to appsH
    }
}

/** Otility's own package, to tell whether it's in front on the main screen. */
private const val OTILITY = "app.leopard.cockpit"

/** Otility's window on the driver cluster that shows a map, map only. */
private const val CLUSTER_WINDOW = ".ClusterMapActivity"

/** How long before a missing cluster window is opened again. */
private const val CLUSTER_RETRY_MS = 10_000L

/** How long the cluster's map, just put on the main screen, waits there to be zoomed in on. */
private const val MAIN_GRACE_MS = 6_000L
/** A pop-up older than this when Otility first hears of it is left where the helper put it. */
private const val POPUP_FRESH_MS = 8_000L
/** The same app's pop-ups this close together come forward once. */
private const val POPUP_AGAIN_MS = 5_000L

private fun TaskRow.isClusterWindow() = pkg == OTILITY && component.endsWith("ClusterMapActivity")

/**
 * Waze or Google Maps on the driver cluster, map only: the app, how it's framed, and the
 * cluster's display. A new [epoch] has the cluster's window take the app back (after
 * the main screen had it).
 */
data class ClusterMap(val app: AppId, val component: String, val frame: ClusterFrame, val displayId: Int, val epoch: Int = 0)
