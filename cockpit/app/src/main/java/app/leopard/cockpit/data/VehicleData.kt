package app.leopard.cockpit.data

import android.content.Context
import android.os.SystemClock
import app.leopard.cockpit.CrashLog
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
import kotlin.math.abs
import kotlin.math.max

/**
 * Car values as the helper reports them, scaled to everyday units. Anything the
 * car didn't report is null, and the UI shows a dash instead of a number.
 */
data class VehicleData(val raw: Map<String, Int> = emptyMap(), val pedalTop: Int = 0) {
    private fun i(key: String): Int? = raw[key]

    val isEmpty: Boolean get() = raw.isEmpty()

    val speedKmh: Int? get() = i("speed")?.takeIf { it in 0..400 }

    /**
     * The speed to a tenth of a km/h, from BYD's speed device, where the car has it
     * ([speedKmh] is the speedometer's whole km/h). Only when it agrees with the
     * speedometer, which reads a little high, so a value in other units is never used.
     */
    val speedFineKmh: Float? get() {
        val fine = i("speedFine")?.takeIf { it in 0..4000 }?.let { it / 10f } ?: return null
        val whole = speedKmh ?: return null
        return fine.takeIf { abs(it - whole) <= max(3f, whole * 0.1f) }
    }
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

    /** The accelerator pedal as the car reports it. Its scale isn't documented: see [pedalTop]. */
    val pedalRaw: Int? get() = i("pedal")?.takeIf { it >= 0 }

    /**
     * Accelerator pedal, 0..1: the car's value over [pedalTop], the most it has reported
     * (held for a moment) on a full press, at least 100.
     */
    val pedal: Float? get() = pedalRaw?.let { (it.toFloat() / max(pedalTop, 100)).coerceIn(0f, 1f) }

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.survive)
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
                    val parsed = VehicleData.parse(o)
                    learnPedal(parsed.pedalRaw)
                    val v = parsed.copy(pedalTop = pedalTop)
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

    /**
     * The most the car's pedal value has reached: its full press. The car doesn't say
     * its scale (0–100 on some, more on others), so Outo learns it from the hardest
     * press it sees, kept from then on. Only a value held for three reads in a row
     * counts, so a glitch can't set it.
     */
    private var pedalTop = sp.getInt(PEDAL_TOP, 0)
    private val pedalRecent = ArrayDeque<Int>()

    private fun learnPedal(raw: Int?) {
        if (raw == null || raw > 10_000) { pedalRecent.clear(); return }
        pedalRecent.addLast(raw)
        while (pedalRecent.size > 3) pedalRecent.removeFirst()
        if (pedalRecent.size < 3) return
        val held = pedalRecent.min()
        if (held > pedalTop) {
            pedalTop = held
            sp.edit().putInt(PEDAL_TOP, held).apply()
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

    private companion object {
        const val PEDAL_TOP = "pedal_top"
    }
}
