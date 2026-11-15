package app.leopard.cockpit.ui.carplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.leopard.cockpit.Otility
import app.leopard.cockpit.R
import app.leopard.cockpit.data.Tab
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalBackdrop
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.SidebarMargin
import app.leopard.cockpit.ui.screens.at
import app.leopard.cockpit.ui.screens.smoothstep
import app.leopard.cockpit.ui.settings.SettingsPanel
import app.leopard.cockpit.ui.theme.OtilityTheme
import app.leopard.cockpit.ui.theme.tokens
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

/** Settings' window, a shade darker than the cards on it, as CarPlay's apps are. */
private val WindowFace = Color(0xFF16181C)

/** Settings' tile: Apple's grey, top to bottom. */
internal val SettingsTileBrush = Brush.verticalGradient(listOf(Color(0xFF9A9AA0), Color(0xFF4A4A4F)))

/**
 * CarPlay mode's Settings: one of Home's apps, so it opens as one, in a window beside
 * the sidebar and as tall as it, growing out of its tile (the tile first, the pages coming
 * in as it grows) and shrinking back into it on Home. Always dark, as CarPlay is, on
 * Home's black with the emblem.
 *
 * [grown]: 0 at the tile, 1 open; [leaving]: on the way out (into the tile for Home,
 * else fading where it is).
 */
@Composable
fun CarPlaySettings(grown: () -> Float, leaving: Boolean) {
    val app = Otility.instance
    val nav = LocalNav.current
    val density = LocalDensity.current
    val settings by app.prefs.settings.collectAsState()
    val dark = remember(settings.accent, settings.reduceTransparency, settings.tintedGlass) {
        tokens(true, settings.accent, settings.reduceTransparency, settings.tintedGlass)
    }
    // Grown out of the tile just tapped; from elsewhere (Back, a search), faded in.
    val enter = remember { nav.settingsOrigin?.takeIf { it.fresh }.also { nav.settingsOrigin = null } }
    val inWindow = remember { arrayOf(Offset.Zero) }
    OtilityTheme(dark) {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            // Home's wallpaper, coming in with the window (Home fades behind it).
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = grown().coerceIn(0f, 1f) }
                    .layerBackdrop(backdrop),
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                Image(
                    painterResource(R.drawable.emblem), null,
                    Modifier
                        .align(Alignment.Center)
                        .size(min(maxHeight * 0.46f, maxWidth * 0.3f))
                        .graphicsLayer { alpha = 0.4f },
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { inWindow[0] = it.positionInRoot() }) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                fun px(d: Dp) = with(density) { d.toPx() }
                // Where an app's window is: right of the sidebar, as tall as it, between the car's bars.
                val safe = WindowInsets.safeDrawing
                val margin = px(SidebarMargin)
                val area = Rect(
                    safe.getLeft(density, LayoutDirection.Ltr) + px(ContentStart),
                    safe.getTop(density) + margin,
                    w - safe.getRight(density, LayoutDirection.Ltr) - margin,
                    h - safe.getBottom(density) - margin,
                )
                val corner = px(WindowCorner)
                fun local(r: Rect) = r.translate(-inWindow[0].x, -inWindow[0].y)
                // Into the tile for Home, when Home comes back on the tile's page (the first
                // of apps, after the dashboard); else fading out where it is, a little smaller.
                val origin = when {
                    leaving && nav.tab == Tab.OVERVIEW && nav.homePage == 1 && nav.settingsTile.width > 0f ->
                        Origin.icon(local(nav.settingsTile))
                    leaving -> Origin.fade(area, corner, 0.92f)
                    else -> enter?.let { it.copy(rect = local(it.rect)) } ?: Origin.fade(area, corner)
                }
                val aw = area.width.roundToInt().coerceAtLeast(1)
                val ah = area.height.roundToInt().coerceAtLeast(1)
                Box(
                    Modifier
                        .at { lerp(origin.rect, area, grown()) }
                        .graphicsLayer {
                            val p = grown().coerceIn(0f, 1f)
                            alpha = if (origin.kind == Origin.Kind.FADE) p else 1f
                            shape = RoundedCornerShape(origin.corner + (corner - origin.corner) * p)
                            clip = true
                        }
                        .background(WindowFace),
                ) {
                    // The tile, filling the window at first, giving way to the pages.
                    if (origin.kind == Origin.Kind.ICON) TileFace(grown)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = if (origin.kind == Origin.Kind.ICON) smoothstep(0.3f, 0.8f, grown()) else 1f
                            },
                    ) {
                        // Laid out at the window's size and scaled with it as it grows or
                        // shrinks: Settings isn't laid out again at each frame.
                        Box(Modifier.cover(aw, ah)) {
                            CompositionLocalProvider(LocalBackdrop provides backdrop) {
                                SettingsPanel(Modifier.fillMaxSize(), corner = WindowCorner)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The Settings tile's face: its grey and gear, the gear settling to the middle as it grows. */
@Composable
private fun TileFace(grown: () -> Float) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - smoothstep(0.35f, 0.75f, grown()) }
            .drawBehind { drawRect(SettingsTileBrush) },
    ) {
        Box(
            Modifier.layout { m, c ->
                val side = minOf(c.maxWidth, c.maxHeight).toFloat()
                // The tile's gear is 64% of it; it shrinks back towards an icon's size.
                val share = 0.64f + (0.24f - 0.64f) * smoothstep(0f, 0.6f, grown().coerceIn(0f, 1f))
                val s = (side * share).roundToInt().coerceAtLeast(1)
                val pl = m.measure(Constraints.fixed(s, s))
                layout(c.maxWidth, c.maxHeight) { pl.place((c.maxWidth - s) / 2, (c.maxHeight - s) / 2) }
            },
        ) {
            Glyph(Icons.gear, Color.White, modifier = Modifier.fillMaxSize())
        }
    }
}
