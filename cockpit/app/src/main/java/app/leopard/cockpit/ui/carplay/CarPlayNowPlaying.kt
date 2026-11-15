package app.leopard.cockpit.ui.carplay

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.data.Tab
import app.leopard.cockpit.media.NowPlaying
import app.leopard.cockpit.media.Track
import app.leopard.cockpit.media.VIDEO_APPS
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.Capsule
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.SidebarMargin
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.openZoomed
import app.leopard.cockpit.ui.overview.Artwork
import app.leopard.cockpit.ui.overview.clock
import app.leopard.cockpit.ui.pressable
import app.leopard.cockpit.ui.screens.at
import app.leopard.cockpit.ui.screens.smoothstep
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.OtilityTheme
import app.leopard.cockpit.ui.theme.T
import app.leopard.cockpit.ui.theme.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The player's window, under the artwork's blur. */
private val PlayerFace = Color(0xFF121317)

/**
 * CarPlay mode's Now Playing, full: the artwork big, the song and artist, a bar to drag to
 * another place in the song, large buttons, and along the bottom the media apps to play from
 * (YouTube Music, Spotify, the radio), one tap each. It opens in a window beside the sidebar,
 * growing out of Home's Now Playing card, and shrinks back into it. On the artwork's colours,
 * blurred, as CarPlay's Now Playing is.
 *
 * [grown]: 0 at the card, 1 open; [leaving]: on the way out.
 */
@Composable
fun CarPlayNowPlaying(grown: () -> Float, leaving: Boolean) {
    val app = Otility.instance
    val nav = LocalNav.current
    val density = LocalDensity.current
    val settings by app.prefs.settings.collectAsState()
    val dark = remember(settings.accent, settings.reduceTransparency, settings.tintedGlass) {
        tokens(true, settings.accent, settings.reduceTransparency, settings.tintedGlass)
    }
    val enter = remember { nav.nowPlayingOrigin?.takeIf { it.fresh }.also { nav.nowPlayingOrigin = null } }
    val inWindow = remember { arrayOf(Offset.Zero) }
    OtilityTheme(dark) {
        Box(Modifier.fillMaxSize()) {
            // Home's black, coming in with the window (Home fades behind it).
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = grown().coerceIn(0f, 1f) }.background(Color.Black))
            BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { inWindow[0] = it.positionInRoot() }) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                fun px(d: Dp) = with(density) { d.toPx() }
                // Where an app's window is: right of the sidebar, as tall as it, between the car's bars.
                val safe = WindowInsets.safeDrawing
                val margin = px(SidebarMargin)
                val area = Rect(
                    safe.getLeft(density, LayoutDirection.Ltr) + px(ContentStart),
                    safe.getTop(density) + margin,
                    w - safe.getRight(density, LayoutDirection.Ltr) - margin,
                    h - safe.getBottom(density) - margin,
                )
                val corner = px(WindowCorner)
                fun local(r: Rect) = r.translate(-inWindow[0].x, -inWindow[0].y)
                // Back into the card on Home's dashboard; else fading out where it is.
                val origin = when {
                    leaving && nav.tab == Tab.OVERVIEW && nav.homePage == 0 && nav.nowPlayingCard.width > 0f ->
                        Origin(local(nav.nowPlayingCard), px(28.dp), Origin.Kind.CARD)
                    leaving -> Origin.fade(area, corner, 0.92f)
                    else -> enter?.let { it.copy(rect = local(it.rect)) } ?: Origin.fade(area, corner)
                }
                val aw = area.width.roundToInt().coerceAtLeast(1)
                val ah = area.height.roundToInt().coerceAtLeast(1)
                Box(
                    Modifier
                        .at { lerp(origin.rect, area, grown()) }
                        .graphicsLayer {
                            val p = grown().coerceIn(0f, 1f)
                            alpha = if (origin.kind == Origin.Kind.FADE) p else 1f
                            shape = RoundedCornerShape(origin.corner + (corner - origin.corner) * p)
                            clip = true
                        }
                        .background(PlayerFace),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (origin.kind == Origin.Kind.CARD) smoothstep(0.15f, 0.7f, grown()) else 1f },
                    ) {
                        // Laid out at the window's size and scaled with it as it grows or shrinks.
                        Box(Modifier.cover(aw, ah)) { Player() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Player() {
    val otility = Otility.instance
    val nav = LocalNav.current
    val t = LocalTokens.current
    val context = LocalContext.current
    val track by NowPlaying.track.collectAsState()
    val apps by otility.catalog.apps.collectAsState()
    val tr = track
    val info = tr?.let { k -> apps.firstOrNull { it.id.pkg == k.pkg } }
    val accent by animateColorAsState(tr?.accent?.let { Color(it) } ?: t.acc, tween(600), label = "playerAccent")

    // The apps to play from: those playing or paused now first, then those that play when
    // asked (found once, off the main thread).
    val sessions by NowPlaying.sessions.collectAsState()
    var askable by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { askable = withContext(Dispatchers.IO) { NowPlaying.mediaApps(context) } }
    val sources = remember(apps, sessions, askable, tr?.pkg) {
        val byPkg = apps.associateBy { it.id.pkg }
        val now = (listOfNotNull(tr?.pkg) + sessions).distinct()
        // Video apps only while they have something: they're for watching, not for playing from here.
        val more = askable.filter { it !in now && it !in VIDEO_APPS && it !in NOT_SOURCES }.mapNotNull { byPkg[it] }.sortedBy { it.label.lowercase() }
        (now.mapNotNull { byPkg[it] } + more).take(MAX_SOURCES)
    }
    // An app asked to play: its button shows it's starting until it plays; if it doesn't
    // (nothing to resume), the app opens, to choose something there.
    var starting by remember { mutableStateOf<String?>(null) }
    val here = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) { onDispose { here[0] = false } }
    LaunchedEffect(tr?.pkg, tr?.playing) { if (tr != null && tr.pkg == starting && tr.playing) starting = null }
    val playFrom: (AppInfo) -> Unit = { a ->
        val pkg = a.id.pkg
        if (tr?.pkg == pkg) {
            if (!tr.playing) NowPlaying.playPause()
        } else {
            starting = pkg
            NowPlaying.playFrom(pkg) {
                if (here[0] && starting == pkg) {
                    starting = null
                    nav.openZoomed(AppId(pkg))
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(tr, accent)
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 22.dp)) {
            val showSources = sources.isNotEmpty()
            val sourcesH = if (showSources) 92.dp else 0.dp
            val topH = 52.dp
            // The artwork as big as the room between the top row and the sources allows.
            val art = minOf(maxHeight - topH - sourcesH - 24.dp, maxWidth * 0.42f).coerceAtLeast(120.dp)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(topH), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.chevronDown, 48.dp) { nav.tab = Tab.OVERVIEW }
                    Spacer(Modifier.width(14.dp))
                    if (info != null) {
                        AppIcon(info, 26.dp)
                        Spacer(Modifier.width(10.dp))
                        Txt(info.label, T.headline, Color.White.copy(alpha = 0.85f), maxLines = 1, modifier = Modifier.weight(1f))
                    } else Spacer(Modifier.weight(1f))
                    if (tr != null) {
                        Row(
                            Modifier
                                .height(44.dp)
                                .background(Color.White.copy(alpha = 0.14f), Capsule)
                                .pressable({ nav.openZoomed(AppId(tr.pkg)) })
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph(Icons.expand, Color.White, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Txt(if (tr.video) "Watch in App" else "Open App", T.subhead.copy(fontWeight = FontWeight.SemiBold), Color.White, maxLines = 1)
                        }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(tr, accent, art, Modifier.graphicsLayer {
                        // A touch smaller while paused, as Apple's.
                        val s = if (tr?.playing == true) 1f else 0.94f
                        scaleX = s; scaleY = s
                    })
                    Spacer(Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(tr?.title ?: "Not Playing", T.largeTitle, Color.White, maxLines = 2)
                        Spacer(Modifier.height(4.dp))
                        Txt(
                            when {
                                tr?.problem != null -> tr.problem
                                tr != null -> tr.artist ?: info?.label.orEmpty()
                                showSources -> "Choose an app below to play from"
                                else -> "Plays here when a media app plays"
                            },
                            T.title2, Color.White.copy(alpha = 0.65f), maxLines = 1,
                        )
                        Spacer(Modifier.height(28.dp))
                        if (tr != null) SeekBar(tr)
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            BigButton(Icons.previous, 76.dp, tr?.canPrevious == true) { NowPlaying.previous() }
                            Spacer(Modifier.width(36.dp))
                            PlayButton(tr)
                            Spacer(Modifier.width(36.dp))
                            BigButton(Icons.next, 76.dp, tr?.canNext == true) { NowPlaying.next() }
                        }
                    }
                }
                if (showSources) {
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        Modifier.fillMaxWidth().height(sourcesH - 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(sources, key = { it.id.pkg }) { a ->
                            SourceButton(a, current = tr?.pkg == a.id.pkg, playing = tr?.pkg == a.id.pkg && tr.playing, starting = starting == a.id.pkg) { playFrom(a) }
                        }
                    }
                }
            }
        }
    }
}

/** Apps with a media browser that aren't for playing music or the radio (the Google app's is for its assistant). */
private val NOT_SOURCES = setOf("com.google.android.googlequicksearchbox")

/** At most this many apps to play from, along the bottom. */
private const val MAX_SOURCES = 8

/** The artwork, blurred and dimmed, filling the window: the song's own colours behind it all. */
@Composable
private fun Backdrop(tr: Track?, accent: Color) {
    // Blurred by drawing a tiny copy large (smooth on any Android, unlike a blur effect).
    val tiny = remember(tr?.art) {
        tr?.art?.let { b -> runCatching { Bitmap.createScaledBitmap(b, 10, 10, true).asImageBitmap() }.getOrNull() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (tiny != null) {
            Image(
                tiny, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.7f },
                contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset.Zero,
                    radius = constraints.maxWidth * 0.8f,
                ),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.6f)))))
    }
}

/**
 * How far into the song, as a bar to drag (or tap) to another place, thickening under the
 * finger as Apple's does; the time gone and left under it. A live stream (the radio): LIVE.
 */
@Composable
private fun SeekBar(tr: Track) {
    val duration = tr.durationMs
    if (duration == null) {
        Box(Modifier.background(Color(0xFFFF453A), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Txt("LIVE", T.footnote.copy(fontWeight = FontWeight.Bold), Color.White)
        }
        return
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(tr.playing, tr.at, tr.positionMs) {
        now = SystemClock.elapsedRealtime()
        while (tr.playing) {
            delay(250)
            now = SystemClock.elapsedRealtime()
        }
    }
    // Under the finger, 0 to 1.
    var scrub by remember { mutableStateOf<Float?>(null) }
    // Let go: the bar stays there until the app says where it is (or a moment passes).
    var sent by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(tr.positionMs, tr.at) { sent = null }
    LaunchedEffect(sent) { if (sent != null) { delay(2_000); sent = null } }
    val pos = scrub?.let { (it * duration).toLong() } ?: sent ?: tr.positionAt(now)
    val frac = (pos.toFloat() / duration).coerceIn(0f, 1f)
    val thick by animateDpAsState(if (scrub != null) 12.dp else 7.dp, label = "seekThick")
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(tr.canSeek, duration) {
                    if (!tr.canSeek) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        scrub = (down.position.x / size.width).coerceIn(0f, 1f)
                        drag(down.id) { c ->
                            c.consume()
                            scrub = (c.position.x / size.width).coerceIn(0f, 1f)
                        }
                        scrub?.let { f ->
                            val ms = (f * duration).toLong()
                            NowPlaying.seekTo(ms)
                            sent = ms
                        }
                        scrub = null
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(thick).background(Color.White.copy(alpha = 0.22f), Capsule)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color.White.copy(alpha = if (scrub != null) 1f else 0.85f), Capsule))
            }
        }
        Row {
            Txt(clock(pos), T.subhead.copy(fontFeatureSettings = "tnum"), Color.White.copy(alpha = 0.65f))
            Spacer(Modifier.weight(1f))
            Txt("-" + clock(duration - pos), T.subhead.copy(fontFeatureSettings = "tnum"), Color.White.copy(alpha = 0.65f))
        }
    }
}

/** Play or pause: a white circle, the biggest button. */
@Composable
private fun PlayButton(tr: Track?) {
    Box(
        Modifier
            .size(96.dp)
            .alpha(if (tr != null) 1f else 0.35f)
            .background(Color.White, CircleShape)
            .clip(CircleShape)
            .pressable({ NowPlaying.playPause() }, enabled = tr != null, scaleDown = 0.88f),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(if (tr?.playing == true) Icons.pause else Icons.play, Color.Black, size = 44.dp)
    }
}

@Composable
private fun BigButton(icon: ImageVector, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).pressable(onClick, enabled = enabled, scaleDown = 0.84f).alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, Color.White, size = size * 0.55f) }
}

@Composable
private fun RoundButton(icon: ImageVector, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).background(Color.White.copy(alpha = 0.14f), CircleShape).clip(CircleShape).pressable(onClick, scaleDown = 0.88f),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, Color.White, size = size * 0.45f) }
}

/**
 * One app to play from: its icon and name. Lit: the one playing (or paused) now. While it's
 * being asked to play, its icon breathes.
 */
@Composable
private fun SourceButton(a: AppInfo, current: Boolean, playing: Boolean, starting: Boolean, onClick: () -> Unit) {
    val breath = if (starting) {
        rememberInfiniteTransition(label = "starting").animateFloat(
            1f, 0.35f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "starting",
        )
    } else null
    Row(
        Modifier
            .height(64.dp)
            .background(if (current) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.1f), Capsule)
            .pressable(onClick, scaleDown = 0.94f)
            .padding(start = 10.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.graphicsLayer { alpha = breath?.value ?: 1f }) { AppIcon(a, 44.dp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Txt(a.label, T.headline, Color.White, maxLines = 1)
            if (current || starting) {
                Txt(
                    when {
                        starting -> "Starting…"
                        playing -> "Playing"
                        else -> "Paused"
                    },
                    T.footnote, Color.White.copy(alpha = 0.6f), maxLines = 1,
                )
            }
        }
    }
}
