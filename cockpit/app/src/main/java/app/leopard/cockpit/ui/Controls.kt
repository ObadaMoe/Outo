package app.leopard.cockpit.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import kotlin.math.roundToInt

@Composable
fun Txt(text: String, style: TextStyle, color: Color = LocalTokens.current.text, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    // Text that runs out of lines ends in "…" rather than stopping mid-word.
    BasicText(text, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

/** An app icon in Apple's rounded-square shape, with a faint rim like the Otility icon. */
@Composable
fun AppIcon(app: AppInfo?, size: Dp, modifier: Modifier = Modifier) {
    val shape = rounded(size * 0.225f)
    Box(
        modifier
            .size(size)
            .shadow(size * 0.12f, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .background(Color(0xFF2C2D33)),
    ) {
        if (app != null) {
            Image(app.icon, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier
                .fillMaxSize()
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.45f), 0.15f to Color.White.copy(alpha = 0.08f),
                        0.85f to Color.White.copy(alpha = 0.06f), 1f to Color.White.copy(alpha = 0.3f),
                    ),
                    shape,
                ),
        )
    }
}

/** Squishes a little when pressed, like Apple's buttons. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true, scaleDown: Float = 0.94f): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scaleDown else 1f, spring(0.6f, 600f), label = "press")
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

/** A round glass button with an icon. */
@Composable
fun GlassIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp, tint: Color = LocalTokens.current.text, kind: GlassKind = GlassKind.Button) {
    Box(
        modifier.size(size).pressable(onClick).glass(Capsule, kind),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, tint, size = size * 0.42f) }
}

/** A glass capsule button with text (and an optional icon). */
@Composable
fun GlassPillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, filled: Boolean = false) {
    val t = LocalTokens.current
    val fg = if (filled) t.onFill else t.text
    Row(
        modifier
            .height(48.dp)
            .pressable(onClick)
            .then(if (filled) Modifier.shadow(10.dp, Capsule).background(t.fill, Capsule) else Modifier.glass(Capsule, GlassKind.Button))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Glyph(icon, fg, size = 18.dp)
        Txt(label, T.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), fg)
    }
}

/** Apple's switch. */
@Composable
fun Switch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val haptic = LocalHapticFeedback.current
    val x by animateDpAsState(if (checked) 22.dp else 0.dp, spring(0.7f, Spring.StiffnessMediumLow), label = "knob")
    val track by animateColorAsState(if (checked) t.fill else t.track, label = "track")
    Box(
        modifier
            .size(52.dp, 32.dp)
            .clip(Capsule)
            .background(track)
            .toggleable(checked, remember { MutableInteractionSource() }, null, role = Role.Switch) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onChange(it)
            }
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .offset(x = x)
                .size(28.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** A row in a grouped settings list. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val t = LocalTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(30.dp).background(iconColor ?: t.fill, rounded(8.dp)), contentAlignment = Alignment.Center) {
                Glyph(icon, Color.White, size = 18.dp)
            }
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Txt(title, T.callout)
            if (subtitle != null) Txt(subtitle, T.footnote, t.text2)
        }
        trailing()
    }
}

/** A row that does something when tapped: tinted like a link, red when it removes. */
@Composable
fun ActionRow(title: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val t = LocalTokens.current
    val color = when {
        !enabled -> t.text2
        destructive -> t.destructive
        else -> t.accText
    }
    Box(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Txt(title, T.callout, color)
    }
}

/** A capsule segmented control with a sliding selection. */
@Composable
fun <V> Segmented(options: List<Pair<V, String>>, selected: V, onSelect: (V) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    Box(modifier.height(38.dp).background(t.track, Capsule).padding(3.dp).onSizeChanged { widthPx = it.width }) {
        val segPx = widthPx.toFloat() / options.size
        // Animated in segments, not pixels: on the first frame the width is still 0,
        // and the pill would slide in from the first option every time it appears.
        val at by animateFloatAsState(index.toFloat(), spring(0.8f, Spring.StiffnessMedium, visibilityThreshold = 0.001f), label = "seg")
        Box(
            Modifier.offset { IntOffset((at * segPx).roundToInt(), 0) }.width(with(density) { segPx.toDp() }).fillMaxHeight()
                .shadow(2.dp, Capsule).background(if (t.dark) Color.White.copy(alpha = 0.24f) else Color.White, Capsule),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { (v, label) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) { onSelect(v) },
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(label, T.subhead.copy(fontWeight = if (v == selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1)
                }
            }
        }
    }
}

/** A slider with a white knob, 0..1. */
@Composable
fun Slider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val t = LocalTokens.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    Box(
        modifier
            .height(32.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)); onDone() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = onDone) { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val knob = 28.dp
        Box(Modifier.fillMaxWidth().height(6.dp).background(t.track, Capsule))
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(6.dp).background(t.fill, Capsule))
        Box(
            Modifier
                .offset { IntOffset(((widthPx - knob.toPx()) * value).roundToInt(), 0) }
                .size(knob)
                .shadow(4.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/**
 * Groups drawn as cards raised off the surface they're on, with a soft shadow, as in
 * Apple's System Settings (Settings' pages); otherwise sunk into it, like an iOS inset list.
 */
val LocalRaisedGroups = staticCompositionLocalOf { false }

/** A rounded group of rows, like an iOS inset list (raised: see [LocalRaisedGroups]). */
@Composable
fun Group(modifier: Modifier = Modifier, header: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    val shape = rounded(18.dp)
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) Txt(header.uppercase(), T.sectionHeader, t.text2, Modifier.padding(start = 6.dp))
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth().then(if (LocalRaisedGroups.current) Modifier.raisedCard(shape) else Modifier.clip(shape).background(t.inset)),
        ) { content() }
        if (footer != null) Txt(footer, T.footnote, t.text2, Modifier.padding(horizontal = 6.dp))
    }
}

/**
 * A card standing off the surface it's on: an opaque face (white in light mode, a
 * lighter grey than the panel in dark), a soft shadow under it and a faint rim.
 */
@Composable
fun Modifier.raisedCard(shape: Shape): Modifier {
    val t = LocalTokens.current
    val face = if (t.dark) Color(0xFF26292F) else Color.White
    val shade = Color.Black.copy(alpha = if (t.dark) 0.55f else 0.16f)
    return this
        .shadow(if (t.dark) 10.dp else 6.dp, shape, ambientColor = shade, spotColor = shade)
        .background(face, shape)
        .border(0.5.dp, if (t.dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.06f), shape)
        .clip(shape)
}

@Composable
fun Separator(modifier: Modifier = Modifier, start: Dp = 16.dp) {
    Box(modifier.padding(start = start).fillMaxWidth().height(0.5.dp).background(LocalTokens.current.sep))
}

/**
 * A soft fog along the bottom edge, black in dark mode and white in light, so
 * the tab bar and dock stand out from busy pictures. Drawn under the glass, so
 * the glass picks it up too.
 */
@Composable
fun BottomFog(alpha: () -> Float = { 1f }, height: Dp = 260.dp) {
    val t = LocalTokens.current
    val c = if (t.dark) Color.Black else Color.White
    val strength = if (t.dark) 0.72f else 0.62f
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(height)
                .graphicsLayer { this.alpha = alpha() }
                .background(
                    Brush.verticalGradient(
                        0f to c.copy(alpha = 0f),
                        0.3f to c.copy(alpha = strength * 0.12f),
                        0.55f to c.copy(alpha = strength * 0.38f),
                        0.8f to c.copy(alpha = strength * 0.75f),
                        1f to c.copy(alpha = strength),
                    ),
                ),
        )
    }
}

/**
 * Fog over the top and bottom of the car photo, so the status bar, the top
 * controls, the tab bar and the dock read on it: black in dark mode, white in
 * light mode. [top] and [bottom] are shares of the screen's height.
 */
@Composable
fun EdgeFog(
    topAlpha: () -> Float = { 1f },
    bottomAlpha: () -> Float = { 1f },
    sideAlpha: () -> Float = { 1f },
    top: Float = 0.24f,
    bottom: Float = 0.40f,
    /** Where the picture starts, in pixels: left of it, plain (the sidebar stands there). */
    start: Float = 0f,
) {
    val t = LocalTokens.current
    val c = if (t.dark) Color.Black else Color.White
    val strength = if (t.dark) 0.86f else 0.8f
    val topBrush = remember(c, strength) { easedFade(c, strength, strongAtTop = true) }
    val bottomBrush = remember(c, strength) { easedFade(c, strength, strongAtTop = false) }
    Box(Modifier.fillMaxSize()) {
        if (start > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(LocalDensity.current) { start.toDp() })
                    .graphicsLayer { alpha = sideAlpha() }
                    .background(c),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(top)
                .graphicsLayer { alpha = topAlpha() }
                .background(topBrush),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(bottom)
                .graphicsLayer { alpha = bottomAlpha() }
                .background(bottomBrush),
        )
        SideFog(sideAlpha, start = start)
    }
}

/**
 * Fog down the left and right edges, black in dark mode and white in light, so
 * the tab bar down the left reads on any picture and the screen has no hard
 * sides. [width] is a share of the screen's width, each side.
 */
@Composable
fun SideFog(alpha: () -> Float = { 1f }, width: Float = 0.12f, start: Float = 0f) {
    val t = LocalTokens.current
    val c = if (t.dark) Color.Black else Color.White
    val strength = if (t.dark) 0.78f else 0.6f
    val left = remember(c, strength) { easedFade(c, strength, strongAtTop = true, horizontal = true) }
    val right = remember(c, strength) { easedFade(c, strength, strongAtTop = false, horizontal = true) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.CenterStart).offset { IntOffset(start.roundToInt(), 0) }
                .fillMaxHeight().fillMaxWidth(width).graphicsLayer { this.alpha = alpha() }.background(left),
        )
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(width).graphicsLayer { this.alpha = alpha() }.background(right))
    }
}

/**
 * Scrolling content fades out softly at the top and bottom of its area, where there
 * is more to scroll to, instead of being cut off hard against the car's bars. Goes
 * before the scroll modifier, so it's the visible area that fades, not the content.
 */
fun Modifier.scrollEdges(state: ScrollableState, fade: Dp = 36.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = fade.toPx().coerceAtMost(size.height / 3f)
        if (state.canScrollBackward) {
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = f),
                size = Size(size.width, f), blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - f, endY = size.height),
                topLeft = Offset(0f, size.height - f), size = Size(size.width, f), blendMode = BlendMode.DstIn,
            )
        }
    }

/** An ease-in-out ramp, so the fog has no visible edge where it starts. */
private val EASE = floatArrayOf(0f, .013f, .049f, .104f, .175f, .259f, .352f, .45f, .55f, .648f, .741f, .825f, .896f, .951f, .987f, 1f)

private fun easedFade(c: Color, strength: Float, strongAtTop: Boolean, horizontal: Boolean = false): Brush {
    val n = EASE.size - 1
    val stops = Array(EASE.size) { i ->
        val pos = i / n.toFloat()
        (if (strongAtTop) 1f - pos else pos) to c.copy(alpha = EASE[i] * strength)
    }
    stops.sortBy { it.first }
    return if (horizontal) Brush.horizontalGradient(*stops) else Brush.verticalGradient(*stops)
}

/** Softly dims what's under it. */
fun Modifier.scrim(alpha: Float): Modifier = drawWithContent {
    drawContent()
    drawRect(Color.Black.copy(alpha = alpha))
}
