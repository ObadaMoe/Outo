package app.leopard.cockpit.helper

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The speed limit Outo gives the car as the navigation's (the app's RoadLimit decides
 * it: the map's, or the camera's newer sign), sent again every second while there is
 * one. BYD's own map sends its limit on every road update; a car that hasn't heard
 * the navigation's limit again lately may take it as stale and keep its camera's.
 *
 * With a limit, the car's front camera is also told a map is guiding (as BYD's map
 * tells it when its guidance starts; see [Vehicle.setNavigating]), and that it stopped
 * once there's none. Without that the camera ignores the map's limit and shows only
 * the signs it reads, each for a few seconds.
 *
 * And a short log of how the car answers, to look at after a drive
 * ([LOG]): a line each time the limit sent, the camera's sign, the limit the car
 * settles on, the sign the cluster shows or the car's limit source changes. Read only,
 * apart from the limit and the navigation state.
 */
internal object SpeedLimits {
    /** The limit to send now, in km/h (0: none; the car keeps its own). */
    @Volatile var sending = 0
        private set

    /** The camera has been told a map is guiding, and not told otherwise since. */
    private var navigating = false
    private var navAt = 0L

    private const val LOG = "/data/local/tmp/outo-limit.log"
    private const val LOG_MAX = 256 * 1024L
    private const val EVERY_MS = 1_000L
    /** How often the camera hears again that a map is guiding (it may have restarted: the car sleeps). */
    private const val NAV_EVERY_MS = 10_000L

    private val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun start() {
        Thread({ loop() }, "otility-limit").apply { isDaemon = true }.start()
    }

    /** The limit from now on (0: none). Returns the car's answer to the limit. */
    @Synchronized
    fun set(kmh: Int): String {
        sending = kmh
        if (kmh > 0) navigate(true)
        val out = runCatching { Vehicle.setSpeedLimit(kmh) }
            .getOrElse { "error: ${(it.cause ?: it).javaClass.simpleName}: ${(it.cause ?: it).message}" }
        if (kmh == 0) navigate(false)
        return out
    }

    /** Outo is gone: no limit, and no map guiding. */
    @Synchronized
    fun clear() {
        sending = 0
        runCatching { Vehicle.setSpeedLimit(0) }
        navigate(false)
    }

    /** Tells the camera, when it changes, or again after [NAV_EVERY_MS] while guiding. */
    private fun navigate(on: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (on == navigating && (!on || now - navAt < NAV_EVERY_MS)) return
        val again = on == navigating
        val out = runCatching { Vehicle.setNavigating(on) }.getOrElse { "error: ${it.message}" }
        navigating = on
        navAt = now
        if (!again) {
            Log.i(HelperMain.TAG, "camera told a map is ${if (on) "guiding" else "not guiding"}: $out")
            runCatching { append("navigation ${if (on) "on" else "off"} ($out)") }
        }
    }

    private fun loop() {
        var last = ""
        var failed = false
        while (true) {
            SystemClock.sleep(EVERY_MS)
            synchronized(this) {
                val kmh = sending
                if (kmh > 0) {
                    navigate(true)
                    val out = runCatching { Vehicle.setSpeedLimit(kmh) }.getOrElse { "error: ${it.message}" }
                    val bad = out.startsWith("error")
                    if (bad && !failed) Log.w(HelperMain.TAG, "speed limit $kmh again: $out")
                    failed = bad
                }
            }
            runCatching {
                val v = Vehicle.read()
                if (!v.has("limitCamera") && !v.has("limitSign")) return@runCatching
                val now = "sent=$sending camera=${v.optInt("limitCamera", -1)} car=${v.optInt("limitCar", -1)}" +
                    " sign=${v.optInt("limitSign", -1)} source=${v.optInt("limitSource", -1)} fusion=${v.optInt("navFusion", -1)}"
                if (now == last) return@runCatching
                last = now
                append("speed=${v.optInt("speed", -1)} $now")
            }
        }
    }

    @Synchronized
    private fun append(text: String) {
        val line = "${clock.format(Date())} $text"
        val f = File(LOG)
        // Keep the newer half once it grows too long.
        if (f.length() > LOG_MAX) {
            val lines = f.readLines()
            f.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
        f.appendText(line + "\n")
    }
}
