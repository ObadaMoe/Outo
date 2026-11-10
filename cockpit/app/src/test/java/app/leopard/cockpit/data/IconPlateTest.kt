package app.leopard.cockpit.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconPlateTest {
    private val size = 100
    private val clear = 0x00000000

    /** A rounded square from [from] to [to] (exclusive), corner radius [r], filled with [color]. */
    private fun roundedSquare(from: Int, to: Int, r: Int, color: Int): IntArray {
        val px = IntArray(size * size) { clear }
        for (y in from until to) for (x in from until to) {
            val dx = maxOf(from + r - x, x - (to - 1 - r), 0)
            val dy = maxOf(from + r - y, y - (to - 1 - r), 0)
            if (dx * dx + dy * dy <= r * r) px[y * size + x] = color
        }
        return px
    }

    @Test
    fun aThemedIconOnItsOwnPlateIsFoundWithItsBox() {
        // The car's icons: a rounded square, inset a little in the picture.
        val px = roundedSquare(6, 94, 20, 0xFF33CCFF.toInt())
        assertArrayEquals(intArrayOf(6, 6, 94, 94), IconPlate.find(px, size))
        // A plain full square is one too.
        assertArrayEquals(intArrayOf(0, 0, 100, 100), IconPlate.find(IntArray(size * size) { 0xFF000000.toInt() }, size))
    }

    @Test
    fun aRoundIconOrABareLogoIsNot() {
        // A circle fills only about 79% of its box.
        assertNull(IconPlate.find(roundedSquare(0, 100, 50, 0xFF33CCFF.toInt()), size))
        // A logo: a plus sign with see-through space around it.
        val plus = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if (x in 40..59 || y in 40..59) 0xFFE02020.toInt() else clear
        }
        assertNull(IconPlate.find(plus, size))
        // Something small in the middle.
        assertNull(IconPlate.find(roundedSquare(30, 70, 4, 0xFF33CCFF.toInt()), size))
        // Nothing at all.
        assertNull(IconPlate.find(IntArray(size * size) { clear }, size))
    }

    @Test
    fun theEdgeColourIsThePlates() {
        val blue = 0xFF33CCFF.toInt()
        val px = roundedSquare(6, 94, 20, blue)
        // A white logo in the middle doesn't change it.
        for (y in 35..65) for (x in 35..65) px[y * size + x] = 0xFFFFFFFF.toInt()
        assertEquals(blue, IconPlate.edgeColor(px, size, IconPlate.find(px, size)!!))
    }
}
