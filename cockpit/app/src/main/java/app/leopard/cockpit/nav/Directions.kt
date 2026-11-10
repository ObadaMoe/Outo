package app.leopard.cockpit.nav

import app.leopard.cockpit.media.NowPlaying
import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import app.leopard.cockpit.Otility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What to do at the next turn: every kind of turn the driver cluster has its own
 * arrow for (see ClusterTurns.arrow).
 */
enum class Maneuver {
    STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    /** Keeping to one side where the road forks, or taking an exit off it. */
    KEEP_LEFT, KEEP_RIGHT,
    /** Turning back, swinging left (where traffic keeps right); [U_TURN_RIGHT] swings right. */
    U_TURN, U_TURN_RIGHT,
    /** Into a roundabout whose exit isn't known. */
    ROUNDABOUT,
    /** Through a roundabout, by the way its exit leaves: right, straight on, left, or back round. */
    ROUNDABOUT_RIGHT, ROUNDABOUT_STRAIGHT, ROUNDABOUT_LEFT, ROUNDABOUT_BACK,
    /** Out of the roundabout. */
    ROUNDABOUT_EXIT,
    /** A stop on the way. */
    WAYPOINT, TOLL, FERRY, ARRIVE, UNKNOWN;

    val isRoundabout: Boolean get() = name.startsWith("ROUNDABOUT")
}

/** The next instruction, as the navigation app shows it in its notification or on its screen. */
data class NextTurn(
    val pkg: String,
    /** "Turn right onto Al Corniche". */
    val instruction: String,
    /** "300 m", if the app gave one. */
    val distance: String?,
    /** "12 min · 5.2 km · 8:42 PM", or the street it leads toward. */
    val detail: String?,
    val maneuver: Maneuver,
    /** The app's own arrow picture, when it sends one. */
    val arrow: Bitmap?,
    val at: Long = System.currentTimeMillis(),
    /** Metres to the turn. */
    val metres: Int? = Units.metres(distance),
    /** The street the turn leads onto. */
    val street: String? = null,
    /** Time left to the destination. */
    val remainingSeconds: Int? = null,
    /** Distance left to the destination. */
    val remainingMetres: Int? = null,
    /** The arrival time as the app shows it ("9:26 PM"). */
    val eta: String? = null,
    /** Read from the map's own screen, which is more exact than its notification. */
    val fromScreen: Boolean = false,
    /** The road's speed limit in km/h, from the sign the map shows (read from its screen). */
    val speedLimit: Int? = null,
    /**
     * Read from the screen by layout alone (the map's views had unknown names), so
     * it's trusted only while the same app's navigation notification is up.
     */
    val guessed: Boolean = false,
) {
    /** "13 min · 5.5 km · 9:26 PM", or the app's own line when it gave none of these. */
    val trip: String?
        get() = listOfNotNull(remainingSeconds?.let(Units::duration), remainingMetres?.let(Units::distance), eta)
            .joinToString(" · ").ifEmpty { null } ?: detail

    /** The same turn, ignoring when it was read. */
    fun sameAs(other: NextTurn?) = other != null && copy(at = 0) == other.copy(at = 0)
}

/**
 * The navigation app's next turn, read from its notification while it guides.
 * Otility reads only the navigation apps listed here and ignores every other
 * notification.
 */
object Directions {
    val PACKAGES = setOf(
        "com.waze", "com.google.android.apps.maps", "app.revanced.android.apps.maps",
        "com.here.app.maps", "com.sygic.aura",
    )

    private val _turn = MutableStateFlow<NextTurn?>(null)
    val turn: StateFlow<NextTurn?> = _turn.asStateFlow()

    /** Android has given Otility notification access and the listener is running. */
    val listening = MutableStateFlow(false)

    /** Each navigation app's notification, the latest last. */
    private val notes = LinkedHashMap<String, NextTurn>()
    private val note: NextTurn? get() = notes.values.lastOrNull()
    /** From the map's screen (Waze or Google Maps), when Otility may read it. */
    private var screen: NextTurn? = null

    fun watches(context: Context, pkg: String): Boolean =
        pkg in PACKAGES ||
            // Debug builds also read the shell's test notifications (cmd notification post).
            (pkg == "com.android.shell" && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

    private fun publish() {
        val next = merge(note, trusted(screen, notes.values))
        if (!next.let { it?.sameAs(_turn.value) ?: (_turn.value == null) }) _turn.value = next
    }

    internal fun update(k: String, turn: NextTurn) = synchronized(this) {
        notes.remove(k)
        notes[k] = turn
        publish()
    }

    /** That notification went away (null: all of them). Another app still guiding takes over. */
    internal fun clear(k: String?) = synchronized(this) {
        if (k == null) notes.clear() else notes.remove(k)
        publish()
    }

    /** What the map shows on screen right now, or null once it stops guiding. */
    fun screen(turn: NextTurn?) = synchronized(this) {
        screen = turn
        publish()
    }

    /** Forgets everything (directions switched off). */
    fun clearAll() = synchronized(this) {
        notes.clear()
        screen = null
        publish()
    }

    /**
     * The screen's turn, unless it was only guessed from the layout and that app
     * isn't guiding by its notification: a route preview or a place card can look
     * like a turn, but only real guidance has the notification.
     */
    internal fun trusted(screen: NextTurn?, notes: Collection<NextTurn>): NextTurn? =
        screen?.takeUnless { s -> s.guessed && notes.none { it.pkg == s.pkg } }

    /**
     * One turn from both sources. The screen is exact and current; the
     * notification can lag a turn behind, so it only fills in what the screen
     * lacks, and its arrow is used only when it names the same street.
     */
    fun merge(note: NextTurn?, screen: NextTurn?): NextTurn? {
        if (screen == null) return note
        if (note == null || note.pkg != screen.pkg) return screen
        val street = screen.street
        val sameTurn = street != null && note.instruction.contains(street, ignoreCase = true)
        val maneuver = if (screen.maneuver == Maneuver.UNKNOWN && sameTurn) note.maneuver else screen.maneuver
        return screen.copy(
            instruction = if (maneuver != screen.maneuver) sentence(maneuver, street) ?: screen.instruction else screen.instruction,
            maneuver = maneuver,
            arrow = if (sameTurn) note.arrow else null,
            remainingSeconds = screen.remainingSeconds ?: note.remainingSeconds,
            remainingMetres = screen.remainingMetres ?: note.remainingMetres,
            eta = screen.eta ?: note.eta,
            detail = screen.detail ?: note.detail,
        )
    }

    /** "Turn left onto Al Corniche"; just the street when the turn isn't known. */
    fun sentence(m: Maneuver, street: String?): String? {
        val verb = when (m) {
            Maneuver.LEFT -> "Turn left"
            Maneuver.RIGHT -> "Turn right"
            Maneuver.SLIGHT_LEFT -> "Slight left"
            Maneuver.SLIGHT_RIGHT -> "Slight right"
            Maneuver.SHARP_LEFT -> "Sharp left"
            Maneuver.SHARP_RIGHT -> "Sharp right"
            Maneuver.KEEP_LEFT -> "Keep left"
            Maneuver.KEEP_RIGHT -> "Keep right"
            Maneuver.U_TURN, Maneuver.U_TURN_RIGHT -> "Make a U-turn"
            Maneuver.ROUNDABOUT -> "Take the roundabout"
            Maneuver.ROUNDABOUT_RIGHT -> "At the roundabout, turn right"
            Maneuver.ROUNDABOUT_STRAIGHT -> "At the roundabout, go straight on"
            Maneuver.ROUNDABOUT_LEFT -> "At the roundabout, turn left"
            Maneuver.ROUNDABOUT_BACK -> "At the roundabout, turn back"
            Maneuver.ROUNDABOUT_EXIT -> "Leave the roundabout"
            Maneuver.WAYPOINT -> "Your stop"
            Maneuver.TOLL -> "Toll gate"
            Maneuver.FERRY -> "Take the ferry"
            Maneuver.ARRIVE -> "Arrive"
            Maneuver.STRAIGHT -> "Continue"
            Maneuver.UNKNOWN -> return street
        }
        return when {
            street == null -> verb
            m == Maneuver.ARRIVE || m == Maneuver.WAYPOINT || m == Maneuver.TOLL -> "$verb at $street"
            m.isRoundabout && m != Maneuver.ROUNDABOUT_EXIT -> "$verb toward $street"
            else -> "$verb onto $street"
        }
    }

    /**
     * "300 m", "1.5 km", "١٫٥ كم". The digits and the word edges are spelled out, so
     * Arabic-Indic digits and the Arabic decimal mark (٫) read the same on the car
     * as in the unit tests (Android's `\d` and `\b` are Unicode, the JVM's aren't).
     */
    private val DISTANCE = Regex(
        """(?i)(?<![\p{L}\p{M}\p{N}_])([0-9٠-٩۰-۹]+(?:[.,٫][0-9٠-٩۰-۹]+)?)\s?(m|km|ft|mi|yd|metres|meters|miles|feet|م|كم)(?![\p{L}\p{M}\p{N}_])""",
    )
    /** The step after this one ("Then turn left"): never this step's instruction or arrow. */
    private val THEN = Regex("""(?i)^(then|ثم)(?!\p{L})""")
    private val NOISE = Regex("""(?i)^(waze|google maps|maps|navigation|.*\bis running\b.*|.*\btap to\b.*)$""")
    private val IN_AFTER = Regex("""(?i)\b(in|after|within)\b|بعد|خلال""")
    /** The trip's totals ("12 min · 5.2 km · 8:42 PM"), not the next turn. */
    private val TRIP = Regex("""(?i)\b(min|mins|hr|hrs|eta|arrival)\b|\d{1,2}:\d{2}|دقيقة|دقائق|ساعة""")
    /** The part of an instruction before the street it leads onto. */
    private val LEADS = Regex("""(?i)\s(onto|toward|towards|to|via)\s|\s(باتجاه|إلى|نحو)\s""")

    /** The part of an instruction before the street ("Turn right" of "Turn right onto Right St"). */
    internal fun head(line: String) = line.split(LEADS, limit = 2).first()

    /** Reads a navigation notification. Null when it isn't guiding (e.g. "Waze is running"). */
    internal fun read(context: Context, sbn: StatusBarNotification): NextTurn? {
        val n = sbn.notification
        val e = n.extras
        fun s(key: String) = e.getCharSequence(key)?.toString()
        val turn = parse(
            sbn.packageName,
            listOf(s(Notification.EXTRA_TITLE), s(Notification.EXTRA_TEXT), s(Notification.EXTRA_BIG_TEXT)),
            s(Notification.EXTRA_SUB_TEXT),
        ) ?: return null
        val arrow = runCatching {
            n.getLargeIcon()?.loadDrawable(context)?.let { d ->
                (d as? BitmapDrawable)?.bitmap ?: d.toBitmap(96, 96)
            }
        }.getOrNull()
        return turn.copy(arrow = arrow)
    }

    /**
     * The turn in a navigation notification's text: its title, text and big text
     * ([lines]) and its sub text. Null when it isn't guiding.
     */
    internal fun parse(pkg: String, lines: List<String?>, subText: String?): NextTurn? {
        val sub = subText?.trim()?.takeIf { it.isNotEmpty() }
        val parts = lines.filterNotNull()
            .flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() && !NOISE.matches(it) }.distinct()
        if (parts.isEmpty()) return null
        // This step's lines, without the next step's ("Then turn left").
        val now = parts.filterNot { THEN.containsMatchIn(it) }.ifEmpty { parts }

        // A line that is only a distance ("300 m", "In 300 m").
        fun bare(line: String) = DISTANCE.containsMatchIn(line) &&
            DISTANCE.replace(line, "").replace(IN_AFTER, "").trim(' ', '·', '-', ',').length <= 2
        // The instruction is the line that says what to do.
        val instruction = now.firstOrNull { !bare(it) && !TRIP.containsMatchIn(it) }
            ?: now.firstOrNull { !bare(it) } ?: now.first()
        // The distance to the turn: its own line, then the instruction's; never the trip's total.
        val distanceMatch = now.firstOrNull(::bare)?.let { DISTANCE.find(it) }
            ?: DISTANCE.find(instruction)
            ?: now.firstOrNull { DISTANCE.containsMatchIn(it) && !TRIP.containsMatchIn(it) }?.let { DISTANCE.find(it) }
        // The turn comes from the instruction's own words ("Turn right", not "toward TV Roundabout").
        val maneuver = maneuver(head(instruction)).takeIf { it != Maneuver.UNKNOWN }
            ?: now.asSequence().map { maneuver(head(it)) }.firstOrNull { it != Maneuver.UNKNOWN }
            ?: Maneuver.UNKNOWN
        if (distanceMatch == null && maneuver == Maneuver.UNKNOWN) return null

        val detail = (parts - instruction).firstOrNull { !bare(it) } ?: sub
        // The trip's totals: lines like "12 min · 5.2 km · 8:42 PM" or "Arrive 9:26 PM".
        val trip = (parts.filter { TRIP.containsMatchIn(it) } + listOfNotNull(sub)).joinToString(" · ")
        return NextTurn(
            pkg = pkg,
            instruction = instruction,
            distance = distanceMatch?.value?.trim(),
            detail = detail?.takeIf { it != instruction },
            maneuver = maneuver,
            arrow = null,
            street = street(instruction),
            remainingSeconds = Units.seconds(trip),
            remainingMetres = Units.metres(trip),
            eta = Units.clock(trip),
        )
    }

    /** "Al Corniche" from "Turn right onto Al Corniche", or a line that is only a street. */
    internal fun street(instruction: String): String? {
        val after = instruction.split(LEADS, limit = 2).getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return after ?: instruction.takeIf { maneuver(it) == Maneuver.UNKNOWN && !DISTANCE.containsMatchIn(it) }
    }

    fun maneuver(text: String): Maneuver {
        val t = text.lowercase()
        return when {
            ROUNDABOUT.containsMatchIn(t) -> roundabout(t)
            Regex("""u-?turn|make a u|استدر|التف""").containsMatchIn(t) -> Maneuver.U_TURN
            Regex("""arriv|destination|you have reached|وصلت|الوجهة""").containsMatchIn(t) -> Maneuver.ARRIVE
            Regex("""\bferry\b|عبارة""").containsMatchIn(t) -> Maneuver.FERRY
            Regex("""sharp\w*\s+(to the\s+)?left|حاد(ة)? (إلى|الى|لل)?ي?سار""").containsMatchIn(t) -> Maneuver.SHARP_LEFT
            Regex("""sharp\w*\s+(to the\s+)?right|حاد(ة)? (إلى|الى|لل)?ي?مين""").containsMatchIn(t) -> Maneuver.SHARP_RIGHT
            Regex("""(slight|bear)\w*\s+(to the\s+)?left""").containsMatchIn(t) -> Maneuver.SLIGHT_LEFT
            Regex("""(slight|bear)\w*\s+(to the\s+)?right""").containsMatchIn(t) -> Maneuver.SLIGHT_RIGHT
            // "Take exit 4 on the left" is a left exit; a plain "take the exit" is to the right.
            Regex("""(keep|fork)\w*\s+(to the\s+)?left|\b(exit|ramp)\b[^,.]{0,24}?\bleft\b""").containsMatchIn(t) -> Maneuver.KEEP_LEFT
            Regex("""(keep|fork)\w*\s+(to the\s+)?right|\b(exit|ramp)\b[^,.]{0,24}?\bright\b|take (the )?(exit|ramp)""").containsMatchIn(t) -> Maneuver.KEEP_RIGHT
            Regex("""\bleft\b|يسار""").containsMatchIn(t) -> Maneuver.LEFT
            Regex("""\bright\b|يمين""").containsMatchIn(t) -> Maneuver.RIGHT
            // "Head" alone too: "Head toward X" is cut to "Head" before it's read.
            Regex("""straight|continue|\bhead\b|keep going|مباشرة|استمر""").containsMatchIn(t) -> Maneuver.STRAIGHT
            else -> Maneuver.UNKNOWN
        }
    }

    private val ROUNDABOUT = Regex("""roundabout|rotary|traffic circle|دوار""")

    /**
     * Which way through the roundabout, when the instruction says ("At the
     * roundabout, turn left"). "Take the 2nd exit" doesn't: how many roads leave a
     * roundabout differs, so that stays a plain roundabout.
     */
    private fun roundabout(t: String): Maneuver = when {
        Regex("""\b(exit|leave) the roundabout|(اخرج|غادر) (من )?الدوار""").containsMatchIn(t) -> Maneuver.ROUNDABOUT_EXIT
        Regex("""u-?turn|turn back|full|استدر|التف""").containsMatchIn(t) -> Maneuver.ROUNDABOUT_BACK
        Regex("""(turn|go|head)\s+(to the\s+)?left|\bleft\b(?! lane)|يسار""").containsMatchIn(t.substringBefore(" onto ")) -> Maneuver.ROUNDABOUT_LEFT
        Regex("""(turn|go|head)\s+(to the\s+)?right|\bright\b(?! lane)|يمين""").containsMatchIn(t.substringBefore(" onto ")) -> Maneuver.ROUNDABOUT_RIGHT
        Regex("""straight|continue|مباشرة""").containsMatchIn(t.substringBefore(" onto ")) -> Maneuver.ROUNDABOUT_STRAIGHT
        else -> Maneuver.ROUNDABOUT
    }
}

/**
 * Android hands Otility the notifications it's allowed to see; only the
 * navigation apps' guidance is kept.
 */
class NavListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Directions.listening.value = true
        // The same access shows what the media apps are playing.
        NowPlaying.start(this)
        runCatching { activeNotifications }.getOrNull()?.forEach(::consider)
    }

    override fun onListenerDisconnected() {
        Directions.listening.value = false
        NowPlaying.stop()
        Directions.clear(null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = consider(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Directions.clear(sbn.key)

    private fun consider(sbn: StatusBarNotification) {
        if (!Directions.watches(this, sbn.packageName)) return
        if (!Otility.instance.prefs.settings.value.showDirections) return
        val n = sbn.notification
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        val test = sbn.packageName == "com.android.shell"
        if (!ongoing && !test && n.category != Notification.CATEGORY_NAVIGATION) return
        val turn = runCatching { Directions.read(this, sbn) }.onFailure { Log.w("Otility", "directions", it) }.getOrNull()
        if (turn != null) Directions.update(sbn.key, turn) else Directions.clear(sbn.key)
    }
}
