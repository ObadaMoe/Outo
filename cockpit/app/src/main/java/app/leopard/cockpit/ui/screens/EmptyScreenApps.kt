package app.leopard.cockpit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.GlassKind
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.glass
import app.leopard.cockpit.ui.noRipple
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T

/** How many apps the shelf on an empty screen offers, besides All Apps. */
private const val SHELF_MAX = 6

/**
 * Zoomed into an empty screen (the main or the passenger one): under the emblem, a glass
 * shelf of the apps used lately, then the pinned ones, to open on it with one tap, and
 * All Apps at its end. [modifier] places it over the screen's part for apps.
 */
@Composable
fun EmptyScreenApps(
    screen: ScreenId,
    appsById: Map<AppId, AppInfo>,
    onOpen: (AppId) -> Unit,
    onAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val otility = Otility.instance
    val recents by otility.prefs.recents.collectAsState()
    val dock by otility.prefs.dock.collectAsState()
    val picks = (recents + dock).distinct().mapNotNull { appsById[it] }.take(SHELF_MAX)
    val t = LocalTokens.current
    BoxWithConstraints(modifier) {
        // Small screens (the passenger one, zoomed in on a narrow window) get smaller icons.
        val icon = (maxWidth / 14).coerceIn(56.dp, 76.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(
                "Open on the ${screen.label.lowercase()} screen",
                T.footnote.copy(fontWeight = FontWeight.SemiBold), t.text2,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .glass(rounded(icon * 0.5f), GlassKind.Bar)
                    .padding(horizontal = icon * 0.3f, vertical = icon * 0.22f),
                horizontalArrangement = Arrangement.spacedBy(icon * 0.3f),
            ) {
                picks.forEach { a -> ShelfItem(a.label, icon, { onOpen(a.id) }) { AppIcon(a, icon) } }
                ShelfItem("All Apps", icon, onAll) {
                    Box(
                        Modifier.size(icon).clip(rounded(icon * 0.225f)).background(t.text.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(Icons.apps, t.text, size = icon * 0.46f) }
                }
            }
        }
    }
}

@Composable
private fun ShelfItem(label: String, icon: Dp, onTap: () -> Unit, picture: @Composable () -> Unit) {
    Column(
        Modifier.width(icon + 18.dp).clip(rounded(14.dp)).noRipple(onTap).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        picture()
        Spacer(Modifier.height(8.dp))
        Txt(label, T.caption.copy(textAlign = TextAlign.Center), LocalTokens.current.text, maxLines = 1)
    }
}
