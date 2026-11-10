package app.leopard.cockpit.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A size a widget is designed for, in grid cells: [w] of the 6 columns, [h] rows. */
data class WidgetSize(val w: Int, val h: Int) {
    /** What the size is called in menus. */
    val label: String
        get() = when {
            h == 1 -> "Slim"
            w <= 2 -> "Small"
            w == 3 -> "Half"
            w <= 4 -> "Medium"
            else -> "Wide"
        }

    override fun toString() = "$w × $h"
}

/** Something a widget can show or leave out, set from its menu. On unless turned off. */
data class WidgetOption(val id: String, val label: String)

private fun sizes(vararg s: Pair<Int, Int>) = s.map { WidgetSize(it.first, it.second) }

/** The Overview's widgets, the sizes each one is designed for, and what it can leave out. */
enum class WidgetKind(
    val title: String,
    val sizes: List<WidgetSize>,
    val default: WidgetSize,
    val options: List<WidgetOption> = emptyList(),
) {
    ENERGY("Energy", sizes(2 to 2, 3 to 2, 4 to 2, 6 to 2), WidgetSize(4, 2), listOf(WidgetOption("details", "Odometer and trip"))),
    SOUND("Engine Sound", sizes(2 to 1, 2 to 2, 3 to 2), WidgetSize(2, 2), listOf(WidgetOption("studio", "Sound Studio button"))),
    NAVIGATION("Navigation", sizes(2 to 2, 3 to 2, 4 to 2, 6 to 2), WidgetSize(6, 2), listOf(WidgetOption("places", "Recent places"))),
    DRIVE("Drive", sizes(2 to 1, 2 to 2, 3 to 2), WidgetSize(2, 2), listOf(WidgetOption("pedal", "Accelerator"))),
    CLIMATE("Climate", sizes(2 to 1, 2 to 2, 3 to 2), WidgetSize(2, 2), listOf(WidgetOption("outside", "Outside temperature"))),
    TYRES("Tyres", sizes(2 to 2, 3 to 2), WidgetSize(2, 2)),
    SCREENS("Screens now", sizes(2 to 2, 3 to 2), WidgetSize(2, 2)),
    WEEK("Last 7 days", sizes(2 to 2, 3 to 2, 4 to 2, 6 to 2), WidgetSize(4, 2)),
    /**
     * The map app, live, like CarPlay's dashboard: not on the grid but beside it,
     * the Overview's full height and [w][WidgetSize.w] of the 6 columns wide; the
     * other widgets scroll in the columns left over.
     */
    MAP("Map", sizes(3 to 2, 4 to 2), WidgetSize(3, 2)),
    /**
     * What a media app is playing, as on CarPlay's dashboard: it comes on its own,
     * first of the widgets (top right beside the map), while an app has a track
     * loaded, playing or paused, and goes when none has.
     */
    MEDIA("Now Playing", sizes(3 to 1, 2 to 2, 3 to 2, 4 to 2), WidgetSize(3, 2)),
    ;

    /** Pinned beside the grid instead of placed on it. */
    val pinned: Boolean get() = this == MAP

    /** Shows only while it has something to show, always first on the board. */
    val automatic: Boolean get() = this == MEDIA

    /** What [size] is called in this widget's menu. */
    fun label(size: WidgetSize): String = if (pinned) (if (size.w <= 3) "Medium" else "Large") else size.label

    /** The designed size closest to [w] × [h] cells (what a stretched corner snaps to). */
    fun nearest(w: Float, h: Float): WidgetSize = sizes.minBy { abs(it.w - w) * 1f + abs(it.h - h) * 1.6f }
}

/** One widget on the Overview: which, how big, and the options turned off. */
data class WidgetSlot(val kind: WidgetKind, val size: WidgetSize = kind.default, val off: Set<String> = emptySet()) {
    fun shows(option: String) = option !in off
}

/** The Overview's widgets in order. A widget that isn't listed was removed and can be added back. */
data class WidgetLayout(val slots: List<WidgetSlot>) {
    val hidden: List<WidgetKind> get() = WidgetKind.entries.filter { k -> slots.none { it.kind == k } }

    /** The pinned map, if it's on the Overview. */
    val map: WidgetSlot? get() = slots.firstOrNull { it.kind.pinned }

    /** The widgets on the grid (all but the pinned map). */
    val grid: WidgetLayout get() = if (map == null) this else WidgetLayout(slots.filter { !it.kind.pinned })

    /** [grid] changed on the board, with the pinned map kept. */
    fun withGrid(g: WidgetLayout) = replacing(g)

    /**
     * What the board shows: the automatic widgets first while they have something
     * to show (or while [editing], so they can be sized or removed), else left out.
     */
    fun shown(active: Boolean, editing: Boolean): WidgetLayout {
        val (auto, rest) = slots.partition { it.kind.automatic }
        return WidgetLayout(if (active || editing) auto + rest else rest)
    }

    /**
     * Widgets moved or resized on the board ([changed], in its order) with the rest
     * kept after them. Only order and size come from [changed]: a widget it lacks
     * stays (the board may have been drawn a moment before Now Playing joined it),
     * one it lists twice counts once, and one that isn't here isn't added.
     * Removing goes through [remove].
     */
    fun replacing(changed: WidgetLayout): WidgetLayout {
        val moved = changed.slots.distinctBy { it.kind }.filter { c -> slots.any { it.kind == c.kind } }
        return WidgetLayout(moved + slots.filter { s -> moved.none { it.kind == s.kind } })
    }

    fun indexOf(kind: WidgetKind) = slots.indexOfFirst { it.kind == kind }

    fun move(kind: WidgetKind, to: Int): WidgetLayout {
        val from = indexOf(kind)
        if (from < 0) return this
        val list = slots.toMutableList()
        val slot = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), slot)
        return WidgetLayout(list)
    }

    fun resize(kind: WidgetKind, size: WidgetSize) =
        if (size !in kind.sizes) this else WidgetLayout(slots.map { if (it.kind == kind) it.copy(size = size) else it })

    fun remove(kind: WidgetKind) = WidgetLayout(slots.filter { it.kind != kind })

    fun add(kind: WidgetKind) = if (indexOf(kind) >= 0) this else WidgetLayout(slots + WidgetSlot(kind))

    fun toggle(kind: WidgetKind, option: String) = WidgetLayout(
        slots.map { if (it.kind == kind) it.copy(off = if (option in it.off) it.off - option else it.off + option) else it },
    )

    fun toJson(): JSONArray = JSONArray(
        slots.map { s ->
            JSONObject().put("k", s.kind.name).put("w", s.size.w).put("h", s.size.h).put("off", JSONArray(s.off.sorted()))
        },
    )

    companion object {
        /** The Overview as it first comes: the same cards and places as before widgets could move. */
        val DEFAULT = WidgetLayout(
            listOf(
                WidgetSlot(WidgetKind.MEDIA),
                WidgetSlot(WidgetKind.ENERGY), WidgetSlot(WidgetKind.SOUND),
                WidgetSlot(WidgetKind.NAVIGATION),
                WidgetSlot(WidgetKind.DRIVE), WidgetSlot(WidgetKind.CLIMATE), WidgetSlot(WidgetKind.TYRES),
                WidgetSlot(WidgetKind.SCREENS), WidgetSlot(WidgetKind.WEEK),
            ),
        )

        /** Reads a saved layout; unknown widgets and sizes a widget wasn't designed for are dropped. */
        fun fromJson(a: JSONArray): WidgetLayout {
            val out = ArrayList<WidgetSlot>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val kind = runCatching { WidgetKind.valueOf(o.getString("k")) }.getOrNull() ?: continue
                if (out.any { it.kind == kind }) continue
                val size = WidgetSize(o.optInt("w"), o.optInt("h")).takeIf { it in kind.sizes } ?: kind.default
                val offArray = o.optJSONArray("off")
                val off = if (offArray == null) emptySet() else (0 until offArray.length()).map { offArray.getString(it) }.toSet()
                out += WidgetSlot(kind, size, off)
            }
            return WidgetLayout(out)
        }
    }
}

/**
 * Places widgets on a grid of [COLUMNS] columns, in order, each at the first
 * place it fits (top to bottom, left to right), so a small widget fills a gap
 * left by a bigger one before it.
 */
object WidgetGrid {
    const val COLUMNS = 6

    data class Cell(val x: Int, val y: Int)

    fun pack(sizes: List<WidgetSize>, columns: Int = COLUMNS): List<Cell> {
        val rows = ArrayList<BooleanArray>()
        fun free(x: Int, y: Int, s: WidgetSize): Boolean {
            if (x + s.w > columns) return false
            for (yy in y until y + s.h) {
                val row = rows.getOrNull(yy) ?: continue
                for (xx in x until x + s.w) if (row[xx]) return false
            }
            return true
        }
        return sizes.map { s ->
            val w = s.w.coerceIn(1, columns)
            val size = WidgetSize(w, s.h.coerceAtLeast(1))
            var y = 0
            var cell: Cell? = null
            while (cell == null) {
                for (x in 0..columns - w) if (free(x, y, size)) { cell = Cell(x, y); break }
                if (cell == null) y++
            }
            while (rows.size < cell.y + size.h) rows += BooleanArray(columns)
            for (yy in cell.y until cell.y + size.h) for (xx in cell.x until cell.x + w) rows[yy][xx] = true
            cell
        }
    }

    /** Rows used by widgets of [sizes] placed at [cells]. */
    fun rows(sizes: List<WidgetSize>, cells: List<Cell>): Int =
        sizes.indices.maxOfOrNull { cells[it].y + sizes[it].h } ?: 0
}
