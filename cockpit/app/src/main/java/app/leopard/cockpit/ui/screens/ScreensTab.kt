package app.leopard.cockpit.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import app.leopard.cockpit.ui.pressable
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.screens.ClusterCrop
import app.leopard.cockpit.R
import app.leopard.cockpit.car.DisplayRow
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.data.Settings
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.DropTarget
import app.leopard.cockpit.screens.ScreenContent
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.screens.Screens
import app.leopard.cockpit.screens.Slot
import app.leopard.cockpit.ui.Alerts
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.EdgeFog
import app.leopard.cockpit.ui.Capsule
import app.leopard.cockpit.ui.GlassChip
import app.leopard.cockpit.ui.GlassIconButton
import app.leopard.cockpit.ui.GlassKind
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalBackdrop
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.MenuEntry
import app.leopard.cockpit.ui.MenuGap
import app.leopard.cockpit.ui.MenuHeader
import app.leopard.cockpit.ui.MenuItem
import app.leopard.cockpit.ui.MenuRequest
import app.leopard.cockpit.ui.MenuTiles
import app.leopard.cockpit.ui.Nav
import app.leopard.cockpit.ui.SideInset
import app.leopard.cockpit.ui.SidebarMargin
import app.leopard.cockpit.ui.SidebarWidth
import app.leopard.cockpit.ui.pinAt
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.glass
import app.leopard.cockpit.ui.noRipple
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import androidx.compose.runtime.CompositionLocalProvider
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where a dragged app would land. */
sealed interface Hover {
    data class On(val target: DropTarget) : Hover
    /** The dashed bar above the main screen: replace everything there. */
    data object Replace : Hover
}

@Stable
/**
 * An app being dragged. The app and the finger are kept in [nav], so the app is drawn
 * above the sidebar and the sidebar can open a place for it.
 */
private class DragState(private val nav: Nav) {
    var app: AppId?
        get() = nav.dragging
        set(v) { nav.dragging = v }
    var pos: Offset
        get() = nav.dragAt
        set(v) { nav.dragAt = v }
    var hover by mutableStateOf<Hover?>(null)
}

/** The left and right panes of a split, with a little space between them. */
private fun panes(content: Rect, ratio: Float): Pair<Rect, Rect> {
    val gap = Screens.splitGap(content.width)
    val lw = (content.width - gap) * ratio
    return Rect(content.left, content.top, content.left + lw, content.bottom) to
        Rect(content.left + lw + gap, content.top, content.right, content.bottom)
}

/** A pull in from an edge under way: its side (-1 the left, 1 the right), how far in and where (y), in the tab's pixels; [splits]: it can make room for a second app, [past] far enough in that it will. */
private data class EdgePullAt(val side: Int, val inward: Float, val y: Float, val splits: Boolean, val past: Boolean)

/** How far in a pull is Back, in dp. */
private const val BACK_AT = 56f

/** How wide a strip along each side Android's own back swipe is off over, zoomed in (wider than Otility's own [EDGE_ZONE]: whatever width the car's takes). */
private val EDGE_EXCLUDED = 48.dp

/** How far in a pull makes room for a second app: a fifth of the screen's width, and at least 150 dp. */
private fun splitAt(width: Float, dp: Float) = max(width * 0.2f, 150f * dp)

/** The room a pull makes, from how far in the finger is (a share of the width): with it up to near a half, then less and less. */
private fun rubber(share: Float): Float = if (share <= 0.42f) share else (0.42f + (share - 0.42f) * 0.25f).coerceAtMost(0.56f)

/**
 * The sign of a pull in from an edge, beside the finger: Back's arrow, which fills in once
 * the pull is long enough for Back; on the main screen, far enough in, "Split View" in
 * the room the app makes, which stays once it's let go.
 */
@Composable
private fun PullSign(p: EdgePullAt, w: Float, h: Float) {
    val t = LocalTokens.current
    val density = LocalDensity.current
    val left = p.side < 0
    val back = p.inward >= with(density) { BACK_AT.dp.toPx() }
    // Comes out of the edge with the finger; Back's stops a little way in, room's stays in its middle.
    val offset = if (p.splits) p.inward * 0.5f else min(p.inward * 0.5f, with(density) { 60.dp.toPx() })
    val x = if (left) offset else w - offset
    val margin = with(density) { 60.dp.toPx() }
    val y = p.y.coerceIn(margin, (h - margin).coerceAtLeast(margin))
    val shown = (p.inward / with(density) { (BACK_AT * 0.6f).dp.toPx() }).coerceIn(0f, 1f)
    Box(
        Modifier
            .layout { m, c ->
                val pl = m.measure(Constraints())
                layout(c.maxWidth, c.maxHeight) { pl.place((x - pl.width / 2f).roundToInt(), (y - pl.height / 2f).roundToInt()) }
            }
            .graphicsLayer { alpha = shown },
    ) {
        if (p.past) {
            GlassChip("Split View", icon = if (left) Icons.leftHalf else Icons.rightHalf)
        } else {
            Box(
                Modifier
                    .size(46.dp)
                    .then(if (back) Modifier.background(t.fill, Capsule) else Modifier.glass(Capsule, GlassKind.Button)),
                contentAlignment = Alignment.Center,
            ) { Glyph(if (left) Icons.back else Icons.chevronRight, if (back) t.onFill else t.text, size = 22.dp) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScreensTab() {
    val otility = Otility.instance
    val controller = otility.screens
    val bridge = otility.bridge
    val nav = LocalNav.current
    val t = LocalTokens.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by controller.state.collectAsState()
    val snapshot by controller.snapshot.collectAsState()
    val clusterMap by controller.clusterMap.collectAsState()
    val helper by HelperConnection.helper.collectAsState()
    val connected = helper != null
    val settings by otility.prefs.settings.collectAsState()
    val apps by otility.catalog.apps.collectAsState()
    val layouts by otility.prefs.layouts.collectAsState()
    val appsById = remember(apps) { apps.associateBy { it.id } }
    // The maps the cluster can show (only their map), Waze first, and the one it shows now.
    val clusterMaps = remember(apps) {
        val order = listOf("com.waze", "com.google.android.apps.maps", "app.revanced.android.apps.maps")
        apps.filter { it.id.pkg in ClusterCrop.APPS }.sortedBy { order.indexOf(it.id.pkg) }
    }
    val clusterPick = if (settings.mapOnCluster) remember(settings.navApp, apps) { otility.navigator.navApp() } else null

    fun dp(v: Float) = v * density.density

    // An app opened from another tab (see Nav.launchFrom): the tab opens zoomed in on it,
    // the app growing out of its icon ([launchOrigin]) instead of the camera flying in from
    // the dashboard. Only when the app was opened at once (not after a question).
    val launchOrigin = remember { nav.launchFrom.also { nav.launchFrom = null } }
    val launchFocus = remember { nav.zoomRequest?.takeIf { launchOrigin != null } }
    val launch = remember { Animatable(if (launchFocus != null) 0f else 1f) }
    LaunchedEffect(Unit) { if (launch.value < 1f) launch.animateTo(1f, spring(dampingRatio = 1f, stiffness = 260f)) }
    val launching by remember { derivedStateOf { launch.value < 1f } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // An app opened from another tab grows out of its icon (see launchFrom below).
            .graphicsLayer {
                val from = launchOrigin
                val p = launch.value
                if (from != null && launchFocus != null && p < 1f) {
                    val sc = 0.06f + 0.94f * p
                    scaleX = sc; scaleY = sc
                    val at = if (from.isSpecified) from else Offset(size.width / 2f, size.height / 2f)
                    transformOrigin = TransformOrigin((at.x / size.width).coerceIn(0f, 1f), (at.y / size.height).coerceIn(0f, 1f))
                    // Corners as round as an icon's at first, square once it fills the screen.
                    shape = RoundedCornerShape((1f - p) * 36.dp.toPx() / sc)
                    clip = true
                }
            }
            .background(Color.Black),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val driverDisplay = snapshot?.displayFor(ScreenId.DRIVER)
        val passengerDisplay = snapshot?.displayFor(ScreenId.PASSENGER)
        fun aspectOf(d: DisplayRow?) = d?.takeIf { it.h > 0 }?.let { it.w.toFloat() / it.h } ?: Dash.SIDE_ASPECT
        val aspects = mapOf(
            ScreenId.MAIN to w / h,
            ScreenId.DRIVER to aspectOf(driverDisplay),
            ScreenId.PASSENGER to aspectOf(passengerDisplay),
        )
        // The car's bars over the main screen: its apps are laid out between them.
        val barTop = WindowInsets.systemBars.getTop(density).toFloat()
        val barBottom = WindowInsets.systemBars.getBottom(density).toFloat()
        val mainBars = if (h > 0f) (barTop / h) to (barBottom / h) else 0f to 0f
        // The photo starts right of the sidebar, so it covers none of it (or of the
        // screens you drop apps on).
        val photoLeft = with(density) { (SidebarMargin + SidebarWidth + SidebarMargin).toPx() }
        val pad = dp(10f)
        val geo = remember(w, h, aspects, mainBars, photoLeft, pad) { StageGeometry(w, h, aspects, mainBars, photoLeft, pad) }

        // ---- The camera ------------------------------------------------------
        // Opened from another tab: already zoomed in (see launchFocus above).
        var focus by remember { mutableStateOf(launchFocus) }
        // The picked map tapped again: opened up over the main screen (or shown there, if it's there).
        val peekMap: (String) -> Unit = { pkg -> controller.peekClusterMap(AppId(pkg)) { focus = it } }
        val peek by controller.peek.collectAsState()
        // Two fingers left or right: the next screen (Driver, Main, Passenger, as they sit in
        // the car), the way pages follow the fingers: right brings the one on the left. The
        // cluster's map comes as itself, all of it, over the main screen, which keeps its
        // app (YouTube plays on); two fingers left put it back on the cluster.
        // [dir]: -1 the screen on the left, 1 the one on the right.
        fun toSide(dir: Int) {
            val to = focus?.let { ScreenId.entries.getOrNull(it.ordinal + dir) } ?: return
            val map = state[ScreenId.DRIVER].apps.firstOrNull()?.takeIf { to == ScreenId.DRIVER && controller.keepsOnCluster(it) }
            if (map == null || !controller.peekClusterMap(map) { focus = it }) focus = to
        }
        // The cluster's map, opened up over the main screen, two fingers down: out to all three screens.
        LaunchedEffect(nav.zoomOutRequest) {
            if (nav.zoomOutRequest) { focus = null; nav.zoomOutRequest = false }
        }
        // Zoomed in on the main screen: the cluster's map, brought there, stays while it's
        // used, and goes back to the cluster when it's zoomed out of alone.
        val onMain = focus == ScreenId.MAIN
        DisposableEffect(onMain) {
            if (onMain) controller.setMainZoomed(true)
            onDispose { if (onMain) controller.setMainZoomed(false) }
        }
        val progress = remember { Animatable(1f) }
        var camFrom by remember { mutableStateOf(focus?.let { geo.zoomOn(it) } ?: geo.identity) }
        var camTo by remember { mutableStateOf(camFrom) }
        val camera: () -> Camera = { between(camFrom, camTo, progress.value) }
        val camTarget = focus?.let { geo.zoomOn(it) } ?: geo.identity
        LaunchedEffect(focus, geo) {
            val target = focus?.let { geo.zoomOn(it) } ?: geo.identity
            // Already there. (A move cut short on its way here, by the stage changing shape
            // when the car's screens are first seen, say, carries on from where it stopped.)
            if (target == camTo && !progress.isRunning && progress.value == 1f) return@LaunchedEffect
            camFrom = camera()
            camTo = target
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 190f))
        }
        // Not until the camera is on its way to the new target, too: in the first frame
        // after a tap the move hasn't started yet, and the zoomed-in controls (and where
        // touches go) would flash in for that frame with the old camera.
        val settled = !progress.isRunning && camTo == camTarget
        // With live previews the dashboard shows the apps themselves; without, only their
        // icons, and only the screen you zoom into shows its apps (kept until the zoom
        // out has finished).
        // Not while Otility is out of sight: the apps would draw for nobody.
        var seen by remember { mutableStateOf(true) }
        LifecycleStartEffect(Unit) {
            seen = true
            onStopOrDispose { seen = false }
        }
        val previews = settings.livePreviews && seen
        var lastFocus by remember { mutableStateOf<ScreenId?>(null) }
        LaunchedEffect(focus) { if (focus != null) lastFocus = focus }
        val zoomedIn by remember { derivedStateOf { camera().z > 0.02f } }
        val liveScreen = focus ?: lastFocus?.takeIf { zoomedIn }
        val noBackdrop = remember { emptyBackdrop() }
        LaunchedEffect(focus) { nav.immersive = focus != null }
        DisposableEffect(Unit) { onDispose { nav.immersive = false } }
        LaunchedEffect(nav.zoomRequest) {
            nav.zoomRequest?.let { focus = it; nav.zoomRequest = null }
        }

        // ---- Controls shown while zoomed in -----------------------------------
        var chrome by remember { mutableStateOf(true) }
        // Zoomed in, the sidebar called back over the app (it comes with the controls).
        var sidebarOpen by remember { mutableStateOf(false) }
        LaunchedEffect(sidebarOpen) { nav.sidebarPeek = sidebarOpen }
        DisposableEffect(Unit) { onDispose { nav.sidebarPeek = false } }
        var chromeTick by remember { mutableIntStateOf(0) }
        val drag = remember { DragState(nav) }
        DisposableEffect(Unit) { onDispose { nav.dragging = null } }
        // Menus are drawn by OtilityApp, above the sidebar.
        var menu: MenuRequest? by nav::menu
        // Where the divider is while it's held (null otherwise). Only the picture follows
        // it: the layout, and the apps' sizes, change once it's let go.
        var dragRatio by remember { mutableStateOf<Float?>(null) }
        val dividerDragging = dragRatio != null
        var startupOpen by remember { mutableStateOf(false) }
        var startupBounds by remember { mutableStateOf(Rect.Zero) }

        // ---- One finger in from the left or right edge, zoomed in ------------------
        // A short pull is Back, inside the app. On the main screen with one app, a longer
        // one makes room beside it for a second, as on an iPad: the app moves over as the
        // finger comes in, and once it's let go, the half the app left waits for another
        // ([waiting]): the apps used lately and the pinned ones, as on an empty screen, or
        // any from the sidebar. Back (or a short pull, or Cancel) gives the room back.
        /** A pull under way, in the tab's pixels; null when none. */
        var pull by remember { mutableStateOf<EdgePullAt?>(null) }
        /** The half of the main screen that waits for a second app; null when none does. */
        var waiting by remember { mutableStateOf<Slot?>(null) }
        /** How much of the main screen its app has made room on, 0 to a half, on [roomSide] (-1 the left, 1 the right). */
        val room = remember { Animatable(0f) }
        var roomSide by remember { mutableIntStateOf(-1) }
        val mainApp = (state[ScreenId.MAIN] as? ScreenContent.Single)?.app
        fun giveRoomBack() {
            waiting = null
            scope.launch { room.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f)) }
        }
        // A pull cut short (zoomed out, or another screen, mid-pull): nothing of it stays.
        LaunchedEffect(settled, focus) {
            if (!settled || focus == null) {
                pull = null
                if (waiting == null && room.value > 0f) room.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
            }
        }
        // Gone with the app it was beside (closed, or another put there: a split now), or out of the main screen.
        LaunchedEffect(focus, mainApp) {
            if (focus != ScreenId.MAIN || mainApp == null) {
                if (waiting != null) waiting = null
                room.snapTo(0f)
            }
        }

        // ---- Straight to the car's screen ---------------------------------------
        // Still, the screens' pictures go straight to the car's screen (see HostFeed):
        // zoomed in, and on the dashboard with live previews. Otility then draws nothing
        // for them, and an app answers a finger sooner. Not on the dashboard while
        // something with glass is over the screens (a menu, a sheet, an alert, the
        // floating window, a drag): the glass would show them sharp. While the camera
        // moves, they stay as they were.
        val pip by controller.pip.collectAsState()
        val alert by Alerts.current.collectAsState()
        val covered = menu != null || drag.app != null || startupOpen || nav.sheet != null || alert != null || pip != null
        var direct by remember { mutableStateOf(false) }
        LaunchedEffect(settled, focus, connected, covered, previews, launching) {
            direct = when {
                !connected -> false
                // Growing out of its icon: drawn in Otility's window, where the growth applies.
                launching -> false
                focus == null && covered -> false
                !settled -> direct
                else -> focus != null || previews
            }
        }
        // Glass redrawing what's behind it would draw those pictures a second time (see
        // Nav.direct): away from the dashboard it shows its tint only. On the dashboard
        // no glass is over the screens, so it blurs as always.
        val glassBlind = direct && zoomedIn
        LaunchedEffect(glassBlind) { nav.direct = glassBlind }
        DisposableEffect(Unit) { onDispose { nav.direct = false } }
        // Zooming in (a place sent from the phone, say) closes it.
        LaunchedEffect(focus) { if (focus != null) startupOpen = false }
        fun poke() { chrome = true; chromeTick++ }
        // The open app whose button turned into a close button (for a few seconds).
        var armed by remember { mutableStateOf<AppId?>(null) }
        LaunchedEffect(armed) { if (armed != null) { delay(3500); armed = null } }
        LaunchedEffect(focus) { armed = null }
        // The layout changed (that app moved or closed, say): a red × left over could
        // close something the driver no longer means.
        LaunchedEffect(state) { armed = null }

        // The cluster takes no apps (only the map, by its switch): its BYD view isn't empty.
        val focusedIdle = focus?.takeIf { it != ScreenId.DRIVER }?.let { state[it] == ScreenContent.Idle } ?: false
        LaunchedEffect(focus) {
            if (focus != null) {
                chrome = true
                sidebarOpen = focus != ScreenId.DRIVER && state[focus!!] == ScreenContent.Idle
                chromeTick++
            } else {
                sidebarOpen = false
            }
        }
        // The screen in view lost its last app (closed here, or it crashed): the sidebar
        // comes back to pick another, as when zooming into an empty screen.
        LaunchedEffect(focusedIdle) { if (focusedIdle) { chrome = true; sidebarOpen = true } }
        LaunchedEffect(focus, chrome, chromeTick, drag.app, menu, dividerDragging, focusedIdle, armed, settings.gestures, waiting) {
            val wait = settings.autoHideSeconds
            // With gestures off nothing could bring hidden controls back (that's two
            // fingers up), so they stay. They also stay while a close button is armed.
            if (focus != null && chrome && wait > 0 && settings.gestures && drag.app == null && menu == null &&
                !dividerDragging && !focusedIdle && armed == null && waiting == null
            ) {
                delay(wait * 1000L)
                chrome = false
                sidebarOpen = false
            }
        }

        // ---- Dropping ----------------------------------------------------------
        val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
        fun replaceBar(cam: Camera): Rect {
            val c = cam.map(geo.content(ScreenId.MAIN), w, h)
            val bw = min(c.width * 0.62f, dp(340f))
            val bh = dp(40f)
            // Zoomed in, at the top (the buttons there are at its sides).
            val minTop = if (focus != null) safeTop + dp(18f) else dp(16f)
            val top = max(c.top - dp(14f) - bh, minTop)
            return Rect(c.center.x - bw / 2f, top, c.center.x + bw / 2f, top + bh)
        }
        val canReplace = state[ScreenId.MAIN] != ScreenContent.Idle && (focus == null || focus == ScreenId.MAIN)

        /** The half of the main screen under [p]: split where the divider is, as the highlight shows. */
        fun mainSlotAt(p: Offset, cam: Camera): Slot {
            val c = cam.map(geo.apps(ScreenId.MAIN), w, h)
            val divider = when (val m = state[ScreenId.MAIN]) {
                is ScreenContent.Split -> panes(c, m.ratio).first.right
                else -> c.center.x
            }
            return if (p.x < divider) Slot.LEFT else Slot.RIGHT
        }

        fun hitTest(p: Offset): Hover? {
            // Over the sidebar it's pinned there, not opened.
            if (nav.sidebarBounds.contains(p)) return null
            val cam = camera()
            if (canReplace && replaceBar(cam).inflate(dp(14f)).contains(p)) return Hover.Replace
            val f = focus
            if (f != null) {
                return when (f) {
                    ScreenId.MAIN -> Hover.On(DropTarget(f, mainSlotAt(p, cam)))
                    // Only the map goes on the cluster, by its switch.
                    ScreenId.DRIVER -> null
                    else -> Hover.On(DropTarget(f, Slot.FULL))
                }
            }
            for (s in ScreenId.entries) {
                if (s == ScreenId.DRIVER) continue
                val r = cam.map(geo.hole(s), w, h).inflate(dp(14f))
                if (r.contains(p)) {
                    return if (s == ScreenId.MAIN) Hover.On(DropTarget(s, mainSlotAt(p, cam)))
                    else Hover.On(DropTarget(s, Slot.FULL))
                }
            }
            return null
        }

        fun dropTargetOf(hv: Hover?): DropTarget? = when (hv) {
            Hover.Replace -> DropTarget(ScreenId.MAIN, Slot.FULL)
            is Hover.On -> hv.target
            null -> null
        }

        /** [anchor]: where the menu lifts the icon from, if not the touched view itself. */
        fun dragCallbacks(
            app: AppInfo,
            onTap: () -> Unit,
            anchor: (() -> Rect)? = null,
            menuFor: () -> List<MenuEntry>,
        ) = LiftCallbacks(
            onTap = onTap,
            onHold = { bounds -> menu = MenuRequest(anchor?.invoke() ?: bounds, menuFor(), app); poke() },
            onDragStart = { p ->
                menu = null
                armed = null
                drag.app = app.id
                drag.pos = p
                drag.hover = hitTest(p)
            },
            onDrag = { p ->
                drag.pos = p
                val hv = hitTest(p)
                if (hv != drag.hover) {
                    drag.hover = hv
                    if (hv != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
            onDrop = { p ->
                val target = dropTargetOf(hitTest(p))
                val id = drag.app
                // Onto the sidebar: pinned there (or moved along it). Before letting go of
                // the app, so the sidebar still knows which one it is.
                if (id != null && target == null) nav.pinAt(id, p)
                drag.app = null
                drag.hover = null
                if (id != null && target != null) controller.place(id, target, confirm = focus == null)
                poke()
            },
            onCancel = { drag.app = null; drag.hover = null },
        )

        // ---- Menus -------------------------------------------------------------
        fun open(app: AppId, target: DropTarget) = controller.place(app, target, confirm = focus == null)

        fun sidebarMenu(a: AppInfo): List<MenuEntry> {
            val main = state[ScreenId.MAIN]
            fun on(slot: Slot) = when (main) {
                is ScreenContent.Single -> slot == Slot.FULL && main.app == a.id
                is ScreenContent.Split -> (slot == Slot.LEFT && main.left == a.id) || (slot == Slot.RIGHT && main.right == a.id)
                else -> false
            }
            return listOf(
                MenuHeader("${a.label} · Main"),
                MenuTiles(listOf(
                    MenuItem("Full", Icons.full, checked = on(Slot.FULL)) { open(a.id, DropTarget(ScreenId.MAIN, Slot.FULL)) },
                    MenuItem("Left Half", Icons.leftHalf, checked = on(Slot.LEFT)) { open(a.id, DropTarget(ScreenId.MAIN, Slot.LEFT)) },
                    MenuItem("Right Half", Icons.rightHalf, checked = on(Slot.RIGHT)) { open(a.id, DropTarget(ScreenId.MAIN, Slot.RIGHT)) },
                )),
                MenuGap,
                MenuHeader("Other Screens"),
                MenuItem("Passenger", Icons.passenger, checked = state.screenOf(a.id) == ScreenId.PASSENGER) {
                    open(a.id, DropTarget(ScreenId.PASSENGER, Slot.FULL))
                },
                MenuItem("Mirror", Icons.mirror) {
                    // On the passenger screen, with its live picture here to watch and control.
                    open(a.id, DropTarget(ScreenId.PASSENGER, Slot.FULL))
                    focus = ScreenId.PASSENGER
                },
                MenuGap,
                MenuItem("Picture in Picture", Icons.pip) { controller.pictureInPicture(a.id) },
                MenuGap,
                MenuItem("Remove from Sidebar", Icons.minus, destructive = true) {
                    otility.prefs.setDock(otility.prefs.dock.value - a.id)
                },
            )
        }

        fun tileMenu(a: AppInfo, on: ScreenId): List<MenuEntry> {
            val others = ScreenId.entries.filter { it != on && it != ScreenId.DRIVER }
            return buildList {
                add(MenuHeader("${a.label} · ${on.label}"))
                add(MenuItem("Zoom In", Icons.expand) { focus = on })
                if (on == ScreenId.MAIN && state[on] is ScreenContent.Split) {
                    add(MenuItem("Swap Sides", Icons.swap) { controller.swap(on) })
                }
                add(MenuGap)
                add(MenuHeader("Move To"))
                others.forEach { s ->
                    val icon = when (s) { ScreenId.DRIVER -> Icons.wheel; ScreenId.MAIN -> Icons.full; ScreenId.PASSENGER -> Icons.passenger }
                    add(MenuItem(s.label, icon) { open(a.id, DropTarget(s, Slot.FULL)) })
                }
                add(MenuItem("Picture in Picture", Icons.pip) { controller.pictureInPicture(a.id) })
                add(MenuGap)
                add(MenuItem("Close", Icons.close, destructive = true) { controller.requestClose(a.id) })
            }
        }

        // The sidebar's apps, while this tab is open: tap to open on the screen in view (the
        // main screen from the dashboard) and zoom in; drag onto a screen; hold for the menu.
        SideEffect {
            nav.dockLift = { a ->
                dragCallbacks(a, onTap = {
                    val wait = waiting
                    // A half waiting for an app: it goes there.
                    if (wait != null && focus == ScreenId.MAIN && a.id != mainApp) controller.place(a.id, DropTarget(ScreenId.MAIN, wait))
                    // If the app is already somewhere, asks whether to move it or go to it.
                    else controller.tap(a.id, focus ?: ScreenId.MAIN) { focus = it }
                    poke()
                }) { sidebarMenu(a) }
            }
            // Its button on the sidebar, tapped again, zooms out (the cluster's map,
            // opened up over it, closes first: ClusterPeek has the button meanwhile).
            if (peek == null) nav.reselect = if (focus != null) ({ focus = null }) else null
        }
        DisposableEffect(Unit) {
            onDispose {
                nav.dockLift = null
                nav.reselect = null
            }
        }

        fun layoutMenu(anchor: Rect): MenuRequest {
            val running = state.running.mapNotNull { appsById[it]?.label }
            val name = running.take(3).joinToString(" + ").ifEmpty { "Layout" }
            return MenuRequest(anchor, buildList {
                add(MenuHeader("Layouts"))
                layouts.forEach { l -> add(MenuItem(l.name, Icons.layouts) { controller.applyLayout(l) }) }
                add(MenuItem("Save Current Layout", Icons.plus, enabled = running.isNotEmpty()) { controller.saveLayout(name) })
                add(MenuGap)
                add(MenuItem("Swap Main Screen", Icons.swap, enabled = state[ScreenId.MAIN] is ScreenContent.Split) {
                    controller.swap(ScreenId.MAIN)
                })
                add(MenuItem("Close All Apps", Icons.close, destructive = true, enabled = running.isNotEmpty()) {
                    controller.clearAll()
                })
            })
        }

        // ---- Sizes of apps hosted on the main screen ----------------------------
        val mainSplit = state[ScreenId.MAIN] as? ScreenContent.Split
        val shownRatio by animateFloatAsState(
            dragRatio ?: mainSplit?.ratio ?: 0.5f,
            if (dividerDragging) snap() else spring(0.82f, 420f),
            label = "ratio",
        )
        // The apps keep their size while the divider is held (their picture stretches)
        // and are resized once, when it's let go: resizing an app's display makes it lay
        // itself out again, which is too slow to do while dragging.
        fun hostSize(app: AppId): Pair<Int, Int> {
            // Between the car's bars.
            val appsH = (h - barTop - barBottom).coerceAtLeast(h / 2f).toInt()
            val gap = Screens.splitGap(w)
            // Beside a half waiting for a second app: a half already.
            if (mainSplit == null && waiting != null && app == mainApp) return ((w - gap) * 0.5f).toInt() to appsH
            val s = mainSplit ?: return w.toInt() to appsH
            val ratio = Screens.clampRatio(s.ratio)
            val share = if (s.left == app) ratio else 1f - ratio
            return ((w - gap) * share).toInt() to appsH
        }

        // ---- The stage: tiles under the dashboard photo, seen through the camera ---
        val stageBackdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize().layerBackdrop(stageBackdrop)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val c = camera()
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = c.k
                        scaleY = c.k
                        translationX = w / 2f - c.cx * c.k
                        translationY = h / 2f - c.cy * c.k
                    },
            ) {
                ScreenId.entries.forEach { s ->
                    val display = when (s) { ScreenId.DRIVER -> driverDisplay; ScreenId.PASSENGER -> passengerDisplay; else -> null }
                    Tile(
                        screen = s, content = state[s], geo = geo,
                        ratio = if (s == ScreenId.MAIN) shownRatio else 0.5f,
                        appsById = appsById, connected = connected, live = previews || s == liveScreen, display = display,
                        settings = settings, hostSize = ::hostSize, direct = direct,
                        room = if (s == ScreenId.MAIN) ({ room.value }) else ({ 0f }),
                        roomSide = roomSide, roomFilled = waiting != null,
                    )
                }
                Image(
                    // The same cabin; outside it's night in dark mode.
                    painterResource(if (LocalTokens.current.dark) R.drawable.dashboard_night else R.drawable.dashboard),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .at(geo.photo)
                        // Only right of the sidebar.
                        .drawWithContent {
                            clipRect(left = geo.photoShown.left - geo.photo.left) { this@drawWithContent.drawContent() }
                        }
                        .graphicsLayer { alpha = 1f - smoothstep(0.08f, 0.75f, camera().z) },
                )
            }
            // Fog over the edges of the photo, behind the car's bars, the top controls and
            // the sidebar. Zoomed in, none: the app is the whole screen.
            val fog by animateFloatAsState(if (focus == null) 1f else 0f, tween(300), label = "fog")
            EdgeFog(topAlpha = { fog }, bottomAlpha = { fog }, sideAlpha = { fog }, start = geo.photoShown.left)
        }

        CompositionLocalProvider(LocalBackdrop provides if (glassBlind) noBackdrop else stageBackdrop) {
            // ---- Touch: tap a screen to zoom in; hold an app to move it or for its menu;
            // ---- when zoomed, touches go to the app ----------------------------------
            if (focus == null) {
                // The cluster's map comes to the main screen, all of it, to search and pick a
                // place (the cluster shows only the middle of it); Back puts it back there.
                val openScreen = { s: ScreenId ->
                    val map = state[ScreenId.DRIVER].apps.firstOrNull()?.takeIf { s == ScreenId.DRIVER && controller.keepsOnCluster(it) }
                    if (map != null) controller.tap(map, ScreenId.MAIN) { focus = it } else focus = s
                }
                ScreenId.entries.forEach { s ->
                    Box(
                        Modifier
                            .at { camera().map(geo.hole(s), w, h) }
                            .noRipple { openScreen(s) },
                    )
                    val content = state[s]
                    content.apps.forEach { id ->
                        val info = appsById[id] ?: return@forEach
                        key(s, id.pkg) {
                            val pane = {
                                val c = geo.apps(s)
                                when (content) {
                                    is ScreenContent.Split -> panes(c, shownRatio).let { (l, r) -> if (content.left == id) l else r }
                                    else -> c
                                }
                            }
                            // The icon drawn inside the screen (the same size AppPoster draws it).
                            val icon = {
                                val p = pane()
                                val half = minOf(p.width, p.height) * 0.18f
                                camera().map(Rect(p.center.x - half, p.center.y - half, p.center.x + half, p.center.y + half), w, h)
                            }
                            Box(
                                Modifier
                                    .at { camera().map(pane(), w, h) }
                                    // The map on the cluster stays there while its switch is on.
                                    .then(
                                        if (s == ScreenId.DRIVER) Modifier.noRipple { openScreen(s) }
                                        else Modifier.liftable(id, dragCallbacks(info, onTap = { focus = s }, anchor = icon) { tileMenu(info, s) }),
                                    ),
                            )
                        }
                    }
                }
            } else if (settled) {
                val router = remember {
                    TouchRouter(
                        bridge, density.density,
                        onTwoUp = { chrome = true; sidebarOpen = true; chromeTick++ },
                        onTwoDown = { focus = null },
                        onTwoSide = { fingers -> toSide(-fingers) },
                        onTouchedPane = {},
                    )
                }
                val f = focus!!
                val cam = camera()
                router.gestures = settings.gestures
                val mainWidth = cam.map(geo.apps(ScreenId.MAIN), w, h).width
                // Room for a second app: on the main screen, beside its only app.
                val splits = f == ScreenId.MAIN && state[f] is ScreenContent.Single && waiting == null && peek == null
                router.edgePull = EdgePull(
                    pulled = { side, inward, y ->
                        val past = splits && inward >= splitAt(mainWidth, density.density)
                        val was = pull
                        if (was != null && past != was.past) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        pull = EdgePullAt(side, inward, y, splits, past)
                        if (splits && mainWidth > 0f) {
                            roomSide = side
                            // A little at first (it may be Back), then with the finger once it's room.
                            val share = inward / mainWidth
                            val target = if (past) rubber(share) else share * 0.3f
                            scope.launch { room.animateTo(target, spring(dampingRatio = 1f, stiffness = 1400f)) }
                        }
                    },
                    released = { side, inward ->
                        val was = pull
                        pull = null
                        when {
                            // Let go far enough in: the room stays, and waits for an app.
                            was != null && was.splits && inward >= splitAt(mainWidth, density.density) -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                waiting = if (side < 0) Slot.LEFT else Slot.RIGHT
                                scope.launch { room.animateTo(0.5f, spring(dampingRatio = 0.84f, stiffness = 380f)) }
                                chrome = true; sidebarOpen = true; chromeTick++
                                false
                            }
                            // A half waiting: Back gives the room back.
                            waiting != null -> {
                                if (inward >= BACK_AT * density.density) giveRoomBack()
                                false
                            }
                            else -> {
                                if (room.value > 0f) scope.launch { room.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f)) }
                                inward >= BACK_AT * density.density
                            }
                        }
                    },
                )
                router.targets = when (f) {
                    ScreenId.MAIN -> {
                        val c = cam.map(geo.apps(f), w, h)
                        when (val m = state[f]) {
                            is ScreenContent.Single -> listOf(
                                TouchTarget(
                                    // Beside a waiting half: its own half.
                                    waiting?.let { side -> panes(c, 0.5f).let { (l, r) -> if (side == Slot.LEFT) r else l } } ?: c,
                                    { controller.hosts.displayOf(m.app) }, { hostSize(m.app).first }, { hostSize(m.app).second },
                                ),
                            )
                            is ScreenContent.Split -> {
                                val (l, r) = panes(c, shownRatio)
                                listOf(
                                    TouchTarget(l, { controller.hosts.displayOf(m.left) }, { hostSize(m.left).first }, { hostSize(m.left).second }),
                                    TouchTarget(r, { controller.hosts.displayOf(m.right) }, { hostSize(m.right).first }, { hostSize(m.right).second }),
                                )
                            }
                            else -> emptyList()
                        }
                    }
                    else -> {
                        val d = if (f == ScreenId.DRIVER) driverDisplay else passengerDisplay
                        // An idle screen shows the emblem here, not what's really on it:
                        // taps there would land blind on the car's own screen.
                        // A map on the cluster, map only: its own display, from where the picture starts.
                        val framed = clusterMap?.takeIf { f == ScreenId.DRIVER && it.displayId == d?.id && it.app in state[f].apps }
                        if (d == null || state[f] == ScreenContent.Idle) emptyList()
                        else if (framed != null) listOf(
                            TouchTarget(
                                cam.map(geo.content(f), w, h), { controller.hosts.displayOf(framed.app) }, { d.w }, { d.h },
                                left = framed.frame.left.toFloat(), top = framed.frame.top.toFloat(),
                            ),
                        )
                        else listOf(TouchTarget(cam.map(geo.content(f), w, h), { d.id }, { d.w }, { d.h }))
                    }
                }
                Box(Modifier.fillMaxSize().routeTouches(router))
            }

            // ---- An empty screen in view: a shelf of apps to open on it ----------------
            if (focusedIdle && settled && drag.app == null) {
                val f = focus!!
                EmptyScreenApps(
                    f, appsById,
                    onOpen = { app -> controller.tap(app, f) { focus = it } },
                    onAll = { nav.tab = app.leopard.cockpit.data.Tab.APPS },
                    modifier = Modifier.at { camera().map(geo.apps(f), w, h) },
                )
            }

            // ---- A pull in from an edge: Back, or (far enough in) room for a second app ---
            pull?.let { p -> PullSign(p, w, h) }

            // ---- The half waiting for a second app: the ones used lately, and pinned ---
            val wait = waiting
            if (wait != null && focus == ScreenId.MAIN && settled && drag.app == null && mainApp != null) {
                EmptyScreenApps(
                    ScreenId.MAIN, appsById,
                    onOpen = { app -> controller.place(app, DropTarget(ScreenId.MAIN, wait)) },
                    onAll = null,
                    modifier = Modifier.at {
                        val c = camera().map(geo.apps(ScreenId.MAIN), w, h)
                        val half = panes(c, 0.5f).let { (l, r) -> if (wait == Slot.LEFT) l else r }
                        // In the part of the left half the sidebar (out while it waits) leaves.
                        val side = nav.sidebarBounds
                        if (wait == Slot.LEFT && sidebarOpen && side.width > 0f) half.copy(left = max(half.left, side.right)) else half
                    },
                    title = "Open beside ${appsById[mainApp]?.label ?: "it"}",
                    exclude = mainApp,
                    onCancel = { giveRoomBack() },
                )
            }

            // ---- The split divider: drag to resize, drag to an edge to go full screen ---
            if (mainSplit != null && (focus == null || focus == ScreenId.MAIN)) {
                val zoomed = focus == ScreenId.MAIN
                val divider = {
                    val c = camera().map(geo.apps(ScreenId.MAIN), w, h)
                    val (l, _) = panes(c, shownRatio)
                    // Zoomed in with the controls hidden it's only the gap itself, so a tap
                    // on either app right beside it goes to that app.
                    val half = dp(
                        when {
                            !zoomed -> 14f
                            chrome || dividerDragging -> 22f
                            else -> 3f
                        },
                    )
                    Rect(l.right - half, c.top, l.right + half + Screens.splitGap(c.width), c.bottom)
                }
                /** Lets go of the divider where it's held, if it is. */
                fun letGo() {
                    val r = dragRatio ?: return
                    dragRatio = null
                    controller.releaseDivider(ScreenId.MAIN, r)
                }
                val handleAlpha by animateFloatAsState(
                    when {
                        dividerDragging -> 1f
                        zoomed && !chrome -> 0f
                        else -> 0.42f
                    },
                    tween(220), label = "handle",
                )
                Box(
                    Modifier
                        .at(divider)
                        .pointerInput(zoomed) {
                            detectTapGestures(onDoubleTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                controller.releaseDivider(ScreenId.MAIN, 0.5f)
                            })
                        }
                        .pointerInput(zoomed) {
                            var crossed = false
                            try {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        dragRatio = (state[ScreenId.MAIN] as? ScreenContent.Split)?.ratio ?: 0.5f
                                        crossed = false
                                        poke()
                                    },
                                    onDragEnd = { letGo(); poke() },
                                    onDragCancel = { letGo() },
                                ) { change, dx ->
                                    change.consume()
                                    val width = camera().map(geo.apps(ScreenId.MAIN), w, h).width
                                    val from = dragRatio ?: return@detectHorizontalDragGestures
                                    if (width <= 0f) return@detectHorizontalDragGestures
                                    val ratio = (from + dx / width).coerceIn(0.04f, 0.96f)
                                    dragRatio = ratio
                                    val past = ratio <= Screens.DISMISS_LEFT || ratio >= Screens.DISMISS_RIGHT
                                    if (past != crossed) {
                                        crossed = past
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            } finally {
                                // Cut short mid-drag (zoomed in or out, the split went, the tab
                                // changed): the detector calls neither end then, and the divider
                                // would stay "held", keeping the controls up for good.
                                letGo()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val size = if (zoomed) 44.dp else 30.dp
                    Box(
                        Modifier
                            .size(size)
                            .graphicsLayer { alpha = handleAlpha }
                            .clip(Capsule)
                            .glass(Capsule, GlassKind.Button)
                            .noRipple {
                                // Hidden with the other controls, it never swaps the apps
                                // unseen (the grabber at the top brings it back).
                                if (zoomed && !chrome) return@noRipple
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                controller.swap(ScreenId.MAIN)
                                poke()
                            },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(Icons.swap, t.text, size = size * 0.5f) }
                }
            }

            // ---- While dragging: where it can go, and where it will go -------------
            if (drag.app != null) {
                if (focus == null) {
                    ScreenId.entries.forEach { s ->
                        Box(
                            Modifier
                                .at { camera().map(geo.content(s), w, h) }
                                .drawBehind {
                                    drawRoundRect(
                                        Color.White.copy(alpha = 0.45f),
                                        cornerRadius = CornerRadius(8.dp.toPx()),
                                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                                    )
                                },
                        )
                    }
                }
                drag.hover?.let { hv ->
                    val rect = {
                        val cam = camera()
                        val main = cam.map(geo.apps(ScreenId.MAIN), w, h)
                        when (hv) {
                            Hover.Replace -> main
                            is Hover.On -> if (hv.target.screen == ScreenId.MAIN) {
                                when (val m = state[ScreenId.MAIN]) {
                                    ScreenContent.Idle -> main
                                    is ScreenContent.Single ->
                                        if (hv.target.slot == Slot.LEFT) Rect(main.left, main.top, main.center.x, main.bottom)
                                        else Rect(main.center.x, main.top, main.right, main.bottom)
                                    is ScreenContent.Split -> panes(main, m.ratio).let { (l, r) -> if (hv.target.slot == Slot.LEFT) l else r }
                                }
                            } else cam.map(geo.content(hv.target.screen), w, h)
                        }
                    }
                    val label = when (hv) {
                        Hover.Replace -> "Replace"
                        is Hover.On -> when {
                            hv.target.screen != ScreenId.MAIN -> hv.target.screen.label
                            state[ScreenId.MAIN] == ScreenContent.Idle -> "Main"
                            hv.target.slot == Slot.LEFT -> "Left Half"
                            else -> "Right Half"
                        }
                    }
                    Box(
                        Modifier
                            .at(rect)
                            .background(t.acc.copy(alpha = 0.26f), rounded(10.dp))
                            .border(2.5.dp, t.acc, rounded(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AppIcon(drag.app?.let { appsById[it] }, 52.dp, Modifier.graphicsLayer { alpha = 0.92f })
                            Box(Modifier.height(8.dp))
                            GlassChip(label)
                        }
                    }
                }
                if (canReplace) {
                    val on = drag.hover == Hover.Replace
                    Row(
                        Modifier
                            .at { replaceBar(camera()) }
                            .then(if (on) Modifier.background(t.fill, Capsule) else Modifier.glass(Capsule, GlassKind.Button))
                            .drawBehind {
                                if (!on) drawRoundRect(
                                    Color.White.copy(alpha = 0.7f),
                                    cornerRadius = CornerRadius(size.height / 2f),
                                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f))),
                                )
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(Icons.swap, if (on) t.onFill else t.text, size = 18.dp)
                        Box(Modifier.width(8.dp))
                        Txt("Replace", T.subhead.copy(fontWeight = FontWeight.SemiBold), if (on) t.onFill else t.text)
                    }
                }
            }

            // ---- Zoomed-in controls ----------------------------------------------
            AnimatedVisibility(
                visible = focus != null && chrome && settled,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(260)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp)) {
                    val f = focus
                    // No buttons on the left: Screens on the sidebar zooms back out (and so
                    // do the car's own Back and two fingers down). Nothing in the middle:
                    // two fingers left or right go to the next screen.
                    if (f == ScreenId.DRIVER) {
                        ClusterSwitch(clusterPick, clusterMaps, controller::setCluster, peekMap, Modifier.align(Alignment.TopEnd))
                    } else if (f != null) {
                        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            state[f].apps.forEach { id ->
                                key(id.pkg) {
                                    OpenAppButton(
                                        appsById[id], armed == id,
                                        onArm = { armed = id; poke() },
                                        onClose = { armed = null; controller.close(id); poke() },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- Controls hidden: a small grabber at the top brings them back ---------
            // One finger, as on iPad; the same as two fingers up.
            AnimatedVisibility(
                visible = focus != null && !chrome && settled,
                enter = fadeIn(tween(260)),
                exit = fadeOut(tween(140)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Grabber(Modifier.align(Alignment.TopCenter)) { chrome = true; sidebarOpen = true; chromeTick++ }
                }
            }

            // ---- Top of the dashboard view: startup and layouts ----------------------
            AnimatedVisibility(
                visible = focus == null,
                enter = fadeIn(tween(240)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Not connected, the red toast at the top says so (see NotConnectedToast).
                Box(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = SideInset, top = 20.dp, end = 20.dp, bottom = 20.dp),
                ) {
                    // Top left, beside the sidebar, across from Layouts: what each screen opens with at startup.
                    GlassIconButton(
                        Icons.startup,
                        { startupOpen = true },
                        Modifier.align(Alignment.TopStart).onGloballyPositioned { startupBounds = it.boundsInRoot() },
                    )
                    var layoutsBounds by remember { mutableStateOf(Rect.Zero) }
                    GlassIconButton(
                        Icons.layouts,
                        { menu = layoutMenu(layoutsBounds) },
                        Modifier.align(Alignment.TopEnd).onGloballyPositioned { layoutsBounds = it.boundsInRoot() },
                    )
                }
            }

            // ---- Above each screen: its open apps; tap one for its close button ------
            AnimatedVisibility(
                visible = focus == null && drag.app == null,
                enter = fadeIn(tween(240)),
                exit = fadeOut(tween(140)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Above the cluster: what it shows, BYD's own view or the map.
                    Box(
                        Modifier.at {
                            val r = camera().map(geo.hole(ScreenId.DRIVER), w, h)
                            Rect(r.left, r.top - dp(80f), r.right, r.top - dp(14f))
                        },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        ClusterSwitch(clusterPick, clusterMaps, controller::setCluster, peekMap)
                    }
                    ScreenId.entries.forEach { s ->
                        val apps = state[s].apps
                        if (s != ScreenId.DRIVER && apps.isNotEmpty()) {
                            Box(
                                Modifier.at {
                                    val r = camera().map(geo.hole(s), w, h)
                                    Rect(r.left, r.top - dp(80f), r.right, r.top - dp(14f))
                                },
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    apps.forEach { id ->
                                        key(id.pkg) {
                                            OpenAppButton(
                                                appsById[id], armed == id,
                                                onArm = { armed = id },
                                                onClose = { armed = null; controller.close(id) },
                                                size = 48.dp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // The app being dragged, and menus, are drawn by OtilityApp, above the sidebar.
            StartupWindow(startupOpen, startupBounds) { startupOpen = false }
        }

        // ---- Back: the car's Back button zooms out; the app's own Back is in the controls
        BackHandler(enabled = focus != null) { focus = null }
        // Registered after: a half waiting for an app goes before zooming out.
        BackHandler(enabled = waiting != null) { giveRoomBack() }
        // Zoomed in, a finger in from the left or right edge is Otility's (Back inside the app,
        // or room for a second app), not the car's own Back: Android's back swipe is off there.
        if (focus != null && settings.gestures) {
            Box(Modifier.align(Alignment.CenterStart).width(EDGE_EXCLUDED).fillMaxHeight().systemGestureExclusion())
            Box(Modifier.align(Alignment.CenterEnd).width(EDGE_EXCLUDED).fillMaxHeight().systemGestureExclusion())
        }
    }
}

/** One screen of the dashboard: black glass with the screen's apps (or the emblem). */
@Composable
private fun Tile(
    screen: ScreenId,
    content: ScreenContent,
    geo: StageGeometry,
    ratio: Float,
    appsById: Map<AppId, AppInfo>,
    connected: Boolean,
    live: Boolean,
    display: DisplayRow?,
    settings: Settings,
    hostSize: (AppId) -> Pair<Int, Int>,
    direct: Boolean,
    // The room its only app makes beside it (a pull in from an edge, a half waiting), 0 to
    // a half, on [roomSide]; [roomFilled]: the app is resized to its part (else it slides over).
    room: () -> Float = { 0f },
    roomSide: Int = -1,
    roomFilled: Boolean = false,
) {
    val hole = geo.hole(screen)
    val c = geo.content(screen)
    Box(Modifier.at(hole).background(Color.Black)) {
        val inner = c.translate(-hole.left, -hole.top)
        Box(Modifier.at(inner).clipToBounds()) {
            when (content) {
                ScreenContent.Idle -> IdleScreen(settings.showEmblem, settings.dimEmblemSeconds)
                else -> {
                    // The apps' part of the screen (clear of the car's bars on the main one).
                    val local = geo.apps(screen).translate(-c.left, -c.top)
                    val made = if (content is ScreenContent.Single) room() else 0f
                    if (content is ScreenContent.Split || made > 0f) SplitBackground()
                    val round = c.width * 0.014f
                    val rects: Map<AppId, Rect> = when (content) {
                        is ScreenContent.Split -> panes(local, ratio).let { (l, r) -> mapOf(content.left to l, content.right to r) }
                        is ScreenContent.Single -> mapOf(
                            content.app to if (made <= 0f) local
                            else if (roomSide < 0) panes(local, made).second
                            else panes(local, 1f - made).first,
                        )
                        else -> emptyMap()
                    }
                    content.apps.forEach { id ->
                        key(id.pkg) {
                            val r = rects[id] ?: local
                            val dim = if (content is ScreenContent.Split) {
                                if (content.left == id) smoothstep(Screens.MIN_RATIO, Screens.DISMISS_LEFT, ratio)
                                else smoothstep(Screens.MAX_RATIO, Screens.DISMISS_RIGHT, ratio)
                            } else 0f
                            val shape = when {
                                content is ScreenContent.Single && made > 0f ->
                                    if (roomSide < 0) RoundedCornerShape(round, 0f, 0f, round) else RoundedCornerShape(0f, round, round, 0f)
                                content !is ScreenContent.Split -> RectangleShape
                                content.left == id -> RoundedCornerShape(0f, round, round, 0f)
                                else -> RoundedCornerShape(round, 0f, 0f, round)
                            }
                            Box(Modifier.at(r).clip(shape)) {
                                if (made > 0f && !roomFilled) {
                                    // Still its full size, slid over: the side going off the screen is cut.
                                    Box(
                                        Modifier
                                            .wrapContentSize(if (roomSide < 0) Alignment.TopStart else Alignment.TopEnd, unbounded = true)
                                            .requiredSize(with(LocalDensity.current) { local.width.toDp() }, with(LocalDensity.current) { local.height.toDp() }),
                                    ) { Pane(screen, id, appsById[id], live, hostSize, direct) }
                                } else {
                                    Pane(screen, id, appsById[id], live, hostSize, direct)
                                }
                                if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f * dim)))
                            }
                        }
                    }
                    // The cluster or the passenger screen: what's really on it, all of it, in
                    // one picture over the apps' posters (which show until it's in).
                    if (screen != ScreenId.MAIN && connected && live && display != null) {
                        key(display.id) { MirrorSurface(display.id, display.w, display.h, Modifier.fillMaxSize(), direct = direct) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pane(
    screen: ScreenId,
    app: AppId,
    info: AppInfo?,
    live: Boolean,
    hostSize: (AppId) -> Pair<Int, Int>,
    direct: Boolean,
) {
    AppPoster(info)
    if (screen == ScreenId.MAIN) {
        val (vw, vh) = hostSize(app)
        val component = info?.component?.flattenToShortString() ?: app.pkg
        // Live (a preview, or zoomed in): the app itself. Kept even while the helper is
        // away: the view stays transparent (the poster shows) and the app appears as soon
        // as it's back. Otherwise only the icon shows and the app waits unseen.
        if (live) HostSurface(app, component, vw, vh, Modifier.fillMaxSize(), direct = direct)
        else HostedApp(app, component, vw, vh)
    }
}

/**
 * An open app's icon in a glass button. Tap it and it turns into red glass with a
 * white ×; tap that and the app closes, no second question.
 */
@Composable
private fun OpenAppButton(info: AppInfo?, armed: Boolean, onArm: () -> Unit, onClose: () -> Unit, size: Dp = 52.dp) {
    val t = LocalTokens.current
    val haptic = LocalHapticFeedback.current
    val red = if (t.dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    val tint by animateColorAsState(if (armed) red.copy(alpha = 0.82f) else t.glassTint, tween(200), label = "closeTint")
    val p by animateFloatAsState(if (armed) 1f else 0f, spring(0.72f, 520f), label = "closeMorph")
    // When it was armed: a double tap (or a bump on the road) shouldn't arm and close at once.
    val armedAt = remember { longArrayOf(0L) }
    Box(
        Modifier
            .size(size)
            .pressable(onClick = {
                val now = SystemClock.uptimeMillis()
                if (armed) {
                    if (now - armedAt[0] < 350) return@pressable
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClose()
                } else {
                    armedAt[0] = now
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onArm()
                }
            })
            .glass(Capsule, GlassKind.Button, tint = tint),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            info, size * 0.7f,
            Modifier.graphicsLayer { alpha = 1f - p; scaleX = 1f - 0.5f * p; scaleY = 1f - 0.5f * p },
        )
        Glyph(
            Icons.close, Color.White,
            Modifier.graphicsLayer { alpha = p; scaleX = 0.5f + 0.5f * p; scaleY = 0.5f + 0.5f * p; rotationZ = -90f * (1f - p) },
            size = size * 0.42f,
        )
    }
}

/** Otility's own background, softly blurred, in the space between split apps. */
@Composable
private fun SplitBackground() {
    val t = LocalTokens.current
    Image(
        painterResource(if (t.dark) R.drawable.cabin_blur_night else R.drawable.cabin_blur), null,
        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
    )
    Box(Modifier.fillMaxSize().background(if (t.dark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.18f)))
}

/**
 * Three small dots at the top of a zoomed-in screen while its controls are hidden.
 * Dark behind them so they read over any app; the touch area is wider than the dots.
 */
@Composable
private fun Grabber(modifier: Modifier = Modifier, onTap: () -> Unit) {
    Box(modifier.size(width = 120.dp, height = 30.dp).noRipple(onTap), contentAlignment = Alignment.Center) {
        Row(
            Modifier.background(Color.Black.copy(alpha = 0.34f), Capsule).padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { Box(Modifier.size(5.dp).background(Color.White.copy(alpha = 0.9f), Capsule)) }
        }
    }
}

/**
 * What the driver cluster shows: BYD's own view, or one of the maps (only its map,
 * framed), by its icon. [picked] is the map's package, or null for BYD's. Tapping the
 * map that is picked already opens it up over the main screen for a moment ([onPeek]).
 */
@Composable
private fun ClusterSwitch(picked: String?, maps: List<AppInfo>, onPick: (String?) -> Unit, onPeek: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(
        modifier.glass(Capsule, GlassKind.Button).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        @Composable
        fun Segment(pkg: String?, content: @Composable (Boolean) -> Unit) {
            val sel = pkg == picked || (pkg == null && maps.none { it.id.pkg == picked })
            Box(
                Modifier
                    .height(40.dp)
                    .clip(Capsule)
                    .background(if (sel) t.sel else Color.Transparent, Capsule)
                    .noRipple { if (!sel) onPick(pkg) else if (pkg != null) onPeek(pkg) }
                    .padding(horizontal = if (pkg == null) 16.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) { content(sel) }
        }
        Segment(null) { sel ->
            Txt("BYD", T.subhead.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium), if (sel) t.text else t.text2, maxLines = 1)
        }
        maps.forEach { a ->
            key(a.id.pkg) {
                Segment(a.id.pkg) { sel ->
                    AppIcon(a, 28.dp, Modifier.graphicsLayer { alpha = if (sel) 1f else 0.6f })
                }
            }
        }
    }
}
