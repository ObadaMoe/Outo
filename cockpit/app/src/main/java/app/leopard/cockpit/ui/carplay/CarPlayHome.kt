package app.leopard.cockpit.ui.carplay

import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.platform.LocalDensity
import app.leopard.cockpit.data.WidgetKind
import app.leopard.cockpit.data.WidgetSize
import app.leopard.cockpit.data.WidgetSlot
import app.leopard.cockpit.ui.LocalBackdrop
import app.leopard.cockpit.ui.overview.MapWidget
import app.leopard.cockpit.ui.overview.NavigationCard
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
private const val EMBLEM_ALPHA = 0.4f

/** An app's place on Home: as many columns and rows as fit, CarPlay's big icons. */
private val CellWidth = 176.dp
private val CellHeight = 168.dp
private val IconSize = 96.dp
/** The page dots' room under the apps. */
private val Dots = 40.dp
/** Room right of the apps. */
private val EndGap = 28.dp

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
 * Settings first among them. Always dark, as CarPlay's is. Home on the sidebar,
 * tapped again here, goes back to the dashboard.
 *
 * While the dashboard is on the screen the map is here, all of it, and the driver
 * cluster shows BYD's own view; turned to the apps (or anywhere else), the cluster
 * has the map back.
 */
@Composable
fun CarPlayHome() {
    val app = Otility.instance
    val settings by app.prefs.settings.collectAsState()
    val dark = remember(settings.accent, settings.reduceTransparency, settings.tintedGlass) {
        tokens(true, settings.accent, settings.reduceTransparency, settings.tintedGlass)
    }
    val all by app.catalog.apps.collectAsState()
    val items = remember(all) { listOf<HomeItem>(HomeItem.Settings) + all.map { HomeItem.App(it) } }
    OtilityTheme(dark) {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            // The wallpaper, under the car's bars too, as the screens' photo is. The
            // cards' glass blurs it.
            BoxWithConstraints(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                Image(
                    painterResource(R.drawable.emblem), null,
                    Modifier
                        .align(Alignment.Center)
                        .size(min(maxHeight * 0.46f, maxWidth * 0.3f))
                        .graphicsLayer { alpha = EMBLEM_ALPHA },
                )
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

@Composable
private fun Pages(items: List<HomeItem>) {
    val nav = LocalNav.current
    val t = LocalTokens.current
    // Where this box is in the window (for the map card's place there).
    val inWindow = remember { arrayOf(Offset.Zero) }
    // How far the map is opened up, 0 to 1: the pages fade under it, and stay put.
    val mapOpen = remember { Animatable(0f) }
    val mapClosed by remember { derivedStateOf { mapOpen.value == 0f } }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { inWindow[0] = it.positionInRoot() }) {
        // The pages span the window, so one turning slides under the sidebar's glass;
        // what's on them keeps right of it.
        val gridWidth = maxWidth - SideInset - EndGap
        val cols = (gridWidth / CellWidth).toInt().coerceAtLeast(3)
        val cellW = gridWidth / cols
        val rows = ((maxHeight - SidebarMargin * 2 - Dots) / CellHeight).toInt().coerceAtLeast(1)
        val appPages = items.chunked(cols * rows)
        // On the page it was on: from an app, Home comes back where it was left.
        val pager = rememberPagerState(initialPage = nav.homePage.coerceIn(0, appPages.size)) { 1 + appPages.size }
        LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { nav.homePage = it } }
        val scope = rememberCoroutineScope()
        // Home tapped again, here: the dashboard, the pages gliding back.
        DisposableEffect(nav, pager) {
            val first: () -> Unit = { scope.launch { pager.animateScrollToPage(0, animationSpec = spring(0.92f, 220f)) } }
            nav.home = first
            onDispose { if (nav.home === first) nav.home = null }
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - mapOpen.value.coerceIn(0f, 1f) }) {
            HorizontalPager(
                pager,
                Modifier.fillMaxSize(),
                key = { it },
                userScrollEnabled = mapClosed,
            ) { page ->
                if (page == 0) Dashboard()
                else AppPage(appPages.getOrNull(page - 1).orEmpty(), cols, cellW, rows)
            }
            PageDots(pager, t, Modifier.align(Alignment.BottomCenter).padding(start = ContentStart, end = SidebarMargin, bottom = SidebarMargin + 10.dp))
        }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        DashboardMap(pager, w, h, inWindow, mapOpen)
    }
}

/**
 * Which page, as CarPlay's dots show it; the lit one slides along with the finger. Over
 * the apps they stay; over the dashboard, where they'd sit on the map, they show while
 * the pages turn and a moment after (on a dark pill there), then fade.
 */
@Composable
private fun PageDots(pager: PagerState, t: app.leopard.cockpit.ui.theme.Tokens, modifier: Modifier) {
    var lately by remember { mutableStateOf(true) }
    LaunchedEffect(pager.settledPage) {
        lately = true
        delay(1600)
        lately = false
    }
    val show = pager.currentPage > 0 || pager.isScrollInProgress || lately
    val shown by animateFloatAsState(if (show) 1f else 0f, tween(if (show) 160 else 480), label = "dots")
    Row(
        modifier
            .graphicsLayer { alpha = shown }
            .drawBehind {
                // Over the dashboard: a dark pill, so they read on the map.
                val at = pager.currentPage + pager.currentPageOffsetFraction
                val a = (1f - at).coerceIn(0f, 1f) * 0.45f
                if (a > 0f) drawRoundRect(Color.Black, alpha = a, cornerRadius = CornerRadius(size.height / 2f))
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
    // Where the pages are, the dashboard at 0.
    val at = pager.currentPage + pager.currentPageOffsetFraction
    // Turning, the map slides under the sidebar, whose glass can't blur its picture
    // (it goes straight to the screen): the sidebar is nearly solid meanwhile (see Nav.direct).
    val under = at > 0f && at < 1f
    DisposableEffect(under) {
        if (under) nav.direct = true
        onDispose { if (under) nav.direct = false }
    }
    DisposableEffect(Unit) { onDispose { nav.dashboardMap = Rect.Zero } }
    if (at >= 1f || w <= 0f || h <= 0f) {
        SideEffect { nav.dashboardMap = Rect.Zero }
        return
    }
    val px = { d: androidx.compose.ui.unit.Dp -> with(density) { d.toPx() } }
    // As tall as the sidebar, right of it (see Dashboard). From the layout, not from where
    // it's drawn: Home zooming in or out mustn't resize the map app.
    val margin = px(SidebarMargin)
    val mapW = (w - px(ContentStart) - margin - px(WindowGap)) * MAP_SHARE
    val left = px(ContentStart) - at * w
    val pane = Rect(left, margin, left + mapW, h - margin)
    // There, an app window of the map lands on it on the way back to Home.
    SideEffect {
        val o = inWindow[0]
        nav.dashboardMap = if (at == 0f && mapOpen.value == 0f) pane.translate(o.x, o.y) else Rect.Zero
    }
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
    )
}

/**
 * The dashboard, as tall as the sidebar, its edges where an app's window has them: room
 * for the map down the left ([DashboardMap] draws it there), Now Playing and Navigation beside it.
 */
@Composable
private fun Dashboard() {
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = ContentStart, end = SidebarMargin, top = SidebarMargin, bottom = SidebarMargin)) {
        val mapW = (maxWidth - WindowGap) * MAP_SHARE
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.width(mapW + WindowGap))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(WindowGap)) {
                NowPlayingCard(MediaSlot, Modifier.weight(1f).fillMaxWidth())
                NavigationCard(NavSlot, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** A page of apps, in the middle of the page, filled from the top. */
@Composable
private fun AppPage(items: List<HomeItem>, cols: Int, cellW: androidx.compose.ui.unit.Dp, rows: Int) {
    // Above the dots, between the sidebar's top and bottom.
    Column(
        Modifier.fillMaxSize().padding(start = SideInset, end = EndGap, top = SidebarMargin, bottom = SidebarMargin + Dots),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.height(CellHeight * rows)) {
            items.chunked(cols).forEach { line ->
                Row(Modifier.height(CellHeight)) {
                    line.forEach { item ->
                        key(item.key) {
                            val cell = Modifier.width(cellW).padding(top = 12.dp)
                            when (item) {
                                is HomeItem.App -> AppTile(item.info, IconSize, cell, label = T.subhead)
                                HomeItem.Settings -> SettingsTile(cell)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Settings, as one of Home's apps: a grey gear icon. A red dot: a new version of Outo is ready. */
@Composable
private fun SettingsTile(modifier: Modifier) {
    val nav = LocalNav.current
    val t = LocalTokens.current
    val update by Otility.instance.updater.state.collectAsState()
    val badge = update is UpdateState.Available
    val open = {
        // The dot is a new version: Settings opens where it's installed.
        if (badge) nav.settingsPage = SettingsPage.ABOUT
        nav.tab = Tab.SETTINGS
    }
    val shape = rounded(IconSize * 0.225f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.pressable(open)) {
            Box(
                Modifier
                    .size(IconSize)
                    .shadow(IconSize * 0.12f, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF9A9AA0), Color(0xFF4A4A4F)))),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(Icons.gear, Color.White, size = IconSize * 0.64f)
            }
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
