package app.leopard.cockpit.data

import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenContent
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.screens.ScreensState
import app.leopard.cockpit.screens.Screens
import org.json.JSONObject

/** A place an app can open when Otility starts. The main screen has one, or two when split. */
enum class StartupSpot(val label: String) {
    DRIVER("Driver"),
    MAIN("Main"),
    /** The main screen's right half (its left half is [MAIN]). */
    RIGHT("Main · Right"),
    PASSENGER("Passenger"),
}

/**
 * The apps that open on each screen when Otility starts. With [split] on, the
 * main screen opens [MAIN][StartupSpot.MAIN] on the left and
 * [RIGHT][StartupSpot.RIGHT] on the right; turned off, the right one is kept
 * (for turning it back on) but doesn't open.
 */
data class StartupApps(val apps: Map<StartupSpot, AppId> = emptyMap(), val split: Boolean = false) {
    operator fun get(spot: StartupSpot): AppId? = apps[spot]

    /** The spots in use with the main screen as it is now (split or not). */
    val spots: List<StartupSpot>
        get() = (if (split) StartupSpot.entries else StartupSpot.entries - StartupSpot.RIGHT) - StartupSpot.DRIVER

    val isEmpty: Boolean get() = spots.none { apps[it] != null }

    /** Puts [app] at [spot]; an app opens in one place only, so it leaves any other spot. */
    fun set(spot: StartupSpot, app: AppId?): StartupApps {
        val next = apps.filterValues { it != app }.toMutableMap()
        if (app == null) next.remove(spot) else next[spot] = app
        return copy(apps = next)
    }

    /** What [screen] opens with, or null to leave it as it is. */
    fun content(screen: ScreenId): ScreenContent? = when (screen) {
        // The cluster shows the map by its switch, not as a startup app.
        ScreenId.DRIVER -> null
        ScreenId.PASSENGER -> apps[StartupSpot.PASSENGER]?.let { ScreenContent.Single(it) }
        ScreenId.MAIN -> {
            val l = apps[StartupSpot.MAIN]
            val r = apps[StartupSpot.RIGHT]?.takeIf { split }
            when {
                l != null && r != null -> ScreenContent.Split(l, r)
                else -> (l ?: r)?.let { ScreenContent.Single(it) }
            }
        }
    }

    /** [state] with these apps on [screens]; each app leaves wherever else it was. */
    fun applyTo(state: ScreensState, screens: Collection<ScreenId> = ScreenId.entries): ScreensState {
        var s = state
        for (screen in screens) {
            val c = content(screen) ?: continue
            for (app in c.apps) if (s.screenOf(app) != screen) s = Screens.close(s, app)
            s = s.with(screen, c)
        }
        return s
    }

    fun toJson(): JSONObject = JSONObject()
        .put("split", split)
        .put("apps", JSONObject().apply { apps.forEach { (spot, app) -> put(spot.name, app.pkg) } })

    companion object {
        fun fromJson(o: JSONObject): StartupApps {
            val a = o.optJSONObject("apps")
            val apps = buildMap {
                if (a != null) for (spot in StartupSpot.entries) a.optString(spot.name).takeIf { it.isNotEmpty() }?.let { put(spot, AppId(it)) }
            }
            return StartupApps(apps, o.optBoolean("split"))
        }
    }
}
