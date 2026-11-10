package app.leopard.cockpit.car

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.leopard.cockpit.helper.HelperMain
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Starts the helper from inside the car, through the car's own debugging
 * service on 127.0.0.1:5555 (it's already switched on in this car).
 *
 * The first time, the car shows "Allow debugging?" for Otility's key; tick
 * "Always allow" and it won't ask again.
 */
object HelperLauncher {
    private const val TAG = "Otility"

    enum class Stage { IDLE, CONNECTING, WAITING_FOR_APPROVAL, STARTING, RUNNING, FAILED }

    data class Status(val stage: Stage, val detail: String = "")

    private val _status = MutableStateFlow(Status(Stage.IDLE))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lock = Mutex()

    /**
     * True in the car's main user, where Otility runs the car. The passenger screen
     * runs as another user (999) that can have its own copy of Otility; that copy
     * must never start (or stop) the helper. Android numbers each user's uids in
     * blocks of 100 000.
     */
    val mainUser: Boolean get() = android.os.Process.myUid() / 100_000 == 0

    /** Otility's own lane for starting the helper, alive as long as Otility is. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Starts the helper unless it's already connected. [open] also brings Otility to the
     * front. The work runs on [scope], so a caller that goes away (a screen closing,
     * the activity being remade) doesn't stop it halfway.
     */
    suspend fun ensure(context: Context, open: Boolean = false): Boolean = request(context, open).await()

    /** [ensure] without waiting for it. */
    fun request(context: Context, open: Boolean = false): Deferred<Boolean> {
        val app = context.applicationContext
        return scope.async { runCatching { ensureNow(app, open) }.getOrDefault(false) }
    }

    private suspend fun ensureNow(app: Context, open: Boolean): Boolean = lock.withLock {
        if (!mainUser) return false
        appContext = app
        try {
            if (!alive()) {
                // A helper that's already running hands over its connection within a
                // couple of seconds of Otility starting; don't restart it needlessly.
                _status.value = Status(Stage.CONNECTING)
                withTimeoutOrNull(2_500) { while (!alive()) delay(150) }
            }
            if (alive() && !olderInstall(app)) {
                _status.value = Status(Stage.RUNNING)
                return true
            }
            if (alive()) Log.i(TAG, "the helper is from an older install of Otility; restarting it")
            // Making the key or talking to the car's debugging service can fail
            // (storage, a dropped connection); say so instead of stopping Otility.
            try {
                withContext(Dispatchers.IO) { start(app, open) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "starting the helper", t)
                _status.value = Status(Stage.FAILED, "Couldn't start the helper")
                false
            }
        } finally {
            // Never left showing progress: lost() ignores a stopped helper while it does.
            val stage = _status.value.stage
            if (stage == Stage.CONNECTING || stage == Stage.WAITING_FOR_APPROVAL || stage == Stage.STARTING) {
                _status.value = if (alive()) Status(Stage.RUNNING) else Status(Stage.FAILED, "Couldn't start the helper")
            }
        }
    }

    private fun alive() = HelperConnection.current?.asBinder()?.isBinderAlive == true

    /**
     * After Otility is updated, a helper still running the old code is replaced. Only
     * when the helper answered and named another APK (or file time): a helper that
     * couldn't answer just now is left running, not restarted (which would drop the
     * apps it hosts).
     */
    private suspend fun olderInstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        val o = runCatching { org.json.JSONObject(HelperConnection.current!!.snapshot()) }
            .onFailure { Log.w(TAG, "asking the helper which install it runs", it) }
            .getOrNull() ?: return@withContext false
        val apk = context.applicationInfo.sourceDir
        // The path alone isn't enough: an update can keep it.
        o.optString("apk") != apk || o.optLong("apkTime", -1L) != File(apk).lastModified()
    }

    /** Set when Otility starts, so a stopped helper can be started again from anywhere. */
    @Volatile var appContext: Context? = null
    private var lastAutoRestart = 0L

    /** Called when the helper's connection drops (car restart, helper stopped). */
    fun lost() {
        // A restart we started ourselves already shows its own progress (and needs no other).
        val stage = _status.value.stage
        if (stage == Stage.CONNECTING || stage == Stage.WAITING_FOR_APPROVAL || stage == Stage.STARTING) return
        if (stage == Stage.RUNNING) _status.value = Status(Stage.IDLE, "Disconnected")
        // The helper stopped while Otility is still running (it was killed or crashed).
        // Its hidden displays went with it, so the main screen's apps fell onto the car's
        // main screen over everything. Start it again so they go back where they were;
        // at most once a minute, so a helper that can't stay up doesn't keep retrying.
        val context = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoRestart < 60_000) return
        lastAutoRestart = now
        scope.launch {
            delay(800)
            Log.i(TAG, "the helper stopped; starting it again")
            ensure(context)
        }
    }

    private suspend fun start(context: Context, open: Boolean): Boolean {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val keys = AdbKeyPair.read(priv, pub)

        // The first connection waits for the driver to tap "Allow"; retry gently.
        var dadb: Dadb? = null
        // Nothing listened on the port: the car's debugging is off (or not up yet), and
        // there's no "Allow" to tap.
        var refused = false
        val waits = longArrayOf(0, 500, 1000, 2000, 3000, 5000, 5000, 8000)
        for ((i, w) in waits.withIndex()) {
            delay(w)
            _status.value = if (i == 0 || refused) Status(Stage.CONNECTING)
                else Status(Stage.WAITING_FOR_APPROVAL, "Tap \"Always allow\" on the car's screen")
            val d = runCatching { Dadb.create("127.0.0.1", 5555, keys, connectTimeout = 3000, socketTimeout = 15000) }
                .getOrNull() ?: continue
            val ready = runCatching { exec(d, "echo ready").contains("ready") }
            refused = ready.exceptionOrNull()
                ?.let { t -> generateSequence(t) { it.cause }.any { it is java.net.ConnectException } } ?: false
            if (ready.getOrDefault(false)) { dadb = d; break }
            // A half-made connection would otherwise stay open.
            runCatching { d.close() }
        }
        if (dadb == null) {
            _status.value = Status(Stage.FAILED,
                if (refused) "The car's debugging service is off" else "Couldn't reach the car's debugging service")
            return false
        }

        _status.value = Status(Stage.STARTING)
        dadb.use { d ->
            val apk = context.applicationInfo.sourceDir
            // setsid puts the helper in its own session, so it isn't stopped when this
            // debugging session closes a moment later.
            val cmd = buildString {
                append("kill \$(pidof ${HelperMain.NICE_NAME}) 2>/dev/null; ")
                append("CLASSPATH='$apk' setsid nohup app_process /system/bin --nice-name=${HelperMain.NICE_NAME} ")
                append("${HelperMain::class.java.name}")
                if (open) append(" --open")
                append(" > /data/local/tmp/otility_helper.log 2>&1 < /dev/null & ")
                // Give it a moment to leave this session before the session closes.
                append("sleep 1; echo started")
            }
            Log.i(TAG, "starting helper")
            exec(d, cmd)
        }
        // The helper hands its binder to us within a couple of seconds.
        val ok = withTimeoutOrNull(10_000) {
            while (!alive()) delay(150)
            true
        } ?: false
        _status.value = if (ok) Status(Stage.RUNNING) else Status(Stage.FAILED, "The helper didn't answer")
        return ok
    }

    /** Runs a command over the plain shell service (works on every adbd). */
    private fun exec(d: Dadb, cmd: String): String {
        return d.open("shell:$cmd").use { stream ->
            val src = stream.source
            buildString {
                while (!src.exhausted()) append(src.readUtf8Line() ?: break).append('\n')
            }
        }
    }
}
