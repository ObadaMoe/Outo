package app.leopard.cockpit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleDataTest {
    private fun pedal(raw: Int, top: Int) = VehicleData(mapOf("pedal" to raw), pedalTop = top).pedal!!

    @Test fun noPedalReported() = assertNull(VehicleData(emptyMap()).pedal)

    @Test fun nothingLearnedYetReadsOutOfHundred() {
        assertEquals(0.5f, pedal(50, 0), 0.001f)
        assertEquals(1f, pedal(400, 0), 0.001f)
    }

    @Test fun aCarThatReportsUpTo255() {
        assertEquals(1f, pedal(255, 255), 0.001f)
        assertEquals(0.5f, pedal(128, 255), 0.01f)
    }

    @Test fun aCarThatReportsUpTo1000() {
        assertEquals(0.05f, pedal(50, 1000), 0.001f)
        assertEquals(1f, pedal(1000, 1000), 0.001f)
    }

    @Test fun aCarThatReportsUpTo100KeepsItsScale() = assertEquals(0.3f, pedal(30, 100), 0.001f)
}
