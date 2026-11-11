package app.leopard.cockpit.data

import android.content.Context
import app.leopard.cockpit.nav.MapScreens
import app.leopard.cockpit.nav.Place
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenContent
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.screens.Screens
import app.leopard.cockpit.screens.ScreensState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class Appearance { LIGHT, DARK, AUTO }
enum class Accent { GLACIER, DUNE, JADE }
enum class Tab { OVERVIEW, SCREENS, APPS, SETTINGS }
enum class AppView { TABLET, PHONE }
enum class SoundMode { STREET, SPORT, TRACK }

data class Settings(
    // Display
    val appearance: Appearance = Appearance.DARK,
    val accent: Accent = Accent.GLACIER,
    val tintedGlass: Boolean = false,
    val reduceTransparency: Boolean = false,
    val showEmblem: Boolean = true,
    /** 0 = the emblem stays at full brightness. */
    val dimEmblemSeconds: Int = 0,
    // Screens
    /** The dashboard's screens show the apps themselves, live, not only their icons. */
    val livePreviews: Boolean = true,
    val confirmReplace: Boolean = true,
    val autoHideSeconds: Int = 3,
    val gestures: Boolean = true,
    val restoreLayout: Boolean = true,
    // Startup
    val startWithCar: Boolean = true,
    val startTab: Tab = Tab.SCREENS,
    // Driver cluster
    val clusterCode: Int = 30,
    /**
     * The switch above the Driver screen: the chosen map (Waze or Google Maps) on the
     * cluster, map only, instead of BYD's own view.
     */
    val mapOnCluster: Boolean = false,
    /** With the map on the cluster: BYD's own view while a turn signal is on, for its blind-spot camera. */
    val signalView: Boolean = true,
    // Navigation
    val phoneHandoff: Boolean = true,
    val navApp: String = "com.waze",
    /** Where places from the phone open; null = wherever the map already is (main if nowhere). */
    val navScreen: ScreenId? = null,
    /** Read the navigation app's next turn from its notification. */
    val showDirections: Boolean = false,
    /** Also read the map app's screen, Waze or Google Maps (exact distance, street, time and distance left). */
    val readMapScreen: Boolean = false,
    /** Send each turn to the windshield display (HUD). */
    val hudTurns: Boolean = false,
    /** With the HUD on: send Waze's or Google Maps' speed limit for the road, in place of the car's own reading. */
    val hudSpeedLimit: Boolean = true,
    /** Send each turn to the driver cluster. */
    val clusterTurns: Boolean = false,
    // Engine sound
    val soundOn: Boolean = false,
    val soundCar: String = "porsche_911_gt3",
    val soundMode: SoundMode = SoundMode.SPORT,
    val soundVolume: Float = 0.6f,
    val soundOutside: Boolean = false,
    val soundOnlyDriving: Boolean = true,
    val soundPitch: Float = 1f,
    val soundRoughness: Float = 0.3f,
    val soundCrackle: Float = 0.3f,
    // Updates
    /** Look for a new version on GitHub by itself (installing it waits for a tap). */
    val autoUpdate: Boolean = true,
)

data class Layout(val name: String, val state: ScreensState)

/** The most apps the sidebar holds (past what fits, they scroll). */
const val DOCK_MAX = 10

/** How many apps [Prefs.recents] remembers. */
const val RECENTS_MAX = 12

/** Small, synchronous store on SharedPreferences, exposed as flows. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("otility", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _dock = MutableStateFlow(readList("dock"))
    val dock: StateFlow<List<AppId>> = _dock.asStateFlow()

    private val _layouts = MutableStateFlow(readLayouts())
    val layouts: StateFlow<List<Layout>> = _layouts.asStateFlow()

    private val _appViews = MutableStateFlow(readAppViews())
    val appViews: StateFlow<Map<AppId, AppView>> = _appViews.asStateFlow()

    val dockInitialized: Boolean get() = sp.contains("dock")

    fun update(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        _settings.value = s
        sp.edit().putString("settings", settingsJson(s).toString()).apply()
    }

    /** The apps pinned on the sidebar, top to bottom. Keeps the first [DOCK_MAX]. */
    fun setDock(apps: List<AppId>) {
        val kept = apps.distinct().take(DOCK_MAX)
        _dock.value = kept
        sp.edit().putString("dock", JSONArray(kept.map { it.pkg }).toString()).apply()
    }

    fun setAppView(app: AppId, view: AppView) {
        _appViews.value = _appViews.value + (app to view)
        sp.edit().putString("appViews", JSONObject(_appViews.value.entries.associate { it.key.pkg to it.value.name }).toString()).apply()
    }

    fun saveLastLayout(state: ScreensState) {
        sp.edit().putString("lastLayout", stateJson(state).toString()).apply()
    }

    fun lastLayout(): ScreensState? = sp.getString("lastLayout", null)?.let { runCatching { parseState(JSONObject(it)) }.getOrNull() }

    fun saveLayout(layout: Layout) {
        _layouts.value = _layouts.value.filter { it.name != layout.name } + layout
        writeLayouts()
    }

    // ---- Apps used lately ----------------------------------------------------------

    private val _recents = MutableStateFlow(readList("recents"))
    /** The apps opened lately on the main and passenger screens, the latest first. */
    val recents: StateFlow<List<AppId>> = _recents.asStateFlow()

    fun noteOpened(app: AppId) {
        val list = (listOf(app) + _recents.value.filter { it != app }).take(RECENTS_MAX)
        if (list == _recents.value) return
        _recents.value = list
        sp.edit().putString("recents", JSONArray(list.map { it.pkg }).toString()).apply()
    }

    // ---- The map app used last -------------------------------------------------

    private val _lastMap = MutableStateFlow(sp.getString("lastMap", null))
    val lastMap: StateFlow<String?> = _lastMap.asStateFlow()

    fun setLastMap(pkg: String) {
        if (pkg == _lastMap.value) return
        _lastMap.value = pkg
        sp.edit().putString("lastMap", pkg).apply()
    }

    // ---- Startup apps ---------------------------------------------------------

    private val _startup = MutableStateFlow(
        sp.getString("startup", null)?.let { runCatching { StartupApps.fromJson(JSONObject(it)) }.getOrNull() } ?: StartupApps(),
    )
    /** The apps each screen opens with when Otility starts. */
    val startup: StateFlow<StartupApps> = _startup.asStateFlow()

    fun setStartup(apps: StartupApps) {
        if (apps == _startup.value) return
        _startup.value = apps
        sp.edit().putString("startup", apps.toJson().toString()).apply()
    }

    // ---- Overview widgets ---------------------------------------------------

    private val _widgets = MutableStateFlow(readWidgets())
    /** The Overview's widgets: order, sizes, options. Removed ones aren't listed. */
    val widgets: StateFlow<WidgetLayout> = _widgets.asStateFlow()

    fun setWidgets(layout: WidgetLayout) {
        if (layout == _widgets.value) return
        _widgets.value = layout
        sp.edit().putString("widgets", layout.toJson().toString()).apply()
    }

    private fun readWidgets(): WidgetLayout {
        val saved = sp.getString("widgets", null)?.let { s -> runCatching { WidgetLayout.fromJson(JSONArray(s)) }.getOrNull() }
            ?: run {
                sp.edit().putBoolean("widgetsMedia", true).apply()
                return WidgetLayout.DEFAULT
            }
        // Now Playing came after widgets could be saved: it joins a saved Overview once
        // (it only shows while something plays); removed after that, it stays removed.
        if (sp.getBoolean("widgetsMedia", false)) return saved
        val withMedia = saved.add(WidgetKind.MEDIA)
        sp.edit().putBoolean("widgetsMedia", true).putString("widgets", withMedia.toJson().toString()).apply()
        return withMedia
    }

    // ---- Places from the phone -----------------------------------------------

    private val _places = MutableStateFlow(readPlaces())
    /** Places sent to the car, newest first. */
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    fun addRecentPlace(place: Place) {
        _places.value = (listOf(place) + _places.value.filterNot { it.sameAs(place) }).take(12)
        writePlaces()
    }

    fun clearPlaces() {
        _places.value = emptyList()
        writePlaces()
    }

    private fun writePlaces() {
        sp.edit().putString("places", JSONArray(_places.value.map { it.toJson() }).toString()).apply()
    }

    private fun readPlaces(): List<Place> = sp.getString("places", null)?.let { s ->
        runCatching { JSONArray(s).let { a -> List(a.length()) { Place.fromJson(a.getJSONObject(it)) } } }.getOrNull()
    } ?: emptyList()

    /** The secret the phone sends with each place, so only the driver's phone can. */
    val phoneKey: String
        @Synchronized get() = sp.getString("phoneKey", null) ?: newPhoneKey()

    @Synchronized
    fun newPhoneKey(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        val key = String(CharArray(10) { chars[rnd.nextInt(chars.length)] })
        sp.edit().putString("phoneKey", key).apply()
        _keyVersion.value++
        return key
    }

    private val _keyVersion = MutableStateFlow(0)
    /** Changes when a new phone key is made (so the QR code redraws). */
    val keyVersion: StateFlow<Int> = _keyVersion.asStateFlow()

    fun deleteLayout(name: String) {
        _layouts.value = _layouts.value.filter { it.name != name }
        writeLayouts()
    }

    // ---- JSON -------------------------------------------------------------

    private fun readList(key: String): List<AppId> = sp.getString(key, null)?.let { s ->
        runCatching { JSONArray(s).let { a -> List(a.length()) { AppId(a.getString(it)) } } }.getOrNull()
    } ?: emptyList()

    private fun readAppViews(): Map<AppId, AppView> = sp.getString("appViews", null)?.let { s ->
        runCatching {
            val o = JSONObject(s)
            o.keys().asSequence().associate { AppId(it) to AppView.valueOf(o.getString(it)) }
        }.getOrNull()
    } ?: emptyMap()

    private fun writeLayouts() {
        val a = JSONArray()
        _layouts.value.forEach { a.put(JSONObject().put("name", it.name).put("state", stateJson(it.state))) }
        sp.edit().putString("layouts", a.toString()).apply()
    }

    private fun readLayouts(): List<Layout> = sp.getString("layouts", null)?.let { s ->
        runCatching {
            val a = JSONArray(s)
            List(a.length()) { i -> a.getJSONObject(i).let { Layout(it.getString("name"), parseState(it.getJSONObject("state"))) } }
        }.getOrNull()
    } ?: emptyList()

    private fun stateJson(s: ScreensState): JSONObject {
        val o = JSONObject()
        for ((id, c) in s.screens) {
            o.put(id.name, when (c) {
                ScreenContent.Idle -> JSONObject().put("t", "idle")
                is ScreenContent.Single -> JSONObject().put("t", "single").put("a", c.app.pkg)
                is ScreenContent.Split -> JSONObject().put("t", "split").put("l", c.left.pkg).put("r", c.right.pkg).put("ratio", c.ratio.toDouble())
            })
        }
        return o
    }

    private fun parseState(o: JSONObject): ScreensState {
        var s = ScreensState()
        for (id in ScreenId.entries) {
            val c = o.optJSONObject(id.name) ?: continue
            s = s.with(id, when (c.optString("t")) {
                "single" -> ScreenContent.Single(AppId(c.getString("a")))
                // Within the limits: an older Otility could save one caught mid-drag.
                "split" -> ScreenContent.Split(
                    AppId(c.getString("l")), AppId(c.getString("r")),
                    Screens.clampRatio(c.optDouble("ratio", 0.5).toFloat()),
                )
                else -> ScreenContent.Idle
            })
        }
        return s
    }

    private fun readSettings(): Settings {
        val s = sp.getString("settings", null) ?: return Settings()
        return runCatching { parseSettings(JSONObject(s)) }.getOrDefault(Settings())
    }

    private fun settingsJson(s: Settings) = JSONObject().apply {
        put("appearance", s.appearance.name); put("accent", s.accent.name)
        put("tintedGlass", s.tintedGlass); put("reduceTransparency", s.reduceTransparency)
        put("showEmblem", s.showEmblem); put("dimEmblemSeconds", s.dimEmblemSeconds)
        put("livePreviews", s.livePreviews)
        put("confirmReplace", s.confirmReplace); put("autoHideSeconds", s.autoHideSeconds)
        put("gestures", s.gestures)
        put("restoreLayout", s.restoreLayout); put("startWithCar", s.startWithCar); put("autoUpdate", s.autoUpdate); put("startTab", s.startTab.name)
        put("clusterCode", s.clusterCode); put("mapOnCluster", s.mapOnCluster); put("signalView", s.signalView)
        put("phoneHandoff", s.phoneHandoff); put("navApp", s.navApp)
        put("navScreen", s.navScreen?.name ?: "AUTO"); put("showDirections", s.showDirections)
        put("readMapScreen", s.readMapScreen); put("hudTurns", s.hudTurns); put("hudSpeedLimit", s.hudSpeedLimit); put("clusterTurns", s.clusterTurns)
        put("soundOn", s.soundOn); put("soundCar", s.soundCar); put("soundMode", s.soundMode.name)
        put("soundVolume", s.soundVolume.toDouble()); put("soundOutside", s.soundOutside)
        put("soundOnlyDriving", s.soundOnlyDriving); put("soundPitch", s.soundPitch.toDouble())
        put("soundRoughness", s.soundRoughness.toDouble()); put("soundCrackle", s.soundCrackle.toDouble())
    }

    private fun parseSettings(o: JSONObject): Settings {
        val d = Settings()
        fun b(k: String, def: Boolean) = o.optBoolean(k, def)
        fun i(k: String, def: Int) = o.optInt(k, def)
        fun f(k: String, def: Float) = o.optDouble(k, def.toDouble()).toFloat()
        return Settings(
            appearance = runCatching { Appearance.valueOf(o.getString("appearance")) }.getOrDefault(d.appearance),
            accent = runCatching { Accent.valueOf(o.getString("accent")) }.getOrDefault(d.accent),
            tintedGlass = b("tintedGlass", d.tintedGlass),
            reduceTransparency = b("reduceTransparency", d.reduceTransparency),
            showEmblem = b("showEmblem", d.showEmblem),
            dimEmblemSeconds = i("dimEmblemSeconds", d.dimEmblemSeconds),
            livePreviews = b("livePreviews", d.livePreviews),
            confirmReplace = b("confirmReplace", d.confirmReplace),
            autoHideSeconds = i("autoHideSeconds", d.autoHideSeconds),
            gestures = b("gestures", d.gestures),
            restoreLayout = b("restoreLayout", d.restoreLayout),
            startWithCar = b("startWithCar", d.startWithCar),
            autoUpdate = b("autoUpdate", d.autoUpdate),
            startTab = runCatching { Tab.valueOf(o.getString("startTab")) }.getOrDefault(d.startTab),
            clusterCode = i("clusterCode", d.clusterCode),
            // Before the switch: on if the cluster had a map, map only.
            mapOnCluster = if (o.has("mapOnCluster")) o.optBoolean("mapOnCluster") else b("clusterMapOnly", true) &&
                (lastLayout()?.get(ScreenId.DRIVER) as? ScreenContent.Single)?.app?.pkg in MapScreens.PACKAGES,
            signalView = b("signalView", d.signalView),
            phoneHandoff = b("phoneHandoff", d.phoneHandoff),
            navApp = o.optString("navApp", d.navApp),
            navScreen = runCatching { ScreenId.valueOf(o.getString("navScreen")) }.getOrNull(),
            showDirections = b("showDirections", d.showDirections),
            readMapScreen = b("readMapScreen", d.readMapScreen),
            hudTurns = b("hudTurns", d.hudTurns),
            hudSpeedLimit = b("hudSpeedLimit", d.hudSpeedLimit),
            clusterTurns = b("clusterTurns", d.clusterTurns),
            soundOn = b("soundOn", d.soundOn),
            soundCar = o.optString("soundCar", d.soundCar),
            soundMode = runCatching { SoundMode.valueOf(o.getString("soundMode")) }.getOrDefault(d.soundMode),
            soundVolume = f("soundVolume", d.soundVolume),
            soundOutside = b("soundOutside", d.soundOutside),
            soundOnlyDriving = b("soundOnlyDriving", d.soundOnlyDriving),
            soundPitch = f("soundPitch", d.soundPitch),
            soundRoughness = f("soundRoughness", d.soundRoughness),
            soundCrackle = f("soundCrackle", d.soundCrackle),
        )
    }
}
