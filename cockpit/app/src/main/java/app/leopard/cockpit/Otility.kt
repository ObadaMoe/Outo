package app.leopard.cockpit

import android.app.Application
import app.leopard.cockpit.car.CarBridge
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.car.HelperLauncher
import app.leopard.cockpit.car.HostManager
import app.leopard.cockpit.data.AppCatalog
import app.leopard.cockpit.data.Prefs
import app.leopard.cockpit.data.VehicleFeed
import app.leopard.cockpit.engine.EngineSynth
import app.leopard.cockpit.engine.SoundDriver
import app.leopard.cockpit.nav.ClusterTurns
import app.leopard.cockpit.nav.HudLink
import app.leopard.cockpit.nav.Navigator
import app.leopard.cockpit.nav.PhoneLink
import app.leopard.cockpit.nav.PhoneLinkService
import app.leopard.cockpit.nav.RoadLimit
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreensController
import app.leopard.cockpit.update.Updater
import android.os.SystemClock
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** App-wide objects, created once. */
class Otility : Application() {
    lateinit var prefs: Prefs
    lateinit var catalog: AppCatalog
    lateinit var bridge: CarBridge
    lateinit var screens: ScreensController
    lateinit var vehicle: VehicleFeed
    lateinit var sound: SoundDriver
    lateinit var navigator: Navigator
    lateinit var phone: PhoneLink
    /** The next turn on the windshield display and on the driver cluster (each off until switched on). */
    lateinit var hud: HudLink
    lateinit var cluster: ClusterTurns
    /** The speed limit both send: the map's, or the car's camera's newer sign. */
    lateinit var roadLimit: RoadLimit
    /** New versions of Outo from GitHub. */
    lateinit var updater: Updater
    val synth = EngineSynth()
    private val main = MainScope()

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Why the last Outo closed, and Outo's own crashes, for reading after a drive.
        CrashLog.install(this)
        HelperLauncher.appContext = this
        prefs = Prefs(this)
        catalog = AppCatalog(this)
        bridge = CarBridge()
        screens = ScreensController(prefs, catalog, bridge, HostManager(bridge, getSharedPreferences("hosts", MODE_PRIVATE)))
        vehicle = VehicleFeed(this, bridge)
        sound = SoundDriver(synth, prefs, vehicle, bridge)
        navigator = Navigator(this, prefs, catalog, screens)
        screens.mapApp = { navigator.mapApp() }
        screens.clusterApp = { navigator.navApp() }
        screens.showScreen = { navigator.showScreen(it) }
        phone = PhoneLink(this)
        roadLimit = RoadLimit()
        hud = HudLink(this, prefs, roadLimit)
        cluster = ClusterTurns(prefs, vehicle, roadLimit)
        seedDock()
        // "Start with the car": the helper opens Otility when the car is switched on, so
        // it knows the setting (BYD force-stops Otility at switch-off; see Ignition).
        if (HelperLauncher.mainUser) bridge.scope.launch {
            combine(HelperConnection.helper, prefs.settings.map { it.startWithCar }.distinctUntilChanged()) { h, on -> h to on }
                .collect { (h, on) -> if (h != null) runCatching { h.setOpenWithCar(on) } }
        }
        followSignals()
        updater = Updater(this, prefs, bridge, vehicle).also { it.start() }
    }

    /**
     * A turn signal: BYD shows its blind-spot camera on the cluster, but in its own
     * view, which the map covers. So while one is on (and a moment after), the cluster
     * shows BYD's view. The lamps blink and are read once a second: a moment without
     * one lit isn't the end yet. One lit for minutes isn't a turn: the map comes back.
     */
    private fun followSignals() = main.launch {
        var since = 0L
        combine(vehicle.data, prefs.settings) { v, s -> s.mapOnCluster && s.signalView && v.turnSignal }
            .distinctUntilChanged()
            .collectLatest { lit ->
                if (lit) {
                    if (since == 0L) since = SystemClock.elapsedRealtime()
                    val left = SIGNAL_MAX_MS - (SystemClock.elapsedRealtime() - since)
                    screens.setSignalling(left > 0)
                    if (left > 0) {
                        delay(left)
                        screens.setSignalling(false)
                    }
                } else {
                    delay(SIGNAL_GRACE_MS)
                    since = 0L
                    screens.setSignalling(false)
                }
            }
    }

    /** Starts keeping in step with the car (called when Otility opens). */
    fun startServices() {
        screens.startSync()
        vehicle.start()
        sound.start()
        PhoneLinkService.sync(this)
    }

    /** First run: a sensible dock from what's installed. */
    private fun seedDock() {
        if (prefs.dockInitialized) return
        val wanted = listOf(
            "com.waze", "app.revanced.android.youtube", "com.google.android.youtube",
            "app.revanced.android.apps.maps", "com.google.android.apps.maps", "com.spotify.music",
            "com.google.android.apps.youtube.music", "com.android.chrome", "com.whatsapp",
        )
        val installed = wanted.filter { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
        prefs.setDock(installed.take(6).map { AppId(it) })
    }

    companion object {
        lateinit var instance: Otility
            private set

        /** How long after the last lit lamp the signal counts as off. */
        private const val SIGNAL_GRACE_MS = 2_500L
        /** A signal on longer than this isn't a turn. */
        private const val SIGNAL_MAX_MS = 90_000L
    }
}
