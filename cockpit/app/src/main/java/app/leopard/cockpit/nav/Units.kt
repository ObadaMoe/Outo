package app.leopard.cockpit.nav

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Reads the distances and times navigation apps show ("350 m", "5.2 km",
 * "1 h 12 min"), in English or Arabic, with Western or Arabic-Indic digits.
 */
object Units {
    /** Lower case, plain spaces and Western digits, so one set of patterns reads both languages. */
    fun normalize(text: String): String {
        val b = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            b.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    ' ', ' ', ' ' -> ' '
                    '٫' -> '.'
                    else -> ch
                },
            )
        }
        // A comma between digits is a decimal point ("5,2 km").
        return DECIMAL_COMMA.replace(b.toString(), "$1.$2")
    }

    private val DECIMAL_COMMA = Regex("""(\d),(\d)""")
    private const val NUM = """(\d+(?:\.\d+)?)"""
    private val KM = Regex("""$NUM\s*(?:km(?![a-z])|kilomet(?:er|re)s?|كم|كيلومتر|كيلو)""")
    private val MI = Regex("""$NUM\s*(?:mi(?![a-z])|miles?)""")
    private val FT = Regex("""$NUM\s*(?:ft(?![a-z])|feet)""")
    private val M = Regex("""$NUM\s*(?:m(?![a-z])|met(?:er|re)s?|متر|م(?![؀-ۿ]))""")

    /** Metres in a distance, or null when there isn't one. */
    fun metres(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        // Not a clock time: "9:26 م" is 9:26 in the evening, not 26 metres.
        val t = CLOCK.replace(normalize(text), " ")
        KM.find(t)?.let { return (it.groupValues[1].toDouble() * 1000).roundToInt() }
        MI.find(t)?.let { return (it.groupValues[1].toDouble() * 1609.34).roundToInt() }
        FT.find(t)?.let { return (it.groupValues[1].toDouble() * 0.3048).roundToInt() }
        M.find(t)?.let { return it.groupValues[1].toDouble().roundToInt() }
        return null
    }

    private val HOURS = Regex("""(\d+)\s*(?:h(?![a-z])|hr|hrs|hour|hours|س(?![؀-ۿ])|ساعة|ساعات|ساعه)""")
    private val MINUTES = Regex("""(\d+)\s*(?:min(?![a-z])|mins|minute|minutes|د(?![؀-ۿ])|دقيقة|دقائق|دقيقه)""")
    /** "1:25 h": hours and minutes together. */
    private val HM = Regex("""(\d+):(\d{2})\s*(?:h(?![a-z])|hr|hrs|س(?![؀-ۿ])|ساعة)""")

    /** Seconds in a time left ("1 h 12 min", "45 min"), or null. A clock time ("8:42 PM") isn't one. */
    fun seconds(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val t = normalize(text)
        HM.find(t)?.let { return it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 }
        val h = HOURS.find(t)?.groupValues?.get(1)?.toInt() ?: 0
        val m = MINUTES.find(t)?.groupValues?.get(1)?.toInt() ?: 0
        return (h * 3600 + m * 60).takeIf { it > 0 }
    }

    /** "9:26 pm", "21:26", "9:26 م" (Arabic evening) or "9:26 ص" (morning). */
    private val CLOCK = Regex(
        // Not "1:25 h", which is a time left.
        """(?<![\d:])(\d{1,2}):(\d{2})(?![\d:])(?!\s*(?:h(?![a-z])|hrs?(?![a-z])|ساعة|س(?![؀-ۿ])))\s*(a\.?m\.?|p\.?m\.?|ص|م(?![؀-ۿ]))?""",
    )

    /** An arrival time as shown ("9:26 PM"), or null. */
    fun clock(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = CLOCK.find(normalize(text)) ?: return null
        val (h, min, ampm) = m.destructured
        if (h.toInt() > 23 || min.toInt() > 59) return null
        val suffix = when {
            ampm.startsWith("a") || ampm == "ص" -> " AM"
            ampm.startsWith("p") || ampm == "م" -> " PM"
            else -> ""
        }
        return "${h.toInt()}:$min$suffix"
    }

    /** The same time on a 24-hour clock ("21:26"), as the HUD wants it. */
    fun clock24(text: String?): String? {
        val c = clock(text) ?: return null
        val m = CLOCK.find(normalize(c)) ?: return null
        var h = m.groupValues[1].toInt()
        val ampm = m.groupValues[3]
        if (ampm.startsWith("p") && h < 12) h += 12
        if (ampm.startsWith("a") && h == 12) h = 0
        return "%02d:%s".format(Locale.US, h, m.groupValues[2])
    }

    // Numbers below are written with Western digits whatever the car's language:
    // in Arabic, format() would write "٢١:٢٦", which the HUD isn't known to read.

    /** "350 m", "1.2 km", "12 km". */
    fun distance(metres: Int): String = when {
        metres < 1000 -> "${(metres / 10) * 10} m"
        metres < 10_000 -> "${"%.1f".format(Locale.US, metres / 1000.0)} km"
        else -> "${(metres / 1000.0).roundToInt()} km"
    }

    /** "45 min", "1 h 12 min". */
    fun duration(seconds: Int): String {
        val minutes = (seconds + 30) / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    /** The arrival time, [seconds] from [now]: "14:35", or "2:35" in 12-hour mode. */
    fun arrival(seconds: Int, now: Long = System.currentTimeMillis(), is24h: Boolean = true): String {
        val c = Calendar.getInstance().apply { timeInMillis = now + seconds * 1000L }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val hour = if (is24h) h else (h % 12).let { if (it == 0) 12 else it }
        return if (is24h) "%02d:%02d".format(Locale.US, hour, m) else "%d:%02d".format(Locale.US, hour, m)
    }
}
