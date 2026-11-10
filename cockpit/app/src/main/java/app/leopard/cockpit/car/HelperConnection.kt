package app.leopard.cockpit.car

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import app.leopard.cockpit.helper.IOtilityHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the helper's binder once the helper hands it over. */
object HelperConnection {
    private const val TAG = "Otility"

    private val _helper = MutableStateFlow<IOtilityHelper?>(null)
    val helper: StateFlow<IOtilityHelper?> = _helper.asStateFlow()

    val current: IOtilityHelper? get() = _helper.value

    internal fun receive(binder: IBinder) {
        val h = IOtilityHelper.Stub.asInterface(binder)
        try {
            binder.linkToDeath({
                Log.w(TAG, "helper stopped")
                if (_helper.compareAndSet(h, null)) HelperLauncher.lost()
            }, 0)
        } catch (_: Throwable) {
            return
        }
        Log.i(TAG, "helper connected, version ${runCatching { h.version() }.getOrNull()}")
        _helper.value = h
    }
}

/**
 * The helper calls this provider to hand over its binder. Only the shell user
 * (or root) may: the provider is guarded by a permission normal apps can't hold,
 * and the caller is checked again here.
 */
class HelperBinderProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) return null
        if (method == "binder") {
            extras?.getBinder("binder")?.let { HelperConnection.receive(it) }
        }
        return Bundle()
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
