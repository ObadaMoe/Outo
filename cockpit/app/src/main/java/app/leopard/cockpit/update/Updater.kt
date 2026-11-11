package app.leopard.cockpit.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import app.leopard.cockpit.CrashLog
import app.leopard.cockpit.car.CarBridge
import app.leopard.cockpit.car.HelperLauncher
import app.leopard.cockpit.data.Prefs
import app.leopard.cockpit.data.VehicleFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A version of Outo published on GitHub, with its app file. */
data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Updates Outo from its releases on GitHub, without a computer: looks for a newer
 * version (by itself when "Check for updates automatically" is on, or when asked),
 * and installs one when the driver taps Update while parked. The app file is
 * downloaded here, checked (Outo, newer, signed with the same key) and handed to the
 * helper, which installs it as the car's shell may and opens Outo again; Android
 * closes Outo for the update meanwhile.
 */
class Updater(
    private val context: Context,
    private val prefs: Prefs,
    private val bridge: CarBridge,
    private val vehicle: VehicleFeed,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.survive)
    private val store = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** This Outo's version, as GitHub's tags name them ("0.5.0"). */
    val current: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "0"

    /** Looks for updates by itself while the setting is on: soon after starting, then every few hours. */
    fun start() {
        // The passenger screen's copy of Outo (another Android user) leaves it to the driver's.
        if (!HelperLauncher.mainUser) return
        scope.launch {
            prefs.settings.map { it.autoUpdate }.distinctUntilChanged().collectLatest { on ->
                if (!on) return@collectLatest
                delay(FIRST_CHECK_MS)
                while (true) {
                    val since = System.currentTimeMillis() - store.getLong(CHECKED_AT, 0L)
                    if (since >= RECHECK_MS) check(manual = false)
                    delay(LOOK_EVERY_MS)
                }
            }
        }
    }

    /** "Check Now", or the timer. A check the driver didn't ask for fails quietly. */
    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    private fun check(manual: Boolean) {
        val before = _state.value
        if (before is UpdateState.Checking || before is UpdateState.Downloading || before is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        runCatching { fetchLatest() }
            .onSuccess { r ->
                store.edit().putLong(CHECKED_AT, System.currentTimeMillis()).apply()
                _state.value = if (r != null && isNewer(r.version, current)) UpdateState.Available(r)
                else UpdateState.UpToDate(System.currentTimeMillis())
                if (r != null) Log.i(TAG, "latest release ${r.version} (this is $current)")
            }
            .onFailure { e ->
                Log.w(TAG, "update check", e)
                _state.value = if (manual) UpdateState.Failed("Couldn't reach GitHub. Check the car's internet connection.")
                else before
            }
    }

    /** The newest published release with an app file, or null if there's none yet. */
    private fun fetchLatest(): Release? {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Outo")
            when (c.responseCode) {
                200 -> Unit
                404 -> return null // nothing published yet
                else -> throw IOException("GitHub answered ${c.responseCode}")
            }
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (!a.optString("name").endsWith(".apk", ignoreCase = true)) continue
                return Release(
                    version = o.optString("tag_name").trimStart('v', 'V'),
                    notes = o.optString("body").trim(),
                    apkUrl = a.getString("browser_download_url"),
                    size = a.optLong("size", -1L),
                )
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** The car is moving: an update waits (it restarts Outo, and with it the map on the cluster for a moment). */
    val moving: Boolean get() = (vehicle.data.value.speedKmh ?: 0) > 0

    /** Downloads the release on offer and installs it. */
    fun install() {
        val r = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        } ?: return
        scope.launch {
            val failed = runCatching { installNow(r) }.exceptionOrNull()
            if (failed != null) {
                Log.w(TAG, "update to ${r.version}", failed)
                _state.value = UpdateState.Failed(failed.message ?: "The update didn't finish.", r)
            }
        }
    }

    private fun installNow(r: Release) {
        if (moving) throw IOException("Outo updates while the car is parked.")
        val helper = bridge.helper ?: throw IOException("Connect to the car first (Settings › Car Connection).")
        val helperVersion = runCatching { helper.version() }.getOrDefault(0)
        if (helperVersion < HELPER_INSTALLS) throw IOException("The helper is starting again. Try again in a moment.")
        _state.value = UpdateState.Downloading(r, 0f)
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Outo-${r.version}.apk")
        try {
            download(r, file)
            verify(file)
            _state.value = UpdateState.Installing(r)
            Log.i(TAG, "installing ${r.version}")
            val out = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { helper.installUpdate(it) }
            // Done, Android closes Outo here and the helper opens the new one. Still here:
            // it didn't install.
            if (out != "ok") throw IOException(out?.removePrefix("error: ")?.replaceFirstChar { it.uppercase() } ?: "The helper couldn't install it.")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun download(r: Release, file: File) {
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", "Outo")
            if (c.responseCode != 200) throw IOException("The download failed (GitHub answered ${c.responseCode}).")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            var got = 0L
            var shown = -1
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            if (pct != shown) {
                                shown = pct
                                _state.value = UpdateState.Downloading(r, (got.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (r.size > 0 && got != r.size) throw IOException("The download was cut short. Try again.")
        } finally {
            c.disconnect()
        }
    }

    /** Outo, newer than this one, and signed with the same key (Android insists on that too). */
    @Suppress("DEPRECATION")
    private fun verify(file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: throw IOException("The download isn't a whole app. Try again.")
        if (archive.packageName != context.packageName) throw IOException("The download isn't Outo.")
        val mine = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCode <= mine.longVersionCode) throw IOException("The download isn't newer than this Outo.")
        val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val ours = mine.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (theirs != null && ours != null && theirs != ours) throw IOException("The download is signed by someone else, so it wasn't installed.")
    }

    companion object {
        private const val TAG = "Otility"
        /** Where Outo's releases are published. */
        const val REPO = "ObadaMoe/Outo"
        private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
        /** The first helper that can install updates. */
        private const val HELPER_INSTALLS = 8
        private const val CHECKED_AT = "checkedAt"
        private const val TIMEOUT_MS = 20_000
        /** Not during start-up, when the car and Outo are busy. */
        private const val FIRST_CHECK_MS = 30_000L
        /** Each start of the car checks, unless the last check was this recent. */
        private const val RECHECK_MS = 60 * 60_000L
        /** While Outo stays open (a long drive), how often it looks whether a check is due. */
        private const val LOOK_EVERY_MS = 6 * 60 * 60_000L

        /** [a] is a later version than [b] ("0.5.1" > "0.5.0", "v0.10" > "0.9"). */
        fun isNewer(a: String, b: String): Boolean {
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrElse(i) { 0 }
                val q = y.getOrElse(i) { 0 }
                if (p != q) return p > q
            }
            return false
        }

        private fun parts(v: String): List<Int> =
            v.trim().trimStart('v', 'V').substringBefore('-').substringBefore('+').split('.')
                .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
