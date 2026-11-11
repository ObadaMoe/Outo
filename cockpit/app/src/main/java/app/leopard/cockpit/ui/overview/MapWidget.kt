package app.leopard.cockpit.ui.overview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.leopard.cockpit.ui.SideInset
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.leopard.cockpit.Otility
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.data.WidgetSlot
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.screens.AppPoster
import app.leopard.cockpit.ui.screens.HostSurface
import app.leopard.cockpit.ui.screens.TouchRouter
import app.leopard.cockpit.ui.screens.routeTouches
import app.leopard.cockpit.ui.screens.TouchTarget
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The map widget, like CarPlay's dashboard: the map app used last, live, in the
 * pane beside the other widgets ([pane], in the Overview's pixels). It takes touches where
 * it is (a drag pans the map, two fingers zoom it, a tap goes to its buttons; see
 * [MapCardTouches]); tap it twice and it grows to fill the screen ([full]), with Back to
 * come out again. Hold it still (or tap it while the widgets shake) for its menu. A map
 * on the driver cluster comes off it while the Overview is open, and goes back when it closes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapWidget(
    slot: WidgetSlot,
    pane: Rect,
    full: Rect,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onMenu: (WidgetSlot, Rect) -> Unit,
    onRemove: () -> Unit,
    // How far opened up, 0 to 1; the Overview fades its widgets out by it.
    progress: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) },
) {
    val otility = Otility.instance
    val nav = LocalNav.current
    val t = LocalTokens.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val settings by otility.prefs.settings.collectAsState()
    val apps by otility.catalog.apps.collectAsState()
    val last by otility.prefs.lastMap.collectAsState()
    val screens by otility.screens.state.collectAsState()
    val helper by HelperConnection.helper.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    // The map on the driver cluster (the switch in Screens) runs in a display of its
    // own, which the cluster's window shows; while the Overview is open, it's here instead.
    val clusterMap by otility.screens.clusterMap.collectAsState()
    val pkg = remember(apps, last, settings.navApp, clusterMap?.app) { otility.navigator.mapApp() }
    val id = pkg?.let { AppId(it) }
    val info = apps.firstOrNull { it.id == id }
    val mirrored = id != null && clusterMap?.app == id
    // While Otility is on the screen (it may go with the map opened up).
    var onScreen by remember { mutableStateOf(true) }
    LifecycleStartEffect(Unit) {
        onScreen = true
        onStopOrDispose { onScreen = false }
    }
    // On the passenger screen it's the car's to show: going there instead of pulling
    // it off that screen (and on the cluster before it's framed there).
    val elsewhere = id?.takeIf { !mirrored }?.let { screens.screenOf(it) }?.takeIf { it != ScreenId.MAIN }
    val live = info != null && elsewhere == null
    // While the Overview is open, the cluster's map is here, all of it, at the card's size
    // or opened up (Waze's search and buttons too), not a copy of the cluster's cut of it.
    // The cluster shows BYD's own view meanwhile, and has the map back once the Overview
    // closes (another tab, or Otility leaves the screen).
    val lent = mirrored && live && onScreen
    val lender = remember { Any() }
    DisposableEffect(lent) {
        if (lent) otility.screens.lendClusterMap(lender, true)
        onDispose { if (lent) otility.screens.lendClusterMap(lender, false) }
    }

    val p = progress
    LaunchedEffect(expanded) { p.animateTo(if (expanded) 1f else 0f, spring(1f, 280f)) }
    val settled = !p.isRunning
    LaunchedEffect(editing, live) { if (editing || !live) expanded = false }
    // Opened up: Back at the top of the sidebar, and Home (the Overview's button) closes it too.
    DisposableEffect(expanded) {
        val close = { expanded = false }
        if (expanded) {
            nav.back = close
            nav.reselect = close
        }
        onDispose {
            if (nav.back === close) nav.back = null
            if (nav.reselect === close) nav.reselect = null
        }
    }
    val editingNow by rememberUpdatedState(editing)
    // The gestures below last longer than one drawing of the map: they read these as
    // they are now (after a size change from the menu, say), not as they began.
    val slotNow by rememberUpdatedState(slot)
    val paneNow by rememberUpdatedState(pane)
    val onEditingNow by rememberUpdatedState(onEditing)
    val onMenuNow by rememberUpdatedState(onMenu)
    val onRemoveNow by rememberUpdatedState(onRemove)
    if (pane.width <= 0f || full.width <= 0f) return
    // Opened up, the map leaves the tab bar its rail down the left, with Back above
    // it, as CarPlay does, so nothing of Otility's covers the map app's banners and buttons.
    // [full] is the Overview's area, already between the car's bars.
    val rail = with(density) { SideInset.toPx() }
    val inset = with(density) { 8.dp.toPx() }
    val open = Rect(full.left + rail, full.top + inset, full.right - inset, full.bottom - inset)
    val rect = lerp(pane, open, p.value)
    // The app's real size: the pane's, or the opened-up map's.
    val target = if (expanded) open else pane
    val appW = target.width.roundToInt() and 1.inv()
    val appH = target.height.roundToInt() and 1.inv()
    // The map's app runs all the time (see ScreensController.keepMapRunning): at the card's size.
    val cardW = pane.width.roundToInt() and 1.inv()
    val cardH = pane.height.roundToInt() and 1.inv()
    LaunchedEffect(cardW, cardH) { otility.screens.mapSize = cardW to cardH }
    val corner = lerp(28.dp, 24.dp, p.value)
    val shape = RoundedCornerShape(corner)

    // Small and at rest, the map takes touches where it is (not while the widgets shake,
    // nor without the helper to pass them on: then a tap opens it up, as before).
    val touchable = live && id != null && !(mirrored && !lent) && !editing && !expanded && settled && helper != null
    val touches = if (touchable) {
        val hosts = otility.screens.hosts
        val context = LocalContext.current
        val router = remember {
            TouchRouter(otility.bridge, density.density, onTwoUp = {}, onTwoDown = {}, onTwoSide = {}, onTouchedPane = {})
                .also { it.gestures = false }
        }
        router.targets = listOf(TouchTarget(Rect(0f, 0f, pane.width, pane.height), { hosts.displayOf(id!!) }, { cardW }, { cardH }))
        remember(router) {
            val vc = ViewConfiguration.get(context)
            MapCardTouches(
                router, vc.scaledTouchSlop.toFloat(), vc.scaledDoubleTapSlop.toFloat(),
                onDoubleTap = { expanded = true },
                onHold = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!editingNow) onEditingNow(true)
                    onMenuNow(slotNow, paneNow)
                },
            )
        }
    } else null

    val shake = if (editing) {
        rememberInfiniteTransition(label = "mapShake").animateFloat(
            -1f, 1f, infiniteRepeatable(tween(150, easing = LinearEasing), RepeatMode.Reverse), label = "mapShake",
        ).value
    } else 0f

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                // A big widget shakes less, as on iPhone.
                .graphicsLayer { rotationZ = shake * 0.45f }
                .pointerInput(live, elsewhere) {
                    val badge = 48.dp.toPx()
                    detectTapGestures(
                        onLongPress = {
                            if (expanded) return@detectTapGestures
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (!editingNow) onEditingNow(true)
                            onMenuNow(slotNow, paneNow)
                        },
                        onTap = { at ->
                            when {
                                expanded -> {}
                                editingNow && at.x < badge && at.y < badge -> onRemoveNow()
                                editingNow -> onMenuNow(slotNow, paneNow)
                                elsewhere != null -> nav.openScreens(elsewhere)
                                live -> expanded = true
                            }
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
            ) {
                when {
                    info == null -> Message(null, "No map app", "Install Waze or Google Maps to see the map here.")
                    elsewhere != null -> Message(info, "${info.label} is on the ${elsewhere.label.lowercase()} screen", "Tap to go to it.")
                    // Off the screen, the cluster keeps it: nothing to show here meanwhile.
                    mirrored && !lent -> AppPoster(info)
                    else -> {
                        // The app's icon until its picture arrives (or while the car isn't connected).
                        AppPoster(info)
                        HostSurface(
                            id!!, info.component.flattenToShortString(), appW, appH,
                            // Laid out at the app's size, scaled to cover the pane as it grows or shrinks.
                            Modifier.cover(appW, appH),
                            // Straight to the screen: the map's pictures don't make the Overview
                            // draw itself again, a frame later, and it answers a finger sooner.
                            // The rounded corners hold: the map is under Otility's window, seen
                            // through the hole the view cuts in it. Not while the widgets shake
                            // (the hole would turn, the map not), nor under a sheet, whose glass
                            // blurs what's behind it: it would show the map sharp.
                            direct = !editing && nav.sheet == null,
                        )
                        if (touchable) MapCardTouchLayer(touches!!)
                    }
                }
            }
            if (editing) RemoveBadge(Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp))
        }

        // ---- Opened up: touches go to the app; Back in the rail comes out ------------
        if (expanded && settled && live) {
            val hosts = otility.screens.hosts
            val router = remember {
                TouchRouter(
                    otility.bridge, density.density,
                    onTwoUp = {},
                    onTwoDown = { expanded = false },
                    onTwoSide = {},
                    onTouchedPane = {},
                )
            }
            router.gestures = settings.gestures
            // (The cluster's map too: on the Overview it's here; see [lent].)
            router.targets = listOf(TouchTarget(Rect(0f, 0f, open.width, open.height), { hosts.displayOf(id!!) }, { appW }, { appH }))
            Box(
                Modifier
                    .offset { IntOffset(open.left.roundToInt(), open.top.roundToInt()) }
                    .size(with(density) { open.width.toDp() }, with(density) { open.height.toDp() })
                    .routeTouches(router),
            )
        }
    }

    // Back inside the app, as on a zoomed-in screen; the button comes out of it.
    BackHandler(enabled = expanded) {
        val display = id?.let { otility.screens.hosts.displayOf(it) }
        if (helper != null && display != null) otility.bridge.key(KeyEvent.KEYCODE_BACK, display) else expanded = false
    }
}

/** Over the small map: its touches, to [touches]; anything waiting ends when it goes. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MapCardTouchLayer(touches: MapCardTouches) {
    val view = LocalView.current
    DisposableEffect(touches) { onDispose { touches.dispose() } }
    Box(
        Modifier.fillMaxSize().pointerInteropFilter { ev ->
            // Each move on to the map the moment it comes, not with Otility's next frame.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(ev)
            touches.onTouch(ev)
        },
    )
}

/** Laid out at [w] × [h], scaled to cover the space it's given, around its middle. */
private fun Modifier.cover(w: Int, h: Int): Modifier = layout { m, c ->
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

@Composable
private fun Message(app: app.leopard.cockpit.data.AppInfo?, title: String, detail: String) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxSize().background(Color(0xFF15171B)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (app != null) AppIcon(app, 72.dp) else Glyph(Icons.pin, Color.White.copy(alpha = 0.6f), size = 44.dp)
        Box(Modifier.size(14.dp))
        Txt(title, T.headline.copy(textAlign = TextAlign.Center), Color.White)
        Txt(detail, T.footnote.copy(textAlign = TextAlign.Center), Color.White.copy(alpha = 0.6f))
    }
}
