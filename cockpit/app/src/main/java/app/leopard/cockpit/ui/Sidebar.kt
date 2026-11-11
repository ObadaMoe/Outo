package app.leopard.cockpit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import androidx.compose.foundation.layout.offset
import app.leopard.cockpit.ui.settings.SettingsPage
import app.leopard.cockpit.update.UpdateState
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.car.HelperLauncher
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.data.DOCK_MAX
import app.leopard.cockpit.data.Tab
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.DropTarget
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.screens.Slot
import app.leopard.cockpit.ui.screens.LiftCallbacks
import app.leopard.cockpit.ui.screens.liftable
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The sidebar's width, and how far it keeps from the window's edges. */
val SidebarWidth = 84.dp
val SidebarMargin = 8.dp

/** As round as CarPlay's: its corner curve runs over about a third of the width. */
val SidebarCorner = 32.dp

/** How far the tabs' content keeps from the left edge, clear of the sidebar. */
val SideInset = SidebarMargin + SidebarWidth + 16.dp

/**
 * Pinned apps are full-size app icons, as in CarPlay. Sized so five fit on the car
 * (the sidebar is 588 dp tall between BYD's bars); more scroll.
 */
private val PinIcon = 52.dp
private val PinGap = 10.dp

/** Settings, Apps, Screens and Home: plain icons, smaller than the apps, as CarPlay's Home is. */
private val TabHeight = 48.dp

/**
 * The sidebar, as in CarPlay: down the left side, on every tab. The time and the
 * connection at the top, then the apps pinned to it, then (smaller) Settings, Apps
 * and Screens, and the Overview's Home at the bottom.
 *
 * Tap a pinned app to open it full screen on the main screen, zoomed in. Hold one
 * for its menu, or drag it: up and down the sidebar to move it, or (on the Screens
 * tab) onto a screen. Apps are pinned by dragging them here from Apps.
 */
@Composable
fun Sidebar(modifier: Modifier = Modifier) {
    val app = Otility.instance
    val nav = LocalNav.current
    val t = LocalTokens.current
    val all by app.catalog.apps.collectAsState()
    val pinnedIds by app.prefs.dock.collectAsState()
    val state by app.screens.state.collectAsState()
    val byId = remember(all) { all.associateBy { it.id } }
    val pinned = pinnedIds.mapNotNull { byId[it] }
    val full = pinnedIds.size >= DOCK_MAX
    // Where a dragged app would go among the pinned ones (null: it isn't over the sidebar).
    // Worked out only when that changes, not at each move of the finger.
    val shown by rememberUpdatedState(pinned)
    val isFull by rememberUpdatedState(full)
    val gapAt by remember { derivedStateOf { gapIndex(nav, shown, isFull) } }
    val scroll = rememberScrollState()
    // Gone (the sidebar steps aside while zoomed in): nothing can be dropped on it.
    DisposableEffect(Unit) { onDispose { nav.sidebarBounds = Rect.Zero } }

    Column(
        modifier
            .fillMaxHeight()
            .padding(SidebarMargin)
            .width(SidebarWidth)
            .onGloballyPositioned { nav.sidebarBounds = it.boundsInRoot() }
            // Over an app whose picture goes straight to the screen, glass can't blur it
            // (see Nav.direct): nearly solid then, so the icons read.
            .glass(rounded(SidebarCorner), GlassKind.Bar, tint = if (nav.direct) t.solidGlass.copy(alpha = 0.94f) else null)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Settings at the top, Home at the bottom. A dot on it: a new version of Outo is ready.
        val update by Otility.instance.updater.state.collectAsState()
        TabButton(Tab.SETTINGS, Icons.gear, badge = update is UpdateState.Available)
        // The large map on the Overview has a way back here, where it was above the tab bar.
        AnimatedVisibility(
            nav.back != null,
            enter = fadeIn(tween(200)) + expandVertically(),
            exit = fadeOut(tween(150)) + shrinkVertically(),
        ) {
            GlassIconButton(Icons.back, { nav.back?.invoke() }, Modifier.padding(top = 4.dp), size = 48.dp)
        }

        // The pinned apps, in the middle of the space they have; they scroll when there
        // are more than fit.
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Column(
                // Each app has its gap below it.
                Modifier.scrollEdges(scroll, fade = 24.dp).verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val dragging = nav.dragging
                val lifted = nav.menu?.app?.id
                var i = 0
                pinned.forEach { a ->
                    key(a.id.pkg) {
                        val moving = a.id == dragging
                        if (!moving) GapSlot(gapAt == i++)
                        PinnedApp(
                            a,
                            running = a.id in state.running,
                            dim = moving || a.id == lifted,
                            // Over the sidebar, the place it's leaving closes up and a new one opens.
                            collapsed = moving && gapAt != null,
                        )
                    }
                }
                GapSlot(gapAt == i)
            }
        }

        TabButton(Tab.APPS, Icons.apps)
        Spacer(Modifier.height(4.dp))
        TabButton(Tab.SCREENS, Icons.screens)
        Spacer(Modifier.height(4.dp))
        TabButton(Tab.OVERVIEW, Icons.homeSquare)
    }
}

/** Where among [pinned] the app being dragged would be put, or null if it isn't over the sidebar (or can't go there). */
private fun gapIndex(nav: Nav, pinned: List<AppInfo>, full: Boolean): Int? {
    val dragged = nav.dragging ?: return null
    val p = nav.dragAt
    if (!nav.sidebarBounds.contains(p)) return null
    val others = pinned.filter { it.id != dragged }
    // A new app, and the sidebar has no room for it.
    if (others.size == pinned.size && full) return null
    val i = others.indexOfFirst { (nav.sidebarCenters[it.id] ?: Float.MAX_VALUE) >= p.y }
    return if (i < 0) others.size else i
}

/**
 * Pins [app] on the sidebar where [p] is over it (or moves it there, if it's pinned
 * already). False if [p] isn't over the sidebar, or the sidebar is full.
 */
fun Nav.pinAt(app: AppId, p: Offset): Boolean {
    if (!sidebarBounds.contains(p)) return false
    val prefs = Otility.instance.prefs
    val rest = prefs.dock.value - app
    if (rest.size >= DOCK_MAX) return false
    // Before the first app whose middle is below the finger, as the gap showed.
    val before = rest.indexOfFirst { (sidebarCenters[it] ?: return@indexOfFirst false) >= p.y }
    prefs.setDock(rest.toMutableList().apply { add(if (before < 0) size else before, app) })
    return true
}

/** Opens [app] full screen on the main screen, zoomed in (or asks first, if it's open on another screen). */
fun Nav.openZoomed(app: AppId) {
    // From another tab it grows out of its icon on the sidebar (see [Nav.launchFrom]),
    // or out of the middle for an app that isn't pinned there.
    if (tab != Tab.SCREENS) {
        val y = sidebarCenters[app]
        launchFrom = if (y != null && sidebarBounds != Rect.Zero) Offset(sidebarBounds.center.x, y) else Offset.Unspecified
    }
    openScreens()
    Otility.instance.screens.tap(app, ScreenId.MAIN) { openScreens(it) }
}

/** The room a dragged app would take, opening where it would go. */
@Composable
private fun GapSlot(open: Boolean) {
    val h by animateDpAsState(if (open) PinIcon + PinGap else 0.dp, spring(0.8f, 520f), label = "gap")
    // The spring can swing a little past 0; a negative height would throw.
    Spacer(Modifier.height(h.coerceAtLeast(0.dp)))
}

@Composable
private fun PinnedApp(a: AppInfo, running: Boolean, dim: Boolean, collapsed: Boolean) {
    val nav = LocalNav.current
    val t = LocalTokens.current
    val callbacks = remember(a) { PinnedLift(nav, a).callbacks }
    val h by animateDpAsState(if (collapsed) 0.dp else PinIcon + PinGap, spring(0.8f, 520f), label = "pin")
    val alpha by animateFloatAsState(
        when {
            collapsed -> 0f
            dim -> 0.35f
            else -> 1f
        },
        spring(0.7f, 500f), label = "pinAlpha",
    )
    // Clipped, so a closed-up place takes no taps. (The touch dragging it out carries on.)
    Box(Modifier.width(SidebarWidth).height(h.coerceAtLeast(0.dp)).clipToBounds(), contentAlignment = Alignment.TopCenter) {
        AppIcon(
            a, PinIcon,
            Modifier
                .graphicsLayer { this.alpha = alpha }
                .onGloballyPositioned { nav.sidebarCenters[a.id] = it.boundsInRoot().center.y }
                .liftable(a.id, callbacks),
        )
        // On one of the screens: a dot beside it, as the dock had under it.
        if (running) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 5.dp, top = PinIcon / 2 - 2.dp)
                    .size(4.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(t.text2, CircleShape),
            )
        }
    }
}

/**
 * A pinned app's touches. While the Screens tab is open they're its own (drag onto
 * a screen, its menu there), elsewhere the sidebar's. Decided when the finger holds,
 * for the whole touch.
 */
private class PinnedLift(private val nav: Nav, private val a: AppInfo) {
    private var active: LiftCallbacks? = null
    private fun resolve() = nav.dockLift?.invoke(a) ?: ownLift(nav, a)
    val callbacks = LiftCallbacks(
        onTap = { resolve().onTap() },
        onHold = { bounds -> active = resolve().also { it.onHold(bounds) } },
        onDragStart = { p -> active?.onDragStart(p) },
        onDrag = { p -> active?.onDrag(p) },
        onDrop = { p -> active?.onDrop(p); active = null },
        onCancel = { active?.onCancel(); active = null },
    )
}

/** Away from the Screens tab: tap to open zoomed in, hold for the menu, drag to move it on the sidebar. */
private fun ownLift(nav: Nav, a: AppInfo) = LiftCallbacks(
    onTap = { nav.openZoomed(a.id) },
    onHold = { bounds -> nav.menu = MenuRequest(bounds, pinnedMenu(nav, a), a) },
    onDragStart = { p -> nav.menu = null; nav.dragging = a.id; nav.dragAt = p },
    onDrag = { p -> nav.dragAt = p },
    onDrop = { p -> nav.pinAt(a.id, p); nav.dragging = null },
    onCancel = { nav.dragging = null },
)

private fun pinnedMenu(nav: Nav, a: AppInfo): List<MenuEntry> {
    val otility = Otility.instance
    val screens = otility.screens
    val state = screens.state.value
    fun open(target: DropTarget) {
        screens.place(a.id, target)
        nav.openScreens(target.screen)
    }
    return listOf(
        MenuHeader(a.label),
        MenuTiles(listOf(
            MenuItem("Full", Icons.full) { open(DropTarget(ScreenId.MAIN, Slot.FULL)) },
            MenuItem("Left Half", Icons.leftHalf) { open(DropTarget(ScreenId.MAIN, Slot.LEFT)) },
            MenuItem("Right Half", Icons.rightHalf) { open(DropTarget(ScreenId.MAIN, Slot.RIGHT)) },
        )),
        MenuGap,
        MenuHeader("Other Screens"),
        MenuItem("Passenger", Icons.passenger, checked = state.screenOf(a.id) == ScreenId.PASSENGER) {
            open(DropTarget(ScreenId.PASSENGER, Slot.FULL))
        },
        MenuItem("Picture in Picture", Icons.pip) { screens.pictureInPicture(a.id) },
        MenuGap,
        MenuItem("Remove from Sidebar", Icons.minus, destructive = true) {
            otility.prefs.setDock(otility.prefs.dock.value - a.id)
        },
    )
}

/**
 * Settings (at the top), Apps, Screens and the Overview's Home square (at the bottom): plain icons with nothing
 * behind them, as CarPlay's Home is. The open tab's icon turns blue. Tapped again,
 * a tab goes back to its start (zooms out of a screen, closes the large map).
 */
@Composable
private fun TabButton(tab: Tab, icon: ImageVector, badge: Boolean = false) {
    val nav = LocalNav.current
    val t = LocalTokens.current
    val on = nav.tab == tab
    val open by animateFloatAsState(if (on) 1f else 0f, tween(200), label = "tabOn")
    Box(
        Modifier
            .size(width = 68.dp, height = TabHeight)
            .pressable({
                // The dot is a new version: Settings opens where it's installed.
                if (badge && tab == Tab.SETTINGS) nav.settingsPage = SettingsPage.ABOUT
                if (nav.tab == tab) (nav.reselect ?: nav.back)?.invoke() else nav.tab = tab
            }),
        contentAlignment = Alignment.Center,
    ) {
        // The open tab's icon in the accent colour (blue unless another accent is chosen).
        Glyph(icon, lerp(t.text, t.acc, open), size = 30.dp)
        if (badge) Box(Modifier.align(Alignment.Center).offset(x = 13.dp, y = (-13).dp).size(10.dp).background(t.destructive, CircleShape))
    }
}

/** The app being dragged, under the finger, over everything (the sidebar too). [origin]: where this layer starts in the window. */
@Composable
fun DraggedApp(origin: Offset) {
    val nav = LocalNav.current
    val apps by Otility.instance.catalog.apps.collectAsState()
    val id = nav.dragging ?: return
    val half = with(LocalDensity.current) { 34.dp.toPx() }
    AppIcon(
        apps.firstOrNull { it.id == id }, 68.dp,
        Modifier.graphicsLayer {
            translationX = nav.dragAt.x - origin.x - half
            translationY = nav.dragAt.y - origin.y - half
            scaleX = 1.08f; scaleY = 1.08f
        },
    )
}

/**
 * Not connected to the car: a red toast at the top, in place of the old car-link
 * icon. Tap it to connect. It waits a moment when Otility starts, which usually
 * connects by itself.
 */
@Composable
fun NotConnectedToast(modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val helper by HelperConnection.helper.collectAsState()
    val status by HelperLauncher.status.collectAsState()
    var grace by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(3_000); grace = false }
    val red = if (LocalTokens.current.dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    AnimatedVisibility(
        visible = helper == null && !grace && !nav.immersive,
        modifier = modifier,
        enter = fadeIn(tween(220)) + slideInVertically(spring(0.75f, 420f)) { -it },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { -it },
    ) {
        val text = when (status.stage) {
            HelperLauncher.Stage.CONNECTING -> "Connecting to the car…"
            HelperLauncher.Stage.WAITING_FOR_APPROVAL -> "Tap “Always allow” on the car's screen"
            HelperLauncher.Stage.STARTING -> "Starting…"
            HelperLauncher.Stage.FAILED -> "Couldn't connect to the car · Tap to try again"
            else -> "Not connected to the car · Tap to connect"
        }
        Row(
            Modifier
                .pressable({ scope.launch { HelperLauncher.ensure(context) } })
                .glass(Capsule, GlassKind.Button, tint = red.copy(alpha = 0.86f))
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Icons.unlink, Color.White, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Txt(text, T.subhead.copy(fontWeight = FontWeight.SemiBold), Color.White, maxLines = 1)
        }
    }
}
