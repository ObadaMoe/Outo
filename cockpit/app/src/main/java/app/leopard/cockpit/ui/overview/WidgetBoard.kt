package app.leopard.cockpit.ui.overview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.leopard.cockpit.data.WidgetGrid
import app.leopard.cockpit.data.WidgetKind
import app.leopard.cockpit.data.WidgetLayout
import app.leopard.cockpit.data.WidgetSize
import app.leopard.cockpit.data.WidgetSlot
import app.leopard.cockpit.ui.Capsule
import app.leopard.cockpit.ui.GlassKind
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalBackdrop
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.glass
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import com.kyant.backdrop.backdrops.emptyBackdrop
import kotlin.math.roundToInt

/** Space between widgets. */
private val GAP = 16.dp
/** One grid row; a widget two rows tall is as tall as the Overview's old cards. */
private val ROW = 120.dp

private enum class Grab { NONE, MOVE, RESIZE }

/**
 * The Overview's widgets on a 6-column grid, the way iPhone home screens work:
 * - hold a widget: they all start to shake; keep holding and move to carry it,
 *   or let go to open its menu (sizes, what it shows, remove);
 * - while they shake: drag one anywhere, stretch its corner to resize it (it
 *   snaps to the sizes it was designed for), or tap − to remove it.
 * [layout] changes only when a move or resize ends ([onChange]); in between the
 * board shows the change live. The − removes a widget through [onRemove].
 */
@Composable
fun WidgetBoard(
    layout: WidgetLayout,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onChange: (WidgetLayout) -> Unit,
    onRemove: (WidgetKind) -> Unit,
    onMenu: (WidgetSlot, Rect) -> Unit,
    scroll: ScrollState,
    /** The scrolling area, in root coordinates (for scrolling while carrying a widget near its edge). */
    viewport: () -> Rect,
    /** How many columns the board has: all 6, or those the pinned map leaves. */
    columns: Int = WidgetGrid.COLUMNS,
    /** The size a widget is drawn at here (beside the map, the board's full width). */
    fit: (WidgetSize) -> WidgetSize = { it },
    /** Whether a widget's corner stretches it (not beside the map, where widgets take the full width). */
    resizable: Boolean = true,
    /** A widget that stays first (Now Playing, at the top as on CarPlay): nothing moves above it. */
    lead: WidgetKind? = null,
    content: @Composable (WidgetSlot) -> Unit,
) {
    val t = LocalTokens.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val still = remember { emptyBackdrop() }
    val editingNow by rememberUpdatedState(editing)
    val layoutNow by rememberUpdatedState(layout)
    val onEditingNow by rememberUpdatedState(onEditing)
    val onChangeNow by rememberUpdatedState(onChange)
    val onRemoveNow by rememberUpdatedState(onRemove)
    val onMenuNow by rememberUpdatedState(onMenu)
    // Read when a widget moves, not when its gesture began: Now Playing joins (or
    // leaves) the board while widgets are held.
    val leadNow by rememberUpdatedState(lead)

    /** The layout while a widget is carried or stretched. */
    var live by remember { mutableStateOf<WidgetLayout?>(null) }
    var active by remember { mutableStateOf<WidgetKind?>(null) }
    var grab by remember { mutableStateOf(Grab.NONE) }
    /** The carried widget's top-left on the board. */
    var carried by remember { mutableStateOf(Offset.Zero) }
    /** The stretched widget's size as the finger has it, before snapping. */
    var stretched by remember { mutableStateOf(Size.Zero) }
    /** The finger holding a widget, in window coordinates (they stay put while the board moves). */
    var finger by remember { mutableStateOf(Offset.Unspecified) }
    /** The point of the held widget under the finger, so it stays held where it was picked up. */
    var grabAt by remember { mutableStateOf(Offset.Zero) }
    val board = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val shown = live ?: layout

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = with(density) { GAP.toPx() }
        val row = with(density) { ROW.toPx() }
        val width = maxWidth
        val col = (with(density) { width.toPx() } - gap * (columns - 1)) / columns
        fun topLeft(c: WidgetGrid.Cell) = Offset(c.x * (col + gap), c.y * (row + gap))
        fun sizeOf(s: WidgetSize) = Size(s.w * col + (s.w - 1) * gap, s.h * row + (s.h - 1) * gap)

        fun pack(l: WidgetLayout) = WidgetGrid.pack(l.slots.map { fit(it.size) }, columns)
        val cells = pack(shown)
        val rows = WidgetGrid.rows(shown.slots.map { fit(it.size) }, cells)
        val height = with(density) { (rows * row + (rows - 1).coerceAtLeast(0) * gap).toDp() }

        /** Keeps the held widget under the finger, wherever the board has moved to. */
        fun follow() {
            val f = finger
            val b = board[0]?.takeIf { it.isAttached }
            if (f.isUnspecified || b == null) return
            carried = b.windowToLocal(f) - grabAt
        }

        /** Puts the carried widget where its middle is nearest to where it would sit. */
        fun reorder(kind: WidgetKind) {
            val base = live ?: layoutNow
            // Only while it's there: a board taken a moment before it joined has none.
            val first = leadNow?.takeIf { base.indexOf(it) == 0 }
            val slot = base.slots.firstOrNull { it.kind == kind } ?: return
            val middle = carried + sizeOf(fit(slot.size)).center
            fun distance(l: WidgetLayout): Float {
                val c = pack(l)[l.indexOf(kind)]
                return (topLeft(c) + sizeOf(fit(slot.size)).center - middle).getDistance()
            }
            var best = base
            var bestDistance = distance(base)
            for (i in base.slots.indices) {
                val l = base.move(kind, i)
                if (l == base) continue
                if (first != null && l.slots.firstOrNull()?.kind != first) continue
                val d = distance(l)
                // A margin, so the widgets don't flick back and forth on a border.
                if (d < bestDistance - col * 0.2f) { best = l; bestDistance = d }
            }
            if (best != base) {
                live = best
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        // While a widget is held: keep it under the finger as the board moves under it
        // (the Add Widget row and Now Playing coming in as editing starts, or scrolling),
        // and once it's carried, scroll when it's near the top or bottom edge.
        LaunchedEffect(grab, active) {
            if (grab != Grab.MOVE) return@LaunchedEffect
            val kind = active ?: return@LaunchedEffect
            val edge = with(density) { 96.dp.toPx() }
            val fastest = with(density) { 14.dp.toPx() }
            while (true) {
                withFrameNanos { }
                val before = carried
                follow()
                // Only held (for its menu), not carried yet: nothing moves for it.
                if (live == null) continue
                if (carried != before) reorder(kind)
                val f = finger
                val b = board[0]?.takeIf { it.isAttached } ?: continue
                if (f.isUnspecified) continue
                val p = b.localToRoot(b.windowToLocal(f))
                val v = viewport()
                val speed = when {
                    p.y > v.bottom - edge -> ((p.y - (v.bottom - edge)) / edge).coerceAtMost(1f) * fastest
                    p.y < v.top + edge -> -((v.top + edge - p.y) / edge).coerceAtMost(1f) * fastest
                    else -> 0f
                }
                if (speed != 0f) scroll.scrollBy(speed)
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .onGloballyPositioned { board[0] = it }
                // A tap between widgets ends editing (not with another finger carrying one).
                .pointerInput(Unit) { detectTapGestures { if (editingNow && active == null) onEditingNow(false) } },
        ) {
            shown.slots.forEachIndexed { index, slot ->
                key(slot.kind) {
                    val kind = slot.kind
                    val target = topLeft(cells[index])
                    val targetSize = sizeOf(fit(slot.size))
                    val pos = remember { Animatable(target, Offset.VectorConverter) }
                    val dim = remember { Animatable(targetSize, Size.VectorConverter) }
                    val isCarried = active == kind && grab == Grab.MOVE
                    var dropped by remember { mutableStateOf<Offset?>(null) }
                    LaunchedEffect(target, isCarried) {
                        if (isCarried) return@LaunchedEffect
                        dropped?.let { pos.snapTo(it); dropped = null }
                        pos.animateTo(target, spring(0.8f, 420f))
                    }
                    LaunchedEffect(targetSize) { dim.animateTo(targetSize, spring(0.85f, 520f)) }
                    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }

                    val shake: State<Float>? = if (editing && active != kind) {
                        val inf = rememberInfiniteTransition(label = "shake")
                        inf.animateFloat(
                            -1f, 1f,
                            infiniteRepeatable(tween(120 + (index % 3) * 14, easing = LinearEasing), RepeatMode.Reverse, StartOffset(index * 53)),
                            label = "shake",
                        )
                    } else null
                    val lifted = active == kind

                    val at = if (isCarried) carried else dropped ?: pos.value
                    Box(
                        Modifier
                            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                            .requiredSize(with(density) { dim.value.width.toDp() }, with(density) { dim.value.height.toDp() })
                            .zIndex(if (lifted) 2f else 0f)
                            .onGloballyPositioned { coords[0] = it }
                            // Restarted when the board's shape changes (the map added, removed or
                            // resized), so a gesture never works on the grid as it was before.
                            .pointerInput(kind, columns, resizable, width) {
                                val slop = viewConfiguration.touchSlop
                                val badge = 48.dp.toPx()
                                val corner = 56.dp.toPx()
                                /** [local] (a point on this widget) in window coordinates. */
                                fun window(local: Offset): Offset? = coords[0]?.takeIf { it.isAttached }?.localToWindow(local)
                                /** The widget's menu, beside the place it goes back to (the board may still be making room). */
                                fun openMenu() {
                                    val l = layoutNow
                                    val i = l.indexOf(kind)
                                    val b = board[0]?.takeIf { it.isAttached }
                                    if (i >= 0 && b != null) {
                                        val now = l.slots[i]
                                        val place = Rect(b.localToRoot(topLeft(pack(l)[i])), sizeOf(fit(now.size)))
                                        val seen = place.intersect(viewport())
                                        onMenuNow(now, if (seen.isEmpty) place else seen)
                                    }
                                }
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    var mode = Grab.MOVE
                                    var onBadge = false
                                    if (!editingNow) {
                                        // Wait for a hold. A tap or a scroll belongs to the widget and the page.
                                        var holdAt = down.position
                                        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            var travel = Offset.Zero
                                            while (true) {
                                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                                val c = e.changes.firstOrNull { it.id == down.id }
                                                if (c == null || !c.pressed || e.changes.size > 1) break
                                                travel += c.positionChangeIgnoreConsumed()
                                                holdAt = c.position
                                                if (travel.getDistance() > slop) break
                                            }
                                            true
                                        }
                                        if (ended != null) return@awaitEachGesture
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onEditingNow(true)
                                        // Lifted now, so it stays under the finger while the board
                                        // makes room for editing above it.
                                        grabAt = holdAt
                                        finger = window(holdAt) ?: Offset.Unspecified
                                        follow()
                                        active = kind
                                        grab = Grab.MOVE
                                    } else {
                                        onBadge = down.position.x < badge && down.position.y < badge
                                        if (resizable && down.position.x > size.width - corner && down.position.y > size.height - corner) mode = Grab.RESIZE
                                        if (mode == Grab.MOVE && !onBadge) {
                                            // Even while editing, a widget moves only once it's been held a
                                            // moment, so touching one doesn't shove it: a quick swipe scrolls
                                            // the page instead, and a tap opens its menu.
                                            down.consume()
                                            var holdAt = down.position
                                            val early: Int? = withTimeoutOrNull(EDIT_HOLD_MS) {
                                                var travel = Offset.Zero
                                                var r = -1
                                                while (r < 0) {
                                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                                    val c = e.changes.firstOrNull { it.id == down.id }
                                                    r = when {
                                                        c == null || !c.pressed -> 0
                                                        e.changes.size > 1 -> 1
                                                        else -> {
                                                            travel += c.positionChangeIgnoreConsumed()
                                                            holdAt = c.position
                                                            if (travel.getDistance() > slop) 1 else -1
                                                        }
                                                    }
                                                }
                                                r
                                            }
                                            when (early) {
                                                0 -> { openMenu(); return@awaitEachGesture }
                                                1 -> return@awaitEachGesture
                                                else -> {
                                                    // Held: it lifts, as when editing began, and follows the finger.
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    grabAt = holdAt
                                                    finger = window(holdAt) ?: Offset.Unspecified
                                                    follow()
                                                    active = kind
                                                    grab = Grab.MOVE
                                                }
                                            }
                                        }
                                    }
                                    down.consume()
                                    var travel = Offset.Zero
                                    var started = false
                                    while (true) {
                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                        val c = e.changes.firstOrNull { it.id == down.id }
                                        val d = c?.positionChangeIgnoreConsumed() ?: Offset.Zero
                                        e.changes.forEach { it.consume() }
                                        if (c == null || !c.pressed) break
                                        travel += d
                                        if (mode == Grab.MOVE && (started || active == kind)) {
                                            window(c.position)?.let { finger = it }
                                        }
                                        if (!started && !onBadge && travel.getDistance() > slop) {
                                            started = true
                                            val base = live ?: layoutNow
                                            val i = base.indexOf(kind)
                                            if (i < 0) break
                                            live = base
                                            if (mode == Grab.MOVE) {
                                                // Not lifted by a hold: held at the point first touched.
                                                if (active != kind || grab != Grab.MOVE) {
                                                    grabAt = down.position
                                                    finger = window(c.position) ?: Offset.Unspecified
                                                }
                                                follow()
                                            } else {
                                                val s0 = sizeOf(base.slots[i].size)
                                                stretched = Size(s0.width + travel.x, s0.height + travel.y)
                                            }
                                            active = kind
                                            grab = mode
                                            continue
                                        }
                                        if (!started) continue
                                        if (mode == Grab.MOVE) {
                                            follow()
                                            reorder(kind)
                                        } else {
                                            stretched = Size(stretched.width + d.x, stretched.height + d.y)
                                            val snapped = kind.nearest((stretched.width + gap) / (col + gap), (stretched.height + gap) / (row + gap))
                                            val base = live ?: layoutNow
                                            if (base.slots.firstOrNull { it.kind == kind }?.size != snapped) {
                                                live = base.resize(kind, snapped)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                    // Let go: a held widget goes back to its place from under the finger.
                                    if (active == kind && grab == Grab.MOVE) dropped = carried
                                    when {
                                        started -> {
                                            live?.let { onChangeNow(it) }
                                            live = null
                                            active = null
                                            grab = Grab.NONE
                                            finger = Offset.Unspecified
                                        }
                                        onBadge -> onRemoveNow(kind)
                                        // A hold let go without moving: the widget's menu.
                                        else -> {
                                            if (active == kind) {
                                                active = null
                                                grab = Grab.NONE
                                                finger = Offset.Unspecified
                                            }
                                            openMenu()
                                        }
                                    }
                                }
                            }
                            .graphicsLayer {
                                rotationZ = (shake?.value ?: 0f) * 1.1f
                                val s = if (lifted && grab == Grab.MOVE) 1.04f else 1f
                                scaleX = s; scaleY = s
                                shadowElevation = if (lifted) 24.dp.toPx() else 0f
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                            },
                    ) {
                        // A shaking card moves every frame, and glass that bends what's behind it
                        // would redraw its blur and lens each time for every card. The cabin
                        // photo under the cards is already blurred, so while editing the cards
                        // tint it without sampling it: the same look at a fraction of the work.
                        CompositionLocalProvider(LocalBackdrop provides if (editing) still else LocalBackdrop.current) {
                            content(slot.copy(size = fit(slot.size)))
                        }
                        if (editing) {
                            // Like iOS: the badge sits over the corner, clear of the card's title.
                            RemoveBadge(Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp))
                            if (resizable) ResizeCorner(Modifier.align(Alignment.BottomEnd))
                        }
                        if (active == kind && grab == Grab.RESIZE) {
                            SizeLabel(slot.size, Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }
}

/** The − in a shaking widget's corner. */
/** While editing, how long a widget is held before it moves with the finger. */
private const val EDIT_HOLD_MS = 280L

@Composable
internal fun RemoveBadge(modifier: Modifier) {
    Box(
        modifier
            .size(28.dp)
            .background(Color(0xF23A3A3C), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Glyph(Icons.minus, Color.White, size = 16.dp) }
}

/** The arc in a shaking widget's bottom-right corner: grab it to stretch the widget. */
@Composable
private fun ResizeCorner(modifier: Modifier) {
    Canvas(modifier.size(56.dp)) {
        val r = 20.dp.toPx()
        val inset = 9.dp.toPx()
        val tl = Offset(size.width - inset - 2 * r, size.height - inset - 2 * r)
        val arc = Size(2 * r, 2 * r)
        drawArc(Color.Black.copy(alpha = 0.35f), 0f, 90f, false, tl, arc, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
        drawArc(Color.White, 0f, 90f, false, tl, arc, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** "Medium · 4 × 2" while a widget is stretched. */
@Composable
private fun SizeLabel(size: WidgetSize, modifier: Modifier) {
    val t = LocalTokens.current
    Box(modifier.glass(Capsule, GlassKind.Button).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Txt("${size.label} · $size", T.headline.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), t.text, maxLines = 1)
    }
}
