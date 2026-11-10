package app.leopard.cockpit.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.data.StartupApps
import app.leopard.cockpit.data.StartupSpot
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.GlassKind
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.SideInset
import app.leopard.cockpit.ui.Switch
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.glass
import app.leopard.cockpit.ui.noRipple
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import kotlin.math.roundToInt

/**
 * Startup Apps: the three screens drawn small, as on the dashboard. Tap one (or
 * one half of the main screen, once it's split) to choose the app it opens
 * with when Otility starts.
 */
@Composable
fun StartupWindow(open: Boolean, anchor: Rect, onDismiss: () -> Unit) {
    val p = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    if (open) shown = true
    LaunchedEffect(open) {
        p.animateTo(if (open) 1f else 0f, if (open) spring(0.78f, 420f) else tween(150))
        if (!open) shown = false
    }
    if (!shown) return
    BackHandler(enabled = open) { onDismiss() }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f * p.value.coerceIn(0f, 1f)))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        val gap = with(density) { 12.dp.roundToPx() }
        // Below the power button, but clear of the tab bar down the left side, which
        // is drawn over everything in the tab (as the rest of Otility keeps clear of it).
        val left = maxOf(anchor.left.roundToInt(), with(density) { SideInset.roundToPx() })
        StartupCard(
            Modifier
                .offset { IntOffset(left, anchor.bottom.roundToInt() + gap) }
                .graphicsLayer {
                    alpha = p.value.coerceIn(0f, 1f)
                    val s = 0.86f + 0.14f * p.value
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        )
    }
}

@Composable
private fun StartupCard(modifier: Modifier) {
    val t = LocalTokens.current
    val app = Otility.instance
    val startup by app.prefs.startup.collectAsState()
    val apps by app.catalog.apps.collectAsState()
    val byId = remember(apps) { apps.associateBy { it.id } }
    var picking by remember { mutableStateOf<StartupSpot?>(null) }
    fun change(f: (StartupApps) -> StartupApps) = app.prefs.setStartup(f(app.prefs.startup.value))
    // A half that went away with the split can't be picking any more.
    if (picking != null && picking !in startup.spots) picking = null

    Column(
        modifier
            .width(540.dp)
            .glass(rounded(26.dp), GlassKind.Menu)
            .clip(rounded(26.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(vertical = 16.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Startup Apps", T.headline)
                Txt("Each screen opens with its app when Outo starts.", T.footnote, t.text2, maxLines = 1)
            }
            if (!startup.isEmpty) {
                Txt(
                    "Clear", T.subhead.copy(fontWeight = FontWeight.Medium), t.accText,
                    Modifier.clip(rounded(10.dp)).noRipple { change { StartupApps(split = it.split) }; picking = null }.padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- The three screens, small -------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            fun tap(spot: StartupSpot) { picking = if (picking == spot) null else spot }
            // Not the cluster: its switch in Screens says whether it shows the map.
            MiniScreen("Main", Modifier.weight(1.9f), 92.dp) {
                Row(Modifier.fillMaxSize()) {
                    Part(byId[startup[StartupSpot.MAIN]], picking == StartupSpot.MAIN, Modifier.weight(1f).fillMaxHeight()) { tap(StartupSpot.MAIN) }
                    if (startup.split) {
                        Box(Modifier.width(2.dp).fillMaxHeight().background(t.text.copy(alpha = 0.35f)))
                        Part(byId[startup[StartupSpot.RIGHT]], picking == StartupSpot.RIGHT, Modifier.weight(1f).fillMaxHeight()) { tap(StartupSpot.RIGHT) }
                    }
                }
            }
            MiniScreen("Passenger", Modifier.weight(1f), 66.dp) {
                Part(byId[startup[StartupSpot.PASSENGER]], picking == StartupSpot.PASSENGER, Modifier.fillMaxSize()) { tap(StartupSpot.PASSENGER) }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ---- Split the main screen ------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Icons.leftHalf, t.text, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Txt("Split Main Screen", T.callout, modifier = Modifier.weight(1f))
            Switch(startup.split, { on -> change { it.copy(split = on) } })
        }

        // ---- Choosing an app ----------------------------------------------------------
        AnimatedVisibility(
            visible = picking != null,
            enter = fadeIn(tween(160)) + expandVertically(spring(0.86f, 500f)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
        ) {
            val spot = picking ?: StartupSpot.MAIN
            val title = when {
                spot == StartupSpot.MAIN && startup.split -> "Main · Left"
                else -> spot.label
            }
            Column {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(t.sep))
                Txt(title, T.footnote, t.text2, Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(82.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxWidth().height(206.dp),
                ) {
                    item(key = "none") {
                        Choice(null, startup[spot] == null) { change { it.set(spot, null) }; picking = null }
                    }
                    items(apps, key = { it.id.pkg }) { a ->
                        Choice(a, startup[spot] == a.id) { change { it.set(spot, a.id) }; picking = null }
                    }
                }
            }
        }
    }
}

/** One screen drawn small: a dark glass frame with its name under it. */
@Composable
private fun MiniScreen(label: String, modifier: Modifier, height: Dp, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(Color.Black.copy(alpha = if (t.dark) 0.45f else 0.75f), rounded(12.dp))
                .border(1.dp, t.text.copy(alpha = 0.18f), rounded(12.dp))
                .clip(rounded(12.dp))
                .padding(3.dp),
        ) { content() }
        Spacer(Modifier.height(6.dp))
        Txt(label, T.caption, t.text2, maxLines = 1)
    }
}

/** A part of a small screen: the app it opens with, or a plus. Blue while its app is being chosen. */
@Composable
private fun Part(app: AppInfo?, picking: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val t = LocalTokens.current
    val fill by animateColorAsState(if (picking) t.fill else Color.Transparent, tween(160), label = "part")
    Box(
        modifier.background(fill, rounded(9.dp)).clip(rounded(9.dp)).noRipple(onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (app != null) AppIcon(app, 34.dp)
        else Glyph(Icons.plus, if (picking) t.onFill else Color.White.copy(alpha = 0.6f), size = 18.dp)
    }
}

/** An app to choose (or None), with a check on the one chosen. */
@Composable
private fun Choice(app: AppInfo?, chosen: Boolean, onTap: () -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.clip(rounded(14.dp)).noRipple(onTap).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            if (app != null) AppIcon(app, 48.dp)
            else Box(Modifier.size(48.dp).background(t.track, rounded(11.dp)), contentAlignment = Alignment.Center) {
                Glyph(Icons.close, t.text2, size = 20.dp)
            }
            if (chosen) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(6.dp, (-6).dp)
                        .size(20.dp)
                        .background(t.fill, CircleShape)
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Glyph(Icons.check, t.onFill, size = 11.dp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Txt(app?.label ?: "None", T.caption.copy(textAlign = TextAlign.Center), if (chosen) t.accText else t.text, maxLines = 1)
    }
}
