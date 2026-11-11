package app.leopard.cockpit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.screens.ScreensController
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import kotlinx.coroutines.delay

/** How long the note stays, to put a closed app back. */
private const val UNDO_MS = 4_500L

/**
 * After an app is closed with its red button: "YouTube closed · Undo" at the bottom for
 * a few seconds. The close happened at once, as the driver wants it, with no question;
 * this catches a wrong tap instead. Undo opens the app again where it was.
 */
@Composable
fun UndoCloseNote(modifier: Modifier = Modifier) {
    val screens = Otility.instance.screens
    val closed by screens.closed.collectAsState()
    val apps by Otility.instance.catalog.apps.collectAsState()
    val t = LocalTokens.current
    // Kept while the note slides away, so it doesn't go blank on its way out.
    var shown by remember { mutableStateOf<ScreensController.Closed?>(null) }
    LaunchedEffect(closed) {
        val c = closed ?: return@LaunchedEffect
        shown = c
        delay(UNDO_MS)
        screens.forgetClosed(c)
    }
    AnimatedVisibility(
        visible = closed != null,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(spring(0.8f, 420f)) { it },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it },
    ) {
        val c = shown ?: return@AnimatedVisibility
        val info = apps.firstOrNull { it.id == c.app }
        Row(
            Modifier.glass(Capsule, GlassKind.Button).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(info, 32.dp)
            Spacer(Modifier.width(12.dp))
            Txt("${info?.label ?: "The app"} closed", T.subhead, t.text, maxLines = 1)
            Spacer(Modifier.width(18.dp))
            Box(
                Modifier.clip(Capsule).noRipple { screens.undoClose() }.padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Txt("Undo", T.subhead.copy(fontWeight = FontWeight.SemiBold), t.accText) }
        }
    }
}
