package app.leopard.cockpit.helper

import android.content.AttributionSource
import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Entry point of the helper process. Started through the car's debugging
 * connection with:
 *
 *   CLASSPATH=<path of Otility's APK> app_process /system/bin \
 *       --nice-name=otility_helper app.leopard.cockpit.helper.HelperMain
 *
 * It runs as the shell user, hands its binder to Otility, and stays up until the
 * car powers off (or it's stopped from Settings).
 */
object HelperMain {
    const val TAG = "OtilityHelper"
    const val VERSION = 12
    const val APP_PACKAGE = "app.leopard.cockpit"
    const val AUTHORITY = "app.leopard.cockpit.helper"
    const val NICE_NAME = "otility_helper"
    /** How long displays from a closed Otility wait for the next one to take them back. */
    const val KEEP_MS = 90_000L

    @JvmStatic
    fun main(args: Array<String>) {
        // Leave the debugging session's process group, so closing it doesn't stop us.
        runCatching { Os.setsid() }
        Looper.prepareMainLooper()
        Log.i(TAG, "helper $VERSION starting as uid ${Process.myUid()}")
        // The system context has to be made on the main thread (it needs this thread's Looper).
        runCatching { ShellContext.instance }.onFailure { Log.w(TAG, "no system context", it) }
        val service = HelperService()
        Thread({ deliverLoop(service) }, "otility-deliver").apply { isDaemon = true }.start()
        service.watchPopups()
        SpeedLimits.start()
        // Let Otility open itself at start-up next time (apps that may draw over
        // others are allowed to start from the background).
        run(listOf("appops", "set", APP_PACKAGE, "SYSTEM_ALERT_WINDOW", "allow"))
        if ("--open" in args) {
            // Always the car's main screen, never one of the displays we host apps in.
            run(listOf("am", "start", "--display", "0", "-n", "$APP_PACKAGE/.MainActivity", "-f", "0x10000000"))
        }
        Looper.loop()
    }

    /**
     * Watches for Otility's process and gives it our binder each time it (re)starts.
     * The shell can see every process in /proc, so this is a cheap check.
     */
    private fun deliverLoop(service: HelperService) {
        var deliveredTo = -1
        while (true) {
            // While the Otility that has our binder runs, only it is looked at. Reading every
            // process each time made megabytes of garbage, and the pauses that clear it held
            // up touches on their way to the apps.
            val found = if (deliveredTo != -1 && cmdline(deliveredTo) == APP_PACKAGE) deliveredTo to service.appUid else findApp()
            val pid = found?.first
            if (deliveredTo != -1 && pid != deliveredTo) {
                // Otility closed or restarted (a crash, the car's memory manager). Its
                // views went with it; its displays are kept for the next one (see reset).
                Log.i(TAG, "Otility pid $deliveredTo went away")
                service.reset()
                deliveredTo = -1
            }
            if (found != null && pid != deliveredTo) {
                service.appUid = found.second
                if (sendBinder(service)) {
                    deliveredTo = found.first
                    service.connected()
                    Log.i(TAG, "binder delivered to pid $pid")
                }
            } else if (pid == null) {
                deliveredTo = -1
            }
            service.tidyKept()
            // Opens Otility when the car is switched on (BYD force-stops it at switch-off).
            runCatching { Ignition.tick() }
            SystemClock.sleep(1500)
        }
    }

    private fun run(cmd: List<String>) {
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() }
    }

    /**
     * Otility's process in the car's main user, and its uid. The passenger screen's
     * user (999) can run its own copy of Otility, and any app can take Otility's
     * process name, but not its uid: the binder only goes to the main user's Otility.
     */
    private fun findApp(): Pair<Int, Int>? {
        val want = runCatching { ShellContext.instance.packageManager.getPackageUid(APP_PACKAGE, 0) }.getOrDefault(-1)
        val procs = File("/proc").listFiles() ?: return null
        for (p in procs) {
            val pid = p.name.toIntOrNull() ?: continue
            if (cmdline(pid) != APP_PACKAGE) continue
            val uid = readUid(pid)
            // Without the package's uid (no system context), at least the main user's.
            if (if (want >= 0) uid == want else uid >= 0 && uid / 100_000 == Hidden.MAIN_USER) return pid to uid
        }
        return null
    }

    /** Only [deliverLoop]'s thread reads with it. */
    private val cmdBuf = ByteArray(128)

    /** A process's name (the first part of its command line), read into one small buffer. */
    private fun cmdline(pid: Int): String? = runCatching {
        FileInputStream("/proc/$pid/cmdline").use { s ->
            val n = s.read(cmdBuf)
            var end = 0
            while (end < n && cmdBuf[end] != 0.toByte()) end++
            String(cmdBuf, 0, end)
        }
    }.getOrNull()

    private fun readUid(pid: Int): Int = runCatching {
        File("/proc/$pid/status").readLines().first { it.startsWith("Uid:") }
            .split(Regex("\\s+"))[1].toInt()
    }.getOrDefault(-1)

    /** Calls Otility's provider with our binder in the extras (the same way Shizuku does). */
    private fun sendBinder(binder: IBinder): Boolean {
        val token = Binder()
        return try {
            val am = Hidden.activity
            val holder = Hidden.call(am, "getContentProviderExternal", AUTHORITY, 0, token, "otility") ?: return false
            val provider = holder.javaClass.getField("provider").get(holder) ?: return false
            val extras = Bundle().apply { putBinder("binder", binder) }
            val attribution = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
            provider.javaClass.getMethod(
                "call", AttributionSource::class.java, String::class.java, String::class.java,
                String::class.java, Bundle::class.java,
            ).invoke(provider, attribution, AUTHORITY, "binder", null, extras)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "binder delivery failed", t)
            false
        } finally {
            runCatching { Hidden.call(Hidden.activity, "removeContentProviderExternalAsUser", AUTHORITY, token, 0) }
        }
    }
}

/** am's own failure lines ("Error: Activity not started…", "Exception occurred while executing…"). */
private val AM_FAILED = Regex("""(?m)^\s*(Error\b|Exception occurred|java\.lang\.\w*Exception)""")

internal class HelperService : IOtilityHelper.Stub() {

    @Volatile var appUid: Int = -1

    private val hosts = ConcurrentHashMap<Int, VirtualDisplay>()
    private val mirrors = ConcurrentHashMap<Int, Hidden.Mirror>()
    private var nextMirror = 1

    /** Only Otility (and root/shell for testing) may use the helper. */
    private fun check() {
        val uid = Binder.getCallingUid()
        if (uid != appUid && uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw SecurityException("uid $uid isn't Otility")
        }
    }

    override fun version(): Int = HelperMain.VERSION

    override fun setOpenWithCar(on: Boolean) {
        check()
        if (Ignition.openWithCar != on) Log.i(HelperMain.TAG, "open Otility with the car: $on")
        Ignition.openWithCar = on
    }

    /**
     * Displays a previous Otility made, kept with no picture so their apps stay hidden
     * instead of landing on the car's main screen over everything (where a guiding
     * map also shrinks into picture-in-picture). The next Otility takes each back
     * when it asks for that app's display again; [tidyKept] lets go of the rest.
     */
    private val kept = ConcurrentHashMap<Int, VirtualDisplay>()
    @Volatile private var keptAt = 0L

    /** Otility closed or restarted: its views are gone, but keep the displays (and their apps) for the next one. */
    fun reset() {
        for ((id, vd) in hosts) {
            val s = vd.surface
            runCatching { vd.surface = null }
            s?.release()
            kept[id] = vd
        }
        hosts.clear()
        if (kept.isNotEmpty()) keptAt = SystemClock.uptimeMillis()
        mirrors.values.forEach { runCatching { it.close() } }
        mirrors.clear()
        // Otility gone with a finger down on an app: the app has it lifted (cancelled), or
        // it stays held on the kept display (YouTube's video at 2x, say).
        synchronized(pressing) {
            for ((display, last) in pressing) {
                val cancel = last.toCancel()
                runCatching { Hidden.inject(cancel, display) }
                cancel.recycle(); last.recycle()
            }
            if (pressing.isNotEmpty()) Log.i(HelperMain.TAG, "touches ended on displays ${pressing.keys}")
            pressing.clear()
        }
        // What Otility set in the car itself stays set without it: the engine sound on
        // the outside speaker, and the last turn and distance on the cluster. Undo both;
        // the next Otility sets them again if it wants them.
        if (outside) {
            outside = false
            Log.i(HelperMain.TAG, "outside speaker off: ${Vehicle.exteriorSpeaker(false)}")
        }
        if (navSent) {
            navSent = false
            runCatching { Vehicle.setRoad("") }
            for ((feature, value) in Instrument.CLEAR) {
                exec(listOf("service", "call", "autoservice", "6", "i32", "${Instrument.DEVICE}", "i32", "$feature", "i32", "$value"))
            }
            Log.i(HelperMain.TAG, "cluster navigation cleared")
        }
        if (limitSent) {
            limitSent = false
            SpeedLimits.clear()
        }
    }

    /** Otility has set a speed limit and not cleared it yet. */
    @Volatile private var limitSent = false

    /** Otility has the car's sound on the outside speaker. */
    @Volatile private var outside = false
    /** Otility has written navigation to the cluster and not cleared it yet. */
    @Volatile private var navSent = false

    /**
     * Lets go of kept displays that no app is in any more, and of all of them once
     * Otility has had [KEEP_MS] to take them back (or hasn't come back at all): their
     * apps then return to the main screen, as before.
     */
    fun tidyKept() {
        if (kept.isEmpty()) return
        val expired = SystemClock.uptimeMillis() - keptAt > HelperMain.KEEP_MS
        val used = if (expired) emptySet() else runCatching { Hidden.tasks().map { it.displayId }.toSet() }.getOrNull() ?: return
        for (id in kept.keys.toList()) {
            if (expired || id !in used) {
                kept.remove(id)?.let(::dispose)
                Log.i(HelperMain.TAG, "kept display $id let go")
            }
        }
    }

    /** Otility (re)connected: it has [KEEP_MS] from now to take its displays back. */
    fun connected() {
        if (kept.isNotEmpty()) keptAt = SystemClock.uptimeMillis()
    }

    override fun snapshot(): String {
        check()
        val out = JSONObject()
        val displays = JSONArray()
        for (id in Hidden.displayIds()) {
            val d = Hidden.displayInfo(id) ?: continue
            displays.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("w", d.width); put("h", d.height)
                put("dpi", d.dpi); put("layerStack", d.layerStack); put("flags", d.flags)
                put("type", d.type); put("owner", d.owner ?: "")
            })
        }
        val tasks = JSONArray()
        for (t in Hidden.tasks()) {
            tasks.put(JSONObject().apply {
                put("taskId", t.taskId); put("displayId", t.displayId); put("pkg", t.pkg)
                put("component", t.component); put("visible", t.visible); put("type", t.activityType)
                put("userId", t.userId)
            })
        }
        out.put("displays", displays)
        out.put("tasks", tasks)
        out.put("hosts", JSONArray(hosts.keys.toList()))
        // Apps whose windows were put back from the main screen (see watchPopups), newest last.
        val now = SystemClock.uptimeMillis()
        out.put("popups", JSONArray().apply {
            synchronized(popups) {
                for (p in popups) put(JSONObject().put("seq", p.seq).put("pkg", p.pkg).put("ago", now - p.at))
            }
        })
        // Which install of Otility this helper runs from; after an update the app sees
        // a different path or file time and restarts the helper with the new code
        // (Android sometimes keeps the same path for an update).
        out.put("apk", apkPath)
        out.put("apkTime", apkTime)
        return out.toString()
    }

    private val apkPath = System.getProperty("java.class.path") ?: ""
    /** The APK's time when this helper started, before an update could replace it. */
    private val apkTime = File(apkPath).lastModified()

    /**
     * Runs [move], which opens or moves an app onto [displayId], and keeps Otility in
     * front on the main screen if it was. In the car, an app first opened from BYD's
     * home screen brings that home screen forward whenever it's brought to the front
     * (Android's "return to home"), even as it leaves for another display. Taking it
     * over would otherwise leave the home screen covering Otility.
     */
    private fun <T> keepingOtilityInFront(displayId: Int, wasInFront: Boolean? = null, move: () -> T): T {
        if (displayId == 0) return move()
        val before = wasInFront ?: (ownTask()?.visible == true)
        val result = move()
        if (before) {
            SystemClock.sleep(150)
            val now = ownTask()
            if (now != null && !now.visible) {
                runCatching { Hidden.showTask(now.taskId, 0) }
                    .onSuccess { Log.i(HelperMain.TAG, "home screen came forward; Otility brought back in front") }
                    .onFailure { Log.w(HelperMain.TAG, "Otility back in front", it) }
            }
        }
        return result
    }

    /** Otility's own window on the main screen. */
    private fun ownTask() = runCatching {
        Hidden.tasks().firstOrNull {
            it.pkg == HelperMain.APP_PACKAGE && it.userId == Hidden.MAIN_USER && it.displayId == 0 && it.activityType == 1
        }
    }.getOrNull()

    override fun launch(pkg: String, displayId: Int): Int {
        check()
        return keepingOtilityInFront(displayId) { launchNow(pkg, displayId) }
    }

    private fun launchNow(pkg: String, displayId: Int): Int {
        val args = mutableListOf("am", "start-activity", "--display", displayId.toString(), "-f", "0x10200000")
        if (pkg.contains('/')) {
            args += listOf("-n", pkg)
        } else {
            args += listOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", pkg)
        }
        val packageName = pkg.substringBefore('/')
        if (packageName == HelperMain.APP_PACKAGE) {
            // One of Otility's own windows for another screen (the cluster's map). Otility's
            // main window is its task too, and must stay where it is: nothing is moved.
            exec(args)
            return -1
        }
        val before = mainTasks(packageName).map { it.taskId }.toSet()
        val otilityInFront = ownTask()?.visible == true
        launchedAt[packageName] = SystemClock.uptimeMillis()
        exec(args)
        val task = settle(packageName, displayId)
        watchHandover(packageName, displayId, before, otilityInFront)
        return task
    }

    /** The app's tasks for the car's main user (the passenger screen's own copy left out). */
    private fun mainTasks(packageName: String) = runCatching {
        Hidden.tasks().filter { it.pkg == packageName && it.activityType == 1 && it.userId == Hidden.MAIN_USER }
    }.getOrDefault(emptyList())

    /**
     * Some apps (Waze, for one) open a splash screen that hands over to their real
     * window in a new task a moment later, and Android puts that one on the car's
     * main screen, over Otility. For a few seconds after a launch, a new task of the
     * app that turns up anywhere else is moved to where the app was opened. Tasks the
     * app had before the launch are left alone (one opened from the car's own home
     * screen, say).
     */
    private fun watchHandover(packageName: String, displayId: Int, before: Set<Int>, otilityInFront: Boolean) {
        if (displayId == 0) return
        Thread({
            // Long enough for a slow start on a busy car.
            val deadline = SystemClock.uptimeMillis() + 15_000
            val handled = HashSet<Int>()
            while (SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(150)
                for (t in mainTasks(packageName)) {
                    if (t.taskId in before || t.taskId in handled || t.displayId == displayId) continue
                    handled += t.taskId
                    Log.i(HelperMain.TAG, "$packageName handed over to task ${t.taskId} on display ${t.displayId}; moving it to $displayId")
                    runCatching { keepingOtilityInFront(displayId, otilityInFront) { moveTaskNow(t.taskId, displayId) } }
                        .onFailure { Log.w(HelperMain.TAG, "handover", it) }
                }
            }
        }, "otility-handover").apply { isDaemon = true }.start()
    }

    // ---- Pop-ups ---------------------------------------------------------------

    /** When Otility last started each app in one of its displays (or showed its display again). */
    private val launchedAt = ConcurrentHashMap<String, Long>()

    private class Popup(val seq: Int, val pkg: String, val at: Long)

    /** Apps whose windows were put back from the main screen, newest last; Otility shows them. */
    private val popups = ArrayDeque<Popup>()
    /** Counts on from when this helper started, so a helper started again never repeats a number Otility has seen. */
    private var popupSeq = (SystemClock.uptimeMillis() / 1000).toInt()

    /**
     * An app in one of Otility's displays sometimes opens a window of its own: Waze's
     * "want to go there?", a page of the car's settings. Android can put that window on
     * the car's main screen and take the app's whole task with it, out of Otility and
     * over it. Every moment the helper looks: an app that left one of Otility's displays
     * for the main screen, or a new window one of them opened there, while Otility was
     * in front, goes straight back into that display, and Otility is told, so it shows
     * the app where it can be answered. With Otility not in front (an app opened from
     * the car's own home screen) it stays: that's the driver's.
     */
    fun watchPopups() {
        Thread({
            /** Each task in one of Otility's displays at the last look, and which display. */
            var homes = emptyMap<Int, Int>()
            /** Every task at the last look: one that isn't here is new. */
            var known = emptySet<Int>()
            var otilityInFront = false
            /** When each task was put back, to give up on one Android keeps sending over. */
            val moved = HashMap<Int, ArrayList<Long>>()
            while (true) {
                SystemClock.sleep(POPUP_LOOK_MS)
                if (hosts.isEmpty()) {
                    homes = emptyMap(); known = emptySet(); otilityInFront = false
                    continue
                }
                var tasks = runCatching { Hidden.tasks() }.getOrNull() ?: continue
                // Otility's display for each app, by its name ("otility_" and the app).
                val displayOf = HashMap<String, Int>()
                for ((id, vd) in hosts) runCatching { vd.display.name }.getOrNull()
                    ?.takeIf { it.startsWith(HOST_PREFIX) }?.let { displayOf[it.removePrefix(HOST_PREFIX)] = id }
                var changed = false
                if (otilityInFront) {
                    val now = SystemClock.uptimeMillis()
                    for (t in tasks) {
                        if (t.displayId != 0 || t.userId != Hidden.MAIN_USER || t.activityType != 1) continue
                        if (t.pkg == HelperMain.APP_PACKAGE) continue
                        // Just started by Otility: a splash screen handing over, which watchHandover
                        // puts back. Nothing for the driver to answer.
                        val starting = now - (launchedAt[t.pkg] ?: 0L) < POPUP_QUIET_MS
                        val home: Int
                        val owner: String
                        val from = homes[t.taskId]
                        if (from != null) {
                            // It was in one of Otility's displays a moment ago.
                            if (!hosts.containsKey(from)) continue
                            home = from
                            owner = displayOf.entries.firstOrNull { it.value == from }?.key ?: t.pkg
                        } else if (t.taskId !in known && !starting) {
                            // A new window: from an app in one of Otility's displays?
                            val app = displayOf[t.pkg]?.let { t.pkg } ?: openedBy(t.pkg, t.taskId)?.takeIf { it in displayOf }
                            if (app == null) continue
                            home = displayOf[app] ?: continue
                            owner = app
                        } else continue
                        val times = moved.getOrPut(t.taskId) { ArrayList() }
                        times.removeAll { now - it > POPUP_GIVE_UP_MS }
                        if (times.size >= POPUP_MOVES_MAX) {
                            if (times.size == POPUP_MOVES_MAX) Log.i(HelperMain.TAG, "${t.pkg} keeps coming to the main screen; leaving it there")
                            times += now
                            continue
                        }
                        times += now
                        Log.i(HelperMain.TAG, "${t.pkg} (task ${t.taskId}) came to the main screen from inside Otility; back to display $home")
                        val ok = runCatching { keepingOtilityInFront(home, true) { moveTaskNow(t.taskId, home) } }
                            .onFailure { Log.w(HelperMain.TAG, "pop-up back", it) }.getOrDefault(false)
                        changed = true
                        if (ok && !starting) synchronized(popups) {
                            popups.addLast(Popup(++popupSeq, owner, SystemClock.uptimeMillis()))
                            while (popups.size > 4) popups.removeFirst()
                        }
                    }
                    if (moved.size > 50) moved.keys.retainAll(tasks.map { it.taskId }.toSet())
                }
                if (changed) tasks = runCatching { Hidden.tasks() }.getOrNull() ?: tasks
                homes = tasks.filter { hosts.containsKey(it.displayId) && it.userId == Hidden.MAIN_USER && it.activityType == 1 }
                    .associate { it.taskId to it.displayId }
                known = tasks.map { it.taskId }.toSet()
                val main = tasks.filter { it.displayId == 0 && it.visible }
                otilityInFront = main.any { it.pkg == HelperMain.APP_PACKAGE && it.userId == Hidden.MAIN_USER && it.activityType == 1 } &&
                    main.none { it.activityType == ACTIVITY_TYPE_HOME }
            }
        }, "otility-popups").apply { isDaemon = true }.start()
    }

    /**
     * The app that opened the first window of [taskId] (one of [pkg]'s), as Android
     * keeps it: an app in one of Otility's displays opening a page of the settings, say.
     */
    private fun openedBy(pkg: String, taskId: Int): String? {
        val out = exec(listOf("dumpsys", "activity", "-p", pkg, "activities"))
        var inTask = false
        var opener: String? = null
        for (line in out.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("* Task{")) {
                if (inTask) break
                inTask = l.contains(" #$taskId ")
            } else if (inTask && l.startsWith("launchedFromUid=")) {
                // The last one listed is the task's first window.
                opener = LAUNCHED_FROM.find(l)?.groupValues?.get(1) ?: opener
            }
        }
        return opener?.takeIf { it != "null" && it != pkg }
    }

    /**
     * Some apps open a splash screen that hands over to another activity, which
     * can land on the main screen. Waits for the app's task and moves it if so.
     */
    private fun settle(packageName: String, displayId: Int): Int {
        // Only the main user's copy: the passenger screen can run the same app as its
        // own user, and that one must stay where it is.
        fun find() = Hidden.tasks().firstOrNull {
            it.pkg == packageName && it.activityType == 1 && it.userId == Hidden.MAIN_USER
        }
        val deadline = SystemClock.uptimeMillis() + 4000
        while (SystemClock.uptimeMillis() < deadline) {
            val task = find()
            if (task != null) {
                if (task.displayId != displayId) {
                    SystemClock.sleep(400)
                    val again = find() ?: task
                    if (again.displayId != displayId) runCatching { Hidden.moveTask(again.taskId, displayId) }
                    return again.taskId
                }
                return task.taskId
            }
            SystemClock.sleep(150)
        }
        return -1
    }

    override fun moveTask(taskId: Int, displayId: Int): Boolean {
        check()
        return keepingOtilityInFront(displayId) { moveTaskNow(taskId, displayId) }
    }

    private fun moveTaskNow(taskId: Int, displayId: Int): Boolean {
        val there = runCatching { Hidden.tasks().firstOrNull { it.taskId == taskId }?.displayId == displayId }.getOrDefault(false)
        val moved = there || runCatching { Hidden.moveTask(taskId, displayId); true }.getOrElse {
            Log.w(HelperMain.TAG, "moveTask", it); false
        }
        // A map that was guiding when it fell onto the main screen went into
        // picture-in-picture, and keeps that small window on any display: make it fill.
        if (moved) runCatching { if (Hidden.fillDisplay(taskId)) Log.i(HelperMain.TAG, "task $taskId out of picture-in-picture") }
            .onFailure { Log.w(HelperMain.TAG, "fillDisplay", it) }
        // An app moved to a screen with another size or density sometimes isn't
        // redrawn there and shows only its last frame. Bringing the same task to the
        // front on that screen makes it draw. (Starting the app again instead would
        // give apps like Chrome a second window and lose the page.)
        if (moved) runCatching { Hidden.showTask(taskId, displayId) }
            .onFailure { Log.w(HelperMain.TAG, "showTask", it) }
        return moved
    }

    override fun closeTask(taskId: Int): Boolean {
        check()
        return runCatching { Hidden.removeTask(taskId) }.getOrDefault(false)
    }

    override fun createHostDisplay(name: String, surface: Surface?, width: Int, height: Int, dpi: Int): Int {
        check()
        // The display a previous Otility had for this app, with the app still in it.
        val back = kept.entries.firstOrNull { runCatching { it.value.display.name }.getOrNull() == "otility_$name" }
        if (back != null && kept.remove(back.key) != null) {
            val vd = back.value
            runCatching {
                vd.resize(width, height, dpi)
                vd.surface = surface
            }.onSuccess {
                hosts[back.key] = vd
                Log.i(HelperMain.TAG, "host display ${back.key} taken back for $name")
                if (surface != null) wakeWhenShown(back.key)
                return back.key
            }.onFailure { Log.w(HelperMain.TAG, "taking back display ${back.key}", it); dispose(vd) }
        }
        val vd = ShellContext.createDisplay("otility_$name", surface, width, height, dpi) ?: return -1
        val id = vd.display.displayId
        hosts[id] = vd
        Hidden.setImePolicy(id, 0 /* keyboard on this display */)
        Log.i(HelperMain.TAG, "host display $id ${width}x$height@$dpi")
        return id
    }

    override fun resizeHostDisplay(displayId: Int, width: Int, height: Int, dpi: Int) {
        check()
        hosts[displayId]?.resize(width, height, dpi)
    }

    override fun setHostSurface(displayId: Int, surface: Surface?) {
        check()
        val vd = hosts[displayId] ?: run { surface?.release(); return }
        val old = vd.surface
        vd.surface = surface
        if (old != null && old !== surface) old.release()
        if (surface != null && old == null) wakeWhenShown(displayId)
    }

    /**
     * A display switched on again (it has a picture to draw into) after being off.
     * Android 13, as in the car, leaves an app that sat in it while it was off
     * stopped and invisible, so only Otility's icon for it shows. Newer Android wakes
     * it by itself. A moment later, an app still not visible there is brought to the
     * front in its own display, which wakes it.
     */
    private fun wakeWhenShown(displayId: Int) {
        val otilityInFront = ownTask()?.visible == true
        Thread({
            // An app that sat waiting in the dark carries on now that it can draw. A splash
            // screen started with no picture (Waze, started for the Overview's map before the
            // Overview was seen) hands over only now, to a new task that can land on the main
            // screen over Otility: watch for that, as after a launch.
            val here = runCatching {
                Hidden.tasks().filter { it.displayId == displayId && it.activityType == 1 && it.userId == Hidden.MAIN_USER }
            }.getOrDefault(emptyList())
            for (pkg in here.map { it.pkg }.distinct()) {
                launchedAt[pkg] = SystemClock.uptimeMillis()
                watchHandover(pkg, displayId, mainTasks(pkg).map { it.taskId }.toSet(), otilityInFront)
            }
            SystemClock.sleep(400)
            if (hosts[displayId]?.surface == null) return@Thread
            val asleep = runCatching { Hidden.tasks().filter { it.displayId == displayId && it.activityType == 1 && !it.visible } }
                .getOrDefault(emptyList())
            for (t in asleep) {
                Log.i(HelperMain.TAG, "waking task ${t.taskId} (${t.pkg}) on display $displayId")
                runCatching { keepingOtilityInFront(displayId, otilityInFront) { Hidden.showTask(t.taskId, displayId) } }
                    .onFailure { Log.w(HelperMain.TAG, "wake", it) }
            }
        }, "otility-wake").apply { isDaemon = true }.start()
    }

    override fun releaseHostDisplay(displayId: Int) {
        check()
        hosts.remove(displayId)?.let(::dispose)
    }

    private fun dispose(vd: VirtualDisplay) {
        val s = vd.surface
        runCatching { vd.release() }
        s?.release()
    }

    override fun startMirror(sourceDisplayId: Int, surface: Surface, width: Int, height: Int): Int {
        check()
        val d = Hidden.displayInfo(sourceDisplayId)?.let(Hidden::pictureOf) ?: run { surface.release(); return -1 }
        val mirror = runCatching { Hidden.mirror("otility_mirror_$sourceDisplayId", d, surface, width, height) }
            .getOrElse { Log.w(HelperMain.TAG, "mirror $sourceDisplayId", it); surface.release(); return -1 }
        val id = synchronized(this) { nextMirror++ }
        // The surface came over binder as our own copy: let it go with the mirror.
        mirrors[id] = Hidden.Mirror { mirror.close(); surface.release() }
        return id
    }

    override fun stopMirror(mirrorId: Int) {
        check()
        mirrors.remove(mirrorId)?.let { runCatching { it.close() } }
    }

    override fun injectMotion(event: MotionEvent, displayId: Int) {
        if (Binder.getCallingUid() != appUid) return
        Hidden.unobscure(event)
        runCatching { Hidden.inject(event, displayId) }.onFailure(::injectFailed)
        synchronized(pressing) {
            val last = when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> null
                else -> MotionEvent.obtain(event)
            }
            (if (last == null) pressing.remove(displayId) else pressing.put(displayId, last))?.recycle()
        }
        event.recycle()
    }

    /** Each display's touch going on (a finger down that hasn't come up): the last event of it sent. */
    private val pressing = HashMap<Int, MotionEvent>()

    @Volatile private var injectLogged = false

    /** Logged once, so a broken input path shows up without flooding the log. */
    private fun injectFailed(t: Throwable) {
        if (injectLogged) return
        injectLogged = true
        Log.w(HelperMain.TAG, "touch injection failed", t)
    }

    override fun injectKey(keyCode: Int, displayId: Int) {
        if (Binder.getCallingUid() != appUid) return
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val e = KeyEvent(now, now, action, keyCode, 0, 0, -1 /* virtual keyboard */, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD)
            runCatching { Hidden.inject(e, displayId) }.onFailure(::injectFailed)
        }
    }

    override fun cluster(code: Int): String {
        check()
        return exec(listOf("service", "call", "auto_container", "2", "i32", "1000", "i32", code.toString(), "s16", ""))
    }

    override fun vehicle(): String {
        check()
        return Vehicle.read().toString()
    }

    override fun exteriorSpeaker(on: Boolean): Boolean {
        check()
        outside = on
        return Vehicle.exteriorSpeaker(on)
    }

    override fun openUri(pkg: String, uris: Array<String>, displayId: Int): Boolean {
        check()
        for (uri in uris) {
            val out = exec(listOf(
                "am", "start-activity", "--display", displayId.toString(), "-f", "0x10000000",
                "-a", "android.intent.action.VIEW", "-d", uri, "-p", pkg,
            ))
            // am echoes the link back ("Starting: Intent { … dat=geo:…Error… }"), so only
            // its own error lines count, not the word anywhere.
            if (AM_FAILED.containsMatchIn(out)) {
                Log.w(HelperMain.TAG, "openUri $uri: $out")
                continue
            }
            settle(pkg, displayId)
            return true
        }
        return false
    }

    override fun hidesBars(pkg: String, displayId: Int): Boolean {
        check()
        require(PACKAGE.matches(pkg)) { "not a package: $pkg" }
        // Only that app's windows: a few kilobytes, where all of them would be far more.
        val out = exec(listOf("dumpsys", "window", pkg), timeoutS = 3)
        return !out.startsWith("error") && WindowBars.hides(out, pkg, displayId)
    }

    override fun installUpdate(apk: ParcelFileDescriptor): String {
        check()
        val file = File(UPDATE_FILE)
        try {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(apk).use { input -> file.outputStream().use { input.copyTo(it) } }
            } catch (t: Throwable) {
                return "error: couldn't read the update (${t.message})"
            }
            // Only Outo itself: the helper doesn't install other apps. Android checks the
            // rest (the same signing key, and not an older version).
            val info = runCatching { ShellContext.instance.packageManager.getPackageArchiveInfo(file.path, 0) }.getOrNull()
            if (info?.packageName != HelperMain.APP_PACKAGE) return "error: that file isn't Outo"
            Log.i(HelperMain.TAG, "installing the update to ${info.versionName}")
            val out = exec(listOf("pm", "install", "-r", file.path), INSTALL_TIMEOUT_S)
            if (!out.lineSequence().any { it.trim() == "Success" }) {
                Log.w(HelperMain.TAG, "update: $out")
                return "error: ${out.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: "the install didn't finish"}"
            }
            Log.i(HelperMain.TAG, "updated to ${info.versionName}")
            // Android closed Outo to update it: open it again, and look again a little later
            // in case the car's home screen comes up over it.
            Ignition.reopen("updated to ${info.versionName}")
            return "ok"
        } finally {
            file.delete()
        }
    }

    override fun notificationAccess(allow: Boolean): Boolean {
        check()
        val listener = "${HelperMain.APP_PACKAGE}/${HelperMain.APP_PACKAGE}.nav.NavListener"
        val out = exec(listOf("cmd", "notification", if (allow) "allow_listener" else "disallow_listener", listener))
        if (out.isNotEmpty()) Log.i(HelperMain.TAG, "notification access: $out")
        return !out.contains("rror") && !out.contains("xception")
    }

    override fun accessibilityAccess(allow: Boolean): Boolean {
        check()
        val app = HelperMain.APP_PACKAGE
        val names = setOf("$app/$app.nav.MapScreenReader", "$app/.nav.MapScreenReader")
        val current = exec(listOf("settings", "get", "secure", "enabled_accessibility_services"))
        val list = current.split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.toMutableList()
        if (allow == list.any { it in names }) return true
        if (allow) list += names.first() else list.removeAll { it in names }
        // Other accessibility services the car has on stay as they are.
        val out = if (list.isEmpty()) exec(listOf("settings", "delete", "secure", "enabled_accessibility_services"))
        else exec(listOf("settings", "put", "secure", "enabled_accessibility_services", list.joinToString(":")))
        if (allow) exec(listOf("settings", "put", "secure", "accessibility_enabled", "1"))
        Log.i(HelperMain.TAG, "map reader ${if (allow) "on" else "off"} $out")
        return !out.contains("rror") && !out.contains("xception")
    }

    override fun instrumentSet(device: Int, feature: Int, value: Int): String {
        check()
        require(Instrument.mayWrite(device, feature, value)) { "not a navigation value: $device/$feature=$value" }
        // Guiding leaves something on the cluster until "done" ends it (clearing the
        // values after "done" doesn't).
        if (feature == Instrument.NAV_STATUS) navSent = value != Instrument.DONE
        return exec(listOf("service", "call", "autoservice", "6", "i32", "$device", "i32", "$feature", "i32", "$value"))
    }

    override fun instrumentSetRoad(road: String): String {
        check()
        if (road.isNotEmpty()) navSent = true
        return runCatching { Vehicle.setRoad(road.take(Instrument.ROAD_MAX)) }
            .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }
    }

    override fun capture(displayId: Int, left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): IntArray {
        check()
        // Small pictures only: enough to read an arrow, not to film a screen.
        require(width in 1..CAPTURE_MAX && height in 1..CAPTURE_MAX && right > left && bottom > top) { "bad capture" }
        val d = Hidden.displayInfo(displayId)?.let(Hidden::pictureOf) ?: return IntArray(0)
        return runCatching { Hidden.capture(d, Rect(left, top, right, bottom), width, height) }
            .getOrElse { Log.w(HelperMain.TAG, "capture $displayId", it); IntArray(0) }
    }

    override fun setSpeedLimit(kmh: Int): String {
        check()
        require(kmh in 0..Instrument.SPEED_LIMIT_MAX) { "not a speed limit: $kmh" }
        limitSent = kmh > 0
        // Sent again every second from now on, with the camera told a map is guiding (see SpeedLimits).
        return SpeedLimits.set(kmh)
    }

    override fun instrumentGet(device: Int, feature: Int): Int {
        check()
        require(Instrument.mayRead(device, feature)) { "not a navigation value: $device/$feature" }
        val out = exec(listOf("service", "call", "autoservice", "5", "i32", "$device", "i32", "$feature"))
        return Instrument.parseInt(out) ?: Int.MIN_VALUE
    }

    /**
     * Runs a command and returns what it printed. Its output is read on the side, so
     * a command that never ends (a busy car service) is stopped after [EXEC_TIMEOUT_S]
     * instead of holding this call, and with it Otility's bridge lane, forever.
     */
    private fun exec(cmd: List<String>, timeoutS: Long = EXEC_TIMEOUT_S): String = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = FutureTask { p.inputStream.bufferedReader().readText() }
        Thread(out, "otility-exec").apply { isDaemon = true }.start()
        if (p.waitFor(timeoutS, TimeUnit.SECONDS)) {
            out.get(2, TimeUnit.SECONDS).trim()
        } else {
            p.destroyForcibly()
            Log.w(HelperMain.TAG, "timed out: ${cmd.take(4).joinToString(" ")}")
            "error: timed out"
        }
    } catch (t: Throwable) {
        "error: ${t.message}"
    }

    private companion object {
        const val EXEC_TIMEOUT_S = 10L
        /** A package name, so nothing else reaches dumpsys's arguments. */
        val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        /** Installing a 20 MB update can take a while on a busy car. */
        const val INSTALL_TIMEOUT_S = 180L
        /** Where an update waits while it's installed (the shell can read it here; Outo's own files it can't). */
        const val UPDATE_FILE = "/data/local/tmp/outo-update.apk"
        const val CAPTURE_MAX = 256
        /** How often the helper looks for apps' windows landing on the main screen. */
        const val POPUP_LOOK_MS = 300L
        /** After Otility starts an app, its own hand-over (a splash screen) isn't a pop-up. */
        const val POPUP_QUIET_MS = 8_000L
        /** At most this many times a task is put back in [POPUP_GIVE_UP_MS]; then it stays. */
        const val POPUP_MOVES_MAX = 3
        const val POPUP_GIVE_UP_MS = 60_000L
        /** ACTIVITY_TYPE_HOME: the car's home screen. */
        const val ACTIVITY_TYPE_HOME = 2
        const val HOST_PREFIX = "otility_"
        val LAUNCHED_FROM = Regex("launchedFromPackage=(\\S+)")
    }
}
