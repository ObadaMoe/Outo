package app.leopard.cockpit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.leopard.cockpit.car.HelperLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts Otility when the car really boots (rare: switching the car off and on
 * only puts it to sleep and wakes it). Waking is the helper's: BYD force-stops
 * Otility at switch-off, and a force-stopped app hears nothing (see helper/Ignition).
 *
 * Android limits apps opening screens from the background, so the helper (which
 * the car trusts) opens Otility once it's up; the direct start below covers cars
 * where that limit doesn't apply.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The passenger screen's user (999) can have its own copy of Otility: only the
        // main user's runs the car.
        if (!HelperLauncher.mainUser) return
        val app = context.applicationContext as Otility
        if (!app.prefs.settings.value.startWithCar) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { MainActivity.start(context) }
                // The helper starts on HelperLauncher's own lane. The broadcast (which keeps
                // Otility's process up meanwhile) waits only as long as a normal start
                // takes: one that waits for the driver to allow debugging can take minutes,
                // and Android stops a start-up broadcast that isn't done within a minute.
                val work = HelperLauncher.request(context, open = true)
                withTimeoutOrNull(10_000) { work.join() }
            } finally {
                pending.finish()
            }
        }
    }
}
