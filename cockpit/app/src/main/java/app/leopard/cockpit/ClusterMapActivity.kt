package app.leopard.cockpit

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import app.leopard.cockpit.screens.ClusterMap
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waze or Google Maps on the driver cluster, map only.
 *
 * The app runs on a hidden display bigger than the cluster (see ClusterCrop), and
 * this window, on the cluster, shows only the part of it that is map: the app's
 * picture goes straight into a SurfaceView the size of that display, placed so the
 * rest falls outside the cluster. Nothing of Otility's own is drawn here.
 *
 * Started by the helper (the shell), like the other apps put on the cluster.
 */
class ClusterMapActivity : Activity() {

    private val scope = MainScope()
    private lateinit var root: FrameLayout
    private var shown: ClusterMap? = null
    private var view: SurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        // The cluster has no bars of its own; if it ever had, keep them off the map.
        // (Not before setContentView: the window has no decor until then.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        }
        val here = display?.displayId ?: -1
        Log.i(TAG, "cluster map window on display $here")
        val screens = (application as Otility).screens
        scope.launch {
            // After Otility restarts, Android can bring this window back before Otility
            // has found its map again: give it a moment.
            val first = withTimeoutOrNull(WAIT_MS) { screens.clusterMap.first { it != null && it.displayId == here } }
            if (first == null) {
                Log.i(TAG, "no map for the cluster; closing its window")
                finish()
                return@launch
            }
            screens.clusterMap.collect { map ->
                if (map == null || map.displayId != here) {
                    finish()
                    scope.cancel()
                } else {
                    show(map)
                }
            }
        }
    }

    private fun show(map: ClusterMap) {
        if (map == shown) return
        shown = map
        view?.let { root.removeView(it) } // its surface goes, and with it the app's picture
        val f = map.frame
        val hosts = (application as Otility).screens.hosts
        val sv = SurfaceView(this)
        sv.holder.setFormat(PixelFormat.RGBA_8888)
        sv.holder.setFixedSize(f.width, f.height)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // The main screen has it for now: it's taken back (a new view) once it's given back.
                if ((application as Otility).screens.clusterMapLent) return
                hosts.attach(map.app, map.component, holder.surface, f.width, f.height, f.dpi) { bw, bh ->
                    sv.post { if (view === sv) sv.holder.setFixedSize(bw, bh) }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                hosts.detach(map.app, holder.surface)
            }
        })
        // The whole display, moved up and left so the map part sits on the cluster.
        root.addView(sv, FrameLayout.LayoutParams(f.width, f.height, Gravity.TOP or Gravity.START).apply {
            leftMargin = -f.left
            topMargin = -f.top
        })
        view = sv
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Otility"
        const val WAIT_MS = 8_000L
    }
}
