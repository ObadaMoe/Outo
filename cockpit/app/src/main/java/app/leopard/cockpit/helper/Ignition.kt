package app.leopard.cockpit.helper

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opens Otility when the car is switched on ("Start with the car"), keeps it in front
 * while the car starts up, and opens it again if it closes by itself.
 *
 * The car doesn't boot when it's switched on: it wakes. At switch-off BYD's quick
 * power-off force-stops Otility, and a force-stopped app gets none of the start-up
 * broadcasts the car sends again on waking, so it can't open itself. The helper
 * (a shell process) outlives that, watches the car's ignition (BYD's
 * `sys.acc_status`, OFF while switched off), and opens Otility on the main screen
 * when it turns ON, once the car's start-up animation is done.
 *
 * BYD's home screen can come up over it a few seconds later, as the car finishes starting.
 * For [GUARD_MS] after opening, Otility goes back in front when that happens,
 * unless the driver had just pressed something ([Presses]): then it was theirs, and
 * the helper leaves it. It never goes over another app (the reversing camera, say).
 *
 * What came to the front, and why Otility was opened, is written to [LOG] to read
 * after a drive.
 */
internal object Ignition {
    /** Outlasts Otility (and a helper restart): set by Otility, read here. */
    private const val FILE = "/data/local/tmp/otility-open-with-car"
    /** What came to the front at start-up, and Otility reopened: read over ADB after a drive. */
    const val LOG = "/data/local/tmp/outo-front.log"
    private const val LOG_MAX = 64 * 1024L
    private const val ACC = "sys.acc_status"
    /** The animation the car plays while it wakes; 0 when it's done. */
    private const val ANIMATION = "sys.accanim.status"
    /**
     * After opening, how long BYD's home screen coming up over Otility brings it back.
     * BYD's own came 3 to 8 s after Outo opened in every start logged (11–12 Nov 2026).
     * Longer, and it caught the driver: BYD's bottom bar opens the car's controls in its
     * home app, so the driver's taps on it were taken back too.
     */
    private const val GUARD_MS = 20_000L
    /** At most this many times; once when the driver's presses can't be read. */
    private const val GUARD_MAX = 2
    /**
     * A press this recent means the driver did it (BYD's home button, or the car's
     * controls on its bottom bar). Generous: just after the car wakes, BYD's home app can
     * take a few seconds to come up after the tap, and the helper looks every second or two.
     */
    private const val DRIVER_MS = 6_000L
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
    /** When the car came on, or Otility was reopened (0: nothing to open or watch). */
    private var onAt = 0L
    private var openedAt = 0L
    private var returns = 0
    /** What was in front at the last look, as written to the log. */
    private var front: String? = null

    /**
     * Otility was just updated (which closed it), or the helper was started to open it:
     * opens it now, and keeps it in front as after switching the car on.
     */
    @Synchronized
    fun reopen(why: String) {
        open(why)
        val now = SystemClock.uptimeMillis()
        onAt = now
        openedAt = now
        returns = 0
        front = null
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
            write("the car is on" + if (openWithCar) "" else " (not opening Outo: Start with the car is off)")
            onAt = if (openWithCar) now else 0L
            openedAt = 0L
            returns = 0
            front = null
        }
        // A car without the property (the emulator) counts as on.
        if (acc != "ON" && acc.isNotEmpty()) onAt = 0L
        if (onAt == 0L) return
        if (openedAt == 0L) {
            // After the start-up animation (or a while), so Otility isn't drawn under it.
            if (prop(ANIMATION) == "1" && now - onAt < ANIMATION_MAX) return
            open("the car is on")
            openedAt = now
            return
        }
        if (now - openedAt > GUARD_MS) {
            onAt = 0L
            return
        }
        val main = runCatching { Hidden.tasks() }.getOrNull()
            ?.filter { it.displayId == 0 && it.visible && it.userId == Hidden.MAIN_USER } ?: return
        val shown = main.joinToString { (if (it.activityType == HOME) "home " else "") + it.component }.ifEmpty { "nothing" }
        if (shown != front) {
            front = shown
            write("in front: $shown")
        }
        if (main.any { it.pkg == HelperMain.APP_PACKAGE } || main.none { it.activityType == HOME }) return
        // BYD's home screen is up over Otility.
        when {
            now - Presses.last < DRIVER_MS -> {
                write("BYD's home screen after a press on ${Presses.from}: the driver's, left as it is")
                onAt = 0L
            }
            returns >= (if (Presses.reading > 0) GUARD_MAX else 1) -> {
                write("BYD's home screen keeps coming back: left as it is")
                onAt = 0L
            }
            else -> {
                returns++
                val press = if (Presses.last == 0L) "no press yet" else "last press ${(now - Presses.last) / 100 / 10.0} s before"
                open("BYD's home screen came up over it ($press)")
            }
        }
    }

    /**
     * Otility's process [pid] went away; [inFront]: it was on the main screen a moment before.
     * Opened again if it closed by itself (a crash, a freeze, the system's memory manager),
     * with the car on: not when the car stopped it (switch-off), it was updated or
     * stopped by hand. Its apps are still in their displays (see HelperService.reset).
     */
    fun closed(pid: Int, inFront: Boolean) {
        if (!inFront) return
        Thread({
            // The car's switch-off stops it a moment before the ignition reads OFF.
            SystemClock.sleep(1_500)
            val acc = prop(ACC)
            if (acc != "ON" && acc.isNotEmpty()) return@Thread
            // Android may write it down a moment later.
            val (reason, text) = exit(pid).takeIf { it.first != null } ?: run { SystemClock.sleep(2_000); exit(pid) }
            val unexpected = reason == null || reason in UNEXPECTED
            write("Outo closed while on screen: ${text ?: "no reason given"}" + if (unexpected) "" else " (not reopening)")
            if (!unexpected || text?.contains("quickboot") == true) return@Thread
            val now = SystemClock.uptimeMillis()
            synchronized(revived) {
                revived.removeAll { now - it > REVIVE_WINDOW_MS }
                if (revived.size >= REVIVE_MAX) {
                    write("not reopening: it closed $REVIVE_MAX times in a few minutes")
                    return@Thread
                }
                revived += now
            }
            open("it closed by itself")
        }, "otility-revive").apply { isDaemon = true }.start()
    }

    /** ApplicationExitInfo reasons that weren't anyone's choice: unknown, signalled, memory, crash, native crash, freeze, resources, a dependency, frozen. */
    private val UNEXPECTED = setOf(0, 2, 3, 4, 5, 6, 9, 12, 14)
    private const val REVIVE_MAX = 3
    private const val REVIVE_WINDOW_MS = 5 * 60_000L
    private val revived = ArrayList<Long>()

    /**
     * Why Otility's process [pid] ended, as Android tells it (`dumpsys activity exit-info`):
     * the reason's number and words, and the description ("crash", "stop … due to quickboot").
     * Nulls when Android hasn't written it down.
     */
    private fun exit(pid: Int): Pair<Int?, String?> = runCatching {
        val out = ProcessBuilder("dumpsys", "activity", "exit-info", HelperMain.APP_PACKAGE)
            .redirectErrorStream(true).start().inputStream.bufferedReader().use { it.readText() }
        val record = out.split("ApplicationExitInfo #").firstOrNull { Regex("""\bpid=$pid\b""").containsMatchIn(it) }
            ?: return@runCatching null to null
        val m = Regex("""reason=(\d+) \((.*?)\) subreason""").find(record)
        val desc = Regex("""description=(.*?)(?: state=| trace=|$)""", RegexOption.MULTILINE).find(record)?.groupValues?.get(1)?.trim()
        m?.groupValues?.get(1)?.toIntOrNull() to listOfNotNull(m?.groupValues?.get(2)?.lowercase(), desc?.takeIf { it.isNotEmpty() && it != "null" }).joinToString(": ").ifEmpty { null }
    }.getOrDefault(null to null)

    private fun open(why: String) {
        Log.i(HelperMain.TAG, "opening Otility: $why")
        write("opening Outo: $why")
        runCatching {
            ProcessBuilder(
                "am", "start", "--display", "0", "-n", "${HelperMain.APP_PACKAGE}/.MainActivity", "-f", "0x10000000",
            ).redirectErrorStream(true).start().waitFor()
        }.onFailure { Log.w(HelperMain.TAG, "opening Otility", it) }
    }

    /** A line in [LOG], with the time; the file starts over when it grows past [LOG_MAX]. */
    @Synchronized
    private fun write(line: String) {
        runCatching {
            val f = File(LOG)
            if (f.length() > LOG_MAX) f.delete()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$time $line\n")
        }
    }

    private fun prop(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("")
}
