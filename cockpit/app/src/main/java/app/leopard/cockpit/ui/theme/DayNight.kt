package app.leopard.cockpit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Automatic appearance: light from sunrise to sunset, dark after. The car's own
 * Android is set to dark for good (ui_night_mode 2), so following it would never
 * turn light; the sun is worked out for Doha instead, where the car is.
 */
object DayNight {
    private const val LAT = 25.29
    private const val LON = 51.53

    /** Dark at [now]: before sunrise or after sunset. */
    fun isDark(now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        val (rise, set) = sunHours(now) ?: return now.hour !in 6..17
        val h = now.hour + now.minute / 60.0
        return h < rise || h >= set
    }

    /**
     * Sunrise and sunset that day, in local hours (NOAA's simple formula, good to a few
     * minutes). Null where the sun doesn't rise or set.
     */
    fun sunHours(day: ZonedDateTime): Pair<Double, Double>? {
        val n = day.dayOfYear
        val gamma = 2 * Math.PI / 365.0 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val lat = Math.toRadians(LAT)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (cosHa < -1 || cosHa > 1) return null
        val ha = Math.toDegrees(acos(cosHa))
        val tz = day.offset.totalSeconds / 3600.0
        val noon = (720 - 4 * LON - eqTime) / 60.0 + tz
        return (noon - ha * 4 / 60.0) to (noon + ha * 4 / 60.0)
    }
}

/** [DayNight.isDark], looked at again every minute. */
@Composable
fun rememberSunDark(): Boolean {
    var dark by remember { mutableStateOf(DayNight.isDark()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            dark = DayNight.isDark()
        }
    }
    return dark
}
