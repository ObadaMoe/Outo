package app.leopard.cockpit

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.leopard.cockpit.car.HelperLauncher
import app.leopard.cockpit.ui.OtilityApp
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
        setContent { OtilityApp(app) }
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
