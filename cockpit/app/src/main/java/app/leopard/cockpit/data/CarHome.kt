package app.leopard.cockpit.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The cards CarPlay mode's dashboard can show beside the map. Each of its two places
 * holds a stack of one or more, swiped through up and down, as an iPhone's widget stack.
 */
enum class DashCard(val title: String) {
    NOW_PLAYING("Now Playing"),
    NAVIGATION("Navigation"),
    /** The iPhone's notifications; skipped in its stack while there are none. */
    NOTIFICATIONS("Notifications"),
    ENERGY("Energy"),
    CLIMATE("Climate"),
    TYRES("Tyres"),
    DRIVE("Drive"),
    SOUND("Engine Sound"),
    WEEK("Last 7 days"),
}

/**
 * How CarPlay mode's Home is arranged, as the driver left it: the apps' order on its
 * pages (Settings among them, by its key "settings"), the apps taken off them, and the
 * dashboard's two stacks of cards.
 *
 * [order] lists keys in the driver's order. An app it doesn't list (one installed since,
 * or never moved) comes after those, in the car's own order, so nothing new goes missing.
 */
data class CarHome(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val top: List<DashCard> = DEFAULT_TOP,
    val bottom: List<DashCard> = DEFAULT_BOTTOM,
) {
    /** The keys of [all] (in the car's order) as Home shows them: the driver's order, hidden ones left out. */
    fun arrange(all: List<String>): List<String> {
        val present = all.toSet()
        val listed = order.filter { it in present && it !in hidden }.distinct()
        val rest = all.filter { it !in hidden && it !in listed }
        return listed + rest
    }

    /** [shown] (as [arrange] gave it) with [key] moved to [to]: the new order. */
    fun move(shown: List<String>, key: String, to: Int): CarHome {
        if (key !in shown) return this
        val list = shown.toMutableList()
        list.remove(key)
        list.add(to.coerceIn(0, list.size), key)
        return copy(order = list)
    }

    /** Off Home's pages (Settings stays: it's how to get them back). */
    fun hide(key: String): CarHome = if (key == SETTINGS) this else copy(hidden = hidden + key)

    /** Back on Home, at the end of the pages ([all]: the car's keys, as [arrange] takes them). */
    fun show(key: String, all: List<String>): CarHome {
        val back = copy(hidden = hidden - key)
        return back.copy(order = back.arrange(all) - key + key)
    }

    /** One of the dashboard's stacks: [first] the top one. */
    fun stack(first: Boolean) = if (first) top else bottom

    /**
     * [card] in or out of a stack (in: last). A card is in one stack at most (the same
     * live card twice would fight over its app), so one taken from the other stack moves;
     * a stack keeps at least one card.
     */
    fun toggle(first: Boolean, card: DashCard): CarHome {
        val mine = stack(first)
        val other = stack(!first)
        return when {
            card in mine -> (mine - card).takeIf { it.isNotEmpty() }?.let { with(first, it) } ?: this
            card in other -> (other - card).takeIf { it.isNotEmpty() }?.let { with(first, mine + card).with(!first, it) } ?: this
            else -> with(first, mine + card)
        }
    }

    /** Whether [toggle] would do anything: not when it'd leave a stack empty. */
    fun canToggle(first: Boolean, card: DashCard): Boolean = toggle(first, card) != this

    /** [card] to the front of its stack: the one it shows first. */
    fun lead(first: Boolean, card: DashCard): CarHome {
        val s = stack(first)
        if (card !in s) return this
        return with(first, listOf(card) + (s - card))
    }

    private fun with(first: Boolean, s: List<DashCard>) = if (first) copy(top = s) else copy(bottom = s)

    fun toJson(): JSONObject = JSONObject().apply {
        put("order", JSONArray(order))
        put("hidden", JSONArray(hidden.toList()))
        put("top", JSONArray(top.map { it.name }))
        put("bottom", JSONArray(bottom.map { it.name }))
    }

    companion object {
        const val SETTINGS = "settings"
        val DEFAULT_TOP = listOf(DashCard.NOW_PLAYING)
        /** Notifications while there are some, as before stacks; Navigation otherwise. */
        val DEFAULT_BOTTOM = listOf(DashCard.NOTIFICATIONS, DashCard.NAVIGATION)

        fun fromJson(o: JSONObject): CarHome {
            fun strings(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            fun cards(name: String, default: List<DashCard>) =
                strings(name).mapNotNull { n -> DashCard.entries.firstOrNull { it.name == n } }.distinct().ifEmpty { default }
            return CarHome(
                order = strings("order"),
                hidden = strings("hidden").toSet() - SETTINGS,
                top = cards("top", DEFAULT_TOP),
                bottom = cards("bottom", DEFAULT_BOTTOM),
            )
        }
    }
}
