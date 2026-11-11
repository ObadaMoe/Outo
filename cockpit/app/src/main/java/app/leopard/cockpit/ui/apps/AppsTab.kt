package app.leopard.cockpit.ui.apps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.data.AppView
import app.leopard.cockpit.data.DOCK_MAX
import app.leopard.cockpit.screens.DropTarget
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.screens.Slot
import app.leopard.cockpit.ui.SideInset
import app.leopard.cockpit.ui.AppIcon
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.openZoomed
import app.leopard.cockpit.ui.pinAt
import app.leopard.cockpit.ui.MenuEntry
import app.leopard.cockpit.ui.MenuGap
import app.leopard.cockpit.ui.MenuHeader
import app.leopard.cockpit.ui.MenuItem
import app.leopard.cockpit.ui.MenuRequest
import app.leopard.cockpit.ui.MenuTiles
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.noRipple
import app.leopard.cockpit.ui.overview.CabinBackground
import app.leopard.cockpit.ui.screens.LiftCallbacks
import app.leopard.cockpit.ui.screens.SearchField
import app.leopard.cockpit.ui.screens.liftable
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T

/** Every app in the car. Tap to open on the main screen; hold for more. */
@Composable
fun AppsTab() {
    val app = Otility.instance
    val t = LocalTokens.current
    val nav = LocalNav.current
    val all by app.catalog.apps.collectAsState()
    val dock by app.prefs.dock.collectAsState()
    val views by app.prefs.appViews.collectAsState()
    val state by app.screens.state.collectAsState()
    var query by remember { mutableStateOf("") }
    // Drawn by OtilityApp, above the sidebar.
    var menu: MenuRequest? by nav::menu
    val shown = all.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }

    /** Opens it there, and zooms in on that screen. */
    fun openOn(a: AppInfo, target: DropTarget) {
        app.screens.place(a.id, target)
        nav.openScreens(target.screen)
    }

    /** A tap: full screen on the main screen, zoomed in (or a question if it's already open somewhere). */
    fun tapOpen(a: AppInfo) = nav.openZoomed(a.id)

    fun menuFor(a: AppInfo): List<MenuEntry> {
        val view = views[a.id] ?: AppView.TABLET
        val inDock = a.id in dock
        return listOf(
            MenuHeader(a.label),
            MenuTiles(listOf(
                MenuItem("Full", Icons.full) { openOn(a, DropTarget(ScreenId.MAIN, Slot.FULL)) },
                MenuItem("Left Half", Icons.leftHalf) { openOn(a, DropTarget(ScreenId.MAIN, Slot.LEFT)) },
                MenuItem("Right Half", Icons.rightHalf) { openOn(a, DropTarget(ScreenId.MAIN, Slot.RIGHT)) },
            )),
            MenuGap,
            MenuHeader("Other Displays"),
            MenuItem("Passenger", Icons.passenger) { openOn(a, DropTarget(ScreenId.PASSENGER, Slot.FULL)) },
            MenuItem("Picture in Picture", Icons.pip) { app.screens.pictureInPicture(a.id) },
            MenuGap,
            MenuHeader("Shows as"),
            MenuTiles(listOf(
                MenuItem("Tablet", Icons.tablet, checked = view == AppView.TABLET) { app.prefs.setAppView(a.id, AppView.TABLET) },
                MenuItem("Phone", Icons.phone, checked = view == AppView.PHONE) { app.prefs.setAppView(a.id, AppView.PHONE) },
            )),
            MenuGap,
            if (inDock) MenuItem("Remove from Sidebar", Icons.minus, destructive = true) { app.prefs.setDock(dock - a.id) }
            // Mostly pinned by dragging it onto the sidebar; this is for when that's awkward.
            else MenuItem("Add to Sidebar", Icons.plus, enabled = dock.size < DOCK_MAX) { app.prefs.setDock(dock + a.id) },
        )
    }

    val recentIds by app.prefs.recents.collectAsState()
    val searching = query.isNotBlank()
    val byId = remember(all) { all.associateBy { it.id } }
    val recent = if (searching) emptyList() else recentIds.mapNotNull { byId[it] }

    /** One app: its icon (tap to open, hold for the menu, hold and drag onto the sidebar to pin it), its name, and a dot while it's on a screen. */
    @Composable
    fun Tile(a: AppInfo, icon: Dp, modifier: Modifier = Modifier) {
        val on = state.screenOf(a.id)
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(
                a, icon,
                Modifier.liftable(a.id, LiftCallbacks(
                    onTap = { tapOpen(a) },
                    onHold = { bounds -> menu = MenuRequest(bounds, menuFor(a), a) },
                    onDragStart = { p ->
                        menu = null
                        nav.dragging = a.id
                        nav.dragAt = p
                    },
                    onDrag = { p -> nav.dragAt = p },
                    onDrop = { p ->
                        nav.pinAt(a.id, p)
                        nav.dragging = null
                    },
                    onCancel = { nav.dragging = null },
                )),
            )
            Spacer(Modifier.height(8.dp))
            Txt(
                a.label, T.footnote.copy(textAlign = TextAlign.Center), maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp).noRipple { tapOpen(a) },
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(5.dp).background(if (on != null) t.text2 else Color.Transparent, CircleShape))
        }
    }

    CabinBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = SideInset, end = 28.dp, top = 20.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Apps", T.largeTitle, modifier = Modifier.weight(1f))
                SearchField(query, { query = it }, Modifier.width(320.dp), placeholder = "Search apps")
            }
            // Nothing to show (a search with no match): say so rather than leave it blank.
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Txt(if (query.isBlank()) "No Apps" else "No Results", T.title2)
                        Txt(
                            if (query.isBlank()) "Apps the car can open show up here."
                            else "No app is called “${query.trim()}”.",
                            T.subhead.copy(textAlign = TextAlign.Center), t.text2, Modifier.width(420.dp), maxLines = 2,
                        )
                    }
                }
                return@Column
            }
            // As CarPlay's: big icons on pages that turn with a swipe, the apps used
            // lately in a row above them. Columns and rows are as many as fit.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cols = (maxWidth / AppCell).toInt().coerceAtLeast(3)
                val cellW = maxWidth / cols
                val recentRow = recent.take(cols)
                val recentH = if (recentRow.isEmpty()) 0.dp else AppCell + RecentLabel
                val rows = ((maxHeight - recentH - AllLabel - Dots) / AppCell).toInt().coerceAtLeast(1)
                val perPage = cols * rows
                val pages = shown.chunked(perPage)
                val pager = rememberPagerState { pages.size }
                // A new search starts on its first page.
                LaunchedEffect(query) { if (pager.currentPage != 0) pager.scrollToPage(0) }
                Column(Modifier.fillMaxSize()) {
                    if (recentRow.isNotEmpty()) {
                        Section("Recent")
                        Row(Modifier.height(AppCell)) {
                            recentRow.forEach { a ->
                                key("recent", a.id.pkg) { Tile(a, AppIconSize, Modifier.width(cellW).padding(top = 10.dp)) }
                            }
                        }
                    }
                    Section(if (searching) "Results" else "All Apps")
                    HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top, key = { it }) { page ->
                        Column {
                            pages.getOrNull(page).orEmpty().chunked(cols).forEach { line ->
                                Row(Modifier.height(AppCell)) {
                                    line.forEach { a -> key(a.id.pkg) { Tile(a, AppIconSize, Modifier.width(cellW).padding(top = 10.dp)) } }
                                }
                            }
                        }
                    }
                    // Which page, as an iPhone's dots show it.
                    Box(Modifier.fillMaxWidth().height(Dots), contentAlignment = Alignment.Center) {
                        if (pages.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(pages.size) { i ->
                                Box(
                                    Modifier.size(8.dp).background(
                                        if (i == pager.currentPage) t.text else t.text2.copy(alpha = 0.4f), CircleShape,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** An app's place on the Apps pages: its icon, name and dot, and room around them. */
private val AppCell = 150.dp
private val AppIconSize = 84.dp
private val RecentLabel = 30.dp
private val AllLabel = 30.dp
private val Dots = 24.dp

/** A section's name above its apps, as Apple's small capitals. */
@Composable
private fun Section(name: String) {
    Txt(
        name.uppercase(), T.sectionHeader, LocalTokens.current.text2,
        Modifier.padding(start = 8.dp, top = 12.dp).height(18.dp),
    )
}
