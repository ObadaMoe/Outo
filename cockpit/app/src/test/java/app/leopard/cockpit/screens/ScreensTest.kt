package app.leopard.cockpit.screens

import app.leopard.cockpit.screens.ScreenContent.Idle
import app.leopard.cockpit.screens.ScreenContent.Single
import app.leopard.cockpit.screens.ScreenContent.Split
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layout rules, including the awkward cases. */
class ScreensTest {
    private val maps = AppId("maps")
    private val yt = AppId("yt")
    private val music = AppId("music")
    private val chrome = AppId("chrome")

    private val main = ScreenId.MAIN
    private val driver = ScreenId.DRIVER
    private val passenger = ScreenId.PASSENGER

    private fun state(vararg s: Pair<ScreenId, ScreenContent>) = s.fold(ScreensState()) { st, (id, c) -> st.with(id, c) }
    private fun place(st: ScreensState, app: AppId, screen: ScreenId, slot: Slot = Slot.FULL) =
        Screens.place(st, app, DropTarget(screen, slot))

    /** Every app is on one screen at most, and a split never holds the same app twice. */
    private fun assertValid(st: ScreensState) {
        val all = st.screens.values.flatMap { it.apps }
        assertEquals("an app is on two screens: $st", all.toSet().size, all.size)
        st.screens.values.filterIsInstance<Split>().forEach { assertTrue(it.left != it.right) }
        assertTrue("only main splits", ScreenId.entries.filter { !it.canSplit }.all { st[it] !is Split })
    }

    @Test fun openOnIdleScreen() {
        val c = place(ScreensState(), maps, main)
        assertEquals(Single(maps), c.state[main])
        assertTrue(c.closes.isEmpty())
    }

    @Test fun halfOnIdleScreenFillsIt() {
        assertEquals(Single(maps), place(ScreensState(), maps, main, Slot.RIGHT).state[main])
    }

    @Test fun nextToMakesSplitOnTheRequestedSide() {
        val st = state(main to Single(maps))
        assertEquals(Split(maps, yt), place(st, yt, main, Slot.RIGHT).state[main])
        assertEquals(Split(yt, maps), place(st, yt, main, Slot.LEFT).state[main])
    }

    @Test fun fullOnSplitClosesBoth() {
        val c = place(state(main to Split(maps, yt)), music, main)
        assertEquals(Single(music), c.state[main])
        assertEquals(setOf(maps, yt), c.closes.map { it.first }.toSet())
    }

    @Test fun halfOnSplitClosesOnlyThatHalf() {
        val c = place(state(main to Split(maps, yt, 0.3f)), music, main, Slot.RIGHT)
        assertEquals(Split(maps, music, 0.3f), c.state[main])
        assertEquals(listOf(yt to main), c.closes)
    }

    @Test fun halfOfSplitToOtherHalfSwapsAndClosesNothing() {
        val c = place(state(main to Split(maps, yt, 0.3f)), maps, main, Slot.RIGHT)
        assertEquals(Split(yt, maps, 0.7f), c.state[main])
        assertTrue(c.closes.isEmpty())
    }

    @Test fun halfOfSplitToItsOwnHalfChangesNothing() {
        val st = state(main to Split(maps, yt))
        assertEquals(st, place(st, maps, main, Slot.LEFT).state)
    }

    @Test fun halfOfSplitToFullDropsTheOtherHalf() {
        val c = place(state(main to Split(maps, yt)), maps, main)
        assertEquals(Single(maps), c.state[main])
        assertEquals(listOf(yt to main), c.closes)
    }

    @Test fun singleToItsOwnScreenChangesNothing() {
        val st = state(main to Single(maps))
        val c = place(st, maps, main)
        assertEquals(st, c.state)
        assertTrue(c.closes.isEmpty())
        assertEquals(st, place(st, maps, main, Slot.LEFT).state)
    }

    @Test fun movingFromSplitToSideLeavesTheOtherHalfFull() {
        val c = place(state(main to Split(maps, yt)), maps, passenger)
        assertEquals(Single(yt), c.state[main])
        assertEquals(Single(maps), c.state[passenger])
        assertTrue(c.closes.isEmpty())
        assertValid(c.state)
    }

    @Test fun sideScreensNeverSplit() {
        val st = state(passenger to Single(music))
        val c = place(st, maps, passenger, Slot.LEFT)
        assertEquals(Single(maps), c.state[passenger])
        assertEquals(listOf(music to passenger), c.closes)
    }

    @Test fun movingFromSideToMainSplit() {
        val c = place(state(main to Single(maps), passenger to Single(yt)), yt, main, Slot.RIGHT)
        assertEquals(Split(maps, yt), c.state[main])
        assertEquals(Idle, c.state[passenger])
        assertTrue(c.closes.isEmpty())
    }

    @Test fun movingIntoTheHalfHoldingTheOtherHalfOfItsOwnSplitFromElsewhere() {
        // yt is on the passenger screen; main is maps|music. yt replaces music.
        val c = place(state(main to Split(maps, music), passenger to Single(yt)), yt, main, Slot.RIGHT)
        assertEquals(Split(maps, yt), c.state[main])
        assertEquals(Idle, c.state[passenger])
        assertEquals(listOf(music to main), c.closes)
    }

    @Test fun replacingOnOneSideScreenFromTheOther() {
        val c = place(state(driver to Single(maps), passenger to Single(yt)), maps, passenger)
        assertEquals(Idle, c.state[driver])
        assertEquals(Single(maps), c.state[passenger])
        assertEquals(listOf(yt to passenger), c.closes)
    }

    @Test fun closeAndClear() {
        val st = state(main to Split(maps, yt), driver to Single(music))
        assertEquals(Single(yt), Screens.close(st, maps)[main])
        assertEquals(Idle, Screens.close(st, music)[driver])
        assertEquals(st, Screens.close(st, chrome))
        assertEquals(Idle, Screens.clear(st, main)[main])
    }

    @Test fun swapKeepsTheSizes() {
        val st = Screens.swap(state(main to Split(maps, yt, 0.3f)), main)
        assertEquals(Split(yt, maps, 0.7f), st[main])
        assertEquals(state(main to Single(maps)), Screens.swap(state(main to Single(maps)), main))
    }

    @Test fun dividerResizesWithinLimitsAndDismissesAtTheEdges() {
        val st = state(main to Split(maps, yt))
        assertEquals(Split(maps, yt, Screens.MIN_RATIO), Screens.releaseDivider(st, main, 0.15f).first[main])
        assertEquals(Split(maps, yt, Screens.MAX_RATIO), Screens.releaseDivider(st, main, 0.85f).first[main])
        Screens.releaseDivider(st, main, 0.05f).let { (s, gone) -> assertEquals(Single(yt), s[main]); assertEquals(maps, gone) }
        Screens.releaseDivider(st, main, 0.95f).let { (s, gone) -> assertEquals(Single(maps), s[main]); assertEquals(yt, gone) }
        Screens.releaseDivider(state(main to Single(maps)), main, 0.05f).let { (s, gone) -> assertEquals(Single(maps), s[main]); assertNull(gone) }
    }

    @Test fun ratiosOutsideTheLimitsAreClamped() {
        assertEquals(Screens.MIN_RATIO, Screens.clampRatio(0.05f))
        assertEquals(Screens.MAX_RATIO, Screens.clampRatio(0.96f))
        assertEquals(0.5f, Screens.clampRatio(Float.NaN))
        val st = state(main to Split(maps, yt, 0.06f), driver to Single(music))
        assertEquals(state(main to Split(maps, yt, Screens.MIN_RATIO), driver to Single(music)), Screens.clamped(st))
        // Already within the limits: the very same state comes back.
        val ok = state(main to Split(maps, yt, 0.4f))
        assertTrue(ok === Screens.clamped(ok))
        Screens.releaseDivider(state(main to Split(maps, yt)), main, Float.NaN).let { (s, gone) ->
            assertEquals(Split(maps, yt, 0.5f), s[main]); assertNull(gone)
        }
    }

    /** Every move from every small layout keeps the rules. */
    @Test fun everyMoveKeepsTheRules() {
        val apps = listOf(maps, yt, music)
        val contents = buildList {
            add(Idle)
            apps.forEach { add(Single(it)) }
            apps.forEach { a -> apps.filter { it != a }.forEach { b -> add(Split(a, b)) } }
        }
        val starts = mutableListOf<ScreensState>()
        for (m in contents) for (d in contents.filter { it !is Split }) for (p in contents.filter { it !is Split }) {
            val st = state(main to m, driver to d, passenger to p)
            val all = st.screens.values.flatMap { it.apps }
            if (all.toSet().size == all.size) starts += st
        }
        for (st in starts) for (app in apps + chrome) for (screen in ScreenId.entries) for (slot in Slot.entries) {
            val c = place(st, app, DropTarget(screen, slot).let { it.screen }, slot)
            assertValid(c.state)
            assertEquals("$app should be on $screen after $slot from $st", screen, c.state.screenOf(app))
            // What closes is exactly what was running and isn't any more.
            assertEquals((st.running - c.state.running).toSet(), c.closes.map { it.first }.toSet())
        }
    }
}
