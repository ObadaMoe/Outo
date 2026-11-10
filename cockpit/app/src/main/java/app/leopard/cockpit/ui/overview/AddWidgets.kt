package app.leopard.cockpit.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.WidgetKind
import app.leopard.cockpit.data.WidgetLayout
import app.leopard.cockpit.ui.Alerts
import app.leopard.cockpit.ui.GlassChip
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.SheetHeader
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.noRipple
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T

/**
 * Every widget the Overview can show. Tap one to put it back (it goes at the
 * end) or take it off; Reset brings back the Overview as it first came.
 */
@Composable
fun AddWidgets(onClose: () -> Unit) {
    val t = LocalTokens.current
    val prefs = Otility.instance.prefs
    val layout by prefs.widgets.collectAsState()

    Column(Modifier.fillMaxSize()) {
        SheetHeader("Widgets", onClose) {
            if (layout != WidgetLayout.DEFAULT) {
                GlassChip("Reset", onClick = {
                    Alerts.confirm(
                        "Reset the Overview?",
                        "Every widget goes back to its first place and size.",
                        "Reset",
                    ) { prefs.setWidgets(WidgetLayout.DEFAULT) }
                })
            }
        }
        Txt(
            "Tap a widget to add it to the Overview or take it off. Hold a widget on the Overview to move or resize it.",
            T.footnote, t.text2, Modifier.padding(horizontal = 28.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(WidgetKind.entries, key = { it.name }) { kind ->
                val on = layout.indexOf(kind) >= 0
                WidgetTile(kind, on) {
                    prefs.setWidgets(if (on) prefs.widgets.value.remove(kind) else prefs.widgets.value.add(kind))
                }
            }
        }
    }
}

@Composable
private fun WidgetTile(kind: WidgetKind, on: Boolean, onTap: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier
            .height(132.dp)
            .background(t.track, rounded(24.dp))
            .noRipple(onTap)
            .padding(16.dp),
    ) {
        Column(Modifier.graphicsLayer { alpha = if (on) 1f else 0.82f }) {
            Box(Modifier.size(44.dp).background(if (on) t.fill else t.track, rounded(12.dp)), contentAlignment = Alignment.Center) {
                Glyph(widgetIcon(kind), if (on) t.onFill else t.text, size = 24.dp)
            }
            Spacer(Modifier.height(12.dp))
            Txt(kind.title, T.headline, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The sizes it comes in, drawn small, like the menu's size tiles.
                kind.sizes.forEach { s ->
                    Glyph(Icons.widgetSize(s.w, s.h), t.text2, size = 18.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Txt(if (on) "On the Overview" else "Add", T.footnote, if (on) t.text2 else t.accText, maxLines = 1)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(26.dp).background(if (on) t.fill else t.track, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(if (on) Icons.check else Icons.plus, if (on) t.onFill else t.text, size = 14.dp)
        }
    }
}
