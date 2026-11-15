package app.leopard.cockpit.notes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** One of the iPhone's notifications, as Outo shows it. */
data class PhoneNote(
    val uid: Int,
    /** The iPhone app's bundle ID (net.whatsapp.WhatsApp, say). */
    val appId: String,
    val title: String,
    val subtitle: String = "",
    val message: String = "",
    val category: Int = Ancs.OTHER,
    /** When it came, in milliseconds. */
    val at: Long = System.currentTimeMillis(),
    val important: Boolean = false,
    /** The iPhone lets it be cleared from here. */
    val canDismiss: Boolean = false,
)

/**
 * The iPhone's notifications on hand: the latest first, for CarPlay Home's card, and the
 * newest one, for a moment, as a banner from the top anywhere in Outo. Those already on
 * the phone when it connects join the list quietly; silent ones too.
 */
object PhoneNotes {
    /** How many are kept. */
    private const val KEEP = 30

    private val _list = MutableStateFlow<List<PhoneNote>>(emptyList())
    val list: StateFlow<List<PhoneNote>> = _list.asStateFlow()

    private val _banner = MutableStateFlow<PhoneNote?>(null)
    /** The one to show as a banner now (null: none). */
    val banner: StateFlow<PhoneNote?> = _banner.asStateFlow()

    /** The iPhone apps' names, by bundle ID, as the phone gave them. */
    private val names = ConcurrentHashMap<String, String>()
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val appNames: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** Clears one on the iPhone too, where it allows that (set by AncsLink while connected). */
    @Volatile var dismissOnPhone: ((Int) -> Unit)? = null

    /** A notification come or changed; [loud]: a new one, shown as a banner. */
    fun put(note: PhoneNote, loud: Boolean) {
        _list.update { old -> (listOf(note) + old.filter { it.uid != note.uid }).sortedByDescending { it.at }.take(KEEP) }
        if (loud) _banner.value = note
        else if (_banner.value?.uid == note.uid) _banner.value = note
    }

    /** Gone from the iPhone (read there, cleared). */
    fun remove(uid: Int) {
        _list.update { old -> old.filter { it.uid != uid } }
        if (_banner.value?.uid == uid) _banner.value = null
    }

    /** Cleared here: from the list, and from the iPhone where it allows that. */
    fun dismiss(note: PhoneNote) {
        if (note.canDismiss) dismissOnPhone?.invoke(note.uid)
        remove(note.uid)
    }

    /** All of them off the list (the phone keeps its own). */
    fun clear() {
        _list.value = emptyList()
        _banner.value = null
    }

    fun bannerDone(note: PhoneNote) {
        if (_banner.value?.uid == note.uid) _banner.value = null
    }

    fun nameOf(appId: String): String? = names[appId]

    fun setName(appId: String, name: String) {
        if (name.isBlank() || names[appId] == name) return
        names[appId] = name
        _names.value = HashMap(names)
    }

    /** The phone disconnected: what it had can't be cleared from here any more. */
    fun phoneGone() {
        dismissOnPhone = null
        _list.update { old -> old.map { it.copy(canDismiss = false) } }
    }
}
