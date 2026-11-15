package app.leopard.cockpit.ui.apps

import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.text.TextStyle
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
import app.leopard.cockpit.ui.Nav
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

/** Opens [a] there, and zooms in on that screen. */
private fun Nav.openOn(a: AppInfo, target: DropTarget) {
    Otility.instance.screens.place(a.id, target)
    openScreens(target.screen)
}

/** An app's menu, held on All apps or on CarPlay's Home: where it opens, how it shows, the sidebar. */
internal fun appMenu(nav: Nav, a: AppInfo): List<MenuEntry> {
    val app = Otility.instance
    val dock = app.prefs.dock.value
    val view = app.prefs.appViews.value[a.id] ?: AppView.TABLET
    val inDock = a.id in dock
    return listOf(
        MenuHeader(a.label),
        MenuTiles(listOf(
            MenuItem("Full", Icons.full) { nav.openOn(a, DropTarget(ScreenId.MAIN, Slot.FULL)) },
            MenuItem("Left Half", Icons.leftHalf) { nav.openOn(a, DropTarget(ScreenId.MAIN, Slot.LEFT)) },
            MenuItem("Right Half", Icons.rightHalf) { nav.openOn(a, DropTarget(ScreenId.MAIN, Slot.RIGHT)) },
        )),
        MenuGap,
        MenuHeader("Other Displays"),
        MenuItem("Passenger", Icons.passenger) { nav.openOn(a, DropTarget(ScreenId.PASSENGER, Slot.FULL)) },
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

/**
 * One app: its icon (tap to open it full screen on the main screen, hold for the
 * menu, hold and drag onto the sidebar to pin it), its name, and a dot while it's
 * on a screen.
 */
@Composable
internal fun AppTile(
    a: AppInfo, icon: Dp, modifier: Modifier = Modifier, label: TextStyle = T.footnote,
    /** More for its menu, after the app's own (CarPlay Home's Edit Home Screen). */
    more: List<MenuEntry> = emptyList(),
) {
    val nav = LocalNav.current
    val t = LocalTokens.current
    val state by Otility.instance.screens.state.collectAsState()
    val on = state.screenOf(a.id)
    // Where the icon is: the app grows out of it.
    var iconAt by remember { mutableStateOf(Rect.Zero) }
    val open = { nav.openZoomed(a.id, if (iconAt.width > 0f) iconAt.center else Offset.Unspecified, iconAt) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(
            a, icon,
            Modifier.onGloballyPositioned { iconAt = it.boundsInRoot() }.liftable(a.id, LiftCallbacks(
                onTap = open,
                onHold = { bounds -> nav.menu = MenuRequest(bounds, appMenu(nav, a) + more, a) },
                onDragStart = { p ->
                    nav.menu = null
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
            a.label, label.copy(textAlign = TextAlign.Center), maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp).noRipple(open),
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(5.dp).background(if (on != null) t.text2 else Color.Transparent, CircleShape))
    }
}

/** Every app in the car. Tap to open on the main screen; hold for more. */
@Composable
fun AppsTab() {
    val app = Otility.instance
    val t = LocalTokens.current
    val all by app.catalog.apps.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = all.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }

    val recentIds by app.prefs.recents.collectAsState()
    val searching = query.isNotBlank()
    val byId = remember(all) { all.associateBy { it.id } }
    val recent = if (searching) emptyList() else recentIds.mapNotNull { byId[it] }

    @Composable
    fun Tile(a: AppInfo, icon: Dp, modifier: Modifier = Modifier) = AppTile(a, icon, modifier)

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
