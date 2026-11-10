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
 * And a short log of how the car answers, to look at after a drive
 * ([LOG]): a line each time the limit sent, the camera's sign, the limit the car
 * settles on, the sign the cluster shows or the car's limit source changes. Read only,
 * apart from the limit itself.
 */
internal object SpeedLimits {
    /** The limit to send now, in km/h (0: none; the car keeps its own). */
    @Volatile var sending = 0

    private const val LOG = "/data/local/tmp/outo-limit.log"
    private const val LOG_MAX = 256 * 1024L
    private const val EVERY_MS = 1_000L

    fun start() {
        Thread({ loop() }, "otility-limit").apply { isDaemon = true }.start()
    }

    private fun loop() {
        val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        var last = ""
        var failed = false
        while (true) {
            SystemClock.sleep(EVERY_MS)
            val kmh = sending
            if (kmh > 0) {
                val out = runCatching { Vehicle.setSpeedLimit(kmh) }.getOrElse { "error: ${it.message}" }
                val bad = out.startsWith("error")
                if (bad && !failed) Log.w(HelperMain.TAG, "speed limit $kmh again: $out")
                failed = bad
            }
            runCatching {
                val v = Vehicle.read()
                if (!v.has("limitCamera") && !v.has("limitSign")) return@runCatching
                val now = "sent=$kmh camera=${v.optInt("limitCamera", -1)} car=${v.optInt("limitCar", -1)}" +
                    " sign=${v.optInt("limitSign", -1)} source=${v.optInt("limitSource", -1)}"
                if (now == last) return@runCatching
                last = now
                append("${clock.format(Date())} speed=${v.optInt("speed", -1)} $now")
            }
        }
    }

    private fun append(line: String) {
        val f = File(LOG)
        // Keep the newer half once it grows too long.
        if (f.length() > LOG_MAX) {
            val lines = f.readLines()
            f.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
        f.appendText(line + "\n")
    }
}
