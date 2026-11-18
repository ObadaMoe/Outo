package app.leopard.cockpit.nav

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import app.leopard.cockpit.car.HelperConnection
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Takes Waze's picture through the helper and reads its turn card with on-device
 * text recognition (bundled, so it needs no Google services). Runs on the map
 * reader's own thread, at most every [EVERY] ms; between reads the last turn stands.
 */
internal class WazePictureReader {

    private companion object {
        const val TAG = "OtilityScreen"
        const val EVERY = 1200L
        /** The trip line changes slowly. */
        const val TRIP_EVERY = 10_000L
        const val ARROW_EVERY = 3000L
        /** The helper's limits for one picture. */
        const val MAX_SIDE = 720
        const val MAX_PIXELS = 110_000
        const val ARROW_PX = 72
        const val OCR_TIMEOUT_S = 3L
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var at = 0L
    private var last: NextTurn? = null
    private var tripAt = 0L
    private var trip: Pair<Int?, Int?> = null to null
    private var arrow = Maneuver.UNKNOWN
    private var arrowStreet: String? = null
    private var arrowAt = 0L
    private var failures = 0

    fun read(pkg: String, roots: List<Pair<AccessibilityNodeInfo, Int>>): NextTurn? {
        if (pkg !in MapScreens.WAZE) return null
        val now = SystemClock.uptimeMillis()
        if (now - at < EVERY) return last
        at = now
        last = readNow(pkg, roots, now)
        return last
    }

    private fun readNow(pkg: String, roots: List<Pair<AccessibilityNodeInfo, Int>>, now: Long): NextTurn? {
        val helper = HelperConnection.current ?: return null
        // Waze's biggest window is its map.
        val (bounds, display) = roots.map { (root, d) -> Rect().also(root::getBoundsInScreen) to d }
            .maxByOrNull { (r, _) -> r.width() * r.height() } ?: return null
        if (bounds.width() < 300 || bounds.height() < 200) return null

        // The turn card: the top left of the map.
        val area = Rect(bounds.left, bounds.top, bounds.left + bounds.width() * 6 / 10, bounds.top + bounds.height() * 36 / 100)
        val shot = capture(helper, display, area) ?: return null
        val lines = words(shot) ?: return null
        val card = WazePicture.card(lines, shot.w) ?: return null
        val street = card.street?.text?.trim()

        if (street != arrowStreet || now - arrowAt > ARROW_EVERY) {
            arrowStreet = street
            arrowAt = now
            val was = arrow
            arrow = arrowOf(shot, card)
            if (arrow != was) Log.i(TAG, "Waze's arrow (picture): $arrow")
        }
        if (now - tripAt > TRIP_EVERY) {
            tripAt = now
            val bottom = Rect(bounds.left, bounds.bottom - bounds.height() * 15 / 100, bounds.right, bounds.bottom)
            capture(helper, display, bottom)?.let(::words)?.let { trip = WazePicture.trip(it) }
        }
        return MapScreens.fromPicture(pkg, card.distance.text.trim(), street, arrow, trip.first, trip.second)
    }

    private class Shot(val px: IntArray, val w: Int, val h: Int)

    private fun capture(helper: app.leopard.cockpit.helper.IOtilityHelper, display: Int, r: Rect): Shot? {
        val scale = minOf(1f, MAX_SIDE.toFloat() / r.width(), MAX_SIDE.toFloat() / r.height(), sqrt(MAX_PIXELS.toFloat() / (r.width() * r.height())))
        val w = max(8, (r.width() * scale).toInt())
        val h = max(8, (r.height() * scale).toInt())
        val px = runCatching { helper.capture(display, r.left, r.top, r.right, r.bottom, w, h) }
            .onFailure { if (failures++ < 3) Log.w(TAG, "Waze picture", it) }.getOrNull()
        if (px == null || px.size < w * h) return null
        return Shot(px, w, h)
    }

    private fun words(shot: Shot): List<WazePicture.Line>? {
        val bitmap = Bitmap.createBitmap(shot.px, shot.w, shot.h, Bitmap.Config.ARGB_8888)
        return try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), OCR_TIMEOUT_S, TimeUnit.SECONDS)
            text.textBlocks.flatMap { b -> b.lines }.mapNotNull { l ->
                val box = l.boundingBox ?: return@mapNotNull null
                WazePicture.Line(l.text, box.left, box.top, box.right, box.bottom)
            }
        } catch (t: Throwable) {
            if (failures++ < 3) Log.w(TAG, "Waze words", t)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** The arrow sits left of the distance and street, on the card's dark square. */
    private fun arrowOf(shot: Shot, card: WazePicture.Card): Maneuver {
        val d = card.distance
        val h = d.height.coerceAtLeast(8)
        val right = d.left - h / 6
        val left = max(0, right - (h * 2.6f).roundToInt())
        val top = max(0, d.top - (h * 0.4f).roundToInt())
        val bottom = min(shot.h, (card.street?.bottom ?: (d.bottom + h)) + h / 4)
        val w = right - left
        val ah = bottom - top
        if (w < 12 || ah < 12) return Maneuver.UNKNOWN
        val scale = minOf(1f, ARROW_PX.toFloat() / max(w, ah))
        val pw = max(8, (w * scale).roundToInt())
        val ph = max(8, (ah * scale).roundToInt())
        val out = IntArray(pw * ph)
        for (y in 0 until ph) {
            val sy = top + min(ah - 1, (y / scale).toInt())
            for (x in 0 until pw) {
                val sx = left + min(w - 1, (x / scale).toInt())
                out[y * pw + x] = shot.px[sy * shot.w + sx]
            }
        }
        return ArrowReader.read(out, pw, ph)
    }
}
