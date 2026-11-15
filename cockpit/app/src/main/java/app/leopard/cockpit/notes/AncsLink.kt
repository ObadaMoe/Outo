package app.leopard.cockpit.notes

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The iPhone's notifications over Bluetooth (Apple's ANCS, see [Ancs]), as a fitness band
 * gets them: no app on the phone.
 *
 * Outo announces itself to iPhones nearby as an accessory that wants their notifications
 * (the car's Bluetooth name, in the iPhone's Settings › Bluetooth). The iPhone connects
 * there; Outo then reads it as a client over the same link, pairing the first time (the
 * iPhone asks to pair, then to share its notifications). After that the iPhone connects
 * again by itself whenever it's near and Outo is announcing.
 *
 * Every Bluetooth call runs on the main thread, one at a time, as Android wants them.
 */
@SuppressLint("MissingPermission") // Each entry point checks the permissions first (see [allowed]).
class AncsLink(private val context: Context) {

    enum class Stage {
        OFF,
        /** Outo hasn't been allowed to use Bluetooth. */
        NEEDS_PERMISSION,
        /** The car's Bluetooth is off. */
        NO_BLUETOOTH,
        /** The car's Bluetooth can't announce Outo. */
        UNSUPPORTED,
        /** Announcing, for the iPhone to connect. */
        WAITING,
        CONNECTING,
        /** The iPhone is asked to pair. */
        PAIRING,
        CONNECTED,
    }

    /** [carName]: what the iPhone lists; [phone]: the iPhone's name; [problem]: why it isn't working. */
    data class State(val stage: Stage = Stage.OFF, val carName: String? = null, val phone: String? = null, val problem: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val manager get() = context.getSystemService(BluetoothManager::class.java)

    /** Switched on in Settings (it starts again once Bluetooth or the permission comes). */
    private var wanted = false
    private var running = false
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var gatt: BluetoothGatt? = null
    private var phone: BluetoothDevice? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    /** Services were looked for before pairing, and the iPhone hid them: look again once paired. */
    private var discoverAfterBond = false

    private val answers = Ancs.Answers()
    /** Bluetooth operations, one at a time: each waits for the last one's callback. */
    private val ops = ArrayDeque<Op>()
    private var busy: Op? = null
    /** Control Point commands, each waiting for its whole answer before the next. */
    private val commands = ArrayDeque<Pair<Ancs.Asked, ByteArray>>()
    /** What each notification's event said, until its attributes come. */
    private val events = HashMap<Int, Ancs.Event>()
    private val namesAsked = HashSet<String>()

    private sealed interface Op {
        data class Subscribe(val ch: BluetoothGattCharacteristic) : Op
        data class Write(val ch: BluetoothGattCharacteristic, val value: ByteArray) : Op
    }

    /** What Outo has to be allowed (from Android 12; before that, installing allows it). */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()

    fun allowed(): Boolean = permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun start() = main.post {
        wanted = true
        startNow()
    }

    fun stop() = main.post {
        wanted = false
        stopNow()
        _state.value = State(Stage.OFF)
    }

    // ---- Starting and stopping -------------------------------------------------------------

    private var adapterWatch: BroadcastReceiver? = null

    private fun startNow() {
        if (running || !wanted) return
        watchAdapter()
        if (!allowed()) {
            _state.value = State(Stage.NEEDS_PERMISSION)
            return
        }
        val adapter = manager?.adapter
        if (adapter == null) {
            _state.value = State(Stage.UNSUPPORTED, problem = "This car has no Bluetooth Outo can use.")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = State(Stage.NO_BLUETOOTH)
            return
        }
        if (Build.VERSION.SDK_INT < 31 || adapter.bluetoothLeAdvertiser == null) {
            _state.value = State(Stage.UNSUPPORTED, problem = "The car's Bluetooth can't announce Outo to the iPhone.")
            return
        }
        running = true
        watchBonds()
        val carName = runCatching { adapter.name }.getOrNull()
        server = runCatching { manager?.openGattServer(context, serverCallback) }.onFailure { Log.w(TAG, "gatt server", it) }.getOrNull()
        _state.value = State(Stage.WAITING, carName = carName)
        advertise(adapter, withName = true)
    }

    private fun stopNow() {
        lose()
        stopAdvertising()
        runCatching { server?.close() }
        server = null
        running = false
        bondWatch?.let { runCatching { context.unregisterReceiver(it) } }
        bondWatch = null
    }

    /** Bluetooth switched on or off, a permission given: start or stop with it. */
    private fun watchAdapter() {
        if (adapterWatch != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> main.post { startNow() }
                    BluetoothAdapter.STATE_OFF -> main.post {
                        stopNow()
                        if (wanted) _state.value = State(Stage.NO_BLUETOOTH)
                    }
                }
            }
        }
        adapterWatch = r
        ContextCompat.registerReceiver(context, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** The permission was just given in Settings. */
    fun allowedNow() = main.post { if (wanted && !running) startNow() }

    // ---- Announcing ------------------------------------------------------------------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            main.post { advertising = true }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post {
                advertising = false
                Log.w(TAG, "advertising failed: $errorCode")
                when (errorCode) {
                    // A long car name doesn't fit: without it (the iPhone then lists Outo by its address).
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> manager?.adapter?.let { advertise(it, withName = false) }
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> advertising = true
                    else -> _state.update { it.copy(problem = "The car's Bluetooth won't announce Outo (error $errorCode).") }
                }
            }
        }
    }

    private fun advertise(adapter: BluetoothAdapter, withName: Boolean) {
        if (Build.VERSION.SDK_INT < 31) return
        val adv = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // "Wants your notifications": what makes an iPhone list an accessory and offer to share them.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceSolicitationUuid(ParcelUuid(Ancs.SERVICE))
            .build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(withName).build()
        runCatching { adv.startAdvertising(settings, data, scan, advertiseCallback) }
            .onFailure { Log.w(TAG, "advertise", it) }
    }

    private fun stopAdvertising() {
        if (Build.VERSION.SDK_INT >= 31) runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    /** Announcing again (a connection can end it). */
    private fun readvertise() {
        stopAdvertising()
        if (running) manager?.adapter?.let { advertise(it, withName = true) }
    }

    // ---- The iPhone connecting -------------------------------------------------------------

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                when (newState) {
                    // Someone connected to the car: if Outo isn't reading a phone yet, see whether
                    // it's an iPhone with notifications to share.
                    BluetoothProfile.STATE_CONNECTED -> if (running && gatt == null) connect(device)
                    BluetoothProfile.STATE_DISCONNECTED -> if (device.address == phone?.address) {
                        lose()
                        readvertise()
                    }
                }
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        phone = device
        _state.update { it.copy(stage = Stage.CONNECTING, phone = nameOf(device), problem = null) }
        gatt = runCatching { device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE) }
            .onFailure { Log.w(TAG, "connect", it) }.getOrNull()
        if (gatt == null) lose()
    }

    /** The phone gone (or not an iPhone): back to announcing. */
    private fun lose() {
        runCatching { gatt?.close() }
        gatt = null
        phone = null
        controlPoint = null
        discoverAfterBond = false
        ops.clear()
        busy = null
        commands.clear()
        events.clear()
        answers.reset()
        main.removeCallbacks(answerLate)
        PhoneNotes.phoneGone()
        if (running) _state.update { it.copy(stage = Stage.WAITING, phone = null) }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                when (newState) {
                    // A bigger packet size, so answers take fewer packets; then the services.
                    BluetoothProfile.STATE_CONNECTED -> if (!g.requestMtu(MTU)) g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        lose()
                        readvertise()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (g === gatt) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val service = g.getService(Ancs.SERVICE)
                val device = phone
                when {
                    service != null -> subscribe(service)
                    // An iPhone shows its notifications only to an accessory it's paired with.
                    device != null && device.bondState != BluetoothDevice.BOND_BONDED -> {
                        discoverAfterBond = true
                        pair(device)
                    }
                    // Paired, and no notifications to share: not an iPhone (another phone using the
                    // car's Bluetooth, say). Leave it be.
                    else -> {
                        Log.i(TAG, "no notification service on ${device?.address}")
                        lose()
                    }
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { if (g === gatt) done(status) { if (d.characteristic.uuid == Ancs.NOTIFICATION_SOURCE) connected() } }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val was = busy
                done(status) {}
                // A command the iPhone refused gets no answer: on to the next one.
                if (status != BluetoothGatt.GATT_SUCCESS && was is Op.Write && answers.waiting && !authProblem(status)) {
                    answers.reset()
                    main.removeCallbacks(answerLate)
                    nextCommand()
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            main.post { if (g === gatt) changed(c.uuid, copy) }
        }

        @Deprecated("Before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // From Android 13 the one above is called instead.
            if (Build.VERSION.SDK_INT >= 33) return
            val copy = c.value?.copyOf() ?: return
            main.post { if (g === gatt) changed(c.uuid, copy) }
        }
    }

    // ---- Pairing ---------------------------------------------------------------------------

    private var bondWatch: BroadcastReceiver? = null

    private fun pair(device: BluetoothDevice) {
        _state.update { it.copy(stage = Stage.PAIRING) }
        if (device.bondState == BluetoothDevice.BOND_NONE) runCatching { device.createBond() }
    }

    private fun watchBonds() {
        if (bondWatch != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val device = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val bond = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val before = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                main.post {
                    if (device.address != phone?.address) return@post
                    when (bond) {
                        BluetoothDevice.BOND_BONDING -> _state.update { it.copy(stage = Stage.PAIRING) }
                        BluetoothDevice.BOND_BONDED -> {
                            _state.update { it.copy(stage = Stage.CONNECTING) }
                            if (discoverAfterBond) {
                                discoverAfterBond = false
                                gatt?.discoverServices()
                            } else pump()
                        }
                        BluetoothDevice.BOND_NONE -> if (before == BluetoothDevice.BOND_BONDING) {
                            _state.update { it.copy(problem = "Pairing with the iPhone didn't finish. Tap the car again in the iPhone's Settings › Bluetooth.") }
                            lose()
                        }
                    }
                }
            }
        }
        bondWatch = r
        ContextCompat.registerReceiver(context, r, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ---- Subscribing, and the operations' queue ---------------------------------------------

    private fun subscribe(service: android.bluetooth.BluetoothGattService) {
        val ns = service.getCharacteristic(Ancs.NOTIFICATION_SOURCE)
        val cp = service.getCharacteristic(Ancs.CONTROL_POINT)
        val ds = service.getCharacteristic(Ancs.DATA_SOURCE)
        if (ns == null || cp == null || ds == null) {
            Log.w(TAG, "notification service without its characteristics")
            lose()
            return
        }
        controlPoint = cp
        // The answers' source first, so none is missed once the notifications start coming.
        enqueue(Op.Subscribe(ds))
        enqueue(Op.Subscribe(ns))
    }

    private fun enqueue(op: Op) {
        ops.addLast(op)
        pump()
    }

    private fun pump() {
        if (busy != null) return
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: return
        busy = op
        val started = when (op) {
            is Op.Subscribe -> {
                g.setCharacteristicNotification(op.ch, true)
                val d = op.ch.getDescriptor(Ancs.CLIENT_CONFIG)
                d != null && writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            is Op.Write -> writeCharacteristic(g, op.ch, op.value)
        }
        if (!started) {
            // Android is busy with something else: try again shortly.
            busy = null
            ops.addFirst(op)
            main.postDelayed({ pump() }, 150)
        }
    }

    /** An operation's callback: on to the next, or (not paired yet) pair and do it again. */
    private fun done(status: Int, then: () -> Unit) {
        val op = busy
        busy = null
        if (status == BluetoothGatt.GATT_SUCCESS) {
            then()
        } else if (authProblem(status) && op != null) {
            // The iPhone wants pairing first: done again once paired.
            ops.addFirst(op)
            phone?.let { pair(it) }
            return
        } else {
            Log.w(TAG, "gatt op $op failed: $status")
        }
        pump()
    }

    private fun authProblem(status: Int) =
        status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION || status == GATT_AUTH_FAIL

    private fun connected() {
        val device = phone ?: return
        _state.update { it.copy(stage = Stage.CONNECTED, phone = nameOf(device), problem = null) }
        PhoneNotes.dismissOnPhone = { uid ->
            main.post { controlPoint?.let { enqueue(Op.Write(it, Ancs.actionCommand(uid, Ancs.ACTION_NEGATIVE))) } }
        }
        // Announcing on as well, for when it reconnects; some cars stop on a connection.
        if (!advertising) readvertise()
    }

    // ---- Notifications ---------------------------------------------------------------------

    private fun changed(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            Ancs.NOTIFICATION_SOURCE -> {
                val e = Ancs.event(value) ?: return
                when (e.id) {
                    Ancs.REMOVED -> {
                        events.remove(e.uid)
                        PhoneNotes.remove(e.uid)
                    }
                    else -> {
                        // Many already on the phone when it connects: the newest are enough.
                        if (e.preExisting && commands.size > PRE_EXISTING_MAX) return
                        events[e.uid] = e
                        ask(Ancs.Asked.Note(e.uid), Ancs.noteCommand(e.uid))
                    }
                }
            }
            Ancs.DATA_SOURCE -> {
                val answer = answers.feed(value) ?: return
                main.removeCallbacks(answerLate)
                take(answer)
                nextCommand()
            }
        }
    }

    private fun take(answer: Ancs.Answer) {
        when (answer) {
            is Ancs.Answer.Note -> {
                val e = events.remove(answer.uid) ?: return
                val v = answer.values
                val appId = v[Ancs.APP_IDENTIFIER].orEmpty()
                val note = PhoneNote(
                    uid = answer.uid,
                    appId = appId,
                    title = v[Ancs.TITLE].orEmpty(),
                    subtitle = v[Ancs.SUBTITLE].orEmpty(),
                    message = v[Ancs.MESSAGE].orEmpty(),
                    category = e.category,
                    at = Ancs.date(v[Ancs.DATE]) ?: System.currentTimeMillis(),
                    important = e.important,
                    canDismiss = e.canDismiss,
                )
                PhoneNotes.put(note, loud = e.id == Ancs.ADDED && !e.preExisting && !e.silent)
                if (appId.isNotEmpty() && PhoneNotes.nameOf(appId) == null && namesAsked.add(appId)) {
                    ask(Ancs.Asked.App(appId), Ancs.appCommand(appId))
                }
            }
            is Ancs.Answer.App -> answer.values[Ancs.DISPLAY_NAME]?.let { PhoneNotes.setName(answer.appId, it) }
        }
    }

    private fun ask(asked: Ancs.Asked, bytes: ByteArray) {
        commands.addLast(asked to bytes)
        nextCommand()
    }

    /** The next command, once the last one's answer is in. */
    private fun nextCommand() {
        if (answers.waiting) return
        val cp = controlPoint ?: return
        val (asked, bytes) = commands.removeFirstOrNull() ?: return
        answers.expect(asked)
        enqueue(Op.Write(cp, bytes))
        main.postDelayed(answerLate, ANSWER_MS)
    }

    /** No (whole) answer: on to the next command. */
    private val answerLate = Runnable {
        answers.reset()
        nextCommand()
    }

    // ---- Android's two ways of writing ------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else {
            d.value = v
            g.writeDescriptor(d)
        }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            c.value = v
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(c)
        }

    private fun nameOf(device: BluetoothDevice): String? = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "OutoNotes"
        const val MTU = 185
        /** How long an answer may take. */
        const val ANSWER_MS = 4_000L
        /** How many notifications already on the phone are read when it connects. */
        const val PRE_EXISTING_MAX = 40
        /** Android's "authentication failed" status. */
        const val GATT_AUTH_FAIL = 137
    }
}
