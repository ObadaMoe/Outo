package app.leopard.cockpit.nav

import java.io.ByteArrayOutputStream

/**
 * The windshield display's turn frame: BYD's HudRoadInfo_EG message (proto3),
 * written by hand. Proto3 leaves out zero and empty values, so a field that
 * isn't known is simply not sent.
 */
object HudFrame {
    const val GUIDING = 2
    const val STOPPED = 1

    data class Fields(
        /** 3: metres left on the route. */
        val remainingMetres: Int = 0,
        /** 4: seconds left. */
        val remainingSeconds: Int = 0,
        /** 8: the turn arrow as a PNG. */
        val icon: ByteArray? = null,
        /** 9: metres to the turn. */
        val metres: Int = 0,
        /** 10: the street the turn leads onto. */
        val road: String? = null,
        /** 16: 2 while guiding, 1 when stopped. */
        val status: Int = GUIDING,
        /** 26: the arrival time, "HH:mm". */
        val eta: String? = null,
        /** 28: the turn code ([code]). */
        val code: Int = 0,
        /** 11: the road's speed limit in km/h, as BYD's own map sends it. */
        val speedLimit: Int = 0,
        /** 19 and 20: where the car is, in degrees (see [HudRoute]). */
        val longitude: Double = 0.0,
        val latitude: Double = 0.0,
        /** 32: which way the car is heading, in degrees from north. */
        val heading: Double = 0.0,
        /** 30: the road ahead, through the turn, as JSON: `[[longitude,latitude,0],…]`. */
        val guideLine: String? = null,
        /** 31: where the turn is, `"longitude,latitude,0"`. */
        val guidePoint: String? = null,
    ) {
        fun withoutIcon() = copy(icon = null)
    }

    /** The frame sent once when guidance ends, so the HUD clears. */
    val stopped: ByteArray get() = encode(Fields(status = STOPPED))

    fun encode(f: Fields): ByteArray {
        val inner = Writer().apply {
            uint(3, f.remainingMetres)
            uint(4, f.remainingSeconds)
            bytes(8, f.icon)
            uint(9, f.metres)
            string(10, f.road?.let(::cap))
            uint(11, f.speedLimit)
            uint(16, f.status)
            double(19, f.longitude)
            double(20, f.latitude)
            string(26, f.eta)
            uint(28, f.code)
            string(30, f.guideLine)
            string(31, f.guidePoint)
            double(32, f.heading)
        }.toByteArray()
        return Writer().apply { bytes(1, inner, keepEmpty = true) }.toByteArray()
    }

    /** The HUD's turn codes, as BYD's own map sends them. */
    fun code(m: Maneuver): Int = when (m) {
        Maneuver.LEFT, Maneuver.SHARP_LEFT -> 1
        Maneuver.RIGHT, Maneuver.SHARP_RIGHT -> 2
        Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT -> 3
        Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> 5
        // A U-turn to the left, as in right-hand traffic (Qatar), and the other way.
        Maneuver.U_TURN -> 9
        Maneuver.U_TURN_RIGHT -> 10
        // BYD shows roundabouts and the destination with the straight-on code; the picture tells them apart.
        Maneuver.STRAIGHT, Maneuver.ARRIVE, Maneuver.WAYPOINT, Maneuver.TOLL, Maneuver.FERRY,
        Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_RIGHT, Maneuver.ROUNDABOUT_STRAIGHT, Maneuver.ROUNDABOUT_LEFT,
        Maneuver.ROUNDABOUT_BACK, Maneuver.ROUNDABOUT_EXIT -> 11
        Maneuver.UNKNOWN -> 0
    }

    /** At most 200 characters, without cutting a character in half. */
    private fun cap(s: String): String {
        if (s.length <= 200) return s
        val end = if (Character.isHighSurrogate(s[199])) 199 else 200
        return s.substring(0, end)
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        private fun varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        private fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

        fun uint(field: Int, v: Int) {
            if (v <= 0) return
            key(field, 0)
            varint(v.toLong())
        }

        fun bytes(field: Int, b: ByteArray?, keepEmpty: Boolean = false) {
            if (b == null || (b.isEmpty() && !keepEmpty)) return
            key(field, 2)
            varint(b.size.toLong())
            out.write(b)
        }

        /** A 64-bit double, little-endian. Like every proto3 value, left out when it's zero. */
        fun double(field: Int, v: Double) {
            if (v == 0.0) return
            key(field, 1)
            val bits = java.lang.Double.doubleToRawLongBits(v)
            for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt())
        }

        fun string(field: Int, s: String?) = bytes(field, s?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
