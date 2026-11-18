package app.leopard.cockpit.ui.carplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.pressable
import app.leopard.cockpit.ui.theme.T

/**
 * The other half, while an app has moved to one side (two fingers sideways): the
 * apps to open next to it, the ones used lately first. Back puts the app full size again.
 */
@Composable
internal fun HalfPicker(beside: AppId, onPick: (AppId) -> Unit, modifier: Modifier = Modifier) {
    val otility = Otility.instance
    val all by otility.catalog.apps.collectAsState()
    val recents by otility.prefs.recents.collectAsState()
    val apps: List<AppInfo> = remember(all, recents, beside) {
        val byId = all.associateBy { it.id }
        val first = recents.mapNotNull { byId[it] }
        (first + all.filter { it !in first }).filter { it.id != beside }
    }
    Column(
        modifier
            .background(Color(0xFF1C1C1E), RoundedCornerShape(WindowCorner))
            .padding(top = 22.dp),
    ) {
        Txt(
            "Open Next To It", T.headline, Color.White.copy(alpha = 0.92f),
            Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(116.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(apps, key = { it.id.pkg }) { a ->
                Column(
                    Modifier.pressable({ onPick(a.id) }).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(a, 72.dp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.padding(horizontal = 4.dp)) {
                        Txt(a.label, T.subhead.copy(textAlign = TextAlign.Center), Color.White, maxLines = 1)
                    }
                }
            }
        }
    }
}
