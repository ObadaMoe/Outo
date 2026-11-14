package app.leopard.cockpit.nav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * Waze's speed limit as a small sign for the HUD: a red ring with the number in
 * white, inside a blue ring that says it's Waze's, not the camera's. A HUD shows
 * light only, so dark colours would be invisible: the inside stays clear.
 */
object LimitSign {
    /** Waze's blue. */
    private const val BLUE = 0xFF33CCFF.toInt()
    private const val RED = 0xFFFF3B30.toInt()

    private val pngs = HashMap<Pair<Int, Int>, ByteArray>()

    /** The sign for [kmh] as a PNG, [size] pixels square. */
    fun png(kmh: Int, size: Int): ByteArray = synchronized(pngs) {
        pngs.getOrPut(kmh to size) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val c = size / 2f
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            ring.color = BLUE
            ring.strokeWidth = size * 0.07f
            canvas.drawCircle(c, c, size * 0.465f, ring)
            ring.color = RED
            ring.strokeWidth = size * 0.09f
            canvas.drawCircle(c, c, size * 0.36f, ring)
            val text = kmh.toString()
            val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = size * if (text.length >= 3) 0.27f else 0.34f
            }
            val m = digits.fontMetrics
            canvas.drawText(text, c, c - (m.ascent + m.descent) / 2f, digits)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }
}
