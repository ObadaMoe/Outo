package app.leopard.cockpit.helper

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * Reads car values for the Overview and the engine sound.
 *
 * The car's own "auto" service answers a batch of value IDs at once. The IDs are
 * public constants in the car's framework, looked up by name, so nothing here is
 * hard-coded to one model year. Values come back raw; the app scales them (see
 * app.leopard.cockpit.data.VehicleData), and a name the car doesn't know is left
 * out rather than guessed.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object Vehicle {

    /** Our key → the car's value name. */
    private val wanted = linkedMapOf(
        "speed" to "STATISTIC_SPEED_SIG_VDIS",
        "motorRpm" to "ENGINE_FRONT_MOTOR_SPEED",
        "soc" to "STATISTIC_SOC_BATTERY_PERCENTAGE",
        "elecRange" to "STATISTIC_ELEC_DRIVING_RANGE",
        "fuelRange" to "STATISTIC_FUEL_DRIVING_RANGE",
        "fuel" to "STATISTIC_FUEL_PERCENTAGE",
        "odometer" to "STATISTIC_TOTAL_MILEAGE",
        "tempOut" to "AC_TEMP_OUT",
        "tempDriver" to "AC_TEMP_MAIN",
        "tempPassenger" to "AC_TEMP_DEPUTY",
        "acOn" to "AC_POWER_STATE",
        "charging" to "CHARGING_CHARGER_CONNECT_STATE",
        "gear" to "GEARBOX_AUTO_MODE_TYPE",
        "pedal" to "SPEED_ACCELERATOR_DEPTH_10D",
        "lastElec" to "STATISTIC_LAST_ELEC_CON_PHM",
        "totalElec" to "STATISTIC_TOTAL_ELEC_CON_PHM",
        "lastFuel" to "STATISTIC_LAST_FUEL_CON_PHM",
        "tyreFL" to "TYRE_PRESSURE_VALUE_LEFT_FRONT",
        "tyreFR" to "TYRE_PRESSURE_VALUE_RIGHT_FRONT",
        "tyreRL" to "TYRE_PRESSURE_VALUE_LEFT_REAR",
        "tyreRR" to "TYRE_PRESSURE_VALUE_RIGHT_REAR",
        // The turn signals' lamps (0 while off), for BYD's blind-spot camera on the cluster.
        "turnLeft" to "LIGHT_LEFT_TURN_SIGNAL_LIGHT",
        "turnRight" to "LIGHT_RIGHT_TURN_SIGNAL_LIGHT",
    )

    private class Reader(val manager: Any, val method: Method, val keys: List<String>, val ids: IntArray)

    private var made: Reader? = null
    private var lastTry = 0L

    /**
     * Made on first use. If the car's service wasn't ready (the helper can start early),
     * it's tried again every few seconds rather than given up on for good.
     */
    private val reader: Reader?
        @Synchronized get() {
            made?.let { return it }
            val now = SystemClock.uptimeMillis()
            if (lastTry != 0L && now - lastTry < 5_000) return null
            lastTry = now
            return makeReader()?.also { made = it }
        }

    private fun makeReader(): Reader? =
        runCatching {
            val manager = ShellContext.instance.getSystemService("auto") ?: return@runCatching null
            val method = Class.forName("android.hardware.BYDAutoManager")
                .getDeclaredMethod("getIntArray", Int::class.javaPrimitiveType, IntArray::class.java)
                .apply { isAccessible = true }
            val idClass = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
            val keys = ArrayList<String>()
            val ids = ArrayList<Int>()
            for ((key, name) in wanted) {
                val id = runCatching {
                    idClass.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
                }.getOrNull() ?: continue
                keys += key
                ids += id
            }
            Reader(manager, method, keys, ids.toIntArray())
        }.onFailure {
            // Once: on a car (or emulator) without BYD's service it fails every time.
            if (!failLogged) Log.w(HelperMain.TAG, "vehicle reader unavailable", it)
            failLogged = true
        }.getOrNull()

    private var failLogged = false

    fun read(): JSONObject {
        val out = JSONObject()
        val r = reader ?: return out
        val values = runCatching { r.method.invoke(r.manager, 0, r.ids) as? IntArray }.getOrNull() ?: return out
        for (i in r.keys.indices) {
            val v = values.getOrNull(i) ?: continue
            // The car answers a large negative number for values it doesn't have.
            if (v <= -9999) continue
            out.put(r.keys[i], v)
        }
        return out
    }

    /**
     * Routes sound to the speaker outside the car (or back inside). Uses the car's
     * own setting service; returns false if the car refused.
     */
    /**
     * The cluster's next street ([Instrument.ROAD_NAME]). The value is bytes (UTF-16,
     * as BYD's map sends it), which only the car's instrument API takes. Returns its reply.
     */
    fun setRoad(road: String): String =
        setValue("instrument.BYDAutoInstrumentDevice", Instrument.ROAD_NAME, "bufferDataValue", road.toByteArray(Charsets.UTF_16LE))

    /**
     * The road's speed limit for the cluster and the HUD, in km/h (0: none known). BYD's
     * map sets it on the car's setting device, not the cluster's, and the id is read
     * from the car's own list (it differs between car models).
     */
    fun setSpeedLimit(kmh: Int): String {
        val feature = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds").getField("SETTING_SPEED_LIMIT_SET").getInt(null)
        return setValue("setting.BYDAutoSettingDevice", feature, "intValue", kmh)
    }

    /** One value through the car's own device API ([device] under android.hardware.bydauto), as BYD's map sets it. */
    private fun setValue(device: String, feature: Int, field: String, v: Any): String {
        val d = Class.forName("android.hardware.bydauto.$device")
            .getMethod("getInstance", Context::class.java).invoke(null, ShellContext.instance)
            ?: error("no $device")
        val valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue")
        val value = valueClass.getDeclaredConstructor().newInstance()
        valueClass.getField(field).set(value, v)
        val set = d.javaClass.getMethod("set", IntArray::class.java, valueClass)
        return "${set.invoke(d, intArrayOf(feature), value)}"
    }

    fun exteriorSpeaker(on: Boolean): Boolean = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "autoservice") as? IBinder
            ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(binder.interfaceDescriptor ?: "")
            data.writeInt(3) // the exterior sound device
            data.writeInt(-1442839934) // its on/off value
            data.writeInt(if (on) 1 else 0)
            binder.transact(6, data, reply, 0)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }.getOrDefault(false)
}
