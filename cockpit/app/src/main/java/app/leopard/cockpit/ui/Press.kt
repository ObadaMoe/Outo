package app.leopard.cockpit.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Apple's press, for everything tappable: while the finger is on it, it sinks a little
 * and dims, so a tap shows it landed even when what it opens takes a moment (an app on
 * a hidden display, say). Small things sink more than big ones, about 3 dp in from each
 * side, so a whole card only settles. A quick tap still shows it, briefly.
 *
 * It is Otility's [androidx.compose.foundation.LocalIndication], so every `clickable`
 * gets it; pass `indication = null` where something has a press of its own.
 */
object PressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x9e55
}

private class PressNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val amount = Animatable(0f)
    private val paint = Paint()

    override fun onAttach() {
        coroutineScope.launch {
            val presses = ArrayList<PressInteraction.Press>()
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> presses += i
                    is PressInteraction.Release -> presses -= i.press
                    is PressInteraction.Cancel -> presses -= i.press
                    else -> return@collect
                }
                if (presses.isNotEmpty()) {
                    launch { amount.animateTo(1f, tween(90)) { invalidateDraw() } }
                } else {
                    launch {
                        // A tap quicker than the press: shown all the same, for a moment.
                        if (amount.value < 0.7f) amount.animateTo(1f, tween(60)) { invalidateDraw() }
                        amount.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f)) { invalidateDraw() }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = amount.value.coerceIn(0f, 1f)
        if (p == 0f || size.minDimension <= 0f) {
            drawContent()
            return
        }
        val m = size.minDimension
        val deepest = ((m - 6.dp.toPx()) / m).coerceIn(0.9f, 0.985f)
        val s = 1f - (1f - deepest) * p
        scale(s) {
            paint.alpha = 1f - 0.3f * p
            drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            this@draw.drawContent()
            drawContext.canvas.restore()
        }
    }
}
