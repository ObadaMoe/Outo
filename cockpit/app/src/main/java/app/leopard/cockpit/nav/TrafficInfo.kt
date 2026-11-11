package app.leopard.cockpit.nav

import java.io.ByteArrayOutputStream

/**
 * The car's TrafficInfo message (proto3), written by hand: what BYD's own map tells the
 * car's driver assistance about the road, once a second, over SOME/IP (service 0x0007,
 * event 0x8003). The road's limit is field 8 and its class field 19. Outo sends only
 * those, and BYD's "nothing known" values for the jam and the speed camera.
 *
 * In this car BYD's map sends it with no limit (0) and road class 255 (unknown): it
 * has no limits for the region (seen in the car's log, 9 Nov 2026).
 */
object TrafficInfo {
    /** BYD's road class for "unknown". */
    const val UNKNOWN_ROAD = 255

    /**
     * The message for a road limited to [kmh] (0: no limit known) of class [roadClass],
     * wrapped as the TrafficInfoNotify the event carries (field 1).
     */
    fun encode(kmh: Int, roadClass: Int = roadClass(kmh)): ByteArray {
        val inner = ByteArrayOutputStream().apply {
            // No jam ahead, as BYD's map sends it: the distances -1 (as a 32-bit unsigned).
            uint(1, -1)
            uint(2, -1)
            if (kmh > 0) uint(8, kmh)
            // No speed camera: its distance -1 (its limit, 0, is left out).
            uint(10, -1)
            // Proto3 leaves out a 0, as BYD's map does.
            if (roadClass != 0) uint(19, roadClass)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            key(1, 2)
            varint(inner.size.toLong())
            write(inner)
        }.toByteArray()
    }

    /**
     * BYD's road class (as its map turns Baidu's road grades into it) for a road with
     * this limit. Waze doesn't say what kind of road it is, so this is a guess from the
     * limit: 0 motorway, 6 city expressway, 1 main road, 2 secondary, 4 local.
     */
    fun roadClass(kmh: Int): Int = when {
        kmh <= 0 -> UNKNOWN_ROAD
        kmh >= 100 -> 0
        kmh >= 80 -> 6
        kmh >= 60 -> 1
        kmh >= 40 -> 2
        else -> 4
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    /** A uint32 field: [v] as its 32 bits, unsigned (so -1 is 0xFFFFFFFF, as protobuf writes it). */
    private fun ByteArrayOutputStream.uint(field: Int, v: Int) {
        key(field, 0)
        varint(v.toLong() and 0xFFFFFFFFL)
    }
}
