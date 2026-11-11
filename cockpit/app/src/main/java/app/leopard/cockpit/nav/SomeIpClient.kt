package app.leopard.cockpit.nav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One client of the car's SOME/IP gateway (com.ts.car.someip.service), as BYD's own map
 * is one. The gateway knows each client by the name it binds with ([type]), keeps a
 * service going while any client has started it, and lets go of a client's share when
 * that client stops it or unbinds. So BYD's map keeps a service it also holds.
 *
 * The same calls as [HudLink]'s, which has its own binding. One thread at a time.
 */
internal class SomeIpClient(private val context: Context, private val type: String, private val tag: String) {
    companion object {
        private const val GATEWAY_PKG = "com.ts.car.someip.service"
        private const val GATEWAY = "com.ts.car.someip.service.manager.SomeIpServerService"
        private const val IFACE = "ts.car.someip.sdk.ISomeIpServerInterface"
        private const val CALLBACK = "ts.car.someip.sdk.ISomeIpCallback"

        private const val REGISTER = 1
        private const val START = 4
        private const val STOP = 5
        private const val FIRE = 6
        const val OK = 0
        const val ALREADY_STARTED = 13
        /** Codes after which the link is set up again. */
        val RESET = setOf(1, 11, 15)
    }

    /** Why the last [fire] failed, when it did. */
    class Failure(message: String, val reset: Boolean) : Exception(message)

    @Volatile private var gateway: IBinder? = null
    @Volatile private var connection: ServiceConnection? = null
    private val started = HashSet<Long>()

    val connected: Boolean get() = gateway != null

    /**
     * Starts [service] (once per binding; started by another client counts) and sends
     * [payload] as event [topic]. Returns the gateway's answer to the event (0: taken).
     * Throws [Failure] when there's no gateway or it won't start the service, and the
     * binder's own errors when the link breaks.
     */
    suspend fun fire(service: Long, topic: Long, payload: ByteArray): Int {
        val g = gateway ?: connect() ?: throw Failure("no gateway", reset = false)
        if (service !in started) {
            val rc = call(g, START) { it.writeLong(service) }
            if (rc != OK && rc != ALREADY_STARTED) throw Failure("start refused (code $rc)", reset = true)
            started += service
            Log.i(tag, "service ${"%016x".format(service)} started (code $rc)")
        }
        return call(g, FIRE) { data(it, topic, payload) }
    }

    /** Gives up this client's share of [service]. */
    fun stop(service: Long) {
        val g = gateway ?: return
        if (started.remove(service)) runCatching { call(g, STOP) { it.writeLong(service) } }
    }

    fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        gateway = null
        started.clear()
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
        val intent = Intent().setComponent(ComponentName(GATEWAY_PKG, GATEWAY)).setType(type)
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
            .onFailure { Log.w(tag, "register", it) }
        gateway = binder
        started.clear()
        return binder
    }

    /** The gateway went away. Only the current binding counts; the next send binds again. */
    private fun lost(conn: ServiceConnection) {
        if (connection !== conn) return
        gateway = null
        started.clear()
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

    /** What the gateway tells Outo: only whether its link to the car is up. */
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
                if (code == 2) Log.i(tag, "gateway link ${if (data.readInt() != 0) "up" else "down"}")
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
