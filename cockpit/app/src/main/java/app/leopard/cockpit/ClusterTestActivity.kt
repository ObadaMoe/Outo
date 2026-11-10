package app.leopard.cockpit

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A test card for the driver cluster's map channel.
 *
 * It's deliberately loud: a bright frame, a big live clock and a sweeping bar, so
 * from the driver's seat it's obvious whether our frames reach the cluster, and
 * whether they're live (moving) or frozen.
 */
class ClusterTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = display
        Log.i(TAG, "onCreate on display ${d?.displayId} \"${d?.name}\"")
        setContentView(TestCard(this))
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
    }

    private class TestCard(context: Context) : View(context) {
        private val start = SystemClock.uptimeMillis()
        private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 16f
            color = Color.rgb(0, 230, 118)
        }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 196, 0) }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(180, 200, 190)
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(Color.rgb(8, 24, 18))
            canvas.drawRect(RectF(8f, 8f, w - 8f, h - 8f), frame)

            big.textSize = h * 0.22f
            canvas.drawText(clock.format(Date()), w / 2, h * 0.52f, big)
            small.textSize = h * 0.07f
            canvas.drawText("COCKPIT  •  cluster test", w / 2, h * 0.30f, small)
            val d = display
            canvas.drawText(
                "display ${d?.displayId} \"${d?.name}\"  ${width}x${height}  ${resources.displayMetrics.densityDpi} dpi",
                w / 2, h * 0.68f, small,
            )

            // A bar sweeping left to right every 2 s: if it moves on the cluster, frames are live.
            val t = ((SystemClock.uptimeMillis() - start) % 2000L) / 2000f
            val barW = w * 0.12f
            val x = 24f + t * (w - 48f - barW)
            canvas.drawRect(x, h * 0.78f, x + barW, h * 0.86f, bar)

            // About 15 frames a second is plenty to see it move, at a quarter of the
            // work. Android draws nothing while the card isn't on a screen, which
            // also stops this loop until it is again.
            postInvalidateDelayed(66)
        }
    }

    companion object {
        const val TAG = "Cockpit"
    }
}
