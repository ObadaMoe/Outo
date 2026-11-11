package app.leopard.cockpit.ui.overview

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.data.WidgetSlot
import app.leopard.cockpit.media.NowPlaying
import app.leopard.cockpit.media.Track
import app.leopard.cockpit.nav.Directions
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.Capsule
import app.leopard.cockpit.ui.GlassKind
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.glass
import app.leopard.cockpit.ui.pressable
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import kotlinx.coroutines.delay

/**
 * What's playing, as on CarPlay's dashboard: the artwork, the song and artist, how
 * far in, and play/pause with skip. The card takes the artwork's colour. Tap the
 * artwork or the song to open the app. One row tall it keeps to a single line.
 */
@Composable
fun NowPlayingCard(slot: WidgetSlot, modifier: Modifier) {
    val otility = Otility.instance
    val nav = LocalNav.current
    val t = LocalTokens.current
    val track by NowPlaying.track.collectAsState()
    val listening by Directions.listening.collectAsState()
    val apps by otility.catalog.apps.collectAsState()
    // The last song stays on the card a little while after the app drops it: the
    // board keeps the card that long in case another song comes (some apps clear
    // the track between songs), and it fades out showing what it showed.
    var held by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(track) {
        if (track != null) held = track
        else {
            delay(3_000)
            held = null
        }
    }
    val tr = track ?: held
    val info = tr?.let { k -> apps.firstOrNull { it.id.pkg == k.pkg } }
    val accent by animateColorAsState(tr?.accent?.let { Color(it) } ?: t.acc, tween(600), label = "mediaAccent")
    val open: () -> Unit = {
        if (tr != null) otility.screens.tap(AppId(tr.pkg), ScreenId.MAIN) { nav.openScreens(it) }
    }
    val shape = rounded(28.dp)

    BoxWithConstraints(
        modifier
            .glass(shape, GlassKind.Widget, tint = lerp(t.card, accent.copy(alpha = t.card.alpha), 0.32f))
            .clip(shape),
    ) {
        // A soft wash of the artwork's colour from behind it.
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = if (t.dark) 0.34f else 0.22f), Color.Transparent),
                    center = Offset.Zero,
                    radius = constraints.maxWidth * 0.9f,
                ),
            ),
        )
        val slim = slot.size.h == 1
        val wide = maxWidth >= 560.dp
        val bigArt = maxHeight - 40.dp
        val title = tr?.title ?: "Not Playing"
        val detail = when {
            tr?.problem != null -> tr.problem
            tr != null -> tr.artist ?: info?.label.orEmpty()
            !listening -> "Needs notification access, in Settings › Navigation"
            else -> "Shows here while something plays"
        }
        when {
            slim -> Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(tr, accent, 88.dp, Modifier.pressable(open))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).pressable(open, scaleDown = 0.98f)) {
                    Txt(title, T.headline, maxLines = 1)
                    Txt(detail, T.subhead, t.text2, maxLines = 1)
                    if (tr?.durationMs != null) {
                        Spacer(Modifier.height(10.dp))
                        Progress(tr, showTimes = false)
                    }
                }
                Spacer(Modifier.width(8.dp))
                PlayButton(tr, 52.dp)
                MediaButton(Icons.next, 46.dp, tr?.canNext == true) { NowPlaying.next() }
            }
            wide -> Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(tr, accent, bigArt, Modifier.pressable(open))
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Heading(tr, info, title, detail, T.title2, open)
                    Spacer(Modifier.weight(1f))
                    Player(tr, Arrangement.Start)
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(tr, accent, 100.dp, Modifier.pressable(open))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) { Heading(tr, info, title, detail, T.headline, open) }
                }
                Spacer(Modifier.weight(1f))
                Player(tr, Arrangement.Center)
            }
        }
    }
}

/** The app it's playing in, the song and the artist. */
@Composable
private fun Heading(tr: Track?, info: AppInfo?, title: String, detail: String, style: androidx.compose.ui.text.TextStyle, open: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.pressable(open, scaleDown = 0.98f)) {
        if (info != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(info, 18.dp)
                Spacer(Modifier.width(6.dp))
                Txt(info.label, T.footnote, t.text2, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
        }
        Txt(title, style, maxLines = 2)
        Txt(detail, T.subhead, t.text2, maxLines = 1)
    }
}

/** How far in, then previous · play/pause · next. */
@Composable
private fun ColumnScope.Player(tr: Track?, arrangement: Arrangement.Horizontal) {
    if (tr?.durationMs != null) {
        Progress(tr, showTimes = true)
        Spacer(Modifier.height(6.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        MediaButton(Icons.previous, 52.dp, tr?.canPrevious == true) { NowPlaying.previous() }
        Spacer(Modifier.width(18.dp))
        PlayButton(tr, 60.dp)
        Spacer(Modifier.width(18.dp))
        MediaButton(Icons.next, 52.dp, tr?.canNext == true) { NowPlaying.next() }
    }
}

@Composable
private fun PlayButton(tr: Track?, size: Dp) {
    MediaButton(if (tr?.playing == true) Icons.pause else Icons.play, size, tr != null) { NowPlaying.playPause() }
}

@Composable
private fun MediaButton(icon: ImageVector, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier.size(size).clip(Capsule).pressable(onClick, enabled = enabled, scaleDown = 0.86f).alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, t.text, size = size * 0.52f) }
}

/** The song's progress bar, with the time gone and left; moves on by itself while playing. */
@Composable
private fun Progress(tr: Track, showTimes: Boolean) {
    val t = LocalTokens.current
    val duration = tr.durationMs ?: return
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(tr.playing, tr.at, tr.positionMs) {
        now = SystemClock.elapsedRealtime()
        while (tr.playing) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    val pos = tr.positionAt(now)
    Column {
        Box(Modifier.fillMaxWidth().height(4.dp).background(t.text.copy(alpha = 0.16f), Capsule)) {
            Box(Modifier.fillMaxWidth((pos.toFloat() / duration).coerceIn(0f, 1f)).fillMaxHeight().background(t.text.copy(alpha = 0.85f), Capsule))
        }
        if (showTimes) {
            Spacer(Modifier.height(4.dp))
            Row {
                Txt(clock(pos), T.caption.copy(fontFeatureSettings = "tnum"), t.text2)
                Spacer(Modifier.weight(1f))
                Txt("-" + clock(duration - pos), T.caption.copy(fontFeatureSettings = "tnum"), t.text2)
            }
        }
    }
}

/** The artwork, or a note on the artwork's colour when the app gives none. */
@Composable
private fun Artwork(tr: Track?, accent: Color, size: Dp, modifier: Modifier = Modifier) {
    val art = remember(tr?.art) { tr?.art?.asImageBitmap() }
    Box(
        modifier
            .size(size)
            .clip(rounded(size * 0.14f))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), lerp(accent, Color.Black, 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Glyph(Icons.music, Color.White.copy(alpha = 0.85f), size = size * 0.4f)
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
