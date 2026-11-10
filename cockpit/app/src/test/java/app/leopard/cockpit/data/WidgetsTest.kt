package app.leopard.cockpit.data

import app.leopard.cockpit.data.WidgetGrid.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetsTest {
    private fun s(w: Int, h: Int) = WidgetSize(w, h)

    @Test
    fun defaultOverviewPacksLikeTheOldCards() {
        // With nothing playing, Now Playing isn't on the board.
        val sizes = WidgetLayout.DEFAULT.shown(active = false, editing = false).slots.map { it.size }
        val cells = WidgetGrid.pack(sizes)
        // Energy + Sound, Navigation, Drive + Climate + Tyres, Screens + Week.
        assertEquals(
            listOf(Cell(0, 0), Cell(4, 0), Cell(0, 2), Cell(0, 4), Cell(2, 4), Cell(4, 4), Cell(0, 6), Cell(2, 6)),
            cells,
        )
        assertEquals(8, WidgetGrid.rows(sizes, cells))
    }

    @Test
    fun smallWidgetFillsTheGapLeftBeforeIt() {
        val cells = WidgetGrid.pack(listOf(s(4, 2), s(6, 2), s(2, 2)))
        assertEquals(listOf(Cell(0, 0), Cell(0, 2), Cell(4, 0)), cells)
    }

    @Test
    fun slimWidgetsStackInHalfRows() {
        val cells = WidgetGrid.pack(listOf(s(2, 2), s(2, 1), s(2, 1), s(2, 2)))
        assertEquals(listOf(Cell(0, 0), Cell(2, 0), Cell(4, 0), Cell(2, 1)), cells)
    }

    @Test
    fun moveRemoveAddBack() {
        val moved = WidgetLayout.DEFAULT.move(WidgetKind.WEEK, 0)
        assertEquals(WidgetKind.WEEK, moved.slots.first().kind)
        assertEquals(WidgetLayout.DEFAULT.slots.size, moved.slots.size)

        val removed = moved.remove(WidgetKind.SOUND)
        assertEquals(listOf(WidgetKind.SOUND, WidgetKind.MAP), removed.hidden)
        val back = removed.add(WidgetKind.SOUND)
        assertEquals(WidgetKind.SOUND, back.slots.last().kind)
        assertEquals(listOf(WidgetKind.MAP), back.hidden)
        // Adding one that's already there changes nothing.
        assertSame(back, back.add(WidgetKind.SOUND))
    }

    @Test
    fun resizeOnlyToDesignedSizes() {
        val l = WidgetLayout.DEFAULT
        assertEquals(s(2, 2), l.resize(WidgetKind.ENERGY, s(2, 2)).slots[l.indexOf(WidgetKind.ENERGY)].size)
        // Tyres has no slim size: nothing changes.
        assertSame(l, l.resize(WidgetKind.TYRES, s(2, 1)))
    }

    @Test
    fun stretchedCornerSnapsToNearestSize() {
        assertEquals(s(4, 2), WidgetKind.ENERGY.nearest(4.4f, 2.2f))
        assertEquals(s(6, 2), WidgetKind.ENERGY.nearest(5.3f, 1.8f))
        assertEquals(s(2, 1), WidgetKind.SOUND.nearest(2.1f, 1.2f))
        assertEquals(s(3, 2), WidgetKind.SOUND.nearest(3.4f, 1.9f))
    }

    @Test
    fun mapIsPinnedBesideTheGrid() {
        val l = WidgetLayout.DEFAULT.add(WidgetKind.MAP)
        assertEquals(WidgetKind.MAP, l.map?.kind)
        assertTrue(l.grid.slots.none { it.kind == WidgetKind.MAP })
        // A change on the board keeps the map.
        val g = l.grid.move(WidgetKind.WEEK, 0)
        assertEquals(WidgetKind.MAP, l.withGrid(g).map?.kind)
        assertEquals(WidgetKind.WEEK, l.withGrid(g).grid.slots.first().kind)
        assertEquals("Medium", WidgetKind.MAP.label(WidgetSize(3, 2)))
        assertEquals("Large", WidgetKind.MAP.label(WidgetSize(4, 2)))
    }

    @Test
    fun besideTheMapWidgetsStackInTheColumnsLeft() {
        // Three columns left, every widget the full three wide.
        val cells = WidgetGrid.pack(listOf(s(3, 2), s(3, 1), s(3, 2)), columns = 3)
        assertEquals(listOf(Cell(0, 0), Cell(0, 2), Cell(0, 3)), cells)
    }

    @Test
    fun nowPlayingShowsFirstOnlyWithATrack() {
        // Saved anywhere in the order, it's left out with nothing playing...
        val l = WidgetLayout.DEFAULT.move(WidgetKind.MEDIA, 4)
        assertTrue(l.shown(active = false, editing = false).slots.none { it.kind == WidgetKind.MEDIA })
        // ...and comes first while something plays, or while editing.
        assertEquals(WidgetKind.MEDIA, l.shown(active = true, editing = false).slots.first().kind)
        assertEquals(WidgetKind.MEDIA, l.shown(active = false, editing = true).slots.first().kind)
        // Removed, it stays away even while something plays.
        val gone = l.remove(WidgetKind.MEDIA)
        assertTrue(gone.shown(active = true, editing = false).slots.none { it.kind == WidgetKind.MEDIA })
    }

    @Test
    fun changesOnTheBoardKeepWhatIsntOnIt() {
        val l = WidgetLayout.DEFAULT.add(WidgetKind.MAP)
        // Nothing playing: the board has neither the map nor Now Playing.
        val board = l.grid.shown(active = false, editing = false)
        val after = l.replacing(board.move(WidgetKind.WEEK, 0))
        assertEquals(WidgetKind.WEEK, after.slots.first().kind)
        assertTrue(after.indexOf(WidgetKind.MEDIA) >= 0)
        assertEquals(WidgetKind.MAP, after.map?.kind)
        assertEquals(l.slots.size, after.slots.size)
    }

    @Test
    fun changesOnTheBoardOnlyReorderAndResize() {
        val l = WidgetLayout.DEFAULT
        val shown = l.shown(active = true, editing = true)
        // A board drawn before Now Playing left it (editing ended mid-drag): no second copy.
        val twice = WidgetLayout(shown.slots + WidgetSlot(WidgetKind.MEDIA))
        assertEquals(1, l.replacing(twice).slots.count { it.kind == WidgetKind.MEDIA })
        // A board drawn before Now Playing joined it: Now Playing isn't lost.
        val without = l.shown(active = false, editing = false).move(WidgetKind.WEEK, 0)
        val kept = l.replacing(without)
        assertTrue(kept.indexOf(WidgetKind.MEDIA) >= 0)
        assertEquals(WidgetKind.WEEK, kept.slots.first().kind)
        // A widget that was removed meanwhile doesn't come back.
        assertTrue(l.remove(WidgetKind.TYRES).replacing(shown).indexOf(WidgetKind.TYRES) < 0)
        // Sizes come from the board.
        val bigger = shown.resize(WidgetKind.SOUND, WidgetSize(3, 2))
        assertEquals(WidgetSize(3, 2), l.replacing(bigger).slots[l.replacing(bigger).indexOf(WidgetKind.SOUND)].size)
    }

    @Test
    fun optionsToggle() {
        val off = WidgetLayout.DEFAULT.toggle(WidgetKind.ENERGY, "details")
        val i = off.indexOf(WidgetKind.ENERGY)
        assertFalse(off.slots[i].shows("details"))
        assertTrue(off.toggle(WidgetKind.ENERGY, "details").slots[i].shows("details"))
    }
}
