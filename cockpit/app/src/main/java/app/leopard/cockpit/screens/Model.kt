package app.leopard.cockpit.screens

/**
 * What runs where, as Otility sees it.
 *
 * The car has three screens. Each one is idle or shows one app; the main screen
 * can also show two apps side by side. An app is on at most one screen at a
 * time, because Android keeps an app's window on a single display.
 */
enum class ScreenId(val label: String, val canSplit: Boolean) {
    DRIVER("Driver", canSplit = false),
    MAIN("Main", canSplit = true),
    PASSENGER("Passenger", canSplit = false);
}

/** Where on a screen an app goes. FULL replaces whatever is there. */
enum class Slot { FULL, LEFT, RIGHT }

data class DropTarget(val screen: ScreenId, val slot: Slot)

/** An app, identified by its package. */
@JvmInline
value class AppId(val pkg: String)

sealed interface ScreenContent {
    data object Idle : ScreenContent
    data class Single(val app: AppId) : ScreenContent
    /** [ratio] is the left app's share of the width, 0.2–0.8. */
    data class Split(val left: AppId, val right: AppId, val ratio: Float = 0.5f) : ScreenContent

    val apps: List<AppId>
        get() = when (this) {
            Idle -> emptyList()
            is Single -> listOf(app)
            is Split -> listOf(left, right)
        }
}

data class ScreensState(
    val screens: Map<ScreenId, ScreenContent> = ScreenId.entries.associateWith { ScreenContent.Idle },
) {
    operator fun get(id: ScreenId): ScreenContent = screens[id] ?: ScreenContent.Idle

    fun with(id: ScreenId, content: ScreenContent) = copy(screens = screens + (id to content))

    /** The screen an app is on, if any. */
    fun screenOf(app: AppId): ScreenId? = screens.entries.firstOrNull { app in it.value.apps }?.key

    val running: Set<AppId> get() = screens.values.flatMap { it.apps }.toSet()
}

/** The result of a change: the new state, plus the apps it closes (for the confirmation). */
data class Change(val state: ScreensState, val closes: List<Pair<AppId, ScreenId>>) {
    val replacesSomething get() = closes.isNotEmpty()
}

object Screens {

    const val MIN_RATIO = 0.25f

    /** The space between split apps, a share of the screen's width: the background shows through it. */
    fun splitGap(width: Float): Float = maxOf(width * 0.01f, 2f)
    const val MAX_RATIO = 0.75f

    /**
     * Puts [app] at [target]. If the app is already on another screen it moves
     * (it isn't duplicated). Apps that lose their place are listed in
     * [Change.closes].
     */
    fun place(state: ScreensState, app: AppId, target: DropTarget): Change {
        val from = state.screenOf(app)
        val slot = if (target.screen.canSplit) target.slot else Slot.FULL

        // Dragging one half of a split onto the other half of the same screen: swap them.
        if (from == target.screen && slot != Slot.FULL) {
            val c = state[from]
            if (c is ScreenContent.Split) {
                val onLeft = c.left == app
                val wantsLeft = slot == Slot.LEFT
                return Change(if (onLeft == wantsLeft) state else state.with(from, swap(c)), emptyList())
            }
        }

        // Take the app off the screen it's on now.
        var s = if (from != null) state.with(from, without(state[from], app)) else state
        val current = s[target.screen]
        val closes = mutableListOf<Pair<AppId, ScreenId>>()

        val next: ScreenContent = when (slot) {
            Slot.FULL -> {
                current.apps.filter { it != app }.forEach { closes += it to target.screen }
                ScreenContent.Single(app)
            }
            Slot.LEFT, Slot.RIGHT -> when (current) {
                ScreenContent.Idle -> ScreenContent.Single(app)
                is ScreenContent.Single ->
                    if (current.app == app) current
                    else if (slot == Slot.LEFT) ScreenContent.Split(app, current.app)
                    else ScreenContent.Split(current.app, app)
                is ScreenContent.Split ->
                    if (slot == Slot.LEFT) {
                        if (current.left != app) closes += current.left to target.screen
                        current.copy(left = app)
                    } else {
                        if (current.right != app) closes += current.right to target.screen
                        current.copy(right = app)
                    }
            }
        }
        s = s.with(target.screen, next)
        return Change(s, closes)
    }

    fun close(state: ScreensState, app: AppId): ScreensState {
        val on = state.screenOf(app) ?: return state
        return state.with(on, without(state[on], app))
    }

    fun clear(state: ScreensState, screen: ScreenId): ScreensState = state.with(screen, ScreenContent.Idle)

    fun swap(state: ScreensState, screen: ScreenId): ScreensState {
        val c = state[screen]
        return if (c is ScreenContent.Split) state.with(screen, swap(c)) else state
    }

    fun setRatio(state: ScreensState, screen: ScreenId, ratio: Float): ScreensState {
        val c = state[screen]
        return if (c is ScreenContent.Split) state.with(screen, c.copy(ratio = clampRatio(ratio))) else state
    }

    /** A split's ratio within the limits (a broken one goes back to the middle). */
    fun clampRatio(ratio: Float): Float = if (ratio.isNaN()) 0.5f else ratio.coerceIn(MIN_RATIO, MAX_RATIO)

    /** Every split within the limits: a saved layout, or one caught mid-drag, may not be. */
    fun clamped(state: ScreensState): ScreensState {
        if (state.screens.values.none { it is ScreenContent.Split && it.ratio != clampRatio(it.ratio) }) return state
        return state.copy(screens = state.screens.mapValues { (_, c) -> if (c is ScreenContent.Split) c.copy(ratio = clampRatio(c.ratio)) else c })
    }

    /** Past these, letting go of the divider makes one app full screen (like iPad). */
    const val DISMISS_LEFT = 0.12f
    const val DISMISS_RIGHT = 0.88f

    /**
     * Where the divider was let go. Near an edge, the app on the other side
     * fills the screen and the squeezed one leaves it; otherwise it's a resize.
     * Returns the new state and the app that left, if any.
     */
    fun releaseDivider(state: ScreensState, screen: ScreenId, ratio: Float): Pair<ScreensState, AppId?> {
        val c = state[screen] as? ScreenContent.Split ?: return state to null
        return when {
            ratio <= DISMISS_LEFT -> state.with(screen, ScreenContent.Single(c.right)) to c.left
            ratio >= DISMISS_RIGHT -> state.with(screen, ScreenContent.Single(c.left)) to c.right
            else -> setRatio(state, screen, ratio) to null
        }
    }

    private fun swap(c: ScreenContent.Split) = c.copy(left = c.right, right = c.left, ratio = 1f - c.ratio)

    private fun without(c: ScreenContent, app: AppId): ScreenContent = when (c) {
        ScreenContent.Idle -> c
        is ScreenContent.Single -> if (c.app == app) ScreenContent.Idle else c
        is ScreenContent.Split -> when (app) {
            c.left -> ScreenContent.Single(c.right)
            c.right -> ScreenContent.Single(c.left)
            else -> c
        }
    }
}
