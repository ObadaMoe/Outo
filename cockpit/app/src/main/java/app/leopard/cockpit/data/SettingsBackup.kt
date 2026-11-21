package app.leopard.cockpit.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Outo's settings, kept in a file that survives what Android's own backup can't reach
 * (Outo has backups off, and the car has no root): a car software update or reset.
 *
 * - Outo writes `/sdcard/Android/data/app.leopard.cockpit/files/backup/outo-settings.json`
 *   a moment after any setting changes: the sidebar, layouts, widgets, every switch,
 *   and the trip/pedal numbers. `tools/backup-car.sh` pulls it to the Mac.
 * - To bring them back, put a saved copy in Outo's folder as
 *   `/sdcard/Android/data/app.leopard.cockpit/files/outo-restore.json`
 *   (`tools/restore-car-apps.sh` does this). The next time Outo starts, it replaces its
 *   settings with that file, moves it to `backup/restored-<time>.json`, and says so.
 *   (Not in `backup/`: a folder made over ADB belongs to the shell, and Outo couldn't
 *   write its own backup there any more.) A file it already restored is never
 *   restored twice, even when it can't be moved.
 *
 * Only the stores that hold the user's choices are kept: not update checks, crash
 * notes or which apps happened to be open.
 */
object SettingsBackup {
    private const val TAG = "Otility"
    private const val DIR = "backup"
    private const val NAME = "outo-settings.json"
    private const val RESTORE = "outo-restore.json"
    private const val STATE = "settings_backup"
    private const val DONE = "restored"
    private const val FORMAT = 1
    private const val DELAY_MS = 3_000L

    /** The SharedPreferences files that are the user's own setup. */
    private val STORES = listOf("otility", "trips")

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var version = "?"
    // SharedPreferences keeps listeners weakly: these must stay referenced.
    private val listeners = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()
    private val save = Runnable { runCatching { write() }.onFailure { Log.w(TAG, "settings backup", it) } }

    /** When a restore just happened, the time it was saved (for a one-time notice). */
    @Volatile var restoredFrom: Long = 0L
        private set

    private fun base(): File = app.getExternalFilesDir(null) ?: app.filesDir
    private fun dir(): File = File(base(), DIR)

    /** Moves the restore file out of the way (or deletes it); false when neither worked. */
    private fun setAside(file: File, name: String): Boolean {
        dir().mkdirs()
        return file.renameTo(File(dir(), name)) || file.delete()
    }

    /**
     * Call first in [android.app.Application.onCreate], before anything reads the
     * settings: brings in a waiting `outo-restore.json`, then keeps the backup file current.
     */
    fun install(context: Context) {
        app = context.applicationContext
        version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        runCatching { restoreIfWaiting() }.onFailure {
            Log.w(TAG, "settings restore", it)
            // Set aside, so a bad file isn't tried again at every start.
            setAside(File(base(), RESTORE), "restore-failed-${System.currentTimeMillis()}.json")
            toast("Outo couldn't restore its settings from the backup")
        }
        if (restoredFrom > 0) toast("Outo's settings were restored from the backup")
        for (name in STORES) {
            val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> later() }
            sp.registerOnSharedPreferenceChangeListener(l)
            listeners += sp to l
        }
        later()
    }

    private fun toast(text: String) = main.post {
        runCatching { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun later() {
        main.removeCallbacks(save)
        main.postDelayed(save, DELAY_MS)
    }

    private fun write() {
        val stores = JSONObject()
        for (name in STORES) {
            val entries = JSONObject()
            for ((key, value) in app.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val typed = when (value) {
                    is String -> "s" to value
                    is Boolean -> "b" to value
                    is Int -> "i" to value
                    is Long -> "l" to value
                    is Float -> "f" to value.toDouble()
                    is Set<*> -> "ss" to JSONArray(value.filterIsInstance<String>().sorted())
                    else -> continue
                }
                entries.put(key, JSONObject().put("t", typed.first).put("v", typed.second))
            }
            stores.put(name, entries)
        }
        val json = JSONObject()
            .put("format", FORMAT)
            .put("saved", System.currentTimeMillis())
            .put("outo", version)
            .put("stores", stores)
        val d = dir().apply { mkdirs() }
        val tmp = File(d, "$NAME.tmp")
        tmp.writeText(json.toString(1))
        if (!tmp.renameTo(File(d, NAME))) error("couldn't replace $NAME")
    }

    private fun restoreIfWaiting() {
        val file = File(base(), RESTORE)
        if (!file.isFile) return
        val text = file.readText()
        val json = JSONObject(text)
        // Which file this is, kept outside the stores a restore replaces.
        val id = "${json.optLong("saved")}:${text.length}"
        val state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        if (state.getString(DONE, null) == id) {
            setAside(file, "restored-again-${System.currentTimeMillis()}.json")
            return
        }
        require(json.optInt("format") == FORMAT) { "outo-restore.json: unknown format ${json.opt("format")}" }
        val stores = json.getJSONObject("stores")
        for (name in STORES) {
            val entries = stores.optJSONObject(name) ?: continue
            val e = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (key in entries.keys()) {
                val item = entries.getJSONObject(key)
                when (item.getString("t")) {
                    "s" -> e.putString(key, item.getString("v"))
                    "b" -> e.putBoolean(key, item.getBoolean("v"))
                    "i" -> e.putInt(key, item.getInt("v"))
                    "l" -> e.putLong(key, item.getLong("v"))
                    "f" -> e.putFloat(key, item.getDouble("v").toFloat())
                    "ss" -> {
                        val arr = item.getJSONArray("v")
                        e.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                    }
                }
            }
            if (!e.commit()) error("couldn't save restored $name")
        }
        state.edit().putString(DONE, id).commit()
        val saved = json.optLong("saved")
        if (!setAside(file, "restored-${System.currentTimeMillis()}.json")) Log.w(TAG, "couldn't move $RESTORE aside")
        restoredFrom = saved.takeIf { it > 0 } ?: System.currentTimeMillis()
        Log.i(TAG, "settings restored from a backup saved at $saved by Outo ${json.optString("outo")}")
    }
}
