package app.leopard.cockpit.ui.carplay

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenContent
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.SideInset
import app.leopard.cockpit.ui.SidebarMargin
import app.leopard.cockpit.ui.SidebarWidth
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.noRipple
import app.leopard.cockpit.ui.pressable
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.screens.AppPoster
import app.leopard.cockpit.ui.theme.T
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * CarPlay mode's app switcher, like an iPad's: the main screen's apps lately, each
 * one app or two side by side, as cards. Tap one to have it back as it was; swipe one
 * up to close it. Opened with three fingers up, anywhere in Outo.
 */
object AppSwitcher {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    fun show() { _open.value = true }
    fun hide() { _open.value = false }
}

/**
 * Three fingers swiped up, seen before Outo's window hands the touch on (so over an app
 * too). Once it's the switcher's, the window gets a cancel ([cancel]), as when a system
 * gesture takes over, and nothing more of that touch.
 */
class ThreeFingersUp(private val dp: Float, private val enabled: () -> Boolean, private val onSwipe: () -> Unit) {
    private var tracking = false
    private var taken = false
    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L

    /** True: the event is the gesture's, and the window shouldn't have it. */
    fun onTouch(ev: MotionEvent, cancel: (MotionEvent) -> Unit): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = false
                taken = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!taken && ev.pointerCount == 3 && enabled()) {
                    tracking = true
                    x0 = cx(ev); y0 = cy(ev); t0 = ev.eventTime
                } else {
                    tracking = false
                }
            }
            MotionEvent.ACTION_POINTER_UP -> tracking = false
            MotionEvent.ACTION_MOVE -> if (tracking && ev.pointerCount == 3) {
                val dx = cx(ev) - x0
                val dy = cy(ev) - y0
                if (ev.eventTime - t0 > MAX_MS) {
                    tracking = false
                } else if (-dy > FAR_DP * dp && -dy > abs(dx) * 1.3f) {
                    tracking = false
                    taken = true
                    val c = MotionEvent.obtain(ev)
                    c.action = MotionEvent.ACTION_CANCEL
                    cancel(c)
                    c.recycle()
                    onSwipe()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = taken
                tracking = false
                taken = false
                return was
            }
        }
        return taken
    }

    private fun cx(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getX(i); return s / ev.pointerCount }
    private fun cy(ev: MotionEvent): Float { var s = 0f; for (i in 0 until ev.pointerCount) s += ev.getY(i); return s / ev.pointerCount }

    private companion object {
        /** How far up, in dp, and within how long. */
        const val FAR_DP = 60f
        const val MAX_MS = 1000L
    }
}

/** The app switcher over everything, while it's open. */
@Composable
fun AppSwitcherHost() {
    val open by AppSwitcher.open.collectAsState()
    val nav = LocalNav.current
    val density = LocalDensity.current
    val controller = Otility.instance.screens
    AnimatedVisibility(
        visible = open,
        // The cards rise in on their own (see SpaceCard).
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
    ) {
        Switcher(
            onClose = AppSwitcher::hide,
            onOpen = { space, panes ->
                // Each window grows out of its part of the card.
                space.apps.zip(panes).forEach { (app, r) -> nav.launchOrigins[app] = Origin(r, with(density) { CardCorner.toPx() }, Origin.Kind.CARD) }
                AppSwitcher.hide()
                controller.openSpace(space)
                nav.openScreens(ScreenId.MAIN)
            },
            onRemove = controller::closeSpace,
        )
    }
    BackHandler(enabled = open) { AppSwitcher.hide() }
}

/** The cards' corners. */
private val CardCorner = 20.dp
/** Between the two apps of a split, on its card. */
private val CardGap = 6.dp

@Composable
private fun Switcher(onClose: () -> Unit, onOpen: (ScreenContent, List<Rect>) -> Unit, onRemove: (ScreenContent) -> Unit) {
    val otility = Otility.instance
    val spaces by otility.screens.spaces.collectAsState()
    val apps by otility.catalog.apps.collectAsState()
    val byId = remember(apps) { apps.associateBy { it.id } }
    // Only apps still in the car.
    val shown = spaces.filter { s -> s.apps.isNotEmpty() && s.apps.all { it in byId } }
    // The card tapped: it becomes its windows, so it goes at once, and so does the dark
    // behind it (they grow out of it undimmed); the other cards fade.
    var chosen by remember { mutableStateOf<ScreenContent?>(null) }
    // A tap beside the cards closes it, as on an iPad.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (chosen == null) 0.9f else 0f)).noRipple(onClose)) {
        if (shown.isEmpty()) {
            Txt("No Recent Apps", T.title2, Color.White.copy(alpha = 0.55f), Modifier.align(Alignment.Center))
        } else BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Cards the shape of the space apps open in, beside the sidebar.
            val areaW = maxWidth - SidebarWidth - SidebarMargin * 3
            val areaH = maxHeight - SidebarMargin * 2
            val cardW = maxWidth * 0.36f
            val cardH = cardW * (areaH / areaW)
            LazyRow(
                Modifier.align(Alignment.Center).fillMaxWidth(),
                contentPadding = PaddingValues(start = SideInset, end = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(shown, key = { _, s -> s.apps.joinToString("+") { it.pkg } }) { i, space ->
                    SpaceCard(
                        space, byId, cardW, cardH, i, chosen == space,
                        { panes -> chosen = space; onOpen(space, panes) }, { onRemove(space) },
                        Modifier.animateItem(fadeInSpec = null, placementSpec = Motion.settle(), fadeOutSpec = null),
                    )
                }
            }
        }
    }
}

/**
 * One card: the app's (or two apps') names over its picture. Swipe it up to close it.
 * The cards rise in one after another as the switcher opens. [index]: its place in the row.
 */
@Composable
private fun SpaceCard(
    space: ScreenContent,
    byId: Map<AppId, AppInfo>,
    w: Dp,
    h: Dp,
    index: Int,
    chosen: Boolean,
    onOpen: (List<Rect>) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val hPx = with(density) { h.toPx() }
    val lift = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(4) * 40L)
        rise.animateTo(1f, spring(0.82f, 300f))
    }
    val infos = space.apps.map { byId[it] }
    // The card, in the window's pixels: its windows grow out of it.
    var card by remember { mutableStateOf(Rect.Zero) }
    val open = {
        val gap = with(density) { CardGap.toPx() }
        val panes = when (space) {
            is ScreenContent.Split -> {
                val lw = (card.width - gap) * space.ratio
                listOf(Rect(card.left, card.top, card.left + lw, card.bottom), Rect(card.left + lw + gap, card.top, card.right, card.bottom))
            }
            else -> listOf(card)
        }
        onOpen(panes)
    }
    Column(
        modifier
            // Before the lift, so the finger is followed in the row's terms, not the card's.
            .pointerInput(space) {
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().y
                        scope.launch {
                            if (lift.value < -hPx * 0.35f || v < -1400f) {
                                lift.animateTo(-hPx * 2.4f, tween(200))
                                onRemove()
                            } else {
                                lift.animateTo(0f, spring(0.7f, 420f))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { lift.animateTo(0f, spring(0.7f, 420f)) } },
                ) { change, dy ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    // Up as far as it likes; down only a little, and harder.
                    val next = lift.value + if (lift.value + dy > 0f) dy * 0.25f else dy
                    scope.launch { lift.snapTo(next.coerceAtMost(hPx * 0.12f)) }
                }
            }
            .graphicsLayer {
                val r = rise.value
                translationY = lift.value + (1f - r) * hPx * 0.25f
                val s = 0.92f + 0.08f * r
                scaleX = s; scaleY = s
                alpha = if (chosen) 0f else r.coerceIn(0f, 1f) * (1f - (-lift.value / (hPx * 1.6f)).coerceIn(0f, 1f))
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            infos.forEach { a ->
                AppIcon(a, 32.dp)
                Spacer(Modifier.width(8.dp))
            }
            Txt(infos.joinToString(" & ") { it?.label.orEmpty() }, T.headline, Color.White, maxLines = 1)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .size(w, h)
                .onGloballyPositioned { card = it.boundsInRoot() }
                .pressable(open, scaleDown = 0.97f)
                .clip(rounded(CardCorner)),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            when (space) {
                is ScreenContent.Single -> AppPoster(infos.first(), Modifier.fillMaxHeight())
                is ScreenContent.Split -> {
                    Box(Modifier.weight(space.ratio).fillMaxHeight()) { AppPoster(infos[0]) }
                    Box(Modifier.weight(1f - space.ratio).fillMaxHeight()) { AppPoster(infos[1]) }
                }
                ScreenContent.Idle -> {}
            }
        }
    }
}
