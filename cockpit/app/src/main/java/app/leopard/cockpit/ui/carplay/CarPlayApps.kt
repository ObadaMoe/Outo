package app.leopard.cockpit.ui.carplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.data.Tab
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenContent
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.screens.Screens
import app.leopard.cockpit.ui.Alerts
import app.leopard.cockpit.ui.Capsule
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.SidebarMargin
import app.leopard.cockpit.ui.SidebarWidth
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.screens.HostPicture
import app.leopard.cockpit.ui.screens.HostSurface
import app.leopard.cockpit.ui.screens.TouchRouter
import app.leopard.cockpit.ui.screens.TouchTarget
import app.leopard.cockpit.ui.screens.at
import app.leopard.cockpit.ui.screens.routeTouches
import app.leopard.cockpit.ui.screens.smoothstep
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The windows' corners. */
internal val WindowCorner = 22.dp
/** Between two windows side by side, and between the dashboard's widgets. */
internal val WindowGap = 10.dp
/** Where CarPlay mode's windows and dashboard start: right of the sidebar, as far from it as it is from the edge. */
internal val ContentStart = SidebarMargin * 2 + SidebarWidth

/** How wide the handle between two windows takes touches, either side of the gap. */
private val HandleReach = 18.dp

/**
 * One window: where it is (following the layout), and how far it has grown out of its
 * [origin] (0: it's the icon; 1: it's the window).
 */
private class Win(val app: AppId, rect: Rect, var origin: Origin, grown: Float, size: IntSize) {
    val rect = Animatable(rect, Rect.VectorConverter)
    val grow = Animatable(grown)
    /** No longer one of the apps: shrinking away, then gone. */
    var departing = false
    /** The size the app runs at: changed while its picture is veiled (see [reflow]). */
    var size by mutableStateOf(size)
    /**
     * Being resized: the app lays itself out again, which takes it a moment and shows
     * half-drawn pictures meanwhile. Its picture blurs under its icon until it's done,
     * as on an iPad.
     */
    var reflow by mutableStateOf(false)
}

/**
 * CarPlay mode's apps: what the main screen shows, right of the sidebar (which stays),
 * in rounded windows as tall as it: one app, or two side by side. An app grows out of
 * the icon tapped (or its card in the app switcher); on Home it shrinks back into its
 * sidebar icon (or the dashboard's map). Drag the handle between two to resize them;
 * past an edge, the other one fills the space and the squeezed one goes to the
 * background. Home (or Back) sends them all there, to the app switcher (two fingers
 * up). A finger in from the left edge of the windows is Back, inside the app.
 *
 * [presence]: 1 while shown, down to 0 on the way out ([leaving]), as the windows
 * shrink into their icons.
 */
@Composable
fun CarPlayApps(presence: () -> Float = { 1f }, leaving: Boolean = false) {
    val otility = Otility.instance
    val controller = otility.screens
    val nav = LocalNav.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val state by controller.state.collectAsState()
    val apps by otility.catalog.apps.collectAsState()
    val settings by otility.prefs.settings.collectAsState()
    val helper by HelperConnection.helper.collectAsState()
    val pip by controller.pip.collectAsState()
    val alert by Alerts.current.collectAsState()
    val switcher by AppSwitcher.open.collectAsState()
    val appsById = remember(apps) { apps.associateBy { it.id } }
    val main = state[ScreenId.MAIN]
    // On the way out the apps have gone to the background already: the windows keep
    // showing what they showed, shrinking into their icons.
    val shownBefore = remember { arrayOf(main) }
    val content = if (leaving) shownBefore[0] else main
    if (!leaving) shownBefore[0] = main

    // It's shown here already: nothing to zoom into.
    LaunchedEffect(nav.zoomRequest) { if (nav.zoomRequest == ScreenId.MAIN) nav.zoomRequest = null }
    // Nothing left here (closed in the app switcher, say): Home. Not at once: an app opened
    // from the sidebar comes a moment after its question is answered.
    LaunchedEffect(main == ScreenContent.Idle, leaving) {
        if (main == ScreenContent.Idle && !leaving) {
            delay(400)
            nav.tab = Tab.OVERVIEW
        }
    }
    BackHandler(enabled = !leaving) { nav.tab = Tab.OVERVIEW }

    // Where this page is in the window: origins are in the window's pixels.
    val inWindow = remember { arrayOf(Offset.Zero) }
    // Black behind the windows, coming in as they grow (Home fades behind it) and going as they shrink.
    val backdrop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { backdrop.animateTo(1f, tween(260)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { inWindow[0] = it.positionInRoot() }
            .drawBehind { drawRect(Color.Black, alpha = min(backdrop.value, presence()).coerceIn(0f, 1f)) },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        fun px(d: Dp) = with(density) { d.toPx() }
        // Right of the sidebar, as tall as it is, between the car's bars.
        val safe = WindowInsets.safeDrawing
        val margin = px(SidebarMargin)
        val area = Rect(
            safe.getLeft(density, LayoutDirection.Ltr) + px(ContentStart),
            safe.getTop(density) + margin,
            w - safe.getRight(density, LayoutDirection.Ltr) - margin,
            h - safe.getBottom(density) - margin,
        )
        val areaNow by rememberUpdatedState(area)
        val gap = px(WindowGap)
        val corner = px(WindowCorner)
        val blur = px(28.dp)
        fun panes(ratio: Float): Pair<Rect, Rect> {
            val lw = (area.width - gap) * ratio
            return Rect(area.left, area.top, area.left + lw, area.bottom) to Rect(area.left + lw + gap, area.top, area.right, area.bottom)
        }

        // Where the handle is while it's held (null otherwise). Only the pictures follow it:
        // the apps are resized once it's let go (resizing makes an app lay itself out again).
        var dragRatio by remember { mutableStateOf<Float?>(null) }
        val split = content as? ScreenContent.Split
        val ratio = dragRatio ?: split?.ratio ?: 0.5f
        fun hostSize(app: AppId): Pair<Int, Int> {
            val s = split ?: return area.width.toInt() to area.height.toInt()
            val r = Screens.clampRatio(s.ratio)
            val share = if (s.left == app) r else 1f - r
            return ((area.width - gap) * share).toInt() to area.height.toInt()
        }
        val targets: Map<AppId, Rect> = when (content) {
            is ScreenContent.Single -> mapOf(content.app to area)
            is ScreenContent.Split -> panes(ratio).let { (l, r) -> mapOf(content.left to l, content.right to r) }
            ScreenContent.Idle -> emptyMap()
        }

        // ---- The windows, coming and going ---------------------------------------------
        val wins = remember { LinkedHashMap<AppId, Win>() }
        var gen by remember { mutableIntStateOf(0) }
        for ((id, target) in targets) {
            val had = wins[id]
            if (had == null || had.departing) {
                // Out of the icon tapped (or its card), if it was; otherwise it fades in where it goes.
                val from = nav.launchOrigins.remove(id)?.takeIf { it.fresh }
                    ?.let { it.copy(rect = it.rect.translate(-inWindow[0].x, -inWindow[0].y)) }
                    ?: Origin.fade(target, corner)
                if (had != null) {
                    // Coming back while on its way out: it turns round where it is.
                    had.departing = false
                } else {
                    wins[id] = Win(id, target, from, grown = 0f, size = hostSize(id).let { (a, b) -> IntSize(a, b) })
                }
            }
        }
        for (win in wins.values) {
            if (win.app !in targets && !win.departing) {
                win.departing = true
                win.origin = Origin.fade(win.rect.value, corner, 0.92f)
            }
        }
        // The map card on the dashboard, if Home is there: the map app's window lands on it.
        val mapApp = controller.mapApp()
        fun exitOrigin(win: Win): Origin {
            val off = inWindow[0]
            val card = nav.dashboardMap
            if (card.width > 0f && win.app.pkg == mapApp) return Origin(card.translate(-off.x, -off.y), px(28.dp), Origin.Kind.LIVE)
            val icon = nav.sidebarIcons[win.app]
            if (icon != null) return Origin.icon(icon.translate(-off.x, -off.y))
            // Not on the sidebar: it shrinks away in the middle, as on an iPhone.
            return Origin.fade(win.rect.value, corner, 0.6f)
        }

        // Straight to the car's screen (see HostSurface) once everything here is still, and
        // nothing with glass is over the windows (a menu, a sheet, a question): it would show
        // them sharp. Moving, they're drawn in Otility's window, where the motion applies.
        val covered = nav.menu != null || nav.dragging != null || nav.sheet != null || alert != null || pip != null || switcher
        val still by remember {
            derivedStateOf {
                gen.let { _ -> wins.values.all { !it.departing && it.grow.value == 1f && !it.rect.isRunning && !it.reflow } }
            }
        }
        val grown by remember { derivedStateOf { gen.let { _ -> wins.values.all { it.departing || it.grow.value >= 1f } } } }
        val direct = helper != null && still && !leaving && dragRatio == null && !covered
        // The sidebar's glass then doesn't redraw what's behind it (see Nav.direct).
        LaunchedEffect(direct) { nav.direct = direct }
        DisposableEffect(Unit) { onDispose { nav.direct = false } }

        Box(Modifier.fillMaxSize()) {
            for (win in wins.values.toList()) key(win.app.pkg) {
                val target = targets[win.app]
                val info = appsById[win.app]
                val departing = win.departing
                // A new size (a split opening or closing, the handle let go): veiled first, then
                // resized, then shown again once it's drawn at that size (or after a while, if it
                // draws nothing).
                val wanted = hostSize(win.app).let { (a, b) -> IntSize(a, b) }
                val picture = remember { HostPicture() }
                LaunchedEffect(win, wanted, departing, leaving) {
                    if (departing || leaving || win.size == wanted) return@LaunchedEffect
                    win.reflow = true
                    try {
                        delay(VEIL_MS)
                        win.size = wanted
                        withTimeoutOrNull(1500) { snapshotFlow { picture.drew(wanted.width, wanted.height) }.first { it } }
                    } finally {
                        win.reflow = false
                    }
                }
                val vw = win.size.width
                val vh = win.size.height
                // Its picture comes in once the app has drawn at this size (not half laid out at
                // the size it had somewhere else), or after a moment if it draws nothing.
                var ready by remember { mutableStateOf(false) }
                LaunchedEffect(win) {
                    withTimeoutOrNull(900) { snapshotFlow { picture.drew(win.size.width, win.size.height) }.first { it } }
                    ready = true
                }
                val reveal = animateFloatAsState(if (ready) 1f else 0f, tween(220), label = "reveal")
                // Nearly solid while it's resized: half-drawn pictures show through a thin wash.
                val wash = animateFloatAsState(if (win.reflow) 0.95f else 0.7f, tween(VEIL_MS.toInt()), label = "wash")
                // While the handle is held both windows are veiled: their pictures only stretch.
                val veil = animateFloatAsState(
                    if (win.reflow || (dragRatio != null && !departing)) 1f else 0f,
                    tween(if (win.reflow || dragRatio != null) VEIL_MS.toInt() else 300),
                    label = "veil",
                )
                // Follows the layout: with the finger while the handle is held, by spring otherwise.
                LaunchedEffect(win, target, dragRatio != null) {
                    if (target == null) return@LaunchedEffect
                    if (dragRatio != null) win.rect.snapTo(target) else win.rect.animateTo(target, Motion.move())
                }
                LaunchedEffect(win, departing) {
                    if (departing) {
                        win.grow.animateTo(0f, Motion.leave())
                        if (win.departing) {
                            wins.remove(win.app)
                            gen++
                        }
                    } else {
                        win.grow.animateTo(1f, Motion.grow())
                        gen++
                    }
                }
                // How far grown, and from what: on the way out, back into its icon.
                val g = { if (leaving && !win.departing) min(win.grow.value, presence()) else win.grow.value }
                val origin = { if (leaving && !win.departing) exitOrigin(win) else win.origin }
                // Squeezed towards an edge: it dims, as it's about to go.
                val dim = split?.let {
                    if (it.left == win.app) smoothstep(Screens.MIN_RATIO, Screens.DISMISS_LEFT, ratio)
                    else smoothstep(Screens.MAX_RATIO, Screens.DISMISS_RIGHT, ratio)
                } ?: 0f
                Box(
                    Modifier
                        .at { lerp(origin().rect, win.rect.value, g()) }
                        .graphicsLayer {
                            val o = origin()
                            val p = g()
                            alpha = when (o.kind) {
                                Origin.Kind.FADE -> p.coerceIn(0f, 1f)
                                Origin.Kind.LIVE -> smoothstep(0f, 0.35f, p)
                                else -> 1f
                            }
                            shape = RoundedCornerShape(mix(o.corner, corner, p.coerceIn(0f, 1f)))
                            clip = true
                        },
                ) {
                    MorphPoster(info, { origin().kind }, g)
                    // The live picture, laid out at the app's size and scaled to cover the window
                    // as it grows or shrinks; blurred while it's veiled.
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            alpha = when (origin().kind) {
                                Origin.Kind.ICON, Origin.Kind.CARD -> smoothstep(0.35f, 0.85f, g()) * reveal.value
                                Origin.Kind.FADE -> reveal.value
                                Origin.Kind.LIVE -> 1f
                            }
                            val b = veil.value * blur
                            renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Clamp) else null
                        },
                    ) {
                        HostSurface(
                            win.app, info?.component?.flattenToShortString() ?: win.app.pkg, vw, vh,
                            Modifier.cover(vw, vh), direct = direct, picture = picture,
                        )
                    }
                    Veil(info, { veil.value }, { wash.value })
                    if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f * dim)))
                }
            }
        }

        // ---- Touches: to the app under the finger ---------------------------------------
        if (grown && !leaving && helper != null) {
            val router = remember {
                TouchRouter(otility.bridge, density.density, onTwoUp = {}, onTwoDown = {}, onTwoSide = {}, onTouchedPane = {})
            }
            router.gestures = settings.gestures
            // Two fingers are the app's here (a map's zoom, say), except two straight up: the switcher's.
            router.twoFingers = false
            router.targets = content.apps.mapNotNull { id ->
                val r = targets[id] ?: return@mapNotNull null
                // In the app's terms: the size it runs at now.
                val size = { wins[id]?.size ?: hostSize(id).let { (a, b) -> IntSize(a, b) } }
                TouchTarget(r.translate(-area.left, -area.top), { controller.hosts.displayOf(id) }, { size().width }, { size().height })
            }
            Box(Modifier.at(area).routeTouches(router))
        }

        // ---- The handle between two: drag to resize, past an edge for one app -------------
        if (split != null && !leaving) {
            fun letGo() {
                val r = dragRatio ?: return
                dragRatio = null
                controller.releaseDivider(ScreenId.MAIN, r)
            }
            val handle = {
                val (l, _) = panes(dragRatio ?: split.ratio)
                Rect(l.right - px(HandleReach), area.top, l.right + gap + px(HandleReach), area.bottom)
            }
            val held = dragRatio != null
            val pill by animateFloatAsState(if (held) 1f else if (grown) 0.55f else 0f, Motion.settle(), label = "pill")
            Box(
                Modifier
                    .at(handle)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            controller.releaseDivider(ScreenId.MAIN, 0.5f)
                        })
                    }
                    .pointerInput(Unit) {
                        var crossed = false
                        try {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragRatio = (controller.state.value[ScreenId.MAIN] as? ScreenContent.Split)?.ratio ?: 0.5f
                                    crossed = false
                                },
                                onDragEnd = { letGo() },
                                onDragCancel = { letGo() },
                            ) { change, dx ->
                                change.consume()
                                val from = dragRatio ?: return@detectHorizontalDragGestures
                                val width = areaNow.width
                                if (width <= 0f) return@detectHorizontalDragGestures
                                val r = (from + dx / width).coerceIn(0.04f, 0.96f)
                                dragRatio = r
                                val past = r <= Screens.DISMISS_LEFT || r >= Screens.DISMISS_RIGHT
                                if (past != crossed) {
                                    crossed = past
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        } finally {
                            // Cut short (the split went, the tab changed): not left "held".
                            letGo()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // A thin pill in the gap, as on an iPad; it stretches while held.
                Box(
                    Modifier
                        .size(width = 5.dp, height = 64.dp)
                        .graphicsLayer {
                            scaleY = 0.9f + 0.25f * pill.coerceAtLeast(0f)
                            alpha = pill.coerceIn(0f, 1f)
                        }
                        .background(Color.White, Capsule),
                )
            }
        }
    }
}

/**
 * Over a window being resized (or squeezed by the handle): a dark wash of the app's color
 * and its icon in the middle, over its blurred picture.
 */
@Composable
private fun Veil(app: AppInfo?, veil: () -> Float, wash: () -> Float) {
    val tint = app?.let { Color(it.tint) } ?: Color(0xFF3A3D45)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val v = veil()
                if (v > 0f) drawRect(lerp(tint, Color.Black, 0.55f), alpha = wash() * v)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (app != null) {
            AppIcon(
                app, 88.dp,
                Modifier.graphicsLayer {
                    val v = veil()
                    alpha = v
                    val s = 0.85f + 0.15f * v
                    scaleX = s; scaleY = s
                },
            )
        }
    }
}

/**
 * The app's face while its window grows or shrinks, under its picture: grown out of an
 * icon it's the icon at first, filling the window, which then settles to the middle of
 * its color, as the app's poster; out of a card, the card's poster throughout.
 */
@Composable
private fun MorphPoster(app: AppInfo?, kind: () -> Origin.Kind, grow: () -> Float) {
    val tint = app?.let { Color(it.tint) } ?: Color(0xFF3A3D45)
    val brush = remember(tint) { Brush.verticalGradient(listOf(lerp(tint, Color.Black, 0.45f), lerp(tint, Color.Black, 0.78f))) }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val a = if (kind() == Origin.Kind.ICON) smoothstep(0f, 0.3f, grow()) else 1f
                drawRect(brush, alpha = a)
            },
    ) {
        if (app != null) {
            Image(
                app.icon, null,
                Modifier
                    .layout { m, c ->
                        val p = grow().coerceIn(0f, 1f)
                        val side = min(c.maxWidth, c.maxHeight).toFloat()
                        val share = if (kind() == Origin.Kind.ICON) mix(1f, 0.36f, smoothstep(0f, 0.6f, p)) else 0.36f
                        val s = (side * share).roundToInt().coerceAtLeast(1)
                        val placeable = m.measure(Constraints.fixed(s, s))
                        layout(c.maxWidth, c.maxHeight) { placeable.place((c.maxWidth - s) / 2, (c.maxHeight - s) / 2) }
                    }
                    .graphicsLayer {
                        shape = RoundedCornerShape(size.minDimension * 0.225f)
                        clip = true
                    },
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Laid out at the app's real size and scaled to cover the space, as it grows or shrinks. */
internal fun Modifier.cover(w: Int, h: Int): Modifier = layout { m, c ->
    val pl = m.measure(Constraints.fixed(w, h))
    val outW = c.maxWidth
    val outH = c.maxHeight
    layout(outW, outH) {
        pl.placeWithLayer((outW - w) / 2, (outH - h) / 2) {
            val s = max(outW / w.toFloat(), outH / h.toFloat())
            scaleX = s; scaleY = s
        }
    }
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/** How long a window's picture takes to blur under its icon, before the app is resized. */
private const val VEIL_MS = 120L
