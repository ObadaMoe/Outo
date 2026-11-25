package app.leopard.cockpit

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.leopard.cockpit.car.HelperLauncher
import app.leopard.cockpit.data.UiMode
import app.leopard.cockpit.ui.OtilityApp
import app.leopard.cockpit.ui.carplay.AppSwitcher
import app.leopard.cockpit.ui.SidebarReveal
import app.leopard.cockpit.ui.carplay.TwoFingerSwipe
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Otility lives on the car's main screen. If Android opened it inside one of
        // the hidden displays it hosts apps in, start it again on the main screen.
        if (displayId() != Display.DEFAULT_DISPLAY) {
            Log.w("Otility", "opened on display ${displayId()}; moving to the main screen")
            finish()
            start(applicationContext, newTask = true)
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as Otility
        app.startServices()
        // BYD's bars, hidden from the start (and again whenever the setting changes).
        applyBars()
        lifecycleScope.launch {
            app.prefs.settings.map { it.hideTopBar to it.hideBottomBar }.distinctUntilChanged().collect { applyBars() }
        }
        setContent { OtilityApp(app) }
    }

    /**
     * BYD's status bar and button bar, hidden while Outo is in front (Settings › Display),
     * from the moment it opens and for as long as it's there: never per app, so the apps'
     * screens keep one size. A swipe in from the top or bottom edge shows them for a moment,
     * over Outo. Not in a split of BYD's own, where the bars are the other app's too.
     */
    private fun applyBars() {
        val s = (application as Otility).prefs.settings.value
        val split = isInMultiWindowMode
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val top = WindowInsetsCompat.Type.statusBars()
        val bottom = WindowInsetsCompat.Type.navigationBars()
        if (s.hideTopBar && !split) bars.hide(top) else bars.show(top)
        if (s.hideBottomBar && !split) bars.hide(bottom) else bars.show(bottom)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Back in front (after a pop-up of the car's, another app): the car may have put them back.
        if (hasFocus && displayId() == Display.DEFAULT_DISPLAY) applyBars()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyBars()
    }

    /** CarPlay mode: two fingers up anywhere open the app switcher. */
    private val twoUp by lazy {
        TwoFingerSwipe(
            resources.displayMetrics.density, 0, -1,
            enabled = { (application as Otility).prefs.settings.value.let { it.mode == UiMode.CARPLAY && it.gestures } },
            onSwipe = AppSwitcher::show,
        )
    }

    /**
     * CarPlay mode, the sidebar slid away over an app: two fingers to the right bring it
     * back. The only way back, so it doesn't wait on the Gestures switch.
     */
    private val twoRight by lazy {
        TwoFingerSwipe(
            resources.displayMetrics.density, 1, 0,
            enabled = { SidebarReveal.hidden },
            onSwipe = { SidebarReveal.show?.invoke() },
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (twoUp.onTouch(ev) { super.dispatchTouchEvent(it) }) return true
        if (twoRight.onTouch(ev) { super.dispatchTouchEvent(it) }) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        // Every time Otility comes to the front, not only when it's first made: the
        // helper may have stopped while Otility was in the background.
        if (displayId() == Display.DEFAULT_DISPLAY) lifecycleScope.launch { HelperLauncher.ensure(this@MainActivity) }
    }

    private fun displayId(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: Display.DEFAULT_DISPLAY
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens Otility on the main screen. */
        fun start(context: Context, newTask: Boolean = false) {
            val intent = intent(context)
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
            context.startActivity(intent, options)
        }
    }
}
