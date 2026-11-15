package app.leopard.cockpit.ui.overview

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import app.leopard.cockpit.ui.carplay.Origin
import app.leopard.cockpit.ui.carplay.cover
import app.leopard.cockpit.ui.screens.HostSurface
import kotlinx.coroutines.flow.MutableStateFlow
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
import app.leopard.cockpit.data.Tab
import app.leopard.cockpit.data.UiMode
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
import app.leopard.cockpit.ui.openZoomed
import app.leopard.cockpit.ui.GlassIconButton
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.glass
import app.leopard.cockpit.ui.pressable
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import kotlinx.coroutines.delay

/**
 * The card shows the video itself, not the song (see [NowPlayingCard]). Kept while Outo
 * runs, so the card comes back to it.
 */
private val videoShown = MutableStateFlow(false)

/**
 * The video in the card: the app on a landscape display the size of a phone's, where a
 * video app's player fills its screen, with none of the app around it.
 */
private const val VIDEO_W = 960
private const val VIDEO_H = 540
private const val VIDEO_DPI = VIDEO_H * 160 / 400

/**
 * What's playing, as on CarPlay's dashboard: the artwork, the song and artist, how
 * far in, and play/pause with skip. The card takes the artwork's colour. Tap the
 * artwork or the song to open the app. One row tall it keeps to a single line.
 *
 * A video (YouTube, say): a button at the top right turns the card to the video itself,
 * with its own buttons (back, play/pause, next, stop), one to turn it back to the song,
 * and one to open the app full. One row tall, that button opens the app.
 */
@Composable
fun NowPlayingCard(slot: WidgetSlot, modifier: Modifier) {
    val otility = Otility.instance
    val nav = LocalNav.current
    val t = LocalTokens.current
    val track by NowPlaying.track.collectAsState()
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
    val shape = rounded(28.dp)
    val density = LocalDensity.current
    var cardAt by remember { mutableStateOf(Rect.Zero) }
    val settings by otility.prefs.settings.collectAsState()
    // CarPlay mode: the full player, growing out of this card (its sources too, so it
    // opens with nothing playing). Screen mode: the app, on the main screen.
    val open: () -> Unit = {
        if (settings.mode == UiMode.CARPLAY) {
            if (cardAt.width > 0f) nav.nowPlayingOrigin = Origin(cardAt, with(density) { 28.dp.toPx() }, Origin.Kind.CARD)
            nav.tab = Tab.NOW_PLAYING
        } else if (tr != null) otility.screens.tap(AppId(tr.pkg), ScreenId.MAIN) { nav.openScreens(it) }
    }
    val state by otility.screens.state.collectAsState()
    val pip by otility.screens.pip.collectAsState()
    // The app full on the main screen, growing out of this card.
    val expand: () -> Unit = {
        if (tr != null) {
            val app = AppId(tr.pkg)
            nav.openZoomed(app)
            if (cardAt.width > 0f) nav.launchOrigins[app] = Origin(cardAt, with(density) { 28.dp.toPx() }, Origin.Kind.CARD)
        }
    }
    val showVideo by videoShown.collectAsState()
    // Not while it's on another screen or floating: the card would take its picture.
    val videoHere = tr?.video == true && pip?.pkg != tr.pkg &&
        state.screenOf(AppId(tr.pkg)).let { it == null || it == ScreenId.MAIN }

    BoxWithConstraints(
        modifier
            .onGloballyPositioned { cardAt = it.boundsInRoot(); nav.nowPlayingCard = cardAt }
            .glass(shape, GlassKind.Widget, tint = lerp(t.card, accent.copy(alpha = t.card.alpha), 0.32f))
            .clip(shape),
    ) {
        val slim = slot.size.h == 1
        // A video: the button turns the card to it (one row tall, there's no room: the app opens).
        val watch: (() -> Unit)? = if (tr?.video == true) ({ if (slim || !videoHere) expand() else videoShown.value = true }) else null
        val video = showVideo && videoHere && !slim && tr != null
        AnimatedContent(
            video,
            transitionSpec = {
                ContentTransform(
                    fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.97f),
                    fadeOut(tween(200)),
                    sizeTransform = null,
                )
            },
            label = "nowPlayingView",
        ) { v ->
            if (v && tr != null) VideoView(tr, info, accent, onSong = { videoShown.value = false }, onExpand = expand)
            else Box(Modifier.fillMaxSize()) { SongView(tr, info, accent, slim, watch, open) }
        }
    }
}

/** The song: the artwork, the names and the player, laid out for the card's size. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SongView(
    tr: Track?, info: AppInfo?, accent: Color, slim: Boolean, watch: (() -> Unit)?, open: () -> Unit,
) {
    val t = LocalTokens.current
    val listening by Directions.listening.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
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
        val wide = maxWidth >= 560.dp
        val bigArt = maxHeight - 40.dp
        // Narrow: the artwork grows into whatever the player below leaves, up to about
        // two fifths of the width, so a tall card isn't left half empty.
        val player = 60.dp + if (tr?.durationMs != null) 34.dp else 0.dp
        val art = maxOf(100.dp, minOf(maxHeight - 40.dp - player - 20.dp, (maxWidth - 40.dp) * 0.42f))
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
                // One row: the video button with the others, not over the name.
                if (watch != null) MediaButton(Icons.video, 46.dp, true, onClick = watch)
            }
            wide -> Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(tr, accent, bigArt, Modifier.pressable(open))
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Heading(tr, info, title, detail, T.title2, open, Modifier.padding(end = if (watch != null) WatchRoom else 0.dp))
                    Spacer(Modifier.weight(1f))
                    Player(tr, Arrangement.Start)
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = if (art > 120.dp) Alignment.Top else Alignment.CenterVertically) {
                    Artwork(tr, accent, art, Modifier.pressable(open))
                    Spacer(Modifier.width(if (art > 120.dp) 20.dp else 16.dp))
                    Column(Modifier.weight(1f).padding(end = if (watch != null) WatchRoom else 0.dp)) {
                        Heading(tr, info, title, detail, T.headline, open, titleLines = if (art > 120.dp) 3 else 2)
                    }
                }
                Spacer(Modifier.weight(1f))
                Player(tr, Arrangement.Center)
            }
        }
        if (watch != null && !slim) {
            GlassIconButton(Icons.video, watch, Modifier.align(Alignment.TopEnd).padding(14.dp), size = 44.dp)
        }
    }
}

/**
 * The video itself, filling the card. Its buttons show over it on a tap and go a few
 * seconds after, while it plays: back, play/pause, next and stop along the bottom; at
 * the top right, back to the song and the app full.
 */
@Composable
private fun VideoView(tr: Track, info: AppInfo?, accent: Color, onSong: () -> Unit, onExpand: () -> Unit) {
    val otility = Otility.instance
    val app = AppId(tr.pkg)
    var controls by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controls, touchedAt, tr.playing) {
        if (controls && tr.playing) {
            delay(4_000)
            controls = false
        }
    }
    val shown by animateFloatAsState(if (controls) 1f else 0f, tween(if (controls) 160 else 320), label = "videoControls")
    val touch = { touchedAt = SystemClock.uptimeMillis() }
    // Gone from the card and on no screen: put away as an app left in the background is
    // (closed a while later, unless it's still playing).
    DisposableEffect(app) {
        onDispose {
            val s = otility.screens
            if (s.state.value.screenOf(app) == null && s.pip.value != app) s.hosts.park(app)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { controls = !controls; touch() } },
    ) {
        // Until the video's first picture: its artwork, dimmed, as a poster.
        Artwork(tr, accent, 0.dp, Modifier.fillMaxSize().graphicsLayer { alpha = 0.5f }, fill = true)
        HostSurface(
            app, info?.component?.flattenToShortString() ?: app.pkg, VIDEO_W, VIDEO_H,
            Modifier.fillMaxSize().cover(VIDEO_W, VIDEO_H), dpi = VIDEO_DPI,
        )
        // Paused, the app draws its own controls over the picture: the poster covers them,
        // until a moment after it plays again (by when the app has put them away).
        var covered by remember { mutableStateOf(!tr.playing) }
        LaunchedEffect(tr.playing) {
            if (tr.playing) {
                delay(UNCOVER_MS)
                covered = false
            } else covered = true
        }
        val cover by animateFloatAsState(if (covered) 1f else 0f, tween(if (covered) 200 else 450), label = "videoCover")
        if (cover > 0.01f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(Color.Black)) {
                Artwork(tr, accent, 0.dp, Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f }, fill = true)
            }
        }
        if (shown > 0.01f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown }) {
                // Shades at the top and bottom, so the buttons read on any picture.
                Box(
                    Modifier.fillMaxWidth().height(96.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
                )
                Box(
                    Modifier.fillMaxWidth().height(130.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
                )
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (info != null) {
                        AppIcon(info, 22.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Txt(tr.title ?: info?.label.orEmpty(), T.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    // Back to the song, and the app full.
                    GlassIconButton(Icons.music, { touch(); onSong() }, size = 40.dp)
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(Icons.expand, { touch(); onExpand() }, size = 40.dp)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    if (tr.durationMs != null) Progress(tr, showTimes = false, tint = Color.White)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        MediaButton(Icons.previous, 46.dp, tr.canPrevious, Color.White) { touch(); NowPlaying.previous() }
                        Spacer(Modifier.width(14.dp))
                        MediaButton(if (tr.playing) Icons.pause else Icons.play, 54.dp, true, Color.White) { touch(); NowPlaying.playPause() }
                        Spacer(Modifier.width(14.dp))
                        MediaButton(Icons.next, 46.dp, tr.canNext, Color.White) { touch(); NowPlaying.next() }
                        Spacer(Modifier.width(14.dp))
                        MediaButton(Icons.stop, 46.dp, true, Color.White) { touch(); NowPlaying.stopPlaying() }
                    }
                }
            }
        }
    }
}

/** How long after playing again the poster stays over the video (the app's controls are going). */
private const val UNCOVER_MS = 2_500L

/** Room the video button takes at the card's top right, kept clear of the names. */
private val WatchRoom = 52.dp

/** The app it's playing in, the song and the artist. */
@Composable
private fun Heading(tr: Track?, info: AppInfo?, title: String, detail: String, style: androidx.compose.ui.text.TextStyle, open: () -> Unit, modifier: Modifier = Modifier, titleLines: Int = 2) {
    val t = LocalTokens.current
    Column(modifier.pressable(open, scaleDown = 0.98f)) {
        if (info != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(info, 18.dp)
                Spacer(Modifier.width(6.dp))
                Txt(info.label, T.footnote, t.text2, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
        }
        Txt(title, style, maxLines = titleLines)
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
private fun MediaButton(icon: ImageVector, size: Dp, enabled: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier.size(size).clip(Capsule).pressable(onClick, enabled = enabled, scaleDown = 0.86f).alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, tint ?: t.text, size = size * 0.52f) }
}

/** The song's progress bar, with the time gone and left; moves on by itself while playing. */
@Composable
private fun Progress(tr: Track, showTimes: Boolean, tint: Color? = null) {
    val t = LocalTokens.current
    val ink = tint ?: t.text
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
        Box(Modifier.fillMaxWidth().height(4.dp).background(ink.copy(alpha = 0.2f), Capsule)) {
            Box(Modifier.fillMaxWidth((pos.toFloat() / duration).coerceIn(0f, 1f)).fillMaxHeight().background(ink.copy(alpha = 0.9f), Capsule))
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
internal fun Artwork(tr: Track?, accent: Color, size: Dp, modifier: Modifier = Modifier, fill: Boolean = false) {
    val art = remember(tr?.art) { tr?.art?.asImageBitmap() }
    Box(
        modifier
            .then(if (fill) Modifier else Modifier.size(size).clip(rounded(size * 0.14f)))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), lerp(accent, Color.Black, 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Glyph(Icons.music, Color.White.copy(alpha = 0.85f), size = if (fill) 48.dp else size * 0.4f)
    }
}

internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
