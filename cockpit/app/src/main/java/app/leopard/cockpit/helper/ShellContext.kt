package app.leopard.cockpit.helper

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.view.Surface

/**
 * The helper has no app around it, so it builds a minimal Context that says
 * "I'm the shell", which is what Android checks when the shell creates a display.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal class ShellContext private constructor(base: Context) : ContextWrapper(base) {

    override fun getPackageName() = SHELL
    override fun getOpPackageName() = SHELL
    override fun getApplicationContext(): Context = this

    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL).build()

    companion object {
        private const val SHELL = "com.android.shell"

        val instance: ShellContext by lazy { ShellContext(systemContext()) }

        private fun systemContext(): Context {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            return at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        }

        val displayManager: DisplayManager by lazy {
            DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(instance)
        }

        /**
         * Flags for a display hosted inside Otility: its own content only, touch
         * passes through, trusted (so the keyboard and system windows work there),
         * and kept awake on its own.
         */
        fun hostFlags(): Int {
            var f = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                (1 shl 6) /* SUPPORTS_TOUCH */ or
                (1 shl 7) /* ROTATES_WITH_CONTENT */
            if (Build.VERSION.SDK_INT >= 33) {
                f = f or (1 shl 10) /* TRUSTED */ or
                    (1 shl 11) /* OWN_DISPLAY_GROUP */ or
                    (1 shl 12) /* ALWAYS_UNLOCKED */ or
                    (1 shl 13) /* TOUCH_FEEDBACK_DISABLED */
            }
            if (Build.VERSION.SDK_INT >= 34) {
                f = f or (1 shl 14) /* OWN_FOCUS */
            }
            return f
        }

        /**
         * Tries the full set of flags first, then fewer, because some cars refuse
         * one flag or another. The last try is a plain public display.
         */
        /** [surface] may be null: the app runs and draws nothing until Otility shows it. */
        fun createDisplay(name: String, surface: Surface?, w: Int, h: Int, dpi: Int): VirtualDisplay? {
            val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val attempts = listOf(
                hostFlags(),
                base or (1 shl 10) or (1 shl 6), // trusted, touch
                base or (1 shl 6),
                base,
            ).distinct()
            for (flags in attempts) {
                val vd = runCatching { displayManager.createVirtualDisplay(name, w, h, dpi, surface, flags) }
                    .onFailure { android.util.Log.w(HelperMain.TAG, "display flags $flags refused: ${it.message}") }
                    .getOrNull()
                if (vd != null) return vd
            }
            return null
        }
    }
}
