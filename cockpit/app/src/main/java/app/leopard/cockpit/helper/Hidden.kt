package app.leopard.cockpit.helper

import android.content.ComponentName
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.IBinder
import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method

/**
 * Thin reflection wrappers over Android 13 system services, used only inside the
 * helper process (which runs as the shell user, like `adb shell`). Nothing here
 * runs in the normal app.
 */
internal object Hidden {

    // Filled from several binder threads at once.
    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, Method>()

    fun service(name: String): IBinder =
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder

    private fun asInterface(type: String, binder: IBinder): Any =
        Class.forName("$type\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

    val activityTasks: Any by lazy { asInterface("android.app.IActivityTaskManager", service("activity_task")) }
    val activity: Any by lazy { asInterface("android.app.IActivityManager", service("activity")) }
    val window: Any by lazy { asInterface("android.view.IWindowManager", service("window")) }
    val displays: Any by lazy { asInterface("android.hardware.display.IDisplayManager", service("display")) }

    /** Finds a method by name and parameter count (signatures differ a little between versions). */
    fun method(target: Any, name: String, argCount: Int): Method {
        val key = "${target.javaClass.name}#$name/$argCount"
        return methodCache.getOrPut(key) {
            target.javaClass.methods.first { it.name == name && it.parameterTypes.size == argCount }
        }
    }

    fun call(target: Any, name: String, vararg args: Any?): Any? = method(target, name, args.size).invoke(target, *args)

    private fun field(o: Any, name: String): Any? = try {
        o.javaClass.getField(name).get(o)
    } catch (_: NoSuchFieldException) {
        null
    }

    // ---- Tasks -------------------------------------------------------------

    data class TaskRow(
        val taskId: Int,
        val displayId: Int,
        val pkg: String,
        val component: String,
        val visible: Boolean,
        val activityType: Int,
        val bounds: Rect?,
        /** The Android user the app runs as: 0 is the car's main user; the passenger screen has its own (999). */
        val userId: Int,
    )

    /** Every app task, with the display it's on. Home, recents and our own tasks included; callers filter. */
    fun tasks(): List<TaskRow> {
        val infos = call(activityTasks, "getAllRootTaskInfos") as List<*>
        val rows = ArrayList<TaskRow>()
        for (info in infos) {
            if (info == null) continue
            val displayId = field(info, "displayId") as? Int ?: continue
            val visible = (field(info, "visible") ?: field(info, "isVisible")) as? Boolean ?: false
            val type = (field(info, "topActivityType") as? Int)
                ?: runCatching { info.javaClass.getMethod("getActivityType").invoke(info) as Int }.getOrDefault(1)
            val user = field(info, "userId") as? Int ?: 0
            val childIds = field(info, "childTaskIds") as? IntArray
            val childNames = field(info, "childTaskNames") as? Array<*>
            val childBounds = field(info, "childTaskBounds") as? Array<*>
            val childUsers = field(info, "childTaskUserIds") as? IntArray
            if (childIds != null && childNames != null && childIds.size == childNames.size && childIds.size > 1) {
                // A container (e.g. the car's own split screen): list the apps inside.
                for (i in childIds.indices) {
                    val comp = ComponentName.unflattenFromString(childNames[i] as String) ?: continue
                    rows += TaskRow(childIds[i], displayId, comp.packageName, comp.flattenToShortString(),
                        visible, type, childBounds?.getOrNull(i) as? Rect, childUsers?.getOrNull(i) ?: user)
                }
            } else {
                // The app that started the task owns it, even while another app's screen is on
                // top (a permission prompt, a share sheet, a sign-in page).
                val top = (field(info, "baseActivity") ?: field(info, "realActivity") ?: field(info, "topActivity"))
                    as? ComponentName ?: continue
                val id = field(info, "taskId") as? Int ?: continue
                rows += TaskRow(id, displayId, top.packageName, top.flattenToShortString(), visible, type,
                    field(info, "bounds") as? Rect, user)
            }
        }
        return rows
    }

    /**
     * Has Android call [onChange] (on a binder thread) whenever its tasks change: one
     * comes to the front, moves to another display, a window opens. What changed isn't
     * read (the calls differ between versions): the caller looks at the tasks again.
     * Throws if Android won't tell the shell.
     */
    fun watchTasks(onChange: () -> Unit) {
        val descriptor = "android.app.ITaskStackListener"
        val binder = object : android.os.Binder() {
            init {
                attachInterface(null, descriptor)
            }

            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(descriptor)
                    return true
                }
                if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                    // All of them one-way: nothing to answer.
                    runCatching { onChange() }
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        call(activityTasks, "registerTaskStackListener", asInterface(descriptor, binder))
    }

    /** The car's main user. Otility and the apps it runs belong to it; the passenger screen runs as another. */
    const val MAIN_USER = 0

    fun moveTask(taskId: Int, displayId: Int) {
        call(activityTasks, "moveRootTaskToDisplay", taskId, displayId)
    }

    /**
     * Brings an existing task to the front on [displayId], the way the recents screen
     * does: the same task comes back (no new window), so the app keeps its page.
     */
    fun showTask(taskId: Int, displayId: Int) {
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        call(activityTasks, "startActivityFromRecents", taskId, options)
    }

    fun removeTask(taskId: Int): Boolean = call(activityTasks, "removeTask", taskId) as? Boolean ?: false

    /**
     * Takes a task out of picture-in-picture so it fills its display again, the way the
     * system's own picture-in-picture controller expands it. False if it wasn't in it.
     */
    fun fillDisplay(taskId: Int): Boolean {
        val info = (call(activityTasks, "getAllRootTaskInfos") as List<*>)
            .firstOrNull { it != null && field(it, "taskId") == taskId } ?: return false
        val config = field(info, "configuration") as? android.content.res.Configuration ?: return false
        val wc = config.javaClass.getField("windowConfiguration").get(config) ?: return false
        if (wc.javaClass.getMethod("getWindowingMode").invoke(wc) != WINDOWING_MODE_PINNED) return false
        val token = field(info, "token") ?: return false
        val tokenType = Class.forName("android.window.WindowContainerToken")
        val wctType = Class.forName("android.window.WindowContainerTransaction")
        val wct = wctType.getConstructor().newInstance()
        wctType.getMethod("setActivityWindowingMode", tokenType, Int::class.java).invoke(wct, token, WINDOWING_MODE_UNDEFINED)
        wctType.getMethod("setWindowingMode", tokenType, Int::class.java).invoke(wct, token, WINDOWING_MODE_FULLSCREEN)
        // Empty bounds: as big as the display.
        wctType.getMethod("setBounds", tokenType, Rect::class.java).invoke(wct, token, Rect())
        val organizer = call(activityTasks, "getWindowOrganizerController") ?: return false
        call(organizer, "applyTransaction", wct)
        return true
    }

    private const val WINDOWING_MODE_UNDEFINED = 0
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_PINNED = 2

    // ---- Displays ----------------------------------------------------------

    data class DisplayRow(
        val id: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val layerStack: Int,
        val flags: Int,
        val type: Int,
        val owner: String?,
    )

    fun displayIds(): IntArray = runCatching { call(displays, "getDisplayIds") as IntArray }
        .getOrElse { call(displays, "getDisplayIds", false) as IntArray }

    fun displayInfo(id: Int): DisplayRow? {
        val info = call(displays, "getDisplayInfo", id) ?: return null
        fun int(n: String) = field(info, n) as? Int ?: 0
        return DisplayRow(
            id = id,
            name = field(info, "name") as? String ?: "",
            width = int("logicalWidth"),
            height = int("logicalHeight"),
            dpi = int("logicalDensityDpi"),
            layerStack = int("layerStack"),
            flags = int("flags"),
            type = int("type"),
            owner = field(info, "ownerPackageName") as? String,
        )
    }

    /** Keyboard shows on the display itself (0), on the main screen (1), or not at all (2). */
    fun setImePolicy(displayId: Int, policy: Int) {
        runCatching { call(window, "setDisplayImePolicy", displayId, policy) }
    }

    // ---- Mirroring -----------------------------------------------------------

    /**
     * Where [d]'s picture really is. The car's cluster channels ("shared_fission_bg_…_0"
     * and "_1") draw nothing on their own layer stack: their windows are shown on the
     * cluster display they belong to ("fission_bg_…"), so a copy has to come from that
     * one. Any other display is its own picture.
     */
    fun pictureOf(d: DisplayRow): DisplayRow {
        if (!d.name.startsWith("shared_")) return d
        val base = d.name.removePrefix("shared_").substringBeforeLast('_')
        val home = displayIds().asSequence().mapNotNull(::displayInfo).firstOrNull { it.name == base } ?: return d
        return d.copy(layerStack = home.layerStack)
    }

    /** A live copy of another display; [close] ends it. */
    fun interface Mirror { fun close() }

    /**
     * Copies [source]'s picture into [surface]. Android 13 (the car) does it with a
     * SurfaceFlinger display on the source's layer stack; Android 14 took that call
     * away, so newer systems ask DisplayManager for a mirror display instead.
     */
    fun mirror(name: String, source: DisplayRow, surface: Surface, width: Int, height: Int): Mirror {
        val legacy = runCatching { sc("createDisplay", String::class.java, Boolean::class.javaPrimitiveType!!) }.isSuccess
        if (legacy) {
            val token = mirrorLayerStack(name, source.layerStack, Rect(0, 0, source.width, source.height), surface, width, height)
            return Mirror { destroyMirror(token) }
        }
        val int = Int::class.javaPrimitiveType!!
        val vd = DisplayManager::class.java
            .getMethod("createVirtualDisplay", String::class.java, int, int, int, Surface::class.java)
            .invoke(null, name, width, height, source.id, surface) as? VirtualDisplay
            ?: error("couldn't mirror display ${source.id}")
        return Mirror { vd.release() }
    }

    /**
     * One picture of [rect] on [source], scaled to [width] × [height]: ARGB pixels,
     * row by row, or an empty array when no frame came. Android 13 draws just that
     * part into a small SurfaceFlinger display; newer systems mirror the whole
     * display and cut the part out.
     */
    fun capture(source: DisplayRow, rect: Rect, width: Int, height: Int): IntArray {
        val legacy = runCatching { sc("createDisplay", String::class.java, Boolean::class.javaPrimitiveType!!) }.isSuccess
        val w = if (legacy) width else source.width
        val h = if (legacy) height else source.height
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val mirror = try {
            if (legacy) mirrorLayerStack("otility_peek", source.layerStack, rect, reader.surface, w, h).let { t -> Mirror { destroyMirror(t) } }
            else mirror("otility_peek", source, reader.surface, w, h)
        } catch (t: Throwable) {
            reader.close()
            throw t
        }
        try {
            val until = SystemClock.uptimeMillis() + 1500
            var image = reader.acquireLatestImage()
            while (image == null && SystemClock.uptimeMillis() < until) {
                Thread.sleep(25)
                image = reader.acquireLatestImage()
            }
            image ?: return IntArray(0)
            image.use {
                val plane = it.planes[0]
                val buf = plane.buffer
                val row = plane.rowStride
                val step = plane.pixelStride
                fun at(x: Int, y: Int): Int {
                    val i = y * row + x * step
                    val r = buf.get(i).toInt() and 0xFF
                    val g = buf.get(i + 1).toInt() and 0xFF
                    val b = buf.get(i + 2).toInt() and 0xFF
                    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                return if (legacy) {
                    IntArray(width * height) { k -> at(k % width, k / width) }
                } else {
                    // The whole display came: take [rect] out of it, nearest pixel.
                    IntArray(width * height) { k ->
                        val x = (rect.left + (k % width + 0.5f) * rect.width() / width).toInt().coerceIn(0, w - 1)
                        val y = (rect.top + (k / width + 0.5f) * rect.height() / height).toInt().coerceIn(0, h - 1)
                        at(x, y)
                    }
                }
            }
        } finally {
            mirror.close()
            reader.close()
        }
    }

    private val surfaceControl by lazy { Class.forName("android.view.SurfaceControl") }

    private fun sc(name: String, vararg types: Class<*>): Method =
        surfaceControl.getMethod(name, *types).also { it.isAccessible = true }

    /**
     * Mirrors the given layer stack (a display's contents as SurfaceFlinger sees
     * it) into [surface]. Returns the display token to destroy later.
     */
    private fun mirrorLayerStack(name: String, layerStack: Int, source: Rect, surface: Surface, width: Int, height: Int): IBinder {
        val token = sc("createDisplay", String::class.java, Boolean::class.javaPrimitiveType!!)
            .invoke(null, name, false) as IBinder
        sc("openTransaction").invoke(null)
        try {
            sc("setDisplaySurface", IBinder::class.java, Surface::class.java).invoke(null, token, surface)
            sc("setDisplayProjection", IBinder::class.java, Int::class.javaPrimitiveType!!, Rect::class.java, Rect::class.java)
                .invoke(null, token, 0, source, Rect(0, 0, width, height))
            sc("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType!!).invoke(null, token, layerStack)
        } finally {
            sc("closeTransaction").invoke(null)
        }
        return token
    }

    private fun destroyMirror(token: IBinder) {
        runCatching { sc("destroyDisplay", IBinder::class.java).invoke(null, token) }
    }

    // ---- Input -------------------------------------------------------------

    private val inputManager: Any by lazy {
        // Android 14 moved the injecting half of InputManager into InputManagerGlobal.
        runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
    }
    private val injectMethod: Method by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayIdMethod: Method by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private val setFlagsMethod: Method? by lazy {
        runCatching { MotionEvent::class.java.getMethod("setFlags", Int::class.javaPrimitiveType) }.getOrNull()
    }

    /**
     * A touch on Otility is marked "obscured" when anything is drawn over Otility's
     * window. The app it's passed on to isn't covered, and some screens (permission
     * and consent dialogs) ignore obscured touches, so the marks are taken off.
     */
    fun unobscure(event: MotionEvent) {
        val obscured = 0x1 /* FLAG_WINDOW_IS_OBSCURED */ or 0x2 /* FLAG_WINDOW_IS_PARTIALLY_OBSCURED */
        if (event.flags and obscured == 0) return
        runCatching { setFlagsMethod?.invoke(event, event.flags and obscured.inv()) }
    }

    fun inject(event: InputEvent, displayId: Int): Boolean {
        setDisplayIdMethod.invoke(event, displayId)
        return injectMethod.invoke(inputManager, event, 0 /* async */) as Boolean
    }
}
