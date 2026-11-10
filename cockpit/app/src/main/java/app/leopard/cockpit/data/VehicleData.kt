package app.leopard.cockpit.data

import android.content.Context
import android.os.SystemClock
import app.leopard.cockpit.car.CarBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/**
 * Car values as the helper reports them, scaled to everyday units. Anything the
 * car didn't report is null, and the UI shows a dash instead of a number.
 */
data class VehicleData(val raw: Map<String, Int> = emptyMap()) {
    private fun i(key: String): Int? = raw[key]

    val isEmpty: Boolean get() = raw.isEmpty()

    val speedKmh: Int? get() = i("speed")?.takeIf { it in 0..400 }
    val motorRpm: Int? get() = i("motorRpm")
    val soc: Int? get() = i("soc")?.takeIf { it in 0..100 }
    val elecRangeKm: Int? get() = i("elecRange")?.takeIf { it in 0..3000 }
    val fuelRangeKm: Int? get() = i("fuelRange")?.takeIf { it in 0..3000 }
    val fuelPct: Int? get() = i("fuel")?.takeIf { it in 0..100 }
    val odometerKm: Float? get() = i("odometer")?.takeIf { it >= 0 }?.let { it * 0.1f }
    val tempOut: Int? get() = i("tempOut")?.takeIf { it in -60..80 }
    val tempDriver: Int? get() = i("tempDriver")?.takeIf { it in 5..40 }
    val tempPassenger: Int? get() = i("tempPassenger")?.takeIf { it in 5..40 }
    val acOn: Boolean? get() = i("acOn")?.let { it == 1 }
    val pluggedIn: Boolean? get() = i("charging")?.let { it == 1 }
    /**
     * The last speed-limit sign the car's camera read, in km/h (null: none now). The car
     * answers 0 with none, and 255 or more for an unreadable one.
     */
    val cameraLimitKmh: Int? get() = i("limitCamera")?.takeIf { it in 5..250 }

    /** A turn signal is on: one side's lamp lit (both is the hazard lights). */
    val turnSignal: Boolean get() = ((i("turnLeft") ?: 0) > 0) != ((i("turnRight") ?: 0) > 0)

    /** P R N D S, from the gearbox mode. */
    val gear: String? get() = when (i("gear")) {
        1 -> "P"; 2 -> "R"; 3 -> "N"; 4 -> "D"; 5 -> "S"; else -> null
    }
    val moving: Boolean get() = gear in setOf("R", "D", "S")

    /** Accelerator pedal, 0..1. */
    val pedal: Float? get() = i("pedal")?.let { (if (it > 100) it / 1000f else it / 100f).coerceIn(0f, 1f) }

    /** Last trip's electricity use, kWh per 100 km. */
    val lastTripKwh: Float? get() = i("lastElec")?.takeIf { it in 1..1000 }?.let { it * 0.1f }
    val averageKwh: Float? get() = i("totalElec")?.takeIf { it in 1..1000 }?.let { it * 0.1f }

    val totalRangeKm: Int? get() =
        if (elecRangeKm == null && fuelRangeKm == null) null else (elecRangeKm ?: 0) + (fuelRangeKm ?: 0)

    /** Front-left, front-right, rear-left, rear-right, in bar. */
    val tyresBar: List<Float?> get() = listOf("tyreFL", "tyreFR", "tyreRL", "tyreRR").map { k ->
        i(k)?.let { v ->
            when (v) {
                in 100..450 -> v / 100f // kPa
                in 10..45 -> v / 10f // tenths of a bar
                in 1..4 -> v.toFloat()
                else -> null
            }
        }
    }

    companion object {
        fun parse(o: JSONObject): VehicleData =
            VehicleData(o.keys().asSequence().associateWith { o.optInt(it) })
    }
}

/**
 * Reads the car while Otility runs: once a second normally, ten times a second
 * while the engine sound follows the car. Also keeps a small log of kilometres
 * driven per day for the Overview.
 */
class VehicleFeed(context: Context, private val bridge: CarBridge) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sp = context.getSharedPreferences("trips", Context.MODE_PRIVATE)

    private val _data = MutableStateFlow(VehicleData())
    val data: StateFlow<VehicleData> = _data.asStateFlow()

    private val _days = MutableStateFlow(readDays())
    /** The last 7 days, oldest first: date to kilometres. */
    val days: StateFlow<List<Pair<LocalDate, Float>>> = _days.asStateFlow()

    @Volatile var fast: Boolean = false

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            var lastLog = 0L
            var lastRead = SystemClock.elapsedRealtime()
            while (isActive) {
                val o = bridge.vehicle()
                if (o != null) {
                    lastRead = SystemClock.elapsedRealtime()
                    val v = VehicleData.parse(o)
                    _data.value = v
                    val now = System.currentTimeMillis()
                    if (now - lastLog > 60_000) {
                        lastLog = now
                        v.odometerKm?.let { log(it) }
                    }
                } else if (SystemClock.elapsedRealtime() - lastRead >= 1000 && !_data.value.isEmpty) {
                    // The helper stopped answering: last values aren't live any more. Show
                    // dashes (and stop the engine sound, which follows these) until it's back.
                    _data.value = VehicleData()
                }
                delay(if (fast) 100 else 1000)
            }
        }
    }

    private fun log(odo: Float) {
        val today = LocalDate.now().toString()
        val e = sp.edit()
        if (!sp.contains("first_$today")) e.putFloat("first_$today", odo)
        e.putFloat("last_$today", odo).apply()
        _days.value = readDays()
    }

    private fun readDays(): List<Pair<LocalDate, Float>> {
        val today = LocalDate.now()
        return (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val first = sp.getFloat("first_$d", -1f)
            val last = sp.getFloat("last_$d", -1f)
            d to if (first < 0 || last < first) 0f else last - first
        }
    }
}
