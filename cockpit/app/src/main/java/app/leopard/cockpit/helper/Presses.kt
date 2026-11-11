package app.leopard.cockpit.helper

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * When the driver last pressed something: a finger on a screen or a button (the car's
 * own, on the wheel or the dashboard). Read straight from the car's input devices
 * (`/dev/input`, which the shell may read, as `getevent` does), so it also counts
 * presses on BYD's own bars and buttons, which Otility never sees.
 *
 * It tells the driver's doing from the car's: BYD's home screen coming up over Otility
 * right after a press is the driver's choice; with no press, it's the car's start-up.
 */
internal object Presses {
    /** When the last press was (uptime), 0 if none since the helper started. */
    @Volatile var last = 0L
        private set
    /** Which device it came from (its name, as `getevent -i` shows it), for the log. */
    @Volatile var from: String? = null
        private set
    /** How many input devices are being read: 0 when the shell may not read them. */
    @Volatile var reading = 0
        private set

    private const val EV_KEY = 1
    private const val EV_ABS = 3
    /** A new finger on a multi-touch screen (its tracking id; -1 is a finger leaving). */
    private const val ABS_MT_TRACKING_ID = 0x39

    fun start() {
        // struct input_event: a timeval (two longs), then type, code (u16) and value (s32).
        val size = if (Process.is64Bit()) 24 else 16
        val devices = File("/dev/input").listFiles()?.filter { it.name.startsWith("event") }.orEmpty()
        for (d in devices) {
            Thread({ read(d, size) }, "otility-input-${d.name}").apply { isDaemon = true }.start()
        }
    }

    private fun read(device: File, size: Int) {
        val name = runCatching { File("/sys/class/input/${device.name}/device/name").readText().trim() }
            .getOrNull()?.ifEmpty { null } ?: device.name
        var counted = false
        try {
            FileInputStream(device).use { s ->
                synchronized(this) { reading++ }
                counted = true
                val buf = ByteArray(size * 16)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
                while (true) {
                    val n = s.read(buf)
                    if (n <= 0) return
                    var at = 0
                    while (at + size <= n) {
                        val type = bb.getShort(at + size - 8).toInt() and 0xffff
                        val code = bb.getShort(at + size - 6).toInt() and 0xffff
                        val value = bb.getInt(at + size - 4)
                        if ((type == EV_KEY && value == 1) || (type == EV_ABS && code == ABS_MT_TRACKING_ID && value >= 0)) {
                            last = SystemClock.uptimeMillis()
                            from = name
                        }
                        at += size
                    }
                }
            }
        } catch (t: Throwable) {
            // Not readable (or gone): presses there go uncounted, and the car's doing and the
            // driver's look alike (Ignition then brings Otility back only once).
            Log.i(HelperMain.TAG, "no presses from ${device.name}: ${t.message}")
        } finally {
            if (counted) synchronized(this) { reading-- }
        }
    }
}
