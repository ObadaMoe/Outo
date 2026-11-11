package app.leopard.cockpit.helper

/**
 * Whether an app asked to hide the system bars (a video gone full screen, say), read
 * from Android's own account of its windows (`dumpsys window <package>`). On Outo's
 * hidden displays there are no bars to hide, so Android records the wish and nothing
 * more; Outo then hides BYD's bars itself.
 *
 * Android writes it down differently by version, so any of these counts, for a
 * visible window of the app on that display:
 * - Android 14 and later: "statusBars" or "navigationBars" among the window's requested
 *   types that aren't the default (the default has both bars showing);
 * - Android 13 (the car): "ITYPE_STATUS_BAR: invisible" or the navigation bar's;
 * - the older flags: FULLSCREEN or HIDE_NAVIGATION (not their LAYOUT_ forms) in the
 *   window's flags or its views' system UI flags.
 */
internal object WindowBars {

    fun hides(dump: String, pkg: String, displayId: Int): Boolean =
        blocks(dump).any { b ->
            b.header.contains("$pkg/") && b.displayId == displayId && !b.text.contains("isVisible=false") && hidesIn(b.text)
        }

    private class Block(val header: String, val text: String) {
        val displayId: Int? = Regex("""mDisplayId=(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Each window's part of the dump, from its "Window #n Window{…}:" line to the next. */
    private fun blocks(dump: String): List<Block> {
        val out = ArrayList<Block>()
        var header: String? = null
        val text = StringBuilder()
        for (line in dump.lineSequence()) {
            if (WINDOW.containsMatchIn(line)) {
                header?.let { out += Block(it, text.toString()) }
                header = line
                text.setLength(0)
            } else if (header != null) {
                text.append(line).append('\n')
            }
        }
        header?.let { out += Block(it, text.toString()) }
        return out
    }

    private fun hidesIn(text: String): Boolean {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            // Android 13's InsetsVisibilities.
            if (line.contains("ITYPE_STATUS_BAR: invisible") || line.contains("ITYPE_NAVIGATION_BAR: invisible")) return true
            // Android 14+: the requested types that differ from the default.
            if (line.startsWith("Requested") && line.contains("visib", ignoreCase = true) &&
                (line.contains("statusBars") || line.contains("navigationBars"))
            ) return true
            // The older flags, as words of the line (LAYOUT_FULLSCREEN and the like don't count).
            if (line.startsWith("fl=") || line.startsWith("sysui=") || line.startsWith("vsysui=") || line.contains(" sysui=") || line.contains(" vsysui=")) {
                val words = line.split(' ', '=').toSet()
                if ((line.startsWith("fl=") && "FULLSCREEN" in words) ||
                    (!line.startsWith("fl=") && ("FULLSCREEN" in words || "HIDE_NAVIGATION" in words))
                ) return true
            }
        }
        return false
    }

    private val WINDOW = Regex("""^\s*Window #\d+ Window\{""")
}
