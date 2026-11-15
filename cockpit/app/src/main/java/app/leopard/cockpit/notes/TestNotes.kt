package app.leopard.cockpit.notes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Debug builds only: a pretend iPhone notification, from adb, for trying the banner and
 * Home's card without a phone:
 *
 *     adb shell am broadcast -a app.leopard.cockpit.TEST_NOTE -p app.leopard.cockpit \
 *         --es app net.whatsapp.WhatsApp --es name WhatsApp --es title "Sam" --es message "On my way"
 *
 * `--ei remove <uid>` takes one away; `--ez clear true` all of them.
 */
object TestNotes {
    private const val ACTION = "app.leopard.cockpit.TEST_NOTE"
    private var next = 1

    fun listen(context: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when {
                    i.getBooleanExtra("clear", false) -> PhoneNotes.clear()
                    i.hasExtra("remove") -> PhoneNotes.remove(i.getIntExtra("remove", 0))
                    else -> {
                        val appId = i.getStringExtra("app") ?: "com.apple.MobileSMS"
                        i.getStringExtra("name")?.let { PhoneNotes.setName(appId, it) }
                        PhoneNotes.put(
                            PhoneNote(
                                uid = i.getIntExtra("uid", next++),
                                appId = appId,
                                title = i.getStringExtra("title") ?: "Test",
                                subtitle = i.getStringExtra("subtitle").orEmpty(),
                                message = i.getStringExtra("message").orEmpty(),
                                category = i.getIntExtra("category", Ancs.SOCIAL),
                                canDismiss = true,
                            ),
                            loud = !i.getBooleanExtra("quiet", false),
                        )
                    }
                }
            }
        }
        // Exported, so adb (the shell) can send it; debug builds only.
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }
}
