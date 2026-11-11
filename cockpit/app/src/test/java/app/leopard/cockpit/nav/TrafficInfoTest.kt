package app.leopard.cockpit.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficInfoTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test
    fun aLimitWithItsRoadClass() {
        // No jam (-1, -1), limit 60, no camera (-1), road class 1, inside field 1.
        assertEquals(
            "0A 17 08 FF FF FF FF 0F 10 FF FF FF FF 0F 40 3C 50 FF FF FF FF 0F 98 01 01",
            hex(TrafficInfo.encode(60)),
        )
    }

    @Test
    fun noLimitIsSentAsBydsMapSendsIt() {
        // As in the car's log: limit left out (0), road class 255.
        assertEquals(
            "0A 16 08 FF FF FF FF 0F 10 FF FF FF FF 0F 50 FF FF FF FF 0F 98 01 FF 01",
            hex(TrafficInfo.encode(0)),
        )
    }

    @Test
    fun aMotorwayLeavesOutItsClass() {
        // Class 0 is proto3's default, so it isn't written; 130 takes two bytes.
        assertEquals(
            "0A 14 08 FF FF FF FF 0F 10 FF FF FF FF 0F 40 78 50 FF FF FF FF 0F",
            hex(TrafficInfo.encode(120)),
        )
        assertEquals(
            "0A 15 08 FF FF FF FF 0F 10 FF FF FF FF 0F 40 82 01 50 FF FF FF FF 0F",
            hex(TrafficInfo.encode(130)),
        )
    }

    @Test
    fun roadClassFromTheLimit() {
        assertEquals(TrafficInfo.UNKNOWN_ROAD, TrafficInfo.roadClass(0))
        assertEquals(4, TrafficInfo.roadClass(30))
        assertEquals(2, TrafficInfo.roadClass(50))
        assertEquals(1, TrafficInfo.roadClass(60))
        assertEquals(6, TrafficInfo.roadClass(80))
        assertEquals(0, TrafficInfo.roadClass(100))
    }
}
