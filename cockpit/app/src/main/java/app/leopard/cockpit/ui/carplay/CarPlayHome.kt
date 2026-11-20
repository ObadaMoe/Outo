package app.leopard.cockpit.ui.carplay

import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import app.leopard.cockpit.data.CarHome
import app.leopard.cockpit.data.DashCard
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.MenuGap
import app.leopard.cockpit.ui.MenuItem
import app.leopard.cockpit.ui.overview.DashboardWidget
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import app.leopard.cockpit.ui.SidebarMargin
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import app.leopard.cockpit.data.WidgetKind
import app.leopard.cockpit.data.WidgetSize
import app.leopard.cockpit.data.WidgetSlot
import app.leopard.cockpit.ui.LocalBackdrop
import app.leopard.cockpit.ui.overview.MapWidget
import app.leopard.cockpit.ui.overview.NavigationCard
import app.leopard.cockpit.notes.PhoneNotes
import app.leopard.cockpit.ui.NotificationsCard
import androidx.compose.animation.Crossfade
import app.leopard.cockpit.ui.overview.NowPlayingCard
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.leopard.cockpit.Otility
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.R
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.data.Tab
import app.leopard.cockpit.ui.CarBars
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.SideInset
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.apps.AppTile
import app.leopard.cockpit.ui.pressable
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.settings.SettingsPage
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.OtilityTheme
import app.leopard.cockpit.ui.theme.T
import app.leopard.cockpit.ui.theme.tokens
import app.leopard.cockpit.update.UpdateState
import kotlinx.coroutines.launch

/** What Home's pages hold: Settings, then every app in the car. */
private sealed interface HomeItem {
    val key: String

    data object Settings : HomeItem {
        override val key = "settings"
    }

    data class App(val info: AppInfo) : HomeItem {
        override val key get() = info.id.pkg
    }
}

/** The emblem under the apps: quieter than on an idle screen, so their names read. */

/** An app's place on Home: as many columns and rows as fit, CarPlay's big icons. */
private val CellWidth = 176.dp
private val CellHeight = 168.dp
private val IconSize = 96.dp
/** The page dots' strip, under every page's content (the dashboard's too), as CarPlay's. */
private val Dots = 34.dp
/** The dots' own height (8 dp dots, 6 dp above and below). */
private val DotsHeight = 20.dp
/** Room right of the apps. */
private val EndGap = 28.dp
/** Above an app's icon in its place. */
private val TileTop = 12.dp
/** How near the page's side an app being moved turns the page, and how long it waits there first. */
private val EdgeZone = 64.dp
private const val EDGE_TURN_MS = 700L
/** How far the apps wiggle while Home is edited, each way. */
private const val WIGGLE_DEG = 1.8f

/** The dashboard's map: its share of the room right of the sidebar, as CarPlay's. */
private const val MAP_SHARE = 0.62f

private val MapSlot = WidgetSlot(WidgetKind.MAP, WidgetSize(4, 2))
private val MediaSlot = WidgetSlot(WidgetKind.MEDIA, WidgetSize(3, 2))
private val NavSlot = WidgetSlot(WidgetKind.NAVIGATION, WidgetSize(3, 2))

/**
 * CarPlay mode's Home: black with the emblem, as the idle screens. The first page
 * is CarPlay's dashboard: the map app, live, down the left (pan and zoom it where it
 * is, tap it twice to open it up), Now Playing and Navigation beside it. Then every
 * app, on pages that turn with a swipe (on the map a swipe pans it; swipe beside it),
 * Settings first among them. Always dark, as CarPlay's is. Home on the sidebar, tapped
 * again here, goes from the dashboard to the apps and back, as CarPlay's does.
 *
 * While the dashboard is on the screen the map is here, all of it, and the driver
 * cluster shows BYD's own view; turned to the apps (or anywhere else), the cluster
 * has the map back.
 */
@Composable
fun CarPlayHome() {
    val app = Otility.instance
    val settings by app.prefs.settings.collectAsState()
    // Light or dark as Outo's appearance is (Automatic included), the cards too.
    val appDark = LocalTokens.current.dark
    val look = remember(appDark, settings.accent, settings.reduceTransparency, settings.tintedGlass) {
        tokens(appDark, settings.accent, settings.reduceTransparency, settings.tintedGlass)
    }
    val all by app.catalog.apps.collectAsState()
    val items = remember(all) { listOf<HomeItem>(HomeItem.Settings) + all.map { HomeItem.App(it) } }
    OtilityTheme(look) {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            // The wallpaper, under the car's bars too, as the screens' photo is. The
            // cards' glass blurs it.
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                CarPlayWallpaper()
            }
            CompositionLocalProvider(LocalBackdrop provides backdrop) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(CarBars)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    Pages(items)
                }
            }
        }
    }
}

/** Where Home's apps sit on a page, in pixels of the pages' box. */
private data class Grid(
    val left: Float, val top: Float, val cellW: Float, val cellH: Float,
    val cols: Int, val rows: Int, val icon: Float, val iconTop: Float,
) {
    val perPage get() = cols * rows

    /** The [i]th app's icon on a page. */
    fun icon(i: Int): Rect {
        val x = left + (i % cols) * cellW + (cellW - icon) / 2f
        val y = top + (i / cols) * cellH + iconTop
        return Rect(x, y, x + icon, y + icon)
    }

    /** The [i]th app's cell on a page (its icon, name and dot). */
    fun cell(i: Int) = Offset(left + (i % cols) * cellW, top + (i / cols) * cellH)

    /** The place on a page nearest [p]. */
    fun slot(p: Offset): Int {
        val c = ((p.x - left) / cellW).toInt().coerceIn(0, cols - 1)
        val r = ((p.y - top) / cellH).toInt().coerceIn(0, rows - 1)
        return r * cols + c
    }
}

/** An app being moved while Home is edited: the order so far, the finger, and where it holds the icon. */
private data class Drag(val key: String, val order: List<HomeItem>, val at: Offset, val grab: Offset)

@Composable
private fun Pages(items: List<HomeItem>) {
    val app = Otility.instance
    val nav = LocalNav.current
    val t = LocalTokens.current
    val haptic = LocalHapticFeedback.current
    val home by app.prefs.carHome.collectAsState()
    // Where this box is in the window (for the map card's place there).
    val inWindow = remember { arrayOf(Offset.Zero) }
    // How far the map is opened up, 0 to 1: the pages fade under it, and stay put.
    val mapOpen = remember { Animatable(0f) }
    val mapClosed by remember { derivedStateOf { mapOpen.value == 0f } }
    // Editing Home, as an iPhone's: the icons wiggle, drag one to move it (to the page's
    // edge to take it to the next), its "−" takes it off Home; the dashboard's stacks
    // show what's in them. Held on the page (or Edit Home Screen in an app's menu) to
    // start; Done, Home, Back or a tap on the page to finish.
    var editing by remember { mutableStateOf(false) }
    // The stack whose cards are being chosen (true: the top one), and the hidden apps' list.
    var picking by remember { mutableStateOf<Boolean?>(null) }
    var hiddenOpen by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Drag?>(null) }
    if (!editing) {
        picking = null
        hiddenOpen = false
    }
    val ordered = remember(items, home) {
        val byKey = items.associateBy { it.key }
        home.arrange(items.map { it.key }).mapNotNull { byKey[it] }
    }
    val shown = drag?.order ?: ordered
    val wiggle = if (editing) {
        rememberInfiniteTransition(label = "wiggle").animateFloat(
            -1f, 1f, infiniteRepeatable(tween(140, easing = LinearEasing), RepeatMode.Reverse), label = "wiggle",
        )
    } else null
    BackHandler(enabled = editing) { if (picking != null || hiddenOpen) { picking = null; hiddenOpen = false } else editing = false }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { inWindow[0] = it.positionInRoot() }) {
        val density = LocalDensity.current
        fun px(d: Dp) = with(density) { d.toPx() }
        // The pages span the window, so one turning slides under the sidebar's glass;
        // what's on them keeps right of it.
        val gridWidth = maxWidth - SideInset - EndGap
        val cols = (gridWidth / CellWidth).toInt().coerceAtLeast(3)
        val cellW = gridWidth / cols
        val rows = ((maxHeight - SidebarMargin * 2 - Dots) / CellHeight).toInt().coerceAtLeast(1)
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // As the apps' page lays them out: across from the sidebar, in the middle between
        // its top and the dots.
        val grid = Grid(
            left = px(SideInset), top = px(SidebarMargin) + (h - px(SidebarMargin) * 2 - px(Dots) - px(CellHeight) * rows) / 2f,
            cellW = px(cellW), cellH = px(CellHeight), cols = cols, rows = rows, icon = px(IconSize), iconTop = px(TileTop),
        )
        val appPages = shown.chunked(grid.perPage)
        // On the page it was on: from an app, Home comes back where it was left.
        val pager = rememberPagerState(initialPage = nav.homePage.coerceIn(0, appPages.size)) { 1 + appPages.size }
        LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { nav.homePage = it } }
        val scope = rememberCoroutineScope()
        val editingNow by rememberUpdatedState(editing)
        // Home tapped again, here, as CarPlay's: from the dashboard to the apps, from the
        // apps back to the dashboard, the pages gliding. While editing: Done.
        DisposableEffect(nav, pager) {
            val first: () -> Unit = {
                if (editingNow) editing = false
                else scope.launch {
                    val to = if (pager.currentPage == 0 && pager.pageCount > 1) 1 else 0
                    pager.animateScrollToPage(to, animationSpec = spring(0.92f, 220f))
                }
            }
            nav.home = first
            onDispose { if (nav.home === first) nav.home = null }
        }
        val shownNow by rememberUpdatedState(shown)
        val gridNow by rememberUpdatedState(grid)

        /** The app whose icon is under [p] on the page showing, if any. */
        fun appAt(p: Offset): Pair<HomeItem, Rect>? {
            val page = pager.currentPage
            if (page < 1 || abs(pager.currentPageOffsetFraction) > 0.01f) return null
            val g = gridNow
            val base = (page - 1) * g.perPage
            val slack = px(10.dp)
            // While editing, its name too: a tap there isn't one on the page (which is Done).
            val below = if (editingNow) px(40.dp) else slack
            for (i in 0 until minOf(g.perPage, shownNow.size - base)) {
                val r = g.icon(i)
                if (Rect(r.left - slack, r.top - slack, r.right + slack, r.bottom + below).contains(p)) return shownNow[base + i] to r
            }
            return null
        }

        /** [d] with its app moved to the place under [p] on the page showing. */
        fun reorder(d: Drag, p: Offset): Drag {
            val page = pager.currentPage
            if (page < 1) return d.copy(at = p)
            val to = ((page - 1) * gridNow.perPage + gridNow.slot(p)).coerceIn(0, d.order.size - 1)
            val from = d.order.indexOfFirst { it.key == d.key }
            if (from < 0 || from == to) return d.copy(at = p)
            val list = d.order.toMutableList()
            list.add(to, list.removeAt(from))
            return d.copy(order = list, at = p)
        }

        // An app held at the page's edge, while moving it: the page turns, and again a
        // moment later if it's still there.
        var edge by remember { mutableIntStateOf(0) }
        LaunchedEffect(edge) {
            if (edge == 0) return@LaunchedEffect
            while (true) {
                delay(EDGE_TURN_MS)
                val to = pager.currentPage + edge
                if (to < 1 || to >= pager.pageCount) break
                pager.animateScrollToPage(to)
                drag = drag?.let { reorder(it, it.at) }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - mapOpen.value.coerceIn(0f, 1f) }
                .pointerInput(editing) {
                    if (!editing) {
                            // Held on the page, a card or Settings (not on an app, whose hold is its
                        // menu, nor on the map, whose touches are the map's): edit Home.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val p0 = down.position
                            val map = nav.dashboardMap.translate(-inWindow[0].x, -inWindow[0].y)
                            if (appAt(p0)?.first is HomeItem.App || (pager.currentPage == 0 && map.contains(p0)) || !mapClosed) return@awaitEachGesture
                            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed || (c.position - p0).getDistance() > viewConfiguration.touchSlop) break
                                }
                                true
                            }
                            if (ended != null) return@awaitEachGesture
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            editing = true
                            // The rest of this touch is the hold's, not a tap on what's under it.
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                e.changes.forEach { it.consume() }
                                if (e.changes.none { it.pressed }) break
                            }
                        }
                    } else {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val p0 = down.position
                            val hit = appAt(p0)
                            if (hit == null) {
                                // Not on an app: the page turns as ever, the stacks' buttons work;
                                // a plain tap on the page is Done.
                                var moved = false
                                while (true) {
                                    val c = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                                    if ((c.position - p0).getDistance() > viewConfiguration.touchSlop) moved = true
                                    if (!c.pressed) {
                                        if (!moved && !c.isConsumed && picking == null && !hiddenOpen) editing = false
                                        break
                                    }
                                }
                                return@awaitEachGesture
                            }
                            down.consume()
                            val (item, icon) = hit
                            val onBadge = item.key != CarHome.SETTINGS &&
                                Rect(icon.left - px(16.dp), icon.top - px(16.dp), icon.left + px(22.dp), icon.top + px(22.dp)).contains(p0)
                            var moving = false
                            while (true) {
                                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                                if (!moving && (c.position - p0).getDistance() > viewConfiguration.touchSlop) {
                                    moving = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    drag = Drag(item.key, shownNow, c.position, p0 - icon.topLeft)
                                }
                                if (moving) {
                                    drag = drag?.let { reorder(it, c.position) }
                                    edge = when {
                                        c.position.x > w - px(EdgeZone) -> 1
                                        c.position.x < px(ContentStart) + px(EdgeZone) && pager.currentPage > 1 -> -1
                                        else -> 0
                                    }
                                }
                            }
                            edge = 0
                            val d = drag
                            when {
                                d != null -> {
                                    app.prefs.setCarHome(app.prefs.carHome.value.copy(order = d.order.map { it.key }))
                                    drag = null
                                }
                                onBadge -> app.prefs.setCarHome(app.prefs.carHome.value.hide(item.key))
                            }
                        }
                    }
                },
        ) {
            HorizontalPager(
                pager,
                Modifier.fillMaxSize(),
                key = { it },
                userScrollEnabled = mapClosed && drag == null,
            ) { page ->
                if (page == 0) Dashboard(home, editing, onPick = { picking = it })
                else AppPage(
                    appPages.getOrNull(page - 1).orEmpty(), grid, cellW, editing, wiggle, drag?.key,
                    onEdit = { editing = true },
                )
            }
        }
        DashboardMap(pager, w, h, inWindow, mapOpen)
        // In their strip under the pages (the map and cards end above it); going as the
        // map opens up over all of Home. While editing, Done and the hidden apps sit there.
        PageDots(
            pager, t,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = ContentStart, end = SidebarMargin, bottom = SidebarMargin + (Dots - DotsHeight) / 2)
                // Not on the dashboard, whose cards and map take their room: they come in
                // as the apps' pages do (and while editing, for the pages to drag to).
                .graphicsLayer {
                    val toApps = if (editing) 1f else (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    alpha = toApps * (1f - mapOpen.value.coerceIn(0f, 1f))
                },
        )
        if (editing) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = SidebarMargin + 6.dp, bottom = SidebarMargin + 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditPill(if (home.hidden.isEmpty()) "Hidden Apps" else "Hidden Apps (${home.hidden.size})", filled = false) { hiddenOpen = true }
                EditPill("Done", filled = true) { editing = false }
            }
        }
        // The app being moved, over everything, under the finger.
        drag?.let { d ->
            d.order.firstOrNull { it.key == d.key }?.let { item ->
                Box(
                    Modifier
                        .offset { IntOffset((d.at.x - d.grab.x).roundToInt(), (d.at.y - d.grab.y).roundToInt()) }
                        .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; alpha = 0.95f },
                ) { HomeIcon(item) }
            }
        }
        picking?.let { first -> StackPicker(first, home) { picking = null } }
        if (hiddenOpen) HiddenApps(items, home) { hiddenOpen = false }
    }
}

/** A small pill for Home's editing: Done, Hidden Apps. */
@Composable
private fun EditPill(label: String, filled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier
            .height(30.dp)
            .background(if (filled) t.fill else t.text.copy(alpha = 0.14f), CircleShape)
            .pressable(onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, T.subhead.copy(fontWeight = FontWeight.SemiBold), if (filled) t.onFill else t.text, maxLines = 1)
    }
}

/**
 * Which page, as CarPlay's dots show it; the lit one slides along with the finger. Always
 * there, under the dashboard too, so the apps' pages are found.
 */
@Composable
private fun PageDots(pager: PagerState, t: app.leopard.cockpit.ui.theme.Tokens, modifier: Modifier) {
    Row(
        modifier.height(DotsHeight).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(pager.pageCount) { i ->
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        val at = pager.currentPage + pager.currentPageOffsetFraction
                        val near = 1f - abs(i - at).coerceIn(0f, 1f)
                        alpha = 0.35f + 0.65f * near
                        val s = 1f + 0.15f * near
                        scaleX = s; scaleY = s
                    }
                    .background(t.text, CircleShape),
            )
        }
    }
}

/**
 * The dashboard's map, drawn over the first page and turning with it (see
 * [Dashboard] for the room it has there). Gone once that page has turned away
 * all the way, which gives the cluster its map back.
 */
@Composable
private fun DashboardMap(pager: PagerState, w: Float, h: Float, inWindow: Array<Offset>, mapOpen: Animatable<Float, AnimationVector1D>) {
    val density = LocalDensity.current
    val nav = LocalNav.current
    // Where the pages are, the dashboard at 0. Read while drawing only: a page turn
    // moves the map without composing it (and its app's touch targets) again each frame.
    val at = { pager.currentPage + pager.currentPageOffsetFraction }
    // Turning, the map slides under the sidebar, whose glass can't blur its picture
    // (it goes straight to the screen): the sidebar is nearly solid meanwhile (see Nav.direct).
    val under by remember(pager) { derivedStateOf { at().let { it > 0f && it < 1f } } }
    val gone by remember(pager) { derivedStateOf { at() >= 1f } }
    val still by remember(pager) { derivedStateOf { at() == 0f } }
    DisposableEffect(under) {
        if (under) nav.direct = true
        onDispose { if (under) nav.direct = false }
    }
    DisposableEffect(Unit) { onDispose { nav.dashboardMap = Rect.Zero } }
    if (gone || w <= 0f || h <= 0f) {
        SideEffect { nav.dashboardMap = Rect.Zero }
        return
    }
    val px = { d: androidx.compose.ui.unit.Dp -> with(density) { d.toPx() } }
    // Right of the sidebar, from its top to the dots' strip (see Dashboard). From the layout,
    // not from where it's drawn: Home zooming in or out mustn't resize the map app.
    val margin = px(SidebarMargin)
    val mapW = (w - px(ContentStart) - margin - px(WindowGap)) * MAP_SHARE
    val left = px(ContentStart)
    val pane = Rect(left, margin, left + mapW, h - margin)
    // There, an app window of the map lands on it on the way back to Home.
    SideEffect {
        val o = inWindow[0]
        nav.dashboardMap = if (still && mapOpen.value == 0f) pane.translate(o.x, o.y) else Rect.Zero
    }
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = -at() * w }) {
        MapWidget(
            slot = MapSlot,
            pane = pane,
            // Opened up, all of Home beside the sidebar, as an app's window.
            full = Rect(0f, 0f, w, h),
            editing = false,
            onEditing = {},
            onMenu = { _, _ -> },
            onRemove = {},
            progress = mapOpen,
            rail = ContentStart,
            // A tap opens the map app as any app, full screen, growing out of the card
            // (and back into it on Home).
            onOpen = { id ->
                val o = inWindow[0]
                nav.launchOrigins[id] = Origin(pane.translate(o.x, o.y), px(28.dp), Origin.Kind.LIVE)
                nav.openScreens()
                Otility.instance.screens.tap(id, ScreenId.MAIN) { nav.openScreens(it) }
            },
        )
    }
}

/**
 * The dashboard, from the sidebar's top to the dots' strip, its sides where an app's window
 * has them: room for the map down the left ([DashboardMap] draws it there), two stacks of
 * cards beside it. A swipe up or down on a stack goes through its cards, as an iPhone's
 * widget stack; a swipe sideways on the cards or between them turns the page; on the map
 * it pans the map. While Home is edited, a tap on a stack chooses its cards.
 */
@Composable
private fun Dashboard(home: CarHome, editing: Boolean, onPick: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = ContentStart, end = SidebarMargin, top = SidebarMargin, bottom = SidebarMargin)) {
        val mapW = (maxWidth - WindowGap) * MAP_SHARE
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.width(mapW + WindowGap))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(WindowGap)) {
                CardStack(home.top, editing, { onPick(true) }, Modifier.weight(1f).fillMaxWidth())
                CardStack(home.bottom, editing, { onPick(false) }, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * One of the dashboard's stacks: its cards one above the other, swiped through up and down,
 * dots at its side saying which. Notifications is left out while there are none, and comes
 * to the front when one comes in.
 */
@Composable
private fun CardStack(cards: List<DashCard>, editing: Boolean, onPick: () -> Unit, modifier: Modifier) {
    val t = LocalTokens.current
    val notes by PhoneNotes.list.collectAsState()
    val shown = cards.filter { it != DashCard.NOTIFICATIONS || notes.isNotEmpty() }.ifEmpty { cards }
    val pager = rememberPagerState { shown.size }
    val newest = notes.firstOrNull()?.uid
    val seen = remember { arrayOf(newest) }
    LaunchedEffect(newest) {
        if (newest == null || newest == seen[0]) return@LaunchedEffect
        seen[0] = newest
        val i = shown.indexOf(DashCard.NOTIFICATIONS)
        if (i >= 0) pager.animateScrollToPage(i)
    }
    Box(modifier) {
        VerticalPager(
            pager,
            Modifier.fillMaxSize(),
            pageSpacing = WindowGap,
            key = { shown.getOrNull(it)?.name ?: it },
            userScrollEnabled = !editing,
        ) { i ->
            shown.getOrNull(i)?.let { DashCardView(it, Modifier.fillMaxSize()) }
        }
        if (shown.size > 1) StackDots(pager, Modifier.align(Alignment.CenterEnd).padding(end = 7.dp))
        if (editing) {
            // Over the stack while editing: its cards don't take touches, a tap chooses them.
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(rounded(CardCorner))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pressable(onPick, scaleDown = 1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.background(t.solidGlass.copy(alpha = 0.94f), CircleShape).padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(Icons.layouts, t.text, size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Txt("Edit Stack", T.headline)
                    }
                    Spacer(Modifier.height(8.dp))
                    Txt(
                        if (cards.size == 1) cards[0].title else "${cards.size} cards: ${cards.joinToString(", ") { it.title }}",
                        T.footnote, Color.White.copy(alpha = 0.8f), maxLines = 1,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }
}

/** The corner of the dashboard's cards, for what's laid over them. */
private val CardCorner = 24.dp

@Composable
private fun DashCardView(card: DashCard, modifier: Modifier) {
    when (card) {
        DashCard.NOW_PLAYING -> NowPlayingCard(MediaSlot, modifier)
        DashCard.NAVIGATION -> NavigationCard(NavSlot, modifier)
        DashCard.NOTIFICATIONS -> NotificationsCard(modifier)
        DashCard.ENERGY -> DashboardWidget(WidgetKind.ENERGY, modifier)
        DashCard.CLIMATE -> DashboardWidget(WidgetKind.CLIMATE, modifier)
        DashCard.TYRES -> DashboardWidget(WidgetKind.TYRES, modifier)
        DashCard.DRIVE -> DashboardWidget(WidgetKind.DRIVE, modifier)
        DashCard.SOUND -> DashboardWidget(WidgetKind.SOUND, modifier)
        DashCard.WEEK -> DashboardWidget(WidgetKind.WEEK, modifier)
    }
}

/** Which card of a stack is showing: small dots down its side, the lit one following the finger. */
@Composable
private fun StackDots(pager: PagerState, modifier: Modifier) {
    val t = LocalTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(pager.pageCount) { i ->
            Box(
                Modifier
                    .size(5.dp)
                    .graphicsLayer {
                        val at = pager.currentPage + pager.currentPageOffsetFraction
                        alpha = 0.3f + 0.7f * (1f - abs(i - at).coerceIn(0f, 1f))
                    }
                    .background(t.text, CircleShape),
            )
        }
    }
}

/** A card's symbol and colour, in the stack's chooser. */
private fun DashCard.icon(): ImageVector = when (this) {
    DashCard.NOW_PLAYING -> Icons.music
    DashCard.NAVIGATION -> Icons.navigation
    DashCard.NOTIFICATIONS -> Icons.bell
    DashCard.ENERGY -> Icons.energy
    DashCard.CLIMATE -> Icons.climate
    DashCard.TYRES -> Icons.tire
    DashCard.DRIVE -> Icons.gauge
    DashCard.SOUND -> Icons.sound
    DashCard.WEEK -> Icons.trip
}

private fun DashCard.color(): Color = Color(
    when (this) {
        DashCard.NOW_PLAYING -> 0xFFFF2D55
        DashCard.NAVIGATION -> 0xFF0A84FF
        DashCard.NOTIFICATIONS -> 0xFFFF3B30
        DashCard.ENERGY -> 0xFF30D158
        DashCard.CLIMATE -> 0xFF64D2FF
        DashCard.TYRES -> 0xFF8E8E93
        DashCard.DRIVE -> 0xFFFF9F0A
        DashCard.SOUND -> 0xFFBF5AF2
        DashCard.WEEK -> 0xFF5E5CE6
    },
)

/**
 * A page of apps, each in its place of [grid] (as the editing's touches find them), sliding
 * to a new one as an app is moved among them. While editing they wiggle, with a "−" to take
 * one off Home; the one being moved is left out (it's under the finger).
 */
@Composable
private fun AppPage(
    items: List<HomeItem>, grid: Grid, cellW: Dp, editing: Boolean, wiggle: State<Float>?, moving: String?,
    onEdit: () -> Unit,
) {
    val prefs = Otility.instance.prefs
    Box(Modifier.fillMaxSize()) {
        items.forEachIndexed { i, item ->
            key(item.key) {
                val c = grid.cell(i)
                val at by animateIntOffsetAsState(IntOffset(c.x.roundToInt(), c.y.roundToInt()), spring(0.8f, 380f), label = "tile")
                val cell = Modifier
                    .offset { at }
                    .width(cellW)
                    .padding(top = TileTop)
                    .graphicsLayer { alpha = if (item.key == moving) 0f else 1f }
                when {
                    editing -> EditTile(item, i, wiggle, cell)
                    item is HomeItem.App -> AppTile(
                        item.info, IconSize, cell, label = T.subhead,
                        more = listOf(
                            MenuGap,
                            MenuItem("Edit Home Screen", Icons.apps, action = onEdit),
                            MenuItem("Hide from Home", Icons.minus) { prefs.setCarHome(prefs.carHome.value.hide(item.key)) },
                        ),
                    )
                    else -> SettingsTile(cell)
                }
            }
        }
    }
}

/** An app while Home is edited: wiggling, with its "−" (not Settings: it's how to get them back). */
@Composable
private fun EditTile(item: HomeItem, i: Int, wiggle: State<Float>?, modifier: Modifier) {
    // Neighbours wiggle the other way, so the page doesn't sway as one.
    val dir = if (i % 2 == 0) 1f else -1f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.graphicsLayer { rotationZ = (wiggle?.value ?: 0f) * WIGGLE_DEG * dir }) {
            HomeIcon(item)
            if (item.key != CarHome.SETTINGS) RemoveBadge(Modifier.align(Alignment.TopStart).offset((-9).dp, (-9).dp))
        }
        Spacer(Modifier.height(8.dp))
        Txt(item.label, T.subhead.copy(textAlign = TextAlign.Center), maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(9.dp))
    }
}

private val HomeItem.label get() = when (this) {
    is HomeItem.App -> info.label
    HomeItem.Settings -> "Settings"
}

/** An app's icon on Home, alone (the one being moved, under the finger). */
@Composable
private fun HomeIcon(item: HomeItem) {
    when (item) {
        is HomeItem.App -> AppIcon(item.info, IconSize)
        HomeItem.Settings -> SettingsIcon(Modifier)
    }
}

/** The "−" on an app while Home is edited: tap it to take the app off Home. */
@Composable
private fun RemoveBadge(modifier: Modifier) {
    Box(
        modifier
            .size(26.dp)
            .shadow(4.dp, CircleShape)
            .background(Color(0xFF48484A), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 11.dp, height = 2.5.dp).background(Color.White, CircleShape))
    }
}

/**
 * A panel over Home while it's edited: a stack's cards, the hidden apps. Over a dimmed Home,
 * in the room beside the sidebar; a tap outside it, Done or Back closes it.
 */
@Composable
private fun HomeSheet(title: String, note: String?, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(0.86f, 500f)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .padding(start = ContentStart, end = SidebarMargin, top = SidebarMargin, bottom = SidebarMargin),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .graphicsLayer { val s = 0.94f + 0.06f * shown.value; scaleX = s; scaleY = s }
                .shadow(24.dp, rounded(28.dp))
                .background(t.solidGlass, rounded(28.dp))
                .clip(rounded(28.dp))
                // Taps on the panel are its own, not the dimmed Home's.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = 20.dp, bottom = 12.dp),
        ) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(title, T.title2, modifier = Modifier.weight(1f))
                EditPill("Done", filled = true, onClose)
            }
            if (note != null) {
                Spacer(Modifier.height(6.dp))
                Txt(note, T.subhead, t.text2, modifier = Modifier.padding(horizontal = 24.dp))
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** A heading over a group of the panel's rows. */
@Composable
private fun SheetHeader(text: String) {
    Txt(text.uppercase(), T.footnote, LocalTokens.current.text2, modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp))
}

/** A round "+" or "−" at a row's end, as an iPhone's lists have them for adding and taking away. */
@Composable
private fun AddRemove(add: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.3f }
            .background(if (add) Color(0xFF30D158) else Color(0xFFFF453A), CircleShape)
            .pressable(onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 13.dp, height = 2.5.dp).background(Color.White, CircleShape))
        if (add) Box(Modifier.size(width = 2.5.dp, height = 13.dp).background(Color.White, CircleShape))
    }
}

/**
 * Choosing a stack's cards: those in it (the first shows when Home opens), then the rest,
 * each with its "+" or "−". A card is in one stack at most, so adding one from the other
 * stack moves it; a stack keeps at least one.
 */
@Composable
private fun StackPicker(first: Boolean, home: CarHome, onClose: () -> Unit) {
    val prefs = Otility.instance.prefs
    val t = LocalTokens.current
    val mine = home.stack(first)
    val others = home.stack(!first)
    fun change(f: (CarHome) -> CarHome) = prefs.setCarHome(f(prefs.carHome.value))
    HomeSheet(
        if (first) "Top Stack" else "Bottom Stack",
        "Swipe up or down on the stack to go through its cards. The first one shows when Home opens.",
        onClose,
    ) {
        LazyColumn(Modifier.weight(1f, fill = false)) {
            item { SheetHeader("In this stack") }
            items(mine, key = { "in" + it.name }) { card ->
                val lead = card == mine.first()
                CardRow(
                    card,
                    note = when {
                        mine.size == 1 -> "A stack keeps at least one card"
                        lead && card == DashCard.NOTIFICATIONS -> "Shows first, while there are some"
                        lead -> "Shows first"
                        card == DashCard.NOTIFICATIONS -> "Only while there are some"
                        else -> null
                    },
                ) {
                    if (!lead) {
                        EditPill("Show First", filled = false) { change { it.lead(first, card) } }
                        Spacer(Modifier.width(12.dp))
                    }
                    AddRemove(add = false, enabled = home.canToggle(first, card)) { change { it.toggle(first, card) } }
                }
            }
            item { SheetHeader("More cards") }
            items(DashCard.entries.filter { it !in mine }, key = { "out" + it.name }) { card ->
                val there = card in others
                val can = home.canToggle(first, card)
                CardRow(
                    card,
                    note = when {
                        there && !can -> "The other stack's only card"
                        there -> "In the other stack: adding it moves it here"
                        card == DashCard.NOTIFICATIONS -> "Only while there are some"
                        else -> null
                    },
                ) {
                    AddRemove(add = true, enabled = can) { change { it.toggle(first, card) } }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Txt(
            "To change the other stack, tap it on Home.",
            T.footnote, t.text2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CardRow(card: DashCard, note: String?, trailing: @Composable () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(card.color(), rounded(10.dp)), contentAlignment = Alignment.Center) {
            Glyph(card.icon(), Color.White, size = 22.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(card.title, T.body, maxLines = 1)
            if (note != null) Txt(note, T.footnote, t.text2, maxLines = 1)
        }
        trailing()
    }
}

/**
 * The apps taken off Home, each with a "+" to put it back (at the end). And Reset Home
 * Screen, which puts every app back in the car's order (a second tap to do it).
 */
@Composable
private fun HiddenApps(items: List<HomeItem>, home: CarHome, onClose: () -> Unit) {
    val prefs = Otility.instance.prefs
    val t = LocalTokens.current
    val hidden = items.filter { it.key in home.hidden }
    var sure by remember { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { delay(4000); sure = false } }
    HomeSheet(
        "Hidden Apps",
        if (hidden.isEmpty()) "None. While the apps wiggle, tap the − on one to hide it here."
        else "Tap + to put an app back on Home. It goes at the end.",
        onClose,
    ) {
        if (hidden.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(hidden, key = { it.key }) { item ->
                    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item is HomeItem.App) AppIcon(item.info, 42.dp)
                        Spacer(Modifier.width(14.dp))
                        Txt(item.label, T.body, maxLines = 1, modifier = Modifier.weight(1f))
                        AddRemove(add = true, enabled = true) { prefs.setCarHome(prefs.carHome.value.show(item.key, items.map { it.key })) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(1.dp).background(t.sep))
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .pressable({
                    if (!sure) sure = true
                    else {
                        val now = prefs.carHome.value
                        prefs.setCarHome(CarHome(top = now.top, bottom = now.bottom))
                        sure = false
                        onClose()
                    }
                }, scaleDown = 0.98f)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(if (sure) "Tap Again to Reset" else "Reset Home Screen", T.body.copy(fontWeight = FontWeight.SemiBold), t.destructive, modifier = Modifier.weight(1f))
            Txt("Every app back, in the car's order", T.footnote, t.text2, maxLines = 1)
        }
    }
}

/** Settings, as one of Home's apps: a grey gear icon. A red dot: a new version of Outo is ready. */
@Composable
private fun SettingsTile(modifier: Modifier) {
    val nav = LocalNav.current
    val t = LocalTokens.current
    val update by Otility.instance.updater.state.collectAsState()
    val badge = (update is UpdateState.Available || update is UpdateState.Ready)
    val open = {
        // The dot is a new version: Settings opens where it's installed.
        if (badge) nav.settingsPage = SettingsPage.ABOUT
        // Its window grows out of this tile.
        if (nav.settingsTile.width > 0f) nav.settingsOrigin = Origin.icon(nav.settingsTile)
        nav.tab = Tab.SETTINGS
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.pressable(open)) {
            SettingsIcon(Modifier.onGloballyPositioned { nav.settingsTile = it.boundsInRoot() })
            if (badge) {
                Box(Modifier.align(Alignment.TopEnd).offset(5.dp, (-5).dp).size(18.dp).background(t.destructive, CircleShape))
            }
        }
        Spacer(Modifier.height(8.dp))
        Txt("Settings", T.subhead.copy(textAlign = TextAlign.Center), maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
        // The room AppTile's dot takes, so the names line up.
        Spacer(Modifier.height(9.dp))
    }
}

/** Settings' icon: a white gear on grey. */
@Composable
private fun SettingsIcon(modifier: Modifier) {
    val shape = rounded(IconSize * 0.225f)
    Box(
        modifier
            .size(IconSize)
            // As AppIcon: not on CarPlay's black, where it can't be seen.
            .then(if (!LocalTokens.current.dark) Modifier.shadow(IconSize * 0.12f, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f)) else Modifier)
            .clip(shape)
            .background(SettingsTileBrush),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(Icons.gear, Color.White, size = IconSize * 0.64f)
    }
}
