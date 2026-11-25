package app.leopard.cockpit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import app.leopard.cockpit.ui.carplay.Motion
import app.leopard.cockpit.ui.carplay.Origin
import app.leopard.cockpit.ui.carplay.tabMotion
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import app.leopard.cockpit.ui.theme.rememberSunDark
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.ui.screens.ClusterPeek
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.data.Appearance
import app.leopard.cockpit.data.Tab
import app.leopard.cockpit.data.UiMode
import app.leopard.cockpit.ui.carplay.AppSwitcherHost
import app.leopard.cockpit.ui.carplay.CarPlayApps
import app.leopard.cockpit.ui.carplay.CarPlayHome
import app.leopard.cockpit.ui.carplay.CarPlaySettings
import app.leopard.cockpit.ui.carplay.CarPlayNowPlaying
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.ui.apps.AppsTab
import app.leopard.cockpit.ui.overview.OverviewTab
import app.leopard.cockpit.ui.screens.AppPoster
import app.leopard.cockpit.ui.screens.HostSurface
import app.leopard.cockpit.ui.screens.LiftCallbacksFactory
import app.leopard.cockpit.ui.screens.ScreensTab
import app.leopard.cockpit.ui.settings.SettingsPage
import app.leopard.cockpit.ui.settings.SettingsTab
import app.leopard.cockpit.ui.theme.OtilityTheme
import app.leopard.cockpit.ui.theme.tokens
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class SheetKind { SOUND_STUDIO, ADD_WIDGETS }

/** Where the driver is in Otility. */
class Nav(start: Tab) {
    var tab by mutableStateOf(start)
    /** Zoomed into a screen: the sidebar steps aside. */
    var immersive by mutableStateOf(false)
    /** Zoomed in, the sidebar called back over the app (two fingers up, or the grabber). */
    var sidebarPeek by mutableStateOf(false)
    /** The sidebar, in the window's pixels (empty while it's hidden). */
    var sidebarBounds by mutableStateOf(Rect.Zero)
    /** The middle of each of the sidebar's apps, top to bottom, in the window's pixels. */
    val sidebarCenters = HashMap<AppId, Float>()
    /** An app being dragged anywhere in Otility, drawn under the finger above everything. */
    var dragging by mutableStateOf<AppId?>(null)
    var dragAt by mutableStateOf(Offset.Zero)
    /** The menu of something held (an app, Layouts), drawn above the sidebar. Its anchor is in the window's pixels. */
    var menu by mutableStateOf<MenuRequest?>(null)
    /** While the Screens tab is open: how it handles the sidebar's apps (drag onto a screen, its menu). */
    var dockLift: LiftCallbacksFactory? = null
    /** What tapping the open tab's button on the sidebar again does (zoom out of a screen, say). */
    var reselect: (() -> Unit)? = null
    /** What tapping Home on Home does when nothing above has claimed it (CarPlay's Home: back to its dashboard). */
    var home: (() -> Unit)? = null
    /** A Back button at the top of the sidebar, for a page opened up over its tab (the large map). */
    var back by mutableStateOf<(() -> Unit)?>(null)
    /**
     * The screen zoomed into (or being zoomed into or out of) has its pictures going
     * straight to the car's screen. Glass then doesn't redraw what's behind it: that
     * would draw their views a second time, and Android would place a picture by the
     * wrong one.
     */
    var direct by mutableStateOf(false)
    var sheet by mutableStateOf<SheetKind?>(null)
    var settingsPage by mutableStateOf(SettingsPage.DISPLAY)
    /** Another tab asked the Screens tab to zoom into a screen. */
    var zoomRequest by mutableStateOf<ScreenId?>(null)
    /** Something over the Screens tab asked it to zoom out (the cluster's map, opened up, two fingers down). */
    var zoomOutRequest by mutableStateOf(false)
    /**
     * An app opened from another tab (Overview, Apps, Settings): the Screens tab opens
     * straight on it, the app growing out of this point (its icon, in the window's
     * pixels; unspecified: the middle) as on an iPhone, rather than the camera flying in
     * from the dashboard.
     */
    var launchFrom: Offset? = null
    /**
     * CarPlay mode: what an app opening grows out of (the icon tapped, or its card in the
     * app switcher), taken by its window when it shows (see CarPlayApps).
     */
    internal val launchOrigins = HashMap<AppId, Origin>()
    /** Each sidebar app's icon, in the window's pixels: CarPlay mode's windows shrink back into it. */
    val sidebarIcons = HashMap<AppId, Rect>()
    /**
     * CarPlay's dashboard map, in the window's pixels, while Home is on the dashboard (empty
     * otherwise): the map app's window shrinks back into it.
     */
    var dashboardMap = Rect.Zero
    /**
     * The Screens tab as all three screens, in CarPlay mode (which otherwise shows the main
     * screen's apps beside the sidebar there): opened from Settings, or for another screen.
     */
    var allScreens by mutableStateOf(false)
    /** The page CarPlay's Home was on, for when it opens again (Home tapped there goes to the first). */
    var homePage by androidx.compose.runtime.mutableIntStateOf(0)
    /** CarPlay mode: Settings' tile tapped, which its window grows out of (see CarPlaySettings). */
    internal var settingsOrigin: Origin? = null
    /** CarPlay mode: Settings' tile on Home, in the window's pixels: its window shrinks back into it. */
    var settingsTile = Rect.Zero
    /** CarPlay mode: Home's Now Playing card tapped, which the full player grows out of. */
    internal var nowPlayingOrigin: Origin? = null
    /** CarPlay mode: Home's Now Playing card, in the window's pixels: the full player shrinks back into it. */
    var nowPlayingCard = Rect.Zero
    /** CarPlay mode is on (set by [App]). */
    var carPlay = false
    /** CarPlay mode's apps are on the screen, under the floating sidebar (set by [App]). */
    var appsShown by mutableStateOf(false)
    /**
     * CarPlay mode, over its apps: the sidebar dragged off the left edge, a handle there
     * to bring it back. It comes back by itself on Home.
     */
    var sidebarHidden by mutableStateOf(false)
    /** CarPlay mode, over the apps: when the sidebar was last touched (uptime), for hiding it once it's left alone. */
    var sidebarTouched by androidx.compose.runtime.mutableLongStateOf(0L)
    /** How far the sidebar is pulled off to the left, in pixels (0: in its place). */
    val sidebarShift = androidx.compose.animation.core.Animatable(0f)
    /**
     * CarPlay mode, while its apps are shown: a sidebar app let go at this point (the
     * window's pixels) goes into that half, next to what's open. False if it isn't over them.
     */
    var dropOnApps: ((AppId, Offset) -> Boolean)? = null
    fun openScreens(zoom: ScreenId? = null) {
        if (carPlay && zoom != null && zoom != ScreenId.MAIN) {
            // CarPlay mode has no car photo: the cluster's map opens up over the main
            // screen instead, and anything else is shown where CarPlay shows apps.
            val screens = Otility.instance.screens
            if (zoom == ScreenId.DRIVER) {
                val map = screens.state.value[ScreenId.DRIVER].apps.firstOrNull { screens.keepsOnCluster(it) }
                if (map != null && screens.peekClusterMap(map) { tab = Tab.SCREENS }) return
            }
            tab = Tab.SCREENS
            zoomRequest = null
            allScreens = false
            return
        }
        tab = Tab.SCREENS
        zoomRequest = zoom
        allScreens = zoom != null && zoom != ScreenId.MAIN
    }
    /** All three screens, on the car photo, in either mode. */
    fun openAllScreens() {
        tab = Tab.SCREENS
        zoomRequest = null
        allScreens = true
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }

/** Otility's window size in pixels (hosted apps are sized from it). */
val LocalWindowPx = staticCompositionLocalOf { IntSize(1920, 906) }

@Composable
fun OtilityApp(app: Otility) {
    val settings by app.prefs.settings.collectAsState()
    val dark = when (settings.appearance) {
        Appearance.LIGHT -> false
        Appearance.DARK -> true
        // By the sun: the car's own Android is dark for good (see DayNight).
        Appearance.AUTO -> rememberSunDark()
    }
    val t = remember(dark, settings.accent, settings.reduceTransparency, settings.tintedGlass) {
        tokens(dark, settings.accent, settings.reduceTransparency, settings.tintedGlass)
    }
    val nav = remember { Nav(settings.startTab) }
    // A startup app on the main screen: open on it, zoomed in (once, when Otility starts).
    // In CarPlay mode, beside the sidebar.
    LaunchedEffect(Unit) {
        val zoom = app.screens.takeStartupZoom()
        if (zoom != null && app.prefs.settings.value.mode == UiMode.CARPLAY) nav.openScreens(zoom)
        else if (zoom != null && nav.tab == Tab.SCREENS) nav.zoomRequest = zoom
    }
    val carPlay = settings.mode == UiMode.CARPLAY
    nav.carPlay = carPlay
    // CarPlay mode: the main screen's apps, beside the sidebar (the Screens tab, unless
    // it's opened as all three screens). Leaving them (Home, Back, another tab) sends them
    // to the background, to the app switcher.
    val appsShown = carPlay && nav.tab == Tab.SCREENS && !nav.allScreens
    var appsWereShown by remember { mutableStateOf(appsShown) }
    LaunchedEffect(appsShown) {
        if (appsWereShown && !appsShown && carPlay) app.screens.sendMainToBack()
        appsWereShown = appsShown
    }
    LaunchedEffect(nav.tab) { if (nav.tab != Tab.SCREENS) nav.allScreens = false }
    nav.appsShown = appsShown
    // Hidden only over the apps: Home has the sidebar back.
    LaunchedEffect(appsShown) { if (!appsShown) nav.sidebarHidden = false }
    // Away, two fingers down bring it back (seen by MainActivity, over the app).
    val revealHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    SidebarReveal.hidden = appsShown && nav.sidebarHidden
    androidx.compose.runtime.DisposableEffect(nav) {
        SidebarReveal.show = {
            revealHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            nav.sidebarTouched = android.os.SystemClock.uptimeMillis()
            nav.sidebarHidden = false
        }
        onDispose { SidebarReveal.show = null; SidebarReveal.hidden = false }
    }
    // Dark status bar icons and home bar in light mode, so they show on the white fog.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    // A place from the phone opened the map on the main screen: show it.
    LaunchedEffect(Unit) {
        app.navigator.show.collect { s ->
            if (s != null) {
                nav.openScreens(s)
                app.navigator.shown()
            }
        }
    }

    // Otility is laid out left to right even when the car runs in Arabic: the tab
    // bar, the fog, the sliders and the floating window are all drawn for it.
    OtilityTheme(t) { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize().background(t.background)) {
            // Back leaves a tab for Home, and stops there: nothing is behind Home. Set up
            // before the tabs, so Back inside a tab (leaving widget editing, the expanded
            // map) goes first.
            BackHandler(enabled = nav.tab != Tab.SCREENS) { if (nav.tab != Tab.OVERVIEW) nav.tab = Tab.OVERVIEW }
            val density = LocalDensity.current
            // The car's bars, for sizing the main screen's apps before the Screens tab is seen.
            val barTop = WindowInsets.systemBars.getTop(density)
            val barBottom = WindowInsets.systemBars.getBottom(density)
            LaunchedEffect(barTop, barBottom) { Otility.instance.screens.mainBars = barTop to barBottom }
            val windowPx = with(density) {
                IntSize(maxWidth.roundToPx(), (maxHeight.roundToPx() - barTop - barBottom).coerceAtLeast(maxHeight.roundToPx() / 2))
            }
            val backdrop = rememberLayerBackdrop()
            // A menu or a drag doesn't outlive its tab.
            LaunchedEffect(nav.tab) {
                nav.menu = null
                nav.dragging = null
                // Nor does the cluster's map, opened up: it goes back to the cluster.
                Otility.instance.screens.closePeek()
            }
            CompositionLocalProvider(LocalNav provides nav, LocalWindowPx provides windowPx, androidx.compose.foundation.LocalIndication provides PressIndication) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    AnimatedContent(
                        nav.tab to appsShown,
                        transitionSpec = { tabMotion(carPlay) },
                        label = "tab",
                    ) { (tab, apps) ->
                        // The Screens tab's car photo, CarPlay's black Home and its apps fill the
                        // screen, and keep their apps and buttons between the car's bars themselves.
                        // The other tabs live between the bars, whole.
                        if (tab == Tab.SCREENS && apps) {
                            // On the way out the windows shrink back into their icons; this page
                            // stays until they're there.
                            val presence by transition.animateFloat({ Motion.shrink() }, label = "apps") {
                                if (it == EnterExitState.PostExit) 0f else 1f
                            }
                            CarPlayApps(presence = { presence }, leaving = transition.targetState == EnterExitState.PostExit)
                        }
                        else if (tab == Tab.SCREENS) ScreensTab()
                        else if (tab == Tab.OVERVIEW && carPlay) CarPlayHome()
                        else if (tab == Tab.NOW_PLAYING) {
                            // Grows out of Home's Now Playing card, and back into it.
                            val grown by transition.animateFloat(
                                { if (targetState == EnterExitState.Visible) Motion.grow() else Motion.shrink() },
                                label = "nowPlaying",
                            ) { if (it == EnterExitState.Visible) 1f else 0f }
                            CarPlayNowPlaying(grown = { grown }, leaving = transition.targetState == EnterExitState.PostExit)
                        }
                        else if (tab == Tab.SETTINGS && carPlay) {
                            // One of Home's apps: it grows out of its tile, and back into it.
                            val grown by transition.animateFloat(
                                { if (targetState == EnterExitState.Visible) Motion.grow() else Motion.shrink() },
                                label = "settings",
                            ) { if (it == EnterExitState.Visible) 1f else 0f }
                            CarPlaySettings(grown = { grown }, leaving = transition.targetState == EnterExitState.PostExit)
                        }
                        else Box(Modifier.fillMaxSize().windowInsetsPadding(CarBars)) {
                            when (tab) {
                                Tab.OVERVIEW -> OverviewTab()
                                Tab.APPS -> AppsTab()
                                Tab.SETTINGS -> SettingsTab()
                                Tab.SCREENS, Tab.NOW_PLAYING -> {}
                            }
                        }
                    }
                }
                val noBackdrop = remember { emptyBackdrop() }
                CompositionLocalProvider(LocalBackdrop provides if (nav.direct) noBackdrop else backdrop) {
                    // The floating window, the sidebar, menus, sheets and alerts: between the bars too.
                    Box(Modifier.fillMaxSize().windowInsetsPadding(CarBars)) {
                        // The cluster's map, opened up over the main screen for a moment.
                        val peek by Otility.instance.screens.peek.collectAsState()
                        peek?.let {
                            key(it) {
                                val screens = Otility.instance.screens
                                ClusterPeek(
                                    it,
                                    onClose = { screens.closePeek() },
                                    // Two fingers left: the screen to the right of the cluster, the main one.
                                    onMain = { screens.closePeek(); nav.openScreens(ScreenId.MAIN) },
                                    // Two fingers down: out, to all three screens.
                                    onOut = {
                                        screens.closePeek()
                                        if (nav.tab == Tab.SCREENS) nav.zoomOutRequest = true
                                    },
                                )
                            }
                        }
                        PipWindow()
                        AnimatedVisibility(
                            // Zoomed in it steps aside, and comes back with the controls
                            // (and with the cluster's map opened up, for its Back).
                            visible = !nav.immersive || nav.sidebarPeek || peek != null,
                            // Down the whole left side, like CarPlay's.
                            modifier = Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing),
                            enter = fadeIn(tween(220)) + slideInHorizontally(spring(0.8f, Spring.StiffnessMediumLow)) { -it },
                            exit = fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { -it },
                        ) {
                            FloatingSidebar()
                        }
                        // Not connected to the car: in the middle of the page beside the sidebar.
                        NotConnectedToast(
                            Modifier.align(Alignment.TopCenter).padding(start = SideInset, top = 14.dp)
                                .windowInsetsPadding(WindowInsets.safeDrawing),
                        )
                        // An iPhone notification, from the top, beside the sidebar.
                        NoteBanner(
                            Modifier.align(Alignment.TopCenter).padding(start = SideInset + 16.dp, end = 16.dp, top = 12.dp)
                                .windowInsetsPadding(WindowInsets.safeDrawing),
                        )
                        // A closed app, for a moment: Undo opens it again.
                        UndoCloseNote(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).windowInsetsPadding(WindowInsets.safeDrawing))
                        // CarPlay mode: two fingers up, the apps and splits used lately.
                        AppSwitcherHost()
                        // Menus are placed in the window's pixels; this layer starts below the car's status bar.
                        val menu = nav.menu
                        ContextMenu(remember(menu, barTop) { menu?.let { it.copy(anchor = it.anchor.translate(0f, -barTop.toFloat())) } }) {
                            nav.menu = null
                        }
                        DraggedApp(Offset(0f, barTop.toFloat()))
                        SheetHost()
                        UpdatePrompts()
                        AlertHost()
                    }
                }
            }
        }
    } }
}

/**
 * The car's own bars: BYD's status bar along the top and its buttons along the
 * bottom are always on screen, over whatever is drawn under them. Otility keeps
 * everything of its own between them.
 */
val CarBars: WindowInsets
    @Composable get() = WindowInsets.systemBars.only(WindowInsetsSides.Vertical)

/**
 * Picture in picture: an app floating over Otility. Drag it anywhere (it settles
 * in the nearest corner); tap to show its controls.
 */
@Composable
private fun PipWindow() {
    val app = Otility.instance
    val pip by app.screens.pip.collectAsState()
    val apps by app.catalog.apps.collectAsState()
    val helper by HelperConnection.helper.collectAsState()
    val windowPx = LocalWindowPx.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val nav = LocalNav.current
    // The app last shown, so the window still has something to shrink away with.
    var last by remember { mutableStateOf<AppId?>(null) }
    if (pip != null) last = pip

    AnimatedVisibility(
        visible = pip != null,
        enter = fadeIn(tween(200)) + scaleIn(spring(0.75f, 400f), initialScale = 0.6f),
        exit = fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.6f),
    ) {
        val current = pip ?: last ?: return@AnimatedVisibility
        // Closing (or moved to a screen), the app has left: only its poster goes.
        val live = pip != null
        val info = apps.firstOrNull { it.id == current }
        val w = 360.dp
        val h = w * (windowPx.height.toFloat() / windowPx.width)
        val wPx = with(density) { w.toPx() }
        val hPx = with(density) { h.toPx() }
        val margin = with(density) { 24.dp.toPx() }
        val bottomGap = with(density) { 110.dp.toPx() }
        val corners = listOf(
            Offset(windowPx.width - wPx - margin, windowPx.height - hPx - bottomGap),
            Offset(windowPx.width - wPx - margin, margin),
            // Clear of the sidebar down the left side.
            Offset(with(density) { SideInset.toPx() }, margin),
            Offset(margin + with(density) { 280.dp.toPx() }, windowPx.height - hPx - bottomGap),
        )
        val x = remember { Animatable(corners[0].x) }
        val y = remember { Animatable(corners[0].y) }
        var controls by remember { mutableStateOf(false) }
        LaunchedEffect(controls) { if (controls) { kotlinx.coroutines.delay(3000); controls = false } }

        Box(
            Modifier
                .offset { IntOffset(x.value.roundToInt(), y.value.roundToInt()) }
                .size(w, h)
                .glass(rounded(22.dp), GlassKind.Button)
                .clip(rounded(22.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            val here = Offset(x.value, y.value)
                            val target = corners.minBy { (it - here).getDistance() }
                            scope.launch { x.animateTo(target.x, spring(0.75f, 300f)) }
                            scope.launch { y.animateTo(target.y, spring(0.75f, 300f)) }
                        },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { x.snapTo(x.value + amount.x); y.snapTo(y.value + amount.y) }
                    }
                }
                .clickable(remember { MutableInteractionSource() }, null) { controls = !controls },
        ) {
            AppPoster(info)
            if (live) HostSurface(current, info?.component?.flattenToShortString() ?: current.pkg, windowPx.width, windowPx.height, Modifier.fillMaxSize())
            androidx.compose.animation.AnimatedVisibility(
                visible = controls,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(200)),
            ) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.3f))) {
                    Row(Modifier.align(Alignment.TopStart).padding(10.dp)) {
                        GlassIconButton(Icons.close, { app.screens.closePip() }, size = 40.dp)
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                        GlassIconButton(Icons.expand, {
                            app.screens.expandPip()
                            nav.tab = Tab.SCREENS
                        }, size = 40.dp)
                    }
                }
            }
        }
    }
}
