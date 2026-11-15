package app.leopard.cockpit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.NoteText
import app.leopard.cockpit.notes.Ancs
import app.leopard.cockpit.notes.PhoneNote
import app.leopard.cockpit.notes.PhoneNotes
import app.leopard.cockpit.ui.overview.Card
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How an iPhone app looks here: its colour and a symbol for what it does (not its logo). */
private data class Badge(val color: Color, val icon: ImageVector, val ink: Color = Color.White)

private fun badgeOf(appId: String, category: Int): Badge {
    val chat = Icons.chat
    return when (appId) {
        "net.whatsapp.WhatsApp", "net.whatsapp.WhatsAppSMB" -> Badge(Color(0xFF25D366), chat)
        "com.apple.MobileSMS" -> Badge(Color(0xFF34C759), chat)
        "com.apple.mobilephone", "com.apple.facetime" -> Badge(Color(0xFF34C759), Icons.phone)
        "com.burbn.instagram" -> Badge(Color(0xFFD62976), chat)
        "ph.telegra.Telegraph" -> Badge(Color(0xFF2AABEE), chat)
        "com.facebook.Messenger" -> Badge(Color(0xFF0084FF), chat)
        "com.tinyspeck.chatlyio" -> Badge(Color(0xFF4A154B), chat)
        "com.microsoft.skype.teams" -> Badge(Color(0xFF5B5FC7), chat)
        "com.toyopagroup.picaboo" -> Badge(Color(0xFFFFFC00), chat, Color.Black)
        "com.apple.mobilemail", "com.microsoft.Office.Outlook" -> Badge(Color(0xFF1E88E5), Icons.mail)
        "com.google.Gmail" -> Badge(Color(0xFFEA4335), Icons.mail)
        "com.apple.mobilecal", "com.google.calendar" -> Badge(Color(0xFFFF3B30), Icons.calendar)
        else -> when (category) {
            Ancs.SOCIAL -> Badge(Color(0xFF5E5CE6), chat)
            Ancs.EMAIL -> Badge(Color(0xFF1E88E5), Icons.mail)
            Ancs.INCOMING_CALL, Ancs.MISSED_CALL, Ancs.VOICEMAIL -> Badge(Color(0xFF34C759), Icons.phone)
            Ancs.SCHEDULE -> Badge(Color(0xFFFF3B30), Icons.calendar)
            else -> Badge(Color(0xFF8E8E93), Icons.bell)
        }
    }
}

/** Names for apps the phone hasn't named yet. */
private val KNOWN_NAMES = mapOf(
    "net.whatsapp.WhatsApp" to "WhatsApp", "net.whatsapp.WhatsAppSMB" to "WhatsApp Business",
    "com.apple.MobileSMS" to "Messages", "com.apple.mobilephone" to "Phone", "com.apple.facetime" to "FaceTime",
    "com.burbn.instagram" to "Instagram", "ph.telegra.Telegraph" to "Telegram", "com.facebook.Messenger" to "Messenger",
    "com.apple.mobilemail" to "Mail", "com.google.Gmail" to "Gmail", "com.apple.mobilecal" to "Calendar",
)

/** The iPhone app's name, as the phone calls it. */
@Composable
fun noteAppName(appId: String): String {
    val names by PhoneNotes.appNames.collectAsState()
    return names[appId] ?: KNOWN_NAMES[appId] ?: appId.substringAfterLast('.').ifEmpty { "iPhone" }
}

/**
 * Whether a notification shows what it says, or only whose it is: as chosen in Settings
 * (while parked, by default, as CarPlay does). Not knowing the speed counts as moving.
 */
@Composable
fun noteTextShown(): Boolean {
    val app = Otility.instance
    val settings by app.prefs.settings.collectAsState()
    val v by app.vehicle.data.collectAsState()
    return when (settings.noteText) {
        NoteText.ALWAYS -> true
        NoteText.NEVER -> false
        NoteText.PARKED -> v.speedKmh == 0
    }
}

/** The app's colour and symbol, in Apple's rounded square. */
@Composable
fun NoteBadge(appId: String, category: Int, size: Dp) {
    val b = remember(appId, category) { badgeOf(appId, category) }
    Box(Modifier.size(size).background(b.color, rounded(size * 0.225f)), contentAlignment = Alignment.Center) {
        Glyph(b.icon, b.ink, size = size * 0.56f)
    }
}

/** "now", "5m", "2h", then the time. */
private fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - at) / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        m < 6 * 60 -> "${m / 60}h"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }
}

/** Who it's from, then what it says (when it shows), in [lines] lines at most. */
@Composable
private fun NoteLines(note: PhoneNote, showText: Boolean, lines: Int, titleStyle: androidx.compose.ui.text.TextStyle = T.headline) {
    val t = LocalTokens.current
    val title = note.title.ifBlank { note.subtitle }.ifBlank { noteAppName(note.appId) }
    Txt(title, titleStyle, maxLines = 1)
    val text = listOf(note.subtitle.takeIf { it != title }, note.message).filterNot { it.isNullOrBlank() }.joinToString(" · ")
    if (showText && text.isNotBlank()) Txt(text, T.subhead, t.text2, maxLines = lines)
}

/**
 * A new iPhone notification, as a banner from the top, anywhere in Outo, for a few
 * seconds: the app, who it's from and (when it shows) what it says. Swipe it up or tap
 * it to put it away; it stays on Home's card.
 */
@Composable
fun NoteBanner(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val note by PhoneNotes.banner.collectAsState()
    val showText = noteTextShown()
    // The last one, so the banner still has something to slide away with.
    var last by remember { mutableStateOf<PhoneNote?>(null) }
    if (note != null) last = note
    LaunchedEffect(note) {
        val n = note ?: return@LaunchedEffect
        delay(BANNER_MS)
        PhoneNotes.bannerDone(n)
    }
    AnimatedVisibility(
        visible = note != null,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(spring(0.78f, 380f)) { -it - 40 },
        exit = fadeOut(tween(220)) + slideOutVertically(tween(260)) { -it - 40 },
    ) {
        val n = note ?: last ?: return@AnimatedVisibility
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        Row(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .graphicsLayer { translationY = drag.value.coerceAtMost(0f) + drag.value.coerceAtLeast(0f) * 0.2f }
                .pointerInput(n.uid) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (drag.value < -40f) PhoneNotes.bannerDone(n)
                            else scope.launch { drag.animateTo(0f, spring(0.8f, 500f)) }
                        },
                    ) { change, dy ->
                        change.consume()
                        scope.launch { drag.snapTo(drag.value + dy) }
                    }
                }
                .pointerInput(n.uid) { detectTapGestures { PhoneNotes.bannerDone(n) } }
                // Nearly solid, as CarPlay's: over a live app (the map, say) glass can't blur.
                .glass(rounded(24.dp), GlassKind.Bar, tint = t.solidGlass.copy(alpha = 0.96f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteBadge(n.appId, n.category, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(noteAppName(n.appId).uppercase(), T.caption.copy(fontWeight = FontWeight.SemiBold), t.text2, Modifier.weight(1f), maxLines = 1)
                    Txt(ago(n.at), T.caption, t.text2, maxLines = 1)
                }
                Spacer(Modifier.height(2.dp))
                NoteLines(n, showText, lines = 2)
            }
        }
    }
}

/** How long a banner stays. */
private const val BANNER_MS = 6_000L

/**
 * CarPlay Home's notifications from the iPhone, the latest first. Each can be cleared
 * (from the iPhone too, where it allows that), or all of them from here.
 */
@Composable
fun NotificationsCard(modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val notes by PhoneNotes.list.collectAsState()
    val showText = noteTextShown()
    // Their times move on.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Card(
        "Notifications", Icons.bell, modifier,
        trailing = {
            Txt("Clear", T.subhead.copy(fontWeight = FontWeight.SemiBold), t.accText, Modifier.pressable({ PhoneNotes.clear() }), maxLines = 1)
        },
    ) {
        // In a stack the driver chose it for, with none yet: say so.
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Txt("No notifications from your iPhone", T.subhead, t.text2, maxLines = 2)
            }
            return@Card
        }
        // A new one: back up to it.
        val list = rememberLazyListState()
        LaunchedEffect(notes.firstOrNull()?.uid) { list.animateScrollToItem(0) }
        LazyColumn(Modifier.clipToBounds(), list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(notes, key = { it.uid }) { n ->
                Row(Modifier.fillMaxWidth().animateItem(), verticalAlignment = Alignment.Top) {
                    NoteBadge(n.appId, n.category, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(noteAppName(n.appId), T.caption, t.text2, Modifier.weight(1f), maxLines = 1)
                            Txt(ago(n.at, now), T.caption, t.text2, maxLines = 1)
                        }
                        NoteLines(n, showText, lines = 2, titleStyle = T.callout.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.size(28.dp).pressable({ PhoneNotes.dismiss(n) }, scaleDown = 0.85f),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(Icons.close, t.text2, size = 14.dp) }
                }
            }
        }
    }
}
