package app.leopard.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.ui.theme.LocalTokens
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

/** What glass elements bend and blur: the layer drawn behind them. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

/** Apple's continuous ("squircle") corners. */
val Capsule: Shape = ContinuousCapsule
fun rounded(radius: Dp): Shape = ContinuousRoundedRectangle(radius)

enum class GlassKind(val blur: Dp, val lensHeight: Dp, val lensAmount: Dp) {
    /** The sidebar and bars: CarPlay's heavy frost, with the glass's edge refraction. */
    Bar(blur = 22.dp, lensHeight = 16.dp, lensAmount = 32.dp),
    /** Cards on a photo. */
    Card(blur = 24.dp, lensHeight = 10.dp, lensAmount = 18.dp),
    /**
     * The Overview's widgets, as Apple's: frosted and flat, with no bent edge and no
     * rim around it, only a soft shadow under it.
     */
    Widget(blur = 28.dp, lensHeight = 0.dp, lensAmount = 0.dp),
    /** Menus and alerts: frosted so text stays readable. */
    Menu(blur = 30.dp, lensHeight = 14.dp, lensAmount = 26.dp),
    /** Small round controls over app content. */
    Button(blur = 14.dp, lensHeight = 10.dp, lensAmount = 20.dp),
}

/**
 * Liquid Glass, frosted like CarPlay's: the backdrop is bent at the edges (lens),
 * blurred, made more vivid and dimmed, with a faint hairline around the edge.
 * With "Reduce transparency" on it becomes a plain solid surface.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    kind: GlassKind = GlassKind.Bar,
    tint: Color? = null,
    backdrop: Backdrop = LocalBackdrop.current,
    shadow: Boolean = true,
): Modifier {
    val t = LocalTokens.current
    val surface = tint ?: when (kind) {
        GlassKind.Card, GlassKind.Widget -> t.card
        GlassKind.Menu -> t.menu
        else -> t.glassTint
    }
    if (t.reduceTransparency) {
        return this
            .then(if (shadow) Modifier.shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .background(if (tint != null) tint.copy(alpha = 1f) else t.solidGlass, shape)
            .then(if (kind == GlassKind.Widget) Modifier else Modifier.border(0.5.dp, t.sep, shape))
    }
    val flat = kind == GlassKind.Widget
    val d = LocalDensity.current
    val blurPx = with(d) { kind.blur.toPx() }
    val lensH = with(d) { kind.lensHeight.toPx() }
    val lensA = with(d) { kind.lensAmount.toPx() }
    // CarPlay's glass has no glare along its edges, only a faint, even hairline.
    val rim = if (t.dark) 0.12f else 0.5f
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurPx)
            if (!flat) lens(lensH, lensA)
        },
        highlight = if (flat) null else {
            {
                Highlight(
                    width = 1.dp,
                    style = HighlightStyle.Plain(color = Color.White.copy(alpha = rim)),
                )
            }
        },
        shadow = when {
            !shadow -> null
            // Apple's widgets barely lift off what's behind them.
            flat -> { { Shadow(radius = 18.dp, color = Color.Black.copy(alpha = if (t.dark) 0.22f else 0.07f)) } }
            else -> { { Shadow(radius = 28.dp, color = Color.Black.copy(alpha = if (t.dark) 0.38f else 0.14f)) } }
        },
        onDrawSurface = { drawRect(surface) },
    )
}

@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = rounded(28.dp),
    kind: GlassKind = GlassKind.Bar,
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, kind, tint).clip(shape), content = content)
}
