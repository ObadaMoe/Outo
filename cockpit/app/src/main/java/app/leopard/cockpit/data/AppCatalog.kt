package app.leopard.cockpit.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.leopard.cockpit.screens.AppId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppInfo(
    val id: AppId,
    val label: String,
    val component: ComponentName,
    /** Full-bleed square icon; the UI clips it to the app-icon shape. */
    val icon: ImageBitmap,
    /** The icon's main color, for tinted backgrounds. */
    val tint: Int,
)

/** Every app the car can launch, with icons ready to draw. */
class AppCatalog(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private var byId: Map<AppId, AppInfo> = emptyMap()

    operator fun get(id: AppId): AppInfo? = byId[id]

    init {
        refresh()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }, filter)
    }

    fun refresh() {
        scope.launch {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .asSequence()
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { ri ->
                    val ai = ri.activityInfo
                    val bmp = iconBitmap(ri.loadIcon(pm))
                    AppInfo(
                        id = AppId(ai.packageName),
                        label = ri.loadLabel(pm).toString(),
                        component = ComponentName(ai.packageName, ai.name),
                        icon = bmp.asImageBitmap(),
                        tint = dominant(bmp),
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
            byId = list.associateBy { it.id }
            _apps.value = list
        }
    }

    /**
     * Draws the icon as a full square. Adaptive icons are drawn without the
     * system mask (we apply our own rounded shape). An icon that's already on its
     * own plate (the car's themed icons are) fills the square; a bare logo sits on
     * a white tile like on iOS.
     */
    private fun iconBitmap(d: Drawable, size: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            // Adaptive layers are 108dp with a 72dp visible center: draw at 1.5x, centered.
            val inset = size / 4
            d.background?.apply { setBounds(-inset, -inset, size + inset, size + inset); draw(c) }
            d.foreground?.apply { setBounds(-inset, -inset, size + inset, size + inset); draw(c) }
            return bmp
        }
        val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(raw))
        val px = IntArray(size * size)
        raw.getPixels(px, 0, size, 0, 0, size, size)
        val plate = IconPlate.find(px, size)
        if (plate != null) {
            // Its own plate: the plate's colour behind (for its rounder corners), then
            // the plate stretched to the edges, a hair cropped to lose its soft rim.
            c.drawColor(IconPlate.edgeColor(px, size, plate))
            val trim = (plate[2] - plate[0]) / 40
            val src = Rect(plate[0] + trim, plate[1] + trim, plate[2] - trim, plate[3] - trim)
            c.drawBitmap(raw, src, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawColor(Color.WHITE)
            val pad = (size * 0.14f).toInt()
            d.setBounds(pad, pad, size - pad, size - pad)
            d.draw(c)
        }
        raw.recycle()
        return bmp
    }

    /** A quick average of the icon's saturated pixels. */
    private fun dominant(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 12, 12, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        val hsv = FloatArray(3)
        for (y in 0 until 12) for (x in 0 until 12) {
            val p = small.getPixel(x, y)
            Color.colorToHSV(p, hsv)
            if (Color.alpha(p) < 200 || hsv[1] < 0.25f || hsv[2] < 0.2f) continue
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        if (n == 0L) return Color.rgb(60, 64, 72)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
