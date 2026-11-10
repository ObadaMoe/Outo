package app.leopard.cockpit.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.data.AppCatalog
import app.leopard.cockpit.data.Prefs
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.DropTarget
import app.leopard.cockpit.screens.ScreenContent
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.screens.ScreensController
import app.leopard.cockpit.screens.Slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import app.leopard.cockpit.ui.Alert
import app.leopard.cockpit.ui.Alerts
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends places to the navigation app on the screen the driver chose, and keeps
 * the "read the directions" permission in step with the setting.
 */
class Navigator(
    private val context: Context,
    private val prefs: Prefs,
    private val catalog: AppCatalog,
    private val screens: ScreensController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bridge get() = screens.bridge
    private val hosts get() = screens.hosts

    /**
     * A screen the Screens tab should zoom into (the map just opened there). It
     * stays until the UI takes it ([shown]), so it isn't lost when Otility's
     * window is only just opening.
     */
    private val _show = MutableStateFlow<ScreenId?>(null)
    val show: StateFlow<ScreenId?> = _show.asStateFlow()

    fun shown() { _show.value = null }

    /** Shows [screen] in Otility, zoomed in. */
    fun showScreen(screen: ScreenId) { _show.value = screen }

    data class Result(val ok: Boolean, val message: String, val at: Long = System.currentTimeMillis())

    private val _last = MutableStateFlow<Result?>(null)
    /** What happened to the last place sent, for Settings and the phone. */
    val last: StateFlow<Result?> = _last.asStateFlow()

    private var job: Job? = null

    companion object {
        val APPS = listOf(
            "com.waze" to "Waze",
            "com.google.android.apps.maps" to "Google Maps",
            "app.revanced.android.apps.maps" to "Google Maps (ReVanced)",
            "com.here.app.maps" to "HERE WeGo",
            "com.sygic.aura" to "Sygic",
        )
        private const val TAG = "Otility"
        /** How long the map reader may take to start before it's switched off and on. */
        private const val READER_START = 6_000L
        /** The apps "Straight to the driver cluster" applies to. */
        val CLUSTER_APPS = setOf("com.waze", "com.google.android.apps.maps", "app.revanced.android.apps.maps")

        /** Links the app understands, best first. */
        fun uris(pkg: String, p: Place): List<String> {
            val geo = when {
                p.hasPoint -> "geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}" + (p.name?.let { "(${Places.encode(it)})" } ?: "")
                else -> "geo:0,0?q=${Places.encode(p.query ?: p.title)}"
            }
            return when (pkg) {
                "com.waze" -> when {
                    p.hasPoint -> listOf(
                        "https://waze.com/ul?ll=${p.lat},${p.lng}&navigate=yes",
                        "waze://?ll=${p.lat},${p.lng}&navigate=yes",
                    )
                    p.link != null && p.query == null && wazeLink(p.link) -> listOf(
                        p.link + (if ('?' in p.link) "&" else "?") + "navigate=yes",
                        p.link,
                    )
                    // Another map's link the car couldn't follow (offline, say), and no
                    // name to search for: Waze can't open it, but it's all there is.
                    p.link != null && p.query == null && (p.name == null || p.name in Places.PLACEHOLDERS) -> listOf(p.link)
                    // Otherwise Waze searches for the place by its name.
                    else -> {
                        val q = Places.encode(p.query ?: p.title)
                        listOf("https://waze.com/ul?q=$q&navigate=yes", "waze://?q=$q&navigate=yes")
                    }
                }
                "com.google.android.apps.maps", "app.revanced.android.apps.maps" -> when {
                    !p.hasPoint && p.query == null && p.link != null -> listOf(p.link)
                    else -> listOf(
                        "google.navigation:q=" + if (p.hasPoint) "${p.lat},${p.lng}" else Places.encode(p.query ?: p.title),
                        geo,
                    )
                }
                else -> if (!p.hasPoint && p.query == null && p.link != null) listOf(p.link) else listOf(geo)
            }
        }

        /** A link Waze itself opens: waze://… or waze.com. */
        internal fun wazeLink(link: String): Boolean {
            val l = link.trim().lowercase()
            if (l.startsWith("waze:")) return true
            val host = l.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':')
            return host == "waze.com" || host.endsWith(".waze.com")
        }
    }

    init {
        // The map app used last: the Overview's map widget shows it, as CarPlay does.
        scope.launch {
            screens.state.collect { st ->
                st.running.firstOrNull { a -> APPS.any { it.first == a.pkg } }?.let { prefs.setLastMap(it.pkg) }
            }
        }
        // Grant Android's notification access when "Show directions" is on, including
        // after the helper (which grants it) reconnects. It's never taken away when
        // directions are off: Now Playing reads the media apps through the same access.
        // Only when the helper or the setting changes, not on every other setting.
        scope.launch {
            combine(HelperConnection.helper, prefs.settings) { h, s -> h to s.showDirections }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second }
                .collect { (helper, wanted) -> if (helper != null && wanted && !hasAccess()) setAccess(true) }
        }
        // The same for the map reader (Android's accessibility), which reads only Waze and Google Maps.
        scope.launch {
            combine(HelperConnection.helper, prefs.settings) { h, s -> h to (s.showDirections && s.readMapScreen) }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second }
                .collectLatest { (helper, wanted) ->
                    if (helper == null) return@collectLatest
                    if (wanted != readerOn()) setReader(wanted)
                    if (wanted) restartStuckReader()
                }
        }
    }

    fun installed(): List<Pair<String, String>> = APPS.filter { catalog[AppId(it.first)] != null }

    /** The chosen navigation app, or the first one installed. */
    fun navApp(): String? {
        val want = prefs.settings.value.navApp
        return if (catalog[AppId(want)] != null) want else installed().firstOrNull()?.first
    }

    /**
     * The map on the driver cluster (the Overview's map is that one, opened up from
     * there), else the one used last (opened on a screen, or sent a place), else the chosen one.
     */
    fun mapApp(): String? = (screens.clusterMap.value?.app?.pkg ?: prefs.lastMap.value)
        ?.takeIf { catalog[AppId(it)] != null } ?: navApp()

    fun navLabel(): String = navApp()?.let { catalog[AppId(it)]?.label } ?: "Navigation"

    /** Where places open: the chosen screen, or wherever the map already is (main if nowhere). */
    fun targetScreen(pkg: String?): ScreenId {
        // Nothing but the switch's map goes on the cluster.
        val chosen = prefs.settings.value.navScreen?.takeIf { it != ScreenId.DRIVER }
        if (chosen != null) return chosen
        return pkg?.let { screens.state.value.screenOf(AppId(it)) }?.takeIf { it != ScreenId.DRIVER } ?: ScreenId.MAIN
    }

    /**
     * The map is on the driver cluster (the switch above the Driver screen): a place
     * opens in it there, at once, and the main screen stays as it is.
     */
    fun toCluster(pkg: String? = navApp()): Boolean = pkg != null && screens.keepsOnCluster(AppId(pkg))

    /**
     * Opens [place] in the navigation app on [screen] (or the usual one) and
     * starts the route. Returns a sentence to show on the phone.
     */
    fun go(place: Place, screen: ScreenId? = null): Result {
        val pkg = navApp() ?: return result(false, "There's no navigation app in the car.")
        prefs.addRecentPlace(place)
        prefs.setLastMap(pkg)
        val app = AppId(pkg)
        val label = catalog[app]?.label ?: "the map"
        val uris = uris(pkg, place)
        // The cluster's map, brought to the main screen for now: the place opens there.
        val clusterMapOnMain = toCluster(pkg) && screens.state.value.screenOf(app) == ScreenId.MAIN
        val onCluster = toCluster(pkg) && !clusterMapOnMain
        val target = when {
            onCluster -> ScreenId.DRIVER
            clusterMapOnMain -> ScreenId.MAIN
            else -> screen?.takeIf { it != ScreenId.DRIVER } ?: targetScreen(pkg)
        }
        Log.i(TAG, "place for $pkg on $target: ${place.title}")
        job?.cancel()

        if (!bridge.connected) {
            // Without the helper (a phone or emulator): open it over Otility.
            return if (openHere(pkg, uris)) result(true, "${place.title} is open in $label.")
            else result(false, "$label couldn't open that place.")
        }

        // Put the map on its screen now (this runs on the main thread). If that
        // replaces an app, the driver is asked first and the phone is told so.
        val state = screens.state.value
        var question: Alert? = null
        if (state.screenOf(app) != target) {
            val slot = when {
                target != ScreenId.MAIN -> Slot.FULL
                state[ScreenId.MAIN] == ScreenContent.Idle -> Slot.FULL
                // Next to what's playing, on the driver's side.
                else -> Slot.LEFT
            }
            question = screens.place(app, DropTarget(target, slot), confirm = !onCluster, onCancel = {
                job?.cancel()
                result(false, "$label wasn't opened: it was cancelled in the car.")
            })
        }
        if (onCluster) {
            // The cluster shows the driver screen's map now; the main screen stays as it is.
            screens.showClusterChannel()
        } else {
            _show.value = target
        }

        job = scope.launch {
            // Wait for the driver's answer, if there was a question.
            val placed = withTimeoutOrNull(60_000) { screens.state.first { it.screenOf(app) == target } }
            if (placed == null) {
                question?.let(Alerts::dismiss)
                result(false, "$label wasn't opened.")
                return@launch
            }
            val display = withTimeoutOrNull(20_000) { displayFor(app, target) }
            if (display == null) {
                result(false, "The ${target.label.lowercase()} screen isn't available right now.")
                return@launch
            }
            val ok = withContext(bridge.scope.coroutineContext) { bridge.openUri(pkg, uris, display) }
            result(ok, if (ok) "${place.title} is open in $label." else "$label couldn't open that place.")
        }
        val where = if (onCluster) "the driver cluster" else "the ${target.label.lowercase()} screen"
        return if (question != null) result(true, "Confirm on the car's screen: $label opens on $where with ${place.title}.")
        else result(true, "${place.title} is opening in $label on $where.")
    }

    /** Opens the navigation app on its usual screen, without a place (the cluster's map: on the main screen, for a while). */
    fun openApp() {
        val pkg = navApp() ?: return
        val app = AppId(pkg)
        if (screens.keepsOnCluster(app)) {
            screens.tap(app, ScreenId.MAIN) { _show.value = it }
            return
        }
        val target = targetScreen(pkg)
        val state = screens.state.value
        if (state.screenOf(app) != target) {
            val slot = if (target == ScreenId.MAIN && state[ScreenId.MAIN] != ScreenContent.Idle) Slot.LEFT else Slot.FULL
            screens.place(app, DropTarget(target, slot))
        }
        // Show it, wherever it is.
        _show.value = target
    }

    private fun result(ok: Boolean, message: String) = Result(ok, message).also { _last.value = it }

    /** The display the app is on once it's there. */
    private suspend fun displayFor(app: AppId, screen: ScreenId): Int {
        while (true) {
            if (screen == ScreenId.MAIN || (screen == ScreenId.DRIVER && screens.clusterMap.value?.app == app)) {
                // On the cluster map only, the app runs in a hidden display of its own.
                hosts.displayOf(app)?.let { return it }
            } else {
                val snap = withContext(bridge.scope.coroutineContext) { bridge.snapshot() }
                val d = snap?.displayFor(screen)
                if (d != null && snap.tasks.any { it.pkg == app.pkg && it.displayId == d.id }) return d.id
            }
            delay(400)
        }
    }

    private fun openHere(pkg: String, uris: List<String>): Boolean {
        for (u in uris) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (t: Throwable) {
                Log.w(TAG, "open $u", t)
            }
        }
        return false
    }

    // ---- Directions ---------------------------------------------------------

    fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Turns "Show directions" on or off; asks the helper to grant Android's notification access. */
    fun setDirections(on: Boolean) {
        prefs.update { it.copy(showDirections = on) }
        if (!on) Directions.clearAll()
        if (!bridge.connected && on && !hasAccess()) {
            // No helper: Android's own page, where the driver switches Otility on.
            runCatching {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** The map reader is switched on in Android's accessibility settings. */
    fun readerOn(): Boolean {
        val on = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val pkg = context.packageName
        return on.split(':').any { it == "$pkg/$pkg.nav.MapScreenReader" || it == "$pkg/.nav.MapScreenReader" }
    }

    /** Turns "Read the map's screen" on or off; the helper switches the reader in Android's settings. */
    fun setReadScreen(on: Boolean) {
        prefs.update { it.copy(readMapScreen = on) }
        if (!on) Directions.screen(null)
        if (!bridge.connected && on && !readerOn()) {
            // No helper: Android's own page, where the driver switches the reader on.
            runCatching {
                context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /**
     * The car's switch-off force-stops Otility, and Android then keeps the reader as
     * crashed: still switched on, but never started again, so no directions are read
     * until it's switched off and on. That, when it hasn't started a while after the
     * helper is back.
     */
    private suspend fun restartStuckReader() {
        if (withTimeoutOrNull(READER_START) { MapScreenReader.running.first { it } } != null) return
        if (!readerOn()) return
        Log.i(TAG, "map reader on but not running: switching it off and on")
        bridge.scope.launch {
            runCatching {
                val h = bridge.helper ?: return@launch
                // Apart, so Android sees it off (which clears "crashed") before it's on again.
                h.accessibilityAccess(false)
                delay(1500)
                h.accessibilityAccess(true)
            }.onFailure { Log.w(TAG, "map reader restart", it) }
        }
    }

    private fun setReader(allow: Boolean) {
        bridge.scope.launch {
            val ok = runCatching { bridge.helper?.accessibilityAccess(allow) }.onFailure { Log.w(TAG, "map reader", it) }.getOrNull()
            Log.i(TAG, "map reader ${if (allow) "on" else "off"}: $ok")
        }
    }

    private fun setAccess(allow: Boolean) {
        bridge.scope.launch {
            val ok = runCatching { bridge.helper?.notificationAccess(allow) }.getOrNull()
            Log.i(TAG, "notification access ${if (allow) "granted" else "removed"}: $ok")
        }
    }
}
