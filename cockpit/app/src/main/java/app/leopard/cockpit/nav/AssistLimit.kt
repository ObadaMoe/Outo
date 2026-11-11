package app.leopard.cockpit.nav

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.leopard.cockpit.CrashLog
import app.leopard.cockpit.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * A test: the speed limit Outo sends as the navigation's ([RoadLimit]) also goes to the
 * car's driver assistance, on the channel BYD's own map uses for its limit (SOME/IP
 * TrafficInfo, [TrafficInfo]).
 *
 * The HUD and the cluster show what the front camera's computer settles on, from its
 * own sign reading and a map's limit. Outo's limit on the setting device and in the
 * HUD frame is taken and not used. BYD's map sends TrafficInfo once a second, route or
 * not, and in this car always with no limit; that may be the map's word the camera's
 * computer listens to. Outo sends Waze's limit [PER_SECOND] times a second while it has
 * one, so BYD's "none" is the odd one out, and once with no limit when it stops.
 *
 * Off unless switched on. What it sends is written to outo-assist.log in Outo's files
 * folder, to set beside the helper's /data/local/tmp/outo-limit.log after a drive.
 */
class AssistLimit(context: Context, private val prefs: Prefs, private val roadLimit: RoadLimit) {
    companion object {
        private const val TAG = "OutoAssist"
        /** The navigation status and link service (0x0007, instance 7) and its TrafficInfo event (0x8003). */
        const val SERVICE = 0x000B000700070000L
        const val TRAFFIC_INFO = 0x0004000700078003L
        private const val PER_SECOND = 10
        /** How often to look for a limit while there's none. */
        private const val IDLE_MS = 500L
        private const val LOG = "outo-assist.log"
        private const val LOG_MAX = 128 * 1024L
    }

    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status.asStateFlow()

    private val client = SomeIpClient(context, "${context.packageName}.assist", TAG)
    private val log = File(context.getExternalFilesDir(null) ?: context.filesDir, LOG)
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** One thread sends everything, so sends never overlap. */
    private val lane = Executors.newSingleThreadExecutor { r -> Thread(r, "outo-assist").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + lane + CrashLog.survive)

    /** The limit being sent (0: none). */
    private var sending = 0
    /** The last answer written down, so the log has a line only when something changes. */
    private var lastLine = ""
    private var failures = 0
    private var retryAt = 0L

    init {
        scope.launch {
            prefs.settings.map { it.showDirections && it.hudTurns && it.hudSpeedLimit && it.assistLimit }
                .distinctUntilChanged()
                .collectLatest { on ->
                    if (!on) {
                        end()
                        _status.value = "Off"
                        return@collectLatest
                    }
                    lastLine = ""
                    write("switched on")
                    while (true) {
                        val kmh = roadLimit.current
                        if (kmh > 0) send(kmh) else end()
                        if (sending == 0 && failures == 0) _status.value = "Waiting for a speed limit"
                        delay(if (kmh > 0) 1000L / PER_SECOND else IDLE_MS)
                    }
                }
        }
    }

    private suspend fun send(kmh: Int) {
        if (SystemClock.uptimeMillis() < retryAt) return
        val road = TrafficInfo.roadClass(kmh)
        try {
            val rc = client.fire(SERVICE, TRAFFIC_INFO, TrafficInfo.encode(kmh, road))
            if (rc == SomeIpClient.OK) {
                failures = 0
                sending = kmh
                _status.value = "Sending $kmh km/h"
                val from = if (roadLimit.fromCamera) "the car's camera" else "the map"
                note("sending $kmh km/h from $from, road class $road: taken")
            } else {
                fail("$kmh km/h refused (code $rc)", reset = rc in SomeIpClient.RESET)
            }
        } catch (e: SomeIpClient.Failure) {
            fail("$kmh km/h not sent: ${e.message}", reset = e.reset)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail("$kmh km/h not sent: the link broke (${t.javaClass.simpleName})", reset = true)
        }
    }

    /** No limit any more: says so once, as BYD's map does, and gives up the service. */
    private suspend fun end() {
        failures = 0
        retryAt = 0
        if (sending == 0 && !client.connected) return
        if (sending > 0 && client.connected) {
            runCatching { client.fire(SERVICE, TRAFFIC_INFO, TrafficInfo.encode(0)) }
            note("stopped: sent no limit")
        }
        sending = 0
        client.stop(SERVICE)
        client.disconnect()
    }

    /** Waits longer after each failure (up to 30 s) before trying again. */
    private fun fail(why: String, reset: Boolean) {
        failures++
        retryAt = SystemClock.uptimeMillis() + min(30_000L, 1000L shl min(failures, 5))
        _status.value = why
        note(why)
        if (reset) {
            sending = 0
            client.disconnect()
        }
    }

    /** Writes [line] when it differs from the last one written; true if it did. */
    private fun note(line: String): Boolean {
        if (line == lastLine) return false
        lastLine = line
        Log.i(TAG, line)
        write(line)
        return true
    }

    private fun write(line: String) {
        runCatching {
            if (log.length() > LOG_MAX) log.writeText("")
            log.appendText("${clock.format(Date())} $line\n")
        }
    }
}
