package app.leopard.cockpit.notes

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Apple's Notification Center Service (ANCS): how a Bluetooth accessory reads an
 * iPhone's notifications, every app's, with no app on the phone. The iPhone is the
 * GATT server; the accessory (Outo) subscribes to [NOTIFICATION_SOURCE], which tells it
 * a notification came, changed or went, and asks for what one says through
 * [CONTROL_POINT], the answer coming back on [DATA_SOURCE], over as many packets as it
 * takes. Only the protocol lives here; the Bluetooth is AncsLink's.
 */
object Ancs {
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    /** The descriptor a client writes to be told of changes. */
    val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Event IDs.
    const val ADDED = 0
    const val MODIFIED = 1
    const val REMOVED = 2

    // Event flags.
    const val SILENT = 1
    const val IMPORTANT = 2
    const val PRE_EXISTING = 4
    const val POSITIVE_ACTION = 8
    const val NEGATIVE_ACTION = 16

    // Categories.
    const val OTHER = 0
    const val INCOMING_CALL = 1
    const val MISSED_CALL = 2
    const val VOICEMAIL = 3
    const val SOCIAL = 4
    const val SCHEDULE = 5
    const val EMAIL = 6
    const val NEWS = 7

    // Commands.
    const val GET_NOTIFICATION_ATTRIBUTES = 0
    const val GET_APP_ATTRIBUTES = 1
    const val PERFORM_ACTION = 2

    // Notification attributes.
    const val APP_IDENTIFIER = 0
    const val TITLE = 1
    const val SUBTITLE = 2
    const val MESSAGE = 3
    const val DATE = 5

    /** The app attribute: its name, as the iPhone shows it. */
    const val DISPLAY_NAME = 0

    /** Actions on a notification. */
    const val ACTION_POSITIVE = 0
    const val ACTION_NEGATIVE = 1

    /** How long a title, subtitle and message may be, in bytes. */
    const val TITLE_MAX = 96
    const val MESSAGE_MAX = 400

    /** What the notifications asked for are, in order (each answer carries them so). */
    val NOTE_ATTRIBUTES = listOf(APP_IDENTIFIER, TITLE, SUBTITLE, MESSAGE, DATE)

    /** A packet on the Notification Source. */
    data class Event(val id: Int, val flags: Int, val category: Int, val count: Int, val uid: Int) {
        val silent get() = flags and SILENT != 0
        val important get() = flags and IMPORTANT != 0
        val preExisting get() = flags and PRE_EXISTING != 0
        val canDismiss get() = flags and NEGATIVE_ACTION != 0
    }

    /** The Notification Source's 8 bytes, or null if they aren't that. */
    fun event(b: ByteArray): Event? {
        if (b.size < 8) return null
        return Event(b[0].u(), b[1].u(), b[2].u(), b[3].u(), le32(b, 4))
    }

    /** Asks what notification [uid] says: its app, title, subtitle, message and time. */
    fun noteCommand(uid: Int): ByteArray = ByteArrayOutputStream().apply {
        write(GET_NOTIFICATION_ATTRIBUTES)
        write32(uid)
        write(APP_IDENTIFIER)
        write(TITLE); write16(TITLE_MAX)
        write(SUBTITLE); write16(TITLE_MAX)
        write(MESSAGE); write16(MESSAGE_MAX)
        write(DATE)
    }.toByteArray()

    /** Asks the name of the app [appId] (an iPhone bundle ID, say net.whatsapp.WhatsApp). */
    fun appCommand(appId: String): ByteArray = ByteArrayOutputStream().apply {
        write(GET_APP_ATTRIBUTES)
        val id = appId.toByteArray(Charsets.UTF_8)
        write(id, 0, id.size)
        write(0)
        write(DISPLAY_NAME)
    }.toByteArray()

    /** Answers notification [uid] on the iPhone: [ACTION_NEGATIVE] clears it (or declines a call). */
    fun actionCommand(uid: Int, action: Int): ByteArray = ByteArrayOutputStream().apply {
        write(PERFORM_ACTION)
        write32(uid)
        write(action)
    }.toByteArray()

    /** What a command asked for, so its answer can be told complete. */
    sealed interface Asked {
        data class Note(val uid: Int, val attributes: List<Int> = NOTE_ATTRIBUTES) : Asked
        data class App(val appId: String, val attributes: List<Int> = listOf(DISPLAY_NAME)) : Asked
    }

    /** A whole answer on the Data Source. */
    sealed interface Answer {
        data class Note(val uid: Int, val values: Map<Int, String>) : Answer
        data class App(val appId: String, val values: Map<Int, String>) : Answer
    }

    /**
     * Puts the Data Source's packets back together. The iPhone answers one command at a
     * time, as asked (see [expect]); an answer may take several packets, and the client
     * knows it's whole once every attribute asked for has come.
     */
    class Answers {
        private val buf = ByteArrayOutputStream()
        private var asked: Asked? = null

        /** The next packets answer [a]. */
        fun expect(a: Asked) {
            asked = a
            buf.reset()
        }

        /** Nothing asked any more (a timeout, a new connection). */
        fun reset() {
            asked = null
            buf.reset()
        }

        val waiting get() = asked != null

        /** One packet; the answer once it's whole (null until then, or for packets nobody asked for). */
        fun feed(packet: ByteArray): Answer? {
            val a = asked ?: return null
            buf.write(packet, 0, packet.size)
            val b = buf.toByteArray()
            val answer = parse(a, b) ?: return null
            reset()
            return answer
        }

        private fun parse(a: Asked, b: ByteArray): Answer? = when (a) {
            is Asked.Note -> {
                if (b.size < 5 || b[0].u() != GET_NOTIFICATION_ATTRIBUTES) null
                else attributes(b, 5, a.attributes.size)?.let { Answer.Note(le32(b, 1), it) }
            }
            is Asked.App -> {
                if (b.isEmpty() || b[0].u() != GET_APP_ATTRIBUTES) null
                else {
                    val end = (1 until b.size).firstOrNull { b[it].toInt() == 0 }
                    if (end == null) null
                    else attributes(b, end + 1, a.attributes.size)
                        ?.let { Answer.App(String(b, 1, end - 1, Charsets.UTF_8), it) }
                }
            }
        }

        /** [count] attributes (ID, 2-byte length, value) from [at]; null until they're all in. */
        private fun attributes(b: ByteArray, at: Int, count: Int): Map<Int, String>? {
            var i = at
            val out = LinkedHashMap<Int, String>()
            repeat(count) {
                if (i + 3 > b.size) return null
                val id = b[i].u()
                val len = b[i + 1].u() or (b[i + 2].u() shl 8)
                if (i + 3 + len > b.size) return null
                out[id] = String(b, i + 3, len, Charsets.UTF_8)
                i += 3 + len
            }
            return out
        }
    }

    /** The Date attribute (yyyyMMdd'T'HHmmSS, the phone's local time) in milliseconds, or null. */
    fun date(s: String?): Long? {
        if (s == null || s.length < 15) return null
        return runCatching {
            java.time.LocalDateTime.parse(s.take(15), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun Byte.u() = toInt() and 0xFF
    private fun le32(b: ByteArray, at: Int) =
        b[at].u() or (b[at + 1].u() shl 8) or (b[at + 2].u() shl 16) or (b[at + 3].u() shl 24)
    private fun ByteArrayOutputStream.write16(v: Int) { write(v and 0xFF); write(v shr 8 and 0xFF) }
    private fun ByteArrayOutputStream.write32(v: Int) {
        write(v and 0xFF); write(v shr 8 and 0xFF); write(v shr 16 and 0xFF); write(v shr 24 and 0xFF)
    }
}
