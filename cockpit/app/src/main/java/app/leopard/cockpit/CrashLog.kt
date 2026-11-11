package app.leopard.cockpit

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why Outo closed, kept where it can be read over the car's ADB after a drive:
 * `/sdcard/Android/data/app.leopard.cockpit/files/outo-closed.log`.
 *
 * - Outo's own crashes, with where in the code they happened.
 * - At each start, how Android says the last Outo ended: a crash, a native crash, a
 *   freeze (with its trace), memory, or a stop (BYD stops it at switch-off). Android
 *   keeps only a few of these, and a few drives push the one that matters out.
 *
 * Read only by people; Outo itself never acts on it.
 */
object CrashLog {
    private const val TAG = "Otility"
    private const val NAME = "outo-closed.log"
    private const val MAX = 512 * 1024L
    private const val SEEN = "exit_seen_at"

    private lateinit var file: File
    private var version = "?"

    fun install(context: Context) {
        file = File(context.getExternalFilesDir(null) ?: context.filesDir, NAME)
        version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write("crash on ${thread.name}, Outo $version\n${Log.getStackTraceString(e).trimEnd()}") }
            before?.uncaughtException(thread, e)
        }
        // Off the main thread: an ANR's trace can be long.
        Thread({ runCatching { lastExits(context) }.onFailure { Log.w(TAG, "exit reasons", it) } }, "otility-exits").start()
    }

    /** How the earlier Outos ended, since the last ones written down. */
    private fun lastExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        // Newest first; only Outo's own process (not the phone link's).
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp > seen && it.processName == context.packageName }
        if (exits.isEmpty()) return
        for (x in exits.reversed()) {
            val head = "ended ${clock(x.timestamp)}: ${reason(x.reason)}" +
                (x.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                ", ${importance(x.importance)}" +
                (x.pss.takeIf { it > 0 }?.let { ", memory ${it / 1024} MB" } ?: "")
            val trace = if (x.reason == ApplicationExitInfo.REASON_ANR || x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                runCatching { x.traceInputStream?.bufferedReader()?.useLines { it.take(80).joinToString("\n") } }.getOrNull()
            } else null
            write(if (trace.isNullOrBlank()) head else "$head\n$trace")
        }
        prefs.edit().putLong(SEEN, exits.maxOf { it.timestamp }).apply()
    }

    private fun reason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "froze (not responding)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped (switch-off, or force stop)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depends on died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much CPU or memory"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_FREEZER -> "frozen in the background"
        ApplicationExitInfo.REASON_OTHER -> "other"
        15 -> "an app package changed" // REASON_PACKAGE_STATE_CHANGE
        16 -> "updated" // REASON_PACKAGE_UPDATED
        else -> "reason $r"
    }

    private fun importance(i: Int) = when {
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "in the background, working"
        else -> "in the background"
    }

    private fun clock(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    @Synchronized
    private fun write(entry: String) {
        if (!::file.isInitialized) return
        // Keep the newer half once it grows too long.
        if (file.length() > MAX) {
            val text = file.readText()
            val cut = text.indexOf("----\n", text.length / 2)
            file.writeText(if (cut >= 0) text.substring(cut) else "")
        }
        file.appendText("----\n${clock(System.currentTimeMillis())} $entry\n")
    }
}
