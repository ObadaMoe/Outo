package app.leopard.cockpit.ui.screens

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.leopard.cockpit.Otility
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.SideInset
import kotlin.math.roundToInt

/**
 * The cluster's map, opened up over everything for a moment: tapping the map that is
 * already picked in the switch above the Driver screen (or its icon on the sidebar while
 * the main screen has another app). All of it, at this size, Waze's search and buttons
 * too, to pick a place, while the main screen keeps its app: YouTube plays on underneath,
 * and nothing is replaced or split. The cluster shows BYD's own view meanwhile (the app
 * is drawn at one size at a time) and has the map back once it closes: Back in the
 * sidebar, the tab's button, two fingers right (back to the main screen's app: [onMain])
 * or down (out to all three screens: [onOut]). The car's Back goes to the app.
 */
@Composable
fun ClusterPeek(app: AppId, onClose: () -> Unit, onMain: () -> Unit, onOut: () -> Unit) {
    val otility = Otility.instance
    val nav = LocalNav.current
    val density = LocalDensity.current
    val settings by otility.prefs.settings.collectAsState()
    val apps by otility.catalog.apps.collectAsState()
    val clusterMap by otility.screens.clusterMap.collectAsState()
    val helper by HelperConnection.helper.collectAsState()
    val info = apps.firstOrNull { it.id == app }
    val close by rememberUpdatedState(onClose)
    val toMain by rememberUpdatedState(onMain)
    val out by rememberUpdatedState(onOut)
    if (info == null) {
        LaunchedEffect(Unit) { close() }
        return
    }

    // Lent while Otility is on the screen: gone from it (Home, say), the cluster has its map back.
    var onScreen by remember { mutableStateOf(true) }
    LifecycleStartEffect(Unit) {
        onScreen = true
        onStopOrDispose { onScreen = false }
    }
    val lent = onScreen && clusterMap?.app == app
    DisposableEffect(lent) {
        if (lent) otility.screens.lendClusterMap(true)
        onDispose { if (lent) otility.screens.lendClusterMap(false) }
    }

    // Back at the top of the sidebar, and the open tab's button, close it.
    DisposableEffect(Unit) {
        val back = { close() }
        nav.back = back
        nav.reselect = back
        onDispose {
            if (nav.back === back) nav.back = null
            if (nav.reselect === back) nav.reselect = null
        }
    }
    // The car's Back goes into the app, as on a zoomed-in screen (out of Waze's search, say).
    BackHandler {
        val display = otility.screens.hosts.displayOf(app)
        if (helper != null && display != null) otility.bridge.key(KeyEvent.KEYCODE_BACK, display) else close()
    }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(1f, 320f)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Nothing under it takes a touch meanwhile.
            .pointerInput(Unit) { detectTapGestures { } }
            .graphicsLayer { alpha = appear.value },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // The sidebar keeps its rail down the left, with Back at the top, as CarPlay's does.
        val rail = with(density) { SideInset.toPx() }
        val inset = with(density) { 8.dp.toPx() }
        val open = Rect(rail, inset, w - inset, h - inset)
        if (open.width <= 0f || open.height <= 0f) return@BoxWithConstraints
        val appW = open.width.roundToInt() and 1.inv()
        val appH = open.height.roundToInt() and 1.inv()
        val shape = RoundedCornerShape(24.dp)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
        Box(
            Modifier
                .offset { IntOffset(open.left.roundToInt(), open.top.roundToInt()) }
                .size(with(density) { open.width.toDp() }, with(density) { open.height.toDp() })
                .graphicsLayer {
                    val s = 0.96f + 0.04f * appear.value
                    scaleX = s; scaleY = s
                    // From the left, where the cluster is, as a tab to the left would.
                    translationX = -(1f - appear.value) * 48.dp.toPx()
                }
                .clip(shape)
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
        ) {
            // The app's icon until its picture arrives at this size.
            AppPoster(info)
            // In Otility's window (not straight to the screen): it lies over the main
            // screen's app, which may be drawn straight to the screen under the window.
            HostSurface(app, info.component.flattenToShortString(), appW, appH, Modifier.fillMaxSize(), direct = false)
        }
        // Touches on it go to the app, in its own pixels.
        val router = remember {
            TouchRouter(
                otility.bridge, density.density,
                onTwoUp = {},
                onTwoDown = { out() },
                // It is the leftmost screen: only right, to the main screen.
                onTwoSide = { dir -> if (dir > 0) toMain() },
                onTouchedPane = {},
            )
        }
        router.gestures = settings.gestures
        router.targets = listOf(TouchTarget(Rect(0f, 0f, open.width, open.height), { otility.screens.hosts.displayOf(app) }, { appW }, { appH }))
        Box(
            Modifier
                .offset { IntOffset(open.left.roundToInt(), open.top.roundToInt()) }
                .size(with(density) { open.width.toDp() }, with(density) { open.height.toDp() })
                .routeTouches(router),
        )
    }
}
