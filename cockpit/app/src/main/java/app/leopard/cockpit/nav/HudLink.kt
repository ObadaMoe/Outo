package app.leopard.cockpit.nav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import app.leopard.cockpit.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Shows the next turn on the windshield display (HUD) through the car's
 * SOME/IP gateway, on the same channel BYD's own map uses. While a route is
 * guiding it sends the whole frame every [PERIOD] ms, and at once when the turn
 * changes; when guidance ends it sends one "stopped" frame and lets go.
 */
class HudLink(private val context: Context, private val prefs: Prefs) {
    companion object {
        private const val TAG = "OtilityHud"
        private const val GATEWAY_PKG = "com.ts.car.someip.service"
        private const val GATEWAY = "com.ts.car.someip.service.manager.SomeIpServerService"
        private const val IFACE = "ts.car.someip.sdk.ISomeIpServerInterface"
        private const val CALLBACK = "ts.car.someip.sdk.ISomeIpCallback"

        /** The HUD navigation service (service 0x010A, instance 1) and its turn event (0x8001). */
        const val SERVICE = 0x000B010A00010000L
        const val ROAD_INFO = 0x0004010A00018001L

        private const val REGISTER = 1
        private const val START = 4
        private const val STOP = 5
        private const val FIRE = 6
        private const val OK = 0
        private const val ALREADY_STARTED = 13
        /** Codes after which the link is set up again. */
        private val RESET = setOf(1, 11, 15)

        private const val PERIOD = 400L
        /** The arrow picture's size. BYD's map sends 34 px, 7orr 128 px; the car decides what looks right. */
        private const val ICON_PX = 64
    }

    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status.asStateFlow()

    /** One thread sends everything, so frames never overlap. */
    private val lane = Executors.newSingleThreadExecutor { r -> Thread(r, "otility-hud").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + lane)

    @Volatile private var gateway: IBinder? = null
    @Volatile private var started = false
    @Volatile private var connection: ServiceConnection? = null
    private var failures = 0
    private var retryAt = 0L
    private var lastArrow: Pair<Bitmap, ByteArray>? = null

    init {
        scope.launch {
            combine(prefs.settings.map { it.showDirections && it.hudTurns }.distinctUntilChanged(), Directions.turn) { on, t -> on to t }
                .collectLatest { (on, turn) ->
                    when {
                        !on -> {
                            stop()
                            _status.value = "Off"
                        }
                        turn == null -> {
                            if (gateway == null) _status.value = "On. Waiting for a route"
                            // A turn can vanish for a moment between instructions.
                            delay(3000)
                            stop()
                            _status.value = "On. Waiting for a route"
                        }
                        else -> {
                            val frame = frame(turn)
                            while (true) {
                                send(frame)
                                delay(PERIOD)
                            }
                        }
                    }
                }
        }
    }

    private fun frame(t: NextTurn): ByteArray {
        val icon = when {
            t.maneuver != Maneuver.UNKNOWN -> TurnArt.png(t.maneuver, ICON_PX)
            t.arrow != null -> png(t.arrow)
            else -> null
        }
        val route = HudRoute.shape(t.maneuver, t.metres)
        val f = HudFrame.Fields(
            remainingMetres = t.remainingMetres ?: 0,
            remainingSeconds = t.remainingSeconds ?: 0,
            icon = icon,
            // Proto3 leaves out 0, which reads as no distance at all: right at the turn, 10 m.
            metres = t.metres?.coerceAtLeast(10) ?: 0,
            road = t.street,
            eta = t.remainingSeconds?.let { Units.arrival(it) } ?: Units.clock24(t.eta),
            code = HudFrame.code(t.maneuver),
            // The map's limit for this road, in place of the one the car guesses.
            speedLimit = t.speedLimit?.takeIf { prefs.settings.value.hudSpeedLimit } ?: 0,
            longitude = HudRoute.START_LONGITUDE,
            latitude = HudRoute.START_LATITUDE,
            guideLine = route.line,
            guidePoint = route.point,
        )
        val bytes = HudFrame.encode(f)
        return if (bytes.size > 65_536) HudFrame.encode(f.withoutIcon()) else bytes
    }

    /** The map app's own arrow, as a PNG of the HUD's size. */
    private fun png(b: Bitmap): ByteArray {
        lastArrow?.let { (bitmap, bytes) -> if (bitmap === b) return bytes }
        val scaled = Bitmap.createScaledBitmap(b, ICON_PX, ICON_PX, true)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        if (scaled !== b) scaled.recycle()
        lastArrow = b to bytes
        return bytes
    }

    private suspend fun send(frame: ByteArray) {
        if (SystemClock.uptimeMillis() < retryAt) return
        val g = gateway ?: connect() ?: return fail("The car's HUD service isn't available", reset = false)
        try {
            if (!started) {
                val rc = call(g, START) { it.writeLong(SERVICE) }
                if (rc != OK && rc != ALREADY_STARTED) return fail("The HUD didn't start (code $rc)", reset = true)
                started = true
                Log.i(TAG, "HUD service started (code $rc)")
            }
            val rc = call(g, FIRE) { data(it, ROAD_INFO, frame) }
            if (rc == OK) {
                if (failures > 0 || _status.value != "Sending turns") Log.i(TAG, "HUD accepts turns")
                failures = 0
                _status.value = "Sending turns"
            } else {
                fail("The HUD refused a turn (code $rc)", reset = rc in RESET)
            }
        } catch (t: Throwable) {
            fail("The HUD link broke: ${t.javaClass.simpleName}", reset = true)
        }
    }

    /** Waits longer after each failure (up to 30 s) before trying again. */
    private fun fail(why: String, reset: Boolean) {
        failures++
        retryAt = SystemClock.uptimeMillis() + min(30_000L, 1000L shl min(failures, 5))
        if (_status.value != why) Log.w(TAG, why)
        _status.value = why
        if (reset) disconnect()
    }

    private suspend fun connect(): IBinder? {
        // An earlier binding whose gateway died stays bound until it's let go.
        disconnect()
        val ready = CompletableDeferred<IBinder?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                ready.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) = lost(this)

            override fun onBindingDied(name: ComponentName?) = lost(this)

            override fun onNullBinding(name: ComponentName?) {
                ready.complete(null)
            }
        }
        // The gateway knows each client by the intent's type, and releases what a
        // client started when that client unbinds; BYD's own map does the same.
        val intent = Intent().setComponent(ComponentName(GATEWAY_PKG, GATEWAY)).setType(context.packageName)
        connection = conn
        val bound = runCatching { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) {
            disconnect()
            return null
        }
        val binder = withTimeoutOrNull(5000) { ready.await() }
        if (binder == null) {
            disconnect()
            return null
        }
        runCatching { call(binder, REGISTER, result = false) { it.writeStrongBinder(callback) } }
            .onFailure { Log.w(TAG, "register", it) }
        gateway = binder
        started = false
        return binder
    }

    private fun stop() {
        val g = gateway
        if (g != null && started) {
            runCatching { call(g, FIRE) { data(it, ROAD_INFO, HudFrame.stopped) } }
            runCatching { call(g, STOP) { it.writeLong(SERVICE) } }
            Log.i(TAG, "HUD cleared")
        }
        disconnect()
        failures = 0
        retryAt = 0
    }

    /** The gateway went away. Only the current binding counts; the next send binds again. */
    private fun lost(conn: ServiceConnection) {
        if (connection !== conn) return
        gateway = null
        started = false
    }

    private fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        gateway = null
        started = false
    }

    /** A SomeIpData parcel: present, topic, time, length, then the bytes (which carry their length again). */
    private fun data(p: Parcel, topic: Long, payload: ByteArray) {
        p.writeInt(1)
        p.writeLong(topic)
        p.writeLong(System.currentTimeMillis())
        p.writeInt(payload.size)
        p.writeByteArray(payload)
    }

    private fun call(b: IBinder, code: Int, result: Boolean = true, write: (Parcel) -> Unit): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            return if (result) reply.readInt() else OK
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** What the gateway tells Otility: only whether its link to the car is up. */
    private val callback = object : Binder() {
        init {
            attachInterface(null, CALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK)
                return true
            }
            if (code in 1..3) {
                data.enforceInterface(CALLBACK)
                if (code == 2) Log.i(TAG, "HUD link ${if (data.readInt() != 0) "up" else "down"}")
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
