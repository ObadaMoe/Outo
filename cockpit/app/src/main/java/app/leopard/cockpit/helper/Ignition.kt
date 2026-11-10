package app.leopard.cockpit.helper

import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Opens Otility when the car is switched on ("Start with the car").
 *
 * The car doesn't boot when it's switched on: it wakes. At switch-off BYD's quick
 * power-off force-stops Otility, and a force-stopped app gets none of the start-up
 * broadcasts the car sends again on waking, so it can't open itself. The helper
 * (a shell process) outlives that, watches the car's ignition (BYD's
 * `sys.acc_status`, OFF while switched off), and opens Otility on the main screen
 * when it turns ON, once the car's start-up animation is done.
 */
internal object Ignition {
    /** Outlasts Otility (and a helper restart): set by Otility, read here. */
    private const val FILE = "/data/local/tmp/otility-open-with-car"
    private const val ACC = "sys.acc_status"
    /** The animation the car plays while it wakes; 0 when it's done. */
    private const val ANIMATION = "sys.accanim.status"
    /** Looks again this long after opening, in case BYD's home screen came up over it. */
    private val CHECKS = longArrayOf(6_000, 14_000)
    /** At most this long waiting for the animation. */
    private const val ANIMATION_MAX = 12_000L
    /** ACTIVITY_TYPE_HOME: BYD's launcher. */
    private const val HOME = 2

    @Volatile var openWithCar: Boolean = File(FILE).exists()
        set(v) {
            field = v
            runCatching { if (v) File(FILE).writeText("1\n") else File(FILE).delete() }
        }

    private var last: String? = null
    /** When the car came on (0: not waiting to open). */
    private var onAt = 0L
    private var openedAt = 0L
    private var checks = 0

    /**
     * Otility was just updated, which closed it: opens it again now, and looks again
     * later as after switching the car on (while the car is on).
     */
    @Synchronized
    fun reopen(why: String) {
        open(why)
        val now = SystemClock.uptimeMillis()
        onAt = now
        openedAt = now
        checks = 0
    }

    /** Called every second or two from the helper's loop. */
    @Synchronized
    fun tick() {
        val acc = prop(ACC)
        val was = last
        last = acc
        val now = SystemClock.uptimeMillis()
        if (was == "OFF" && acc == "ON") {
            Log.i(HelperMain.TAG, "the car is on")
            onAt = if (openWithCar) now else 0L
            openedAt = 0L
            checks = 0
        }
        if (acc != "ON") onAt = 0L
        if (onAt == 0L) return
        if (openedAt == 0L) {
            // After the start-up animation (or a while), so Otility isn't drawn under it.
            if (prop(ANIMATION) == "1" && now - onAt < ANIMATION_MAX) return
            open("the car is on")
            openedAt = now
            return
        }
        if (checks < CHECKS.size && now - openedAt >= CHECKS[checks]) {
            checks++
            // BYD's home screen came up over it: once more. Not over an app the driver opened.
            val main = runCatching { Hidden.tasks() }.getOrDefault(emptyList())
                .filter { it.displayId == 0 && it.visible && it.userId == Hidden.MAIN_USER }
            if (main.none { it.pkg == HelperMain.APP_PACKAGE } && main.any { it.activityType == HOME }) {
                open("BYD's home screen is in front")
            }
            if (checks == CHECKS.size) onAt = 0L
        }
    }

    private fun open(why: String) {
        Log.i(HelperMain.TAG, "opening Otility: $why")
        runCatching {
            ProcessBuilder(
                "am", "start", "--display", "0", "-n", "${HelperMain.APP_PACKAGE}/.MainActivity", "-f", "0x10000000",
            ).redirectErrorStream(true).start().waitFor()
        }.onFailure { Log.w(HelperMain.TAG, "opening Otility", it) }
    }

    private fun prop(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("")
}
