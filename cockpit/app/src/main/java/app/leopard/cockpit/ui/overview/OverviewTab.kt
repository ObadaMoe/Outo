package app.leopard.cockpit.ui.overview

import app.leopard.cockpit.media.NowPlaying
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import app.leopard.cockpit.ui.SideFog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import app.leopard.cockpit.ui.SideInset
import app.leopard.cockpit.data.WidgetSize
import app.leopard.cockpit.data.WidgetGrid
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.R
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.data.SoundMode
import app.leopard.cockpit.data.VehicleData
import app.leopard.cockpit.data.WidgetKind
import app.leopard.cockpit.data.WidgetLayout
import app.leopard.cockpit.data.WidgetSlot
import app.leopard.cockpit.engine.Garage
import app.leopard.cockpit.nav.Directions
import app.leopard.cockpit.nav.Maneuver
import app.leopard.cockpit.screens.ScreenContent
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.BottomFog
import app.leopard.cockpit.ui.ContextMenu
import app.leopard.cockpit.ui.GlassChip
import app.leopard.cockpit.ui.GlassKind
import app.leopard.cockpit.ui.GlassPillButton
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalBackdrop
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.MenuEntry
import app.leopard.cockpit.ui.MenuGap
import app.leopard.cockpit.ui.MenuHeader
import app.leopard.cockpit.ui.MenuItem
import app.leopard.cockpit.ui.MenuRequest
import app.leopard.cockpit.ui.MenuTiles
import app.leopard.cockpit.ui.Segmented
import app.leopard.cockpit.ui.SheetKind
import app.leopard.cockpit.ui.Switch
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.glass
import app.leopard.cockpit.ui.noRipple
import app.leopard.cockpit.ui.LocalCardTint
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.scrollEdges
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val DASH = "—"

/** The cabin photo, softly blurred, behind Overview, Apps and Settings (at night in dark mode). */
@Composable
fun CabinBackground(content: @Composable () -> Unit) {
    val t = LocalTokens.current
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Image(
                painterResource(if (t.dark) R.drawable.cabin_blur_night else R.drawable.cabin_blur), null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            // In light mode the photo's dark dashboard sits right behind the app names:
            // a stronger white veil keeps the dark text readable there.
            Box(Modifier.fillMaxSize().background(if (t.dark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.42f)))
            BottomFog(height = 200.dp)
            SideFog()
        }
        CompositionLocalProvider(LocalBackdrop provides backdrop) { content() }
    }
}

@Composable
fun OverviewTab() {
    val app = Otility.instance
    val nav = LocalNav.current
    val layout by app.prefs.widgets.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<MenuRequest?>(null) }
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BackHandler(enabled = editing && menu == null) { editing = false }
    // Nothing left to edit.
    LaunchedEffect(layout.slots.isEmpty()) { if (layout.slots.isEmpty()) editing = false }

    var full by remember { mutableStateOf(Rect.Zero) }
    var pane by remember { mutableStateOf(Rect.Zero) }
    val map = layout.map
    // Now Playing joins the board, first, while an app has a track (or while editing).
    // It comes as soon as a track does and goes only once none has come back for a
    // while: some apps clear the track for a moment between songs, and the board
    // shouldn't jump each time. Only whether there's a track is read here, so the
    // board doesn't redraw on every change of what's playing.
    val playing by produceState(NowPlaying.track.value != null) {
        NowPlaying.track.map { it != null }.distinctUntilChanged().collectLatest { on ->
            if (!on) delay(2_500)
            value = on
        }
    }
    val wanted = playing || editing
    // It fades in as it joins the board and fades out before it leaves it.
    val mediaAlpha = remember { Animatable(if (wanted) 1f else 0f) }
    val mapOpen = remember { Animatable(0f) }
    var mediaOn by remember { mutableStateOf(wanted) }
    LaunchedEffect(wanted) {
        if (wanted) {
            mediaOn = true
            mediaAlpha.animateTo(1f, tween(260))
        } else {
            mediaAlpha.animateTo(0f, tween(220))
            mediaOn = false
        }
    }
    val board = remember(layout, mediaOn) { layout.grid.shown(active = mediaOn, editing = false) }

    CabinBackground {
        Box(
            Modifier.fillMaxSize().onGloballyPositioned {
                origin = it.positionInRoot(); viewport = it.boundsInRoot(); full = it.boundsInRoot()
            },
        ) {
            // The Overview is between the car's bars (see CarBars): the map stops a
            // little above BYD's buttons, and the widgets fade out as they scroll to them.
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                // The 6 columns the widgets sit on; the map, when it's added, takes
                // [map.size.w] of them, the Overview's full height, and the widgets
                // scroll in the rest (as on CarPlay's dashboard).
                val side = 28.dp
                val gap = 16.dp
                val mapCols = map?.size?.w ?: 0
                val mapWidth = (maxWidth - SideInset - side - gap) * (if (mapCols <= 3) 0.6f else 0.68f)
                val columns = WidgetGrid.COLUMNS - mapCols
                Row(Modifier.fillMaxSize()) {
                    if (map != null) {
                        Box(
                            Modifier
                                .padding(start = SideInset, top = 20.dp, bottom = 20.dp)
                                .width(mapWidth)
                                .fillMaxHeight()
                                .onGloballyPositioned { pane = it.boundsInRoot() },
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            // The opened-up map covers them; they fade so none peeks out under it.
                            .graphicsLayer { alpha = if (map != null) 1f - mapOpen.value.coerceIn(0f, 1f) else 1f }
                            .scrollEdges(scroll)
                            .verticalScroll(scroll)
                            .padding(start = if (map != null) gap else SideInset, end = side, top = 20.dp, bottom = 20.dp),
                    ) {
                        // Also when the board is empty with nothing to hold (say, only Now
                        // Playing is left and nothing plays), so there's always a way back.
                        // It slides in, so the widgets glide down rather than jump.
                        val empty = board.slots.isEmpty()
                        AnimatedVisibility(
                            visible = editing || empty,
                            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                            exit = fadeOut(tween(160)) + shrinkVertically(tween(180)),
                        ) {
                            Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                GlassChip("Add Widget", icon = Icons.plus, onClick = { nav.sheet = SheetKind.ADD_WIDGETS })
                                if (editing) {
                                    Spacer(Modifier.width(10.dp))
                                    GlassPillButton("Done", { editing = false }, filled = true)
                                }
                            }
                        }
                        WidgetBoard(
                            layout = board,
                            editing = editing,
                            onEditing = { editing = it },
                            // Order and sizes only; nothing is added or lost here.
                            onChange = { g -> app.prefs.setWidgets(app.prefs.widgets.value.replacing(g)) },
                            onRemove = { k -> app.prefs.setWidgets(app.prefs.widgets.value.remove(k)) },
                            onMenu = { slot, bounds -> menu = MenuRequest(bounds.translate(-origin), widgetMenu(slot)) },
                            scroll = scroll,
                            viewport = { viewport },
                            columns = columns,
                            // Beside the map each widget takes the column's full width, in its own height.
                            fit = if (map == null) { s -> s } else { s -> WidgetSize(columns, s.h) },
                            resizable = map == null,
                            lead = board.slots.firstOrNull()?.kind?.takeIf { it.automatic },
                        ) { slot ->
                            if (slot.kind == WidgetKind.MEDIA) {
                                NowPlayingCard(slot, Modifier.fillMaxSize().graphicsLayer { alpha = mediaAlpha.value })
                            } else {
                                Widget(slot)
                            }
                        }
                        if (empty) {
                            Spacer(Modifier.height(16.dp))
                            Txt(
                                if (layout.indexOf(WidgetKind.MEDIA) >= 0) "Now Playing shows here while something plays. Tap Add Widget for more."
                                else "No widgets. Tap Add Widget to bring them back.",
                                T.callout, LocalTokens.current.text2,
                            )
                        }
                    }
                }
            }
            if (map != null) {
                MapWidget(
                    slot = map,
                    // In the Overview's own pixels: it sits below the car's status bar.
                    pane = pane.translate(-origin),
                    full = full.translate(-origin),
                    editing = editing,
                    onEditing = { editing = it },
                    onMenu = { slot, bounds -> menu = MenuRequest(bounds, widgetMenu(slot)) },
                    onRemove = { app.prefs.setWidgets(app.prefs.widgets.value.remove(WidgetKind.MAP)) },
                    progress = mapOpen,
                )
            }
            ContextMenu(menu) { menu = null }
        }
    }
}

/** A widget's menu: its sizes, what it shows, and Remove. */
private fun widgetMenu(slot: WidgetSlot): List<MenuEntry> {
    val prefs = Otility.instance.prefs
    fun change(f: (WidgetLayout) -> WidgetLayout) = prefs.setWidgets(f(prefs.widgets.value))
    val kind = slot.kind
    return buildList {
        add(MenuHeader(kind.title))
        if (kind.sizes.size > 1) {
            add(MenuTiles(kind.sizes.map { s -> MenuItem(kind.label(s), Icons.widgetSize(s.w, s.h), checked = s == slot.size) { change { it.resize(kind, s) } } }))
        }
        kind.options.forEach { o -> add(MenuItem(o.label, checked = slot.shows(o.id)) { change { it.toggle(kind, o.id) } }) }
        add(MenuGap)
        add(MenuItem("Remove Widget", Icons.minus, destructive = true) { change { it.remove(kind) } })
    }
}

/**
 * The car's values, read by the widgets that show them, so a new reading (ten a
 * second while the engine sound follows the car) redraws those and not the board.
 */
@Composable
private fun vehicle(): VehicleData = Otility.instance.vehicle.data.collectAsState().value

@Composable
private fun Widget(slot: WidgetSlot) {
    val m = Modifier.fillMaxSize()
    when (slot.kind) {
        WidgetKind.ENERGY -> EnergyCard(vehicle(), slot, m)
        WidgetKind.SOUND -> SoundCard(slot, m)
        WidgetKind.NAVIGATION -> NavigationCard(slot, m)
        WidgetKind.DRIVE -> DriveCard(vehicle(), slot, m)
        WidgetKind.CLIMATE -> ClimateCard(vehicle(), slot, m)
        WidgetKind.TYRES -> TyresCard(vehicle(), m)
        WidgetKind.SCREENS -> ScreensNowCard(m)
        WidgetKind.WEEK -> WeekCard(slot, m)
        WidgetKind.MEDIA -> NowPlayingCard(slot, m)
        // Drawn beside the grid by MapWidget, never on it.
        WidgetKind.MAP -> {}
    }
}

/** The icon each widget goes by (in its card and in Add Widget). */
fun widgetIcon(kind: WidgetKind): ImageVector = when (kind) {
    WidgetKind.ENERGY -> Icons.energy
    WidgetKind.SOUND -> Icons.sound
    WidgetKind.NAVIGATION -> Icons.navigation
    WidgetKind.DRIVE -> Icons.gauge
    WidgetKind.CLIMATE -> Icons.climate
    WidgetKind.TYRES -> Icons.tire
    WidgetKind.SCREENS -> Icons.screens
    WidgetKind.WEEK -> Icons.trip
    WidgetKind.MAP -> Icons.pin
    WidgetKind.MEDIA -> Icons.music
}

@Composable
fun Card(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    /** One grid row tall: less padding. */
    compact: Boolean = false,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTokens.current
    Column(modifier.glass(rounded(WidgetCorner), GlassKind.Widget).clip(rounded(WidgetCorner)).padding(if (compact) 16.dp else 18.dp)) {
        // As Apple's widgets name themselves: the symbol and the name in the accent
        // colour (the same on every widget), small and semibold, in sentence case.
        val color = t.accText
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(icon, color, size = 17.dp)
            Spacer(Modifier.width(7.dp))
            Txt(title, T.subhead.copy(fontWeight = FontWeight.SemiBold), color, Modifier.weight(1f), maxLines = 1)
            trailing()
        }
        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        androidx.compose.runtime.CompositionLocalProvider(LocalCardTint provides color) { content() }
    }
}

/** Apple's widgets are less round than its sidebar: about this, at these sizes. */
private val WidgetCorner = 24.dp

/**
 * A widget whose values haven't come from the car yet (Outo just started, or isn't
 * connected): one quiet line in the middle of the card, in place of dashes and empty
 * gauges that look broken.
 */
@Composable
private fun ColumnScope.Waiting() {
    val t = LocalTokens.current
    val helper by HelperConnection.helper.collectAsState()
    Box(Modifier.fillMaxWidth().weight(1f, fill = true), contentAlignment = Alignment.Center) {
        Txt(
            if (helper == null) "Shows once Outo is connected to the car" else "Waiting for the car…",
            T.subhead.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), t.text2,
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Column(modifier) {
        Txt(label, T.footnote, t.text2, maxLines = 1)
        Txt(value, T.headline.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

private fun km(v: Int?) = v?.let { "%,d km".format(it) } ?: DASH

@Composable
private fun EnergyCard(v: VehicleData, slot: WidgetSlot, modifier: Modifier) {
    val t = LocalTokens.current
    val total = ((v.elecRangeKm ?: 0) + (v.fuelRangeKm ?: 0)).coerceAtLeast(1)
    if (v.soc == null && v.totalRangeKm == null && v.elecRangeKm == null && v.fuelRangeKm == null) {
        Card("Energy", Icons.energy, modifier) { Waiting() }
        return
    }
    if (slot.size.w <= 3) {
        // Small and Half: the battery ring, the range, and the split between battery and fuel.
        Card("Energy", Icons.energy, modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(v.soc?.let { it / 100f }, 108.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(v.soc?.let { "$it%" } ?: DASH, T.headline)
                        Txt(if (v.pluggedIn == true) "Charging" else "Battery", T.caption, t.text2)
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Txt("Total range", T.footnote, t.text2, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Txt(v.totalRangeKm?.let { "%,d".format(it) } ?: DASH, T.numberL, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Txt("km", T.footnote, t.text2, Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            RangeBar("Electric", v.elecRangeKm, (v.elecRangeKm ?: 0).toFloat() / total, t.acc)
            Spacer(Modifier.height(8.dp))
            RangeBar("Fuel", v.fuelRangeKm, (v.fuelRangeKm ?: 0).toFloat() / total, Color(0xFFFF9F0A))
        }
        return
    }
    Card("Energy", Icons.energy, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(v.soc?.let { it / 100f }, 132.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(v.soc?.let { "$it%" } ?: DASH, T.numberM)
                    Txt(if (v.pluggedIn == true) "Charging" else "Battery", T.caption, t.text2)
                }
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Txt("Total range", T.footnote, t.text2)
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt(v.totalRangeKm?.let { "%,d".format(it) } ?: DASH, T.numberXL)
                    Spacer(Modifier.width(6.dp))
                    Txt("km", T.headline, t.text2, Modifier.padding(bottom = 10.dp))
                }
                RangeBar("Electric", v.elecRangeKm, (v.elecRangeKm ?: 0).toFloat() / total, t.acc)
                Spacer(Modifier.height(8.dp))
                RangeBar("Fuel", v.fuelRangeKm, (v.fuelRangeKm ?: 0).toFloat() / total, Color(0xFFFF9F0A))
            }
        }
        if (slot.shows("details")) {
            Spacer(Modifier.height(18.dp))
            Row {
                Stat("Odometer", v.odometerKm?.let { "%,d km".format(it.roundToInt()) } ?: DASH, Modifier.weight(1f))
                Stat("Last trip", v.lastTripKwh?.let { "%.1f kWh/100 km".format(it) } ?: DASH, Modifier.weight(1f))
                Stat("Fuel tank", v.fuelPct?.let { "$it%" } ?: DASH, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RangeBar(label: String, km: Int?, fraction: Float, color: Color) {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(label, T.footnote, t.text2, Modifier.width(64.dp))
        Box(Modifier.weight(1f).height(8.dp).background(t.track, CircleShape)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).background(color, CircleShape))
        }
        Txt(km(km), T.footnote.copy(fontFeatureSettings = "tnum"), modifier = Modifier.width(80.dp).padding(start = 10.dp), maxLines = 1)
    }
}

/** A ring gauge (battery). */
@Composable
fun Ring(fraction: Float?, size: Dp, center: @Composable () -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(t.track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction != null) {
                drawArc(
                    Brush.sweepGradient(listOf(t.accLo, t.accHi, t.accLo)),
                    -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        center()
    }
}

@Composable
private fun SoundCard(slot: WidgetSlot, modifier: Modifier) {
    val app = Otility.instance
    val t = LocalTokens.current
    val nav = LocalNav.current
    val s by app.prefs.settings.collectAsState()
    val car = Garage.byId(s.soundCar) ?: Garage.cars.first()
    val slim = slot.size.h == 1
    Card("Engine Sound", Icons.sound, modifier, compact = slim, trailing = {
        Switch(s.soundOn, { on -> app.prefs.update { it.copy(soundOn = on) } })
    }) {
        Txt(car.name, if (slim) T.headline else T.title2, maxLines = 1)
        if (slim) return@Card
        Txt(car.engine, T.footnote, t.text2, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        Segmented(
            listOf(SoundMode.STREET to "Street", SoundMode.SPORT to "Sport", SoundMode.TRACK to "Track"),
            s.soundMode, { m -> app.prefs.update { it.copy(soundMode = m) } },
            Modifier.fillMaxWidth(),
        )
        if (slot.shows("studio")) {
            Spacer(Modifier.weight(1f).height(14.dp))
            GlassPillButton("Sound Studio", { nav.sheet = SheetKind.SOUND_STUDIO }, Modifier.fillMaxWidth(), icon = Icons.sound)
        }
    }
}

/** The next turn while the map guides; otherwise where to, and the places sent lately. */
@Composable
private fun NavigationCard(slot: WidgetSlot, modifier: Modifier) {
    val app = Otility.instance
    val t = LocalTokens.current
    val s by app.prefs.settings.collectAsState()
    val turn by Directions.turn.collectAsState()
    val places by app.prefs.places.collectAsState()
    val apps by app.catalog.apps.collectAsState()
    val hasMap = remember(apps, s.navApp) { app.navigator.navApp() != null }
    val label = remember(apps, s.navApp) { app.navigator.navLabel() }
    val next = turn?.takeIf { s.showDirections }
    // The recent places sit beside the turn when there's room for both.
    val side = slot.size.w >= 4 && slot.shows("places")
    val small = slot.size.w <= 3
    Card("Navigation", Icons.navigation, modifier, trailing = { if (hasMap) Txt(label, T.footnote, t.text2) }) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(if (small) 64.dp else 88.dp).background(if (next != null) t.fill else t.track, rounded(if (small) 18.dp else 22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val arrow = next?.arrow
                    when {
                        next == null -> Glyph(Icons.pin, t.text2, size = if (small) 30.dp else 40.dp)
                        next.maneuver != Maneuver.UNKNOWN -> Glyph(turnIcon(next.maneuver), t.onFill, size = if (small) 38.dp else 52.dp)
                        arrow != null -> Image(arrow.asImageBitmap(), null, Modifier.size(if (small) 44.dp else 60.dp))
                        else -> Glyph(Icons.navigation, t.onFill, size = if (small) 32.dp else 44.dp)
                    }
                }
                Spacer(Modifier.width(if (small) 14.dp else 20.dp))
                Column(Modifier.weight(1f)) {
                    if (next != null) {
                        next.distance?.let { Txt(it, if (small) T.headline else T.numberM, maxLines = 1) }
                        Txt(next.instruction, if (small) T.headline else T.title2, maxLines = 2)
                        next.trip?.let { Txt(it, T.footnote, t.text2, maxLines = 1) }
                    } else {
                        Txt("Where to?", if (small) T.headline else T.title2)
                        Spacer(Modifier.height(2.dp))
                        Txt(
                            when {
                                small -> if (s.phoneHandoff) "Send a place from your iPhone." else "Open the map."
                                s.phoneHandoff -> "Send a place from your iPhone, or pick a recent one."
                                else -> "Pick a recent place, or open the map."
                            },
                            T.footnote, t.text2, maxLines = 2,
                        )
                        if (!s.showDirections && hasMap && !small) {
                            Spacer(Modifier.height(10.dp))
                            GlassChip("Show the next turn here", icon = Icons.navigation, onClick = { app.navigator.setDirections(true) })
                        }
                    }
                }
            }
            if (!side) return@Row
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                places.take(3).forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(rounded(14.dp))
                            .background(t.inset)
                            .clickable { app.navigator.go(p) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(Icons.pin, t.accText, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Txt(p.title, T.callout, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
                if (places.isEmpty()) Txt("Places you send appear here.", T.footnote, t.text2)
                if (hasMap) GlassPillButton("Open $label", { app.navigator.openApp() }, Modifier.fillMaxWidth(), icon = Icons.navigation)
            }
        }
        // Without the places beside it, the map's button goes under the turn.
        if (!side && hasMap) {
            Spacer(Modifier.height(10.dp))
            GlassPillButton("Open $label", { app.navigator.openApp() }, Modifier.fillMaxWidth(), icon = Icons.navigation)
        }
    }
}

private fun turnIcon(m: Maneuver): ImageVector = Icons.turn(m)

@Composable
private fun DriveCard(v: VehicleData, slot: WidgetSlot, modifier: Modifier) {
    val t = LocalTokens.current
    val slim = slot.size.h == 1
    Card("Drive", Icons.gauge, modifier, compact = slim) {
        if (v.gear == null && v.speedKmh == null) { Waiting(); return@Card }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (slim) 40.dp else 64.dp).background(t.track, rounded(if (slim) 12.dp else 18.dp)), contentAlignment = Alignment.Center) {
                Txt(v.gear ?: DASH, (if (slim) T.headline else T.numberM).copy(fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt(v.speedKmh?.toString() ?: DASH, T.numberL)
                    Spacer(Modifier.width(4.dp))
                    Txt("km/h", T.footnote, t.text2, Modifier.padding(bottom = 8.dp))
                }
            }
        }
        if (slim) return@Card
        Spacer(Modifier.height(14.dp))
        Stat("Front motor", v.motorRpm?.let { "%,d rpm".format(it) } ?: DASH)
        if (slot.shows("pedal")) {
            Spacer(Modifier.height(10.dp))
            Txt("Accelerator", T.footnote, t.text2)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).background(t.track, CircleShape)) {
                Box(Modifier.fillMaxWidth(v.pedal ?: 0f).height(8.dp).background(t.acc, CircleShape))
            }
        }
    }
}

@Composable
private fun ClimateCard(v: VehicleData, slot: WidgetSlot, modifier: Modifier) {
    val t = LocalTokens.current
    val slim = slot.size.h == 1
    Card("Climate", Icons.climate, modifier, compact = slim, trailing = {
        v.acOn?.let { Txt(if (it) "A/C on" else "A/C off", T.footnote, t.text2) }
    }) {
        if (v.tempDriver == null && v.tempPassenger == null && v.tempOut == null) { Waiting(); return@Card }
        if (slim) {
            // Just the two temperatures, side by side.
            Row(verticalAlignment = Alignment.Bottom) {
                Txt(v.tempDriver?.let { "$it°" } ?: DASH, T.numberM, modifier = Modifier.weight(1f), maxLines = 1)
                Txt(v.tempPassenger?.let { "$it°" } ?: DASH, T.numberM, modifier = Modifier.weight(1f), maxLines = 1)
            }
            return@Card
        }
        Row {
            Column(Modifier.weight(1f)) {
                Txt("Driver", T.footnote, t.text2)
                Txt(v.tempDriver?.let { "$it°" } ?: DASH, T.numberL)
            }
            Column(Modifier.weight(1f)) {
                Txt("Passenger", T.footnote, t.text2)
                Txt(v.tempPassenger?.let { "$it°" } ?: DASH, T.numberL)
            }
        }
        if (slot.shows("outside")) {
            Spacer(Modifier.height(12.dp))
            Stat("Outside", v.tempOut?.let { "$it°C" } ?: DASH)
        }
    }
}

@Composable
private fun TyresCard(v: VehicleData, modifier: Modifier) {
    val t = LocalTokens.current
    val tyres = v.tyresBar
    fun label(i: Int) = tyres[i]?.let { "%.1f".format(it) } ?: DASH
    val none = tyres.all { it == null }
    Card("Tyres", Icons.tire, modifier, trailing = { if (!none) Txt("bar", T.footnote, t.text2) }) {
        if (none) { Waiting(); return@Card }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                Txt(label(0), T.numberM)
                Txt(label(2), T.numberM)
            }
            // A simple car seen from above.
            Canvas(Modifier.size(56.dp, 110.dp)) {
                val body = t.text2.copy(alpha = 0.55f)
                drawRoundRect(body, Offset(8.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), size.height), CornerRadius(18.dp.toPx()), style = Stroke(2.dp.toPx()))
                val tw = 7.dp.toPx(); val th = 20.dp.toPx()
                listOf(
                    Offset(0f, 14.dp.toPx()), Offset(size.width - tw, 14.dp.toPx()),
                    Offset(0f, size.height - 14.dp.toPx() - th), Offset(size.width - tw, size.height - 14.dp.toPx() - th),
                ).forEach { drawRoundRect(t.text.copy(alpha = 0.8f), it, Size(tw, th), CornerRadius(3.dp.toPx())) }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(36.dp)) {
                Txt(label(1), T.numberM)
                Txt(label(3), T.numberM)
            }
        }
    }
}

@Composable
private fun ScreensNowCard(modifier: Modifier) {
    val app = Otility.instance
    val t = LocalTokens.current
    val nav = LocalNav.current
    val state by app.screens.state.collectAsState()
    val apps by app.catalog.apps.collectAsState()
    val byId = apps.associateBy { it.id }
    Card("Screens now", Icons.screens, modifier.noRipple { nav.openScreens() }) {
        ScreenId.entries.forEachIndexed { i, s ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (s) { ScreenId.DRIVER -> Icons.wheel; ScreenId.MAIN -> Icons.full; ScreenId.PASSENGER -> Icons.passenger }
                Glyph(icon, t.text2, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Txt(s.label, T.callout, modifier = Modifier.weight(1f))
                val c = state[s]
                if (c == ScreenContent.Idle) Txt("Idle", T.footnote, t.text2)
                else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { c.apps.forEach { AppIcon(byId[it], 28.dp) } }
            }
        }
    }
}

@Composable
private fun WeekCard(slot: WidgetSlot, modifier: Modifier) {
    val app = Otility.instance
    val t = LocalTokens.current
    val days by app.vehicle.days.collectAsState()
    val total = days.sumOf { it.second.toDouble() }.toFloat()
    val top = days.maxOfOrNull { it.second }?.coerceAtLeast(1f) ?: 1f
    val narrow = slot.size.w <= 2
    Card("Last 7 days", Icons.trip, modifier, trailing = {
        Txt(if (total > 0f) "%,d km".format(total.roundToInt()) else "", T.footnote, t.text2)
    }) {
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(if (narrow) 6.dp else 10.dp), verticalAlignment = Alignment.Bottom) {
            days.forEachIndexed { i, (date, kmDay) ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    val f = (kmDay / top).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((6 + 76 * f).dp)
                            .background(if (i == days.lastIndex) t.acc else t.acc.copy(alpha = 0.45f), rounded(6.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Txt(date.dayOfWeek.getDisplayName(if (narrow) TextStyle.NARROW else TextStyle.SHORT, Locale.getDefault()), T.caption, t.text2, maxLines = 1)
                }
            }
        }
        if (total == 0f) {
            Spacer(Modifier.height(8.dp))
            Txt("Kilometres per day appear here as you drive with Outo open.", T.footnote, t.text2)
        }
    }
}
