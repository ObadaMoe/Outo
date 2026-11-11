package app.leopard.cockpit.engine

import app.leopard.cockpit.data.VehicleData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the engine sound followed while it played, to set beside a recording of it:
 * `/sdcard/Android/data/app.leopard.cockpit/files/outo-engine.log`. A line each time the
 * car's speed, pedal or gear reading changes, with the revs it gave. Past [MAX] the file
 * becomes outo-engine.1.log and starts over, so the last several minutes are kept.
 *
 * Read only by people; Outo itself never acts on it. Not thread-safe: one caller at a time.
 */
class EngineLog(dir: File) {
    private val file = File(dir, NAME)
    private val old = File(dir, OLD)
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var last = ""

    /** The car's readings [v], the speed the sound used, and the revs and virtual gear it gave. */
    fun reading(v: VehicleData, speed: Float, rpm: Float, gear: Int) {
        val car = "speed ${v.speedKmh ?: "-"} fine ${v.raw["speedFine"]?.let { "%.1f".format(Locale.US, it / 10f) } ?: "-"}" +
            " pedal ${v.pedalRaw ?: "-"}/${max(v.pedalTop, 100)} gear ${v.gear ?: "-"}"
        if (car == last) return
        last = car
        write("$car: ${"%.1f".format(Locale.US, speed)} km/h, ${rpm.roundToInt()} rpm in ${gear}")
    }

    fun note(text: String) {
        last = ""
        write(text)
    }

    private fun write(line: String) {
        runCatching {
            if (file.length() > MAX) {
                old.delete()
                file.renameTo(old)
            }
            file.appendText("${clock.format(Date())} $line\n")
        }
    }

    private companion object {
        const val NAME = "outo-engine.log"
        const val OLD = "outo-engine.1.log"
        const val MAX = 256 * 1024L
    }
}
