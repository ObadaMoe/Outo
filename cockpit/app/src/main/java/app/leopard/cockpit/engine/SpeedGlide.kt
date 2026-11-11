package app.leopard.cockpit.engine

import kotlin.math.exp

/**
 * The car's speed for the engine sound, gliding from reading to reading.
 *
 * The speedometer's figure comes in whole km/h, and in a low gear 1 km/h moves the
 * engine's note by a semitone or more. Followed as it came, the revs jumped from note
 * to note and held a dead-steady drone in between: what rolling along slowly with the
 * foot off sounded like. A car's speed can't jump, so this one doesn't either: it eases
 * toward each reading, over about [WHOLE] seconds for whole km/h, or [FINE] for the
 * car's finer speed where it has one.
 */
internal class SpeedGlide {
    private var value = Float.NaN
    private var at = 0L

    /**
     * The speed to use at [now] (ms), easing toward [target] over about [seconds]. After
     * more than a second without a reading, what it was is out of date: [target] as it is.
     */
    fun next(target: Float, now: Long, seconds: Float): Float {
        val dt = now - at
        if (value.isNaN() || dt > 1_000) {
            value = target
        } else {
            value += (target - value) * (1f - exp(-dt.coerceAtLeast(0L) / 1000f / seconds))
        }
        at = now
        return value
    }

    /** Starts over: the next reading is taken as it is. */
    fun reset() {
        value = Float.NaN
    }

    companion object {
        const val WHOLE = 0.6f
        const val FINE = 0.2f
    }
}
