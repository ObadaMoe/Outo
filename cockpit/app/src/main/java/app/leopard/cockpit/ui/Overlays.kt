package app.leopard.cockpit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.ui.overview.AddWidgets
import app.leopard.cockpit.ui.overview.SoundStudio
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import kotlin.math.roundToInt

// ---- Alerts ----------------------------------------------------------------

/** Apple's alert: frosted glass in the middle of the screen, the action in bold. */
@Composable
fun AlertHost() {
    val t = LocalTokens.current
    val alert by Alerts.current.collectAsState()
    var shown by remember { mutableStateOf<Alert?>(null) }
    if (alert != null) shown = alert
    val progress = animateFloatAsState(if (alert != null) 1f else 0f, spring(0.86f, 520f), label = "alert")
    val p by progress
    // Each frame is read only where it's drawn; this redraws only once it's gone.
    val gone by remember { derivedStateOf { progress.value < 0.01f } }
    val a = shown ?: return
    if (gone && alert == null) return

    // Back is Cancel.
    BackHandler(enabled = alert != null) {
        Alerts.dismiss()
        a.buttons.firstOrNull { it.role == Role.CANCEL }?.action?.invoke()
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black, alpha = (0.32f * p).coerceIn(0f, 1f)) }
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(300.dp)
                .graphicsLayer {
                    alpha = p.coerceIn(0f, 1f)
                    val s = 1.12f - 0.12f * p
                    scaleX = s; scaleY = s
                }
                .glass(rounded(26.dp), GlassKind.Menu)
                .clip(rounded(26.dp)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Txt(a.title, T.headline.copy(textAlign = TextAlign.Center))
                if (a.message != null) Txt(a.message, T.footnote.copy(textAlign = TextAlign.Center), t.text2)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(t.sep))
            // Once: the buttons stay on screen while the alert fades, and a second
            // tap then would run the action again.
            val press: (AlertButton) -> Unit = press@{ b ->
                if (Alerts.current.value !== a) return@press
                Alerts.dismiss()
                b.action()
            }
            // As on iOS: two short buttons side by side with Cancel on the left;
            // otherwise stacked, with Cancel at the bottom.
            val cancel = a.buttons.filter { it.role == Role.CANCEL }
            val others = a.buttons - cancel.toSet()
            if (a.buttons.size == 2 && a.buttons.all { it.label.length <= 13 }) {
                val (first, second) = (cancel + others).let { it[0] to it[1] }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    AlertAction(first, Modifier.weight(1f)) { press(first) }
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(t.sep))
                    AlertAction(second, Modifier.weight(1f)) { press(second) }
                }
            } else {
                (others + cancel).forEachIndexed { i, b ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(t.sep))
                    AlertAction(b, Modifier.fillMaxWidth()) { press(b) }
                }
            }
        }
    }
}

@Composable
private fun AlertAction(b: AlertButton, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTokens.current
    val color = if (b.role == Role.DESTRUCTIVE) t.destructive else t.accText
    val weight = if (b.role == Role.CANCEL) FontWeight.Normal else FontWeight.SemiBold
    Box(modifier.height(50.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Txt(b.label, T.body.copy(fontWeight = weight), color)
    }
}

// ---- Sheets ----------------------------------------------------------------

@Composable
fun SheetHost() {
    val nav = LocalNav.current
    val kind = nav.sheet
    var shown by remember { mutableStateOf<SheetKind?>(null) }
    if (kind != null) shown = kind
    val progress = animateFloatAsState(if (kind != null) 1f else 0f, spring(0.9f, 380f), label = "sheet")
    val p by progress
    // Each frame is read only where it's drawn; this redraws only once it's gone.
    val gone by remember { derivedStateOf { progress.value < 0.01f } }
    val current = shown ?: return
    if (gone && kind == null) return
    val close = { nav.sheet = null }
    BackHandler(enabled = kind != null) { close() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black, alpha = (0.38f * p).coerceIn(0f, 1f)) }
            .pointerInput(Unit) { detectTapGestures { close() } },
        contentAlignment = Alignment.Center,
    ) {
        val w = (maxWidth - 64.dp).coerceAtMost(1060.dp)
        val h = (maxHeight - 48.dp).coerceAtMost(640.dp)
        val shift = with(LocalDensity.current) { 60.dp.toPx() }
        Box(
            Modifier
                .size(w, h)
                .graphicsLayer {
                    alpha = p.coerceIn(0f, 1f)
                    translationY = (1f - p) * shift
                }
                .glass(rounded(34.dp), GlassKind.Menu)
                .clip(rounded(34.dp))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            when (current) {
                SheetKind.SOUND_STUDIO -> SoundStudio(onClose = close)
                SheetKind.ADD_WIDGETS -> AddWidgets(onClose = close)
            }
        }
    }
}

/** The title row of a sheet: a title on the left, Done on the right. */
@Composable
fun SheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 28.dp, end = 18.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(title, T.title2, modifier = Modifier.weight(1f))
        trailing()
        Spacer(Modifier.width(10.dp))
        GlassPillButton("Done", onClose, filled = true)
    }
}

// ---- Context menus -----------------------------------------------------------

sealed interface MenuEntry
data class MenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val action: () -> Unit,
) : MenuEntry
/** A row of square tiles, like the design's Full / Left Half / Right Half. */
data class MenuTiles(val items: List<MenuItem>) : MenuEntry
data class MenuHeader(val text: String) : MenuEntry
data object MenuGap : MenuEntry

data class MenuRequest(val anchor: Rect, val entries: List<MenuEntry>, val app: AppInfo? = null)

/**
 * Apple's context menu: the pressed item lifts, everything else dims, and a
 * glass menu opens next to it.
 */
@Composable
fun ContextMenu(request: MenuRequest?, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf<MenuRequest?>(null) }
    if (request != null) shown = request
    val p = remember { Animatable(0f) }
    LaunchedEffect(request != null) {
        p.animateTo(if (request != null) 1f else 0f, if (request != null) spring(0.72f, 420f) else tween(140))
    }
    // Each frame is read only where it's drawn; this redraws only once it's gone.
    val gone by remember { derivedStateOf { p.value < 0.01f } }
    val r = shown ?: return
    if (gone && request == null) return
    BackHandler(enabled = request != null) { onDismiss() }
    val density = LocalDensity.current
    val gap = with(density) { 14.dp.roundToPx() }
    val edge = with(density) { 16.dp.roundToPx() }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black, alpha = 0.28f * p.value.coerceIn(0f, 1f)) }
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        // The lifted item.
        if (r.app != null) {
            val size = with(density) { r.anchor.width.toDp() }
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = r.anchor.left
                        translationY = r.anchor.top
                        val s = 1f + 0.1f * p.value
                        scaleX = s; scaleY = s
                    },
            ) { AppIcon(r.app, size) }
        }
        // Where the card grows from: the side facing the item.
        var origin = TransformOrigin(0.5f, 1f)
        Layout(
            content = {
                MenuCard(r.entries, onDismiss, Modifier.graphicsLayer {
                    alpha = p.value.coerceIn(0f, 1f)
                    val s = 0.82f + 0.18f * p.value
                    scaleX = s; scaleY = s
                    transformOrigin = origin
                })
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val card = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight - edge * 2))
            val maxX = constraints.maxWidth - card.width - edge
            val maxY = constraints.maxHeight - card.height - edge
            val above = r.anchor.top - gap - card.height >= edge
            val below = r.anchor.bottom + gap + card.height <= constraints.maxHeight - edge
            // Neither above nor below has room (a tall menu on a short screen): beside
            // the item, so the menu doesn't cover what it belongs to.
            val right = r.anchor.right.roundToInt() + gap
            val left = r.anchor.left.roundToInt() - gap - card.width
            val besideY = (r.anchor.center.y.roundToInt() - card.height / 2).coerceIn(edge, maxY.coerceAtLeast(edge))
            val centerX = (r.anchor.center.x.roundToInt() - card.width / 2).coerceIn(edge, maxX)
            val (x, y) = when {
                above -> { origin = TransformOrigin(0.5f, 1f); centerX to r.anchor.top.roundToInt() - gap - card.height }
                below -> { origin = TransformOrigin(0.5f, 0f); centerX to r.anchor.bottom.roundToInt() + gap }
                right <= maxX -> { origin = TransformOrigin(0f, 0.5f); right to besideY }
                left >= edge -> { origin = TransformOrigin(1f, 0.5f); left to besideY }
                else -> { origin = TransformOrigin(0.5f, 0f); centerX to (r.anchor.bottom.roundToInt() + gap).coerceAtMost(maxY) }
            }
            layout(constraints.maxWidth, constraints.maxHeight) { card.place(x, y) }
        }
    }
}

@Composable
private fun MenuCard(entries: List<MenuEntry>, onDismiss: () -> Unit, modifier: Modifier) {
    val t = LocalTokens.current
    Column(
        modifier
            .width(280.dp)
            .glass(rounded(22.dp), GlassKind.Menu)
            .clip(rounded(22.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            // Taller than the screen (many saved layouts, a big font): it scrolls.
            .verticalScroll(rememberScrollState()),
    ) {
        var previousWasItem = false
        entries.forEach { e ->
            when (e) {
                is MenuHeader -> {
                    Txt(e.text, T.footnote, t.text2, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp), maxLines = 1)
                    previousWasItem = false
                }
                MenuGap -> {
                    Box(Modifier.fillMaxWidth().height(8.dp).background(Color.Black.copy(alpha = if (t.dark) 0.22f else 0.06f)))
                    previousWasItem = false
                }
                is MenuTiles -> {
                    if (previousWasItem) Separator(start = 0.dp)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        e.items.forEachIndexed { i, item ->
                            if (i > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(t.sep))
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable(enabled = item.enabled) { onDismiss(); item.action() }
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val c = if (item.checked) t.accText else t.text
                                item.icon?.let { Glyph(it, c, size = 24.dp) }
                                Txt(item.label, T.caption, c, maxLines = 1)
                            }
                        }
                    }
                    previousWasItem = true
                }
                is MenuItem -> {
                    if (previousWasItem) Separator(start = 0.dp)
                    val c = when {
                        !e.enabled -> t.text2
                        e.destructive -> t.destructive
                        else -> t.text
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = e.enabled) { onDismiss(); e.action() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(22.dp)) { if (e.checked) Glyph(Icons.check, c, size = 16.dp) }
                        Txt(e.label, T.callout, c, Modifier.weight(1f), maxLines = 1)
                        e.icon?.let { Glyph(it, c, size = 20.dp) }
                    }
                    previousWasItem = true
                }
            }
        }
    }
}

/** A row-sized, tappable glass capsule used by small floating controls. */
@Composable
fun GlassChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(
        modifier
            .height(36.dp)
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .glass(Capsule, GlassKind.Button)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Glyph(it, t.text, size = 16.dp) }
        Txt(text, T.footnote.copy(fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

@Composable
fun Modifier.noRipple(onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
