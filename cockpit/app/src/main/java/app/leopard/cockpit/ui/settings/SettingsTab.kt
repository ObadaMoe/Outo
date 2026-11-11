package app.leopard.cockpit.ui.settings

import androidx.compose.runtime.getValue
import app.leopard.cockpit.ui.raisedCard
import app.leopard.cockpit.ui.LocalRaisedGroups
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.leopard.cockpit.Otility
import app.leopard.cockpit.R
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.car.HelperLauncher
import app.leopard.cockpit.data.Accent
import app.leopard.cockpit.data.Appearance
import app.leopard.cockpit.data.Settings
import app.leopard.cockpit.data.SoundMode
import app.leopard.cockpit.data.Tab
import app.leopard.cockpit.engine.Garage
import app.leopard.cockpit.ui.SideInset
import app.leopard.cockpit.ui.SidebarCorner
import app.leopard.cockpit.ui.SidebarMargin
import app.leopard.cockpit.ui.GlassKind
import app.leopard.cockpit.ui.GlassPillButton
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.ActionRow
import app.leopard.cockpit.ui.overview.CarPhotos
import app.leopard.cockpit.ui.Group
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.LocalNav
import app.leopard.cockpit.ui.Segmented
import app.leopard.cockpit.ui.Separator
import app.leopard.cockpit.ui.SettingRow
import app.leopard.cockpit.update.UpdateState
import app.leopard.cockpit.update.Updater
import kotlin.math.roundToInt
import app.leopard.cockpit.ui.SheetKind
import app.leopard.cockpit.ui.Slider
import app.leopard.cockpit.ui.Switch
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.glass
import app.leopard.cockpit.ui.overview.CabinBackground
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import app.leopard.cockpit.ui.theme.tokens
import app.leopard.cockpit.nav.Directions
import app.leopard.cockpit.nav.MapScreenReader
import app.leopard.cockpit.nav.Navigator
import app.leopard.cockpit.nav.PhoneLink
import app.leopard.cockpit.nav.PhoneLinkService
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.screens.ScreenId
import app.leopard.cockpit.ui.Alerts
import app.leopard.cockpit.ui.QrCode
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SettingsPage(val title: String, val icon: ImageVector, val color: Color, val about: String) {
    DISPLAY("Display", Icons.display, Color(0xFF0A84FF), "Light or dark, the accent colour, the glass and the emblem on idle screens."),
    SCREENS("Screens & Sidebar", Icons.screens, Color(0xFF5E5CE6), "The car's screens, the gestures when zoomed in, and the sidebar."),
    CLUSTER("Driver Cluster", Icons.gauge, Color(0xFFFF9F0A), "What the driver cluster shows, and how Outo sends it there."),
    NAVIGATION("Navigation", Icons.navigation, Color(0xFF30D158), "Your map app, places from your phone, directions on the cluster and the HUD."),
    SOUND("Engine Sound", Icons.sound, Color(0xFFFF375F), "The engine you hear: the car, its mode and volume, and when it plays."),
    STARTUP("Startup", Icons.startup, Color(0xFF32ADE6), "Opening with the car, the tab Outo opens on, and the last layout."),
    CONNECTION("Car Connection", Icons.link, Color(0xFF8E8E93), "Outo's link to the car, and what the car reports."),
    INTEGRATIONS("Integrations", Icons.apps, Color(0xFFBF5AF2), "The apps Outo knows best, and your phone."),
    ABOUT("About", Icons.info, Color(0xFF636366), "The version and updates, and what Outo is built with."),
}

/**
 * Settings, as in Apple's System Settings: one panel, with the pages listed on a
 * rounded card raised off its left side, under the "Settings" title (the open one
 * filled with the accent), and the open page beside them: a header card, then its
 * groups as cards raised off the panel too.
 */
@Composable
fun SettingsTab() {
    val nav = LocalNav.current
    val t = LocalTokens.current
    val panel = rounded(SidebarCorner)
    CabinBackground {
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // Top and bottom with the sidebar, and as close to the right edge.
                .padding(start = SideInset, end = SidebarMargin, top = SidebarMargin, bottom = SidebarMargin)
                .glass(panel, GlassKind.Card)
                .clip(panel),
        ) {
            // ---- The pages: a card of their own, inset from the panel's edge and rounded with it ----
            Column(
                Modifier
                    .padding(start = PagesInset, top = PagesInset, bottom = PagesInset)
                    .width(290.dp)
                    .fillMaxHeight()
                    .raisedCard(rounded(SidebarCorner - PagesInset)),
            ) {
                Txt("Settings", T.largeTitle, modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 12.dp))
                val update by Otility.instance.updater.state.collectAsState()
                val updateReady = update is UpdateState.Available
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SettingsPage.entries.forEach { page ->
                        val on = nav.settingsPage == page
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(rounded(12.dp))
                                .background(if (on) t.fill else Color.Transparent)
                                .clickable { nav.settingsPage = page }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(30.dp).background(page.color, rounded(8.dp)), contentAlignment = Alignment.Center) {
                                Glyph(page.icon, Color.White, size = 18.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Txt(page.title, T.callout.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), if (on) t.onFill else t.text)
                            if (page == SettingsPage.ABOUT && updateReady) {
                                Spacer(Modifier.weight(1f))
                                Box(Modifier.size(10.dp).background(if (on) t.onFill else t.destructive, CircleShape))
                            }
                        }
                    }
                }
            }
            // ---- The open page ----
            // A scroll position per page: another page opens at its top, not half-way down.
            val pageScroll = key(nav.settingsPage) { rememberScrollState() }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(pageScroll)
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                PageHeader(nav.settingsPage)
                CompositionLocalProvider(LocalRaisedGroups provides true) {
                    when (nav.settingsPage) {
                        SettingsPage.DISPLAY -> DisplayPage()
                        SettingsPage.SCREENS -> ScreensPage()
                        SettingsPage.CLUSTER -> ClusterPage()
                        SettingsPage.NAVIGATION -> NavigationPage()
                        SettingsPage.SOUND -> SoundPage()
                        SettingsPage.STARTUP -> StartupPage()
                        SettingsPage.CONNECTION -> ConnectionPage()
                        SettingsPage.INTEGRATIONS -> IntegrationsPage()
                        SettingsPage.ABOUT -> AboutPage()
                    }
                }
            }
        }
    }
}

/** How far the pages' card keeps from the panel's edge. */
private val PagesInset = 10.dp

/** The open page's card at its top: its icon, its name and what's on it, as Apple's pages start. */
@Composable
private fun PageHeader(page: SettingsPage) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().raisedCard(rounded(22.dp)).padding(horizontal = 32.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(52.dp).background(page.color, rounded(13.dp)), contentAlignment = Alignment.Center) {
            Glyph(page.icon, Color.White, size = 30.dp)
        }
        Spacer(Modifier.height(2.dp))
        Txt(page.title, T.title1)
        Txt(page.about, T.subhead.copy(textAlign = TextAlign.Center), t.text2)
    }
}

@Composable
private fun settings(): Settings = Otility.instance.prefs.settings.collectAsState().value

private fun update(f: (Settings) -> Settings) = Otility.instance.prefs.update(f)

@Composable
private fun Toggle(title: String, value: Boolean, subtitle: String? = null, enabled: Boolean = true, set: (Settings, Boolean) -> Settings) {
    SettingRow(title, subtitle = subtitle) {
        Switch(value, { on -> if (enabled) update { set(it, on) } })
    }
}

// ---- Pages -------------------------------------------------------------------

@Composable
private fun DisplayPage() {
    val s = settings()
    val t = LocalTokens.current
    Group(header = "Appearance") {
        Box(Modifier.padding(16.dp)) {
            Segmented(
                listOf(Appearance.LIGHT to "Light", Appearance.DARK to "Dark", Appearance.AUTO to "Automatic"),
                s.appearance, { a -> update { it.copy(appearance = a) } }, Modifier.fillMaxWidth(),
            )
        }
    }
    Group(header = "Accent") {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Accent.entries.forEach { a ->
                val sample = tokens(t.dark, a)
                val on = s.accent == a
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { update { it.copy(accent = a) } }) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .border(if (on) 3.dp else 0.dp, if (on) t.text else Color.Transparent, CircleShape)
                            .padding(5.dp)
                            .background(sample.fill, CircleShape),
                    )
                    Spacer(Modifier.height(6.dp))
                    Txt(a.name.lowercase().replaceFirstChar { it.uppercase() }, T.footnote, if (on) t.text else t.text2)
                }
            }
        }
    }
    Group(header = "Glass", footer = "Tinted glass is easier to read over busy pictures.") {
        Box(Modifier.padding(16.dp)) {
            Segmented(listOf(false to "Clear", true to "Tinted"), s.tintedGlass, { v -> update { it.copy(tintedGlass = v) } }, Modifier.fillMaxWidth())
        }
        Separator()
        Toggle("Reduce transparency", s.reduceTransparency, "Solid surfaces instead of glass") { st, v -> st.copy(reduceTransparency = v) }
    }
    Group(header = "Idle screens") {
        Toggle("Show the emblem", s.showEmblem) { st, v -> st.copy(showEmblem = v) }
        Separator()
        SettingRow("Dim the emblem after") {
            Segmented(
                listOf(0 to "Never", 15 to "15 s", 30 to "30 s", 60 to "1 min"),
                s.dimEmblemSeconds, { v -> update { it.copy(dimEmblemSeconds = v) } }, Modifier.width(300.dp),
            )
        }
    }
}

@Composable
private fun ScreensPage() {
    val s = settings()
    val app = Otility.instance
    val nav = LocalNav.current
    val layouts by app.prefs.layouts.collectAsState()
    Group(header = "Screens") {
        Toggle("Live previews", s.livePreviews, "The apps themselves in the screens, not only their icons") { st, v -> st.copy(livePreviews = v) }
        Separator()
        Toggle("Ask before replacing an app", s.confirmReplace) { st, v -> st.copy(confirmReplace = v) }
        Separator()
        SettingRow("Hide controls when zoomed in after") {
            Segmented(
                listOf(2 to "2 s", 3 to "3 s", 5 to "5 s", 0 to "Never"),
                s.autoHideSeconds, { v -> update { it.copy(autoHideSeconds = v) } }, Modifier.width(280.dp),
            )
        }
        Separator()
        Toggle("Gestures", s.gestures, "Two-finger swipes when zoomed in") { st, v -> st.copy(gestures = v) }
        Separator()
        Toggle("Restore the last layout", s.restoreLayout, "When Outo starts") { st, v -> st.copy(restoreLayout = v) }
    }
    Group(header = "Gestures when zoomed in") {
        listOf(
            "Two fingers up" to "Show the controls and the sidebar",
            "Two fingers down" to "Back to all three screens",
            "Two fingers left or right" to "The next screen, as pages turn. Right from the main screen opens the cluster's map over it; left puts it back",
            "Swipe in from the left edge" to "Back, inside the app",
            "Drag the divider" to "Resize; drag it to the edge for full screen",
            "Double-tap the divider" to "Equal halves",
        ).forEachIndexed { i, (g, what) ->
            if (i > 0) Separator()
            SettingRow(g, subtitle = what)
        }
    }
    Group(header = "Sidebar and layouts") {
        // Pinned from Apps, as on CarPlay there's no list to edit here.
        SettingRow("Sidebar apps", subtitle = "In Apps, hold an app and drag it onto the sidebar. Hold one on the sidebar to remove it.")
        layouts.forEach { l ->
            Separator()
            SettingRow(l.name, subtitle = "Saved layout") {
                Box(Modifier.clickable { app.prefs.deleteLayout(l.name) }.padding(6.dp)) {
                    Glyph(Icons.trash, LocalTokens.current.destructive, size = 20.dp)
                }
            }
        }
    }
}

@Composable
private fun ClusterPage() {
    val s = settings()
    val app = Otility.instance
    val helper by HelperConnection.helper.collectAsState()
    Group(
        footer = "The map channel shows an app on the cluster's map area. If the map doesn't appear, try another channel.",
    ) {
        SettingRow("Map channel") {
            Segmented(listOf(29 to "29", 30 to "30", 31 to "31"), s.clusterCode, { v -> update { it.copy(clusterCode = v) } }, Modifier.width(220.dp))
        }
        Separator()
        Toggle(
            "${app.navigator.navLabel()} on the cluster", s.mapOnCluster,
            "Only its map, instead of BYD's own view. Pick Waze or Google Maps above the Driver screen in Screens",
        ) { st, v -> st.copy(mapOnCluster = v) }
        Separator()
        Toggle(
            "Blind-spot camera when signalling", s.signalView,
            "While a turn signal is on, the cluster shows BYD's own view, with its camera, and the map again after",
        ) { st, v -> st.copy(signalView = v) }
    }
    Group(footer = if (helper == null) "Connect to the car to use these." else null) {
        ActionRow("Show the map channel now", enabled = helper != null) { app.bridge.cluster(true, s.clusterCode) }
        Separator()
        ActionRow("Turn the map channel off", enabled = helper != null) { app.bridge.cluster(false, s.clusterCode) }
    }
}

@Composable
private fun NavigationPage() {
    val s = settings()
    val app = Otility.instance
    val t = LocalTokens.current
    val apps by app.catalog.apps.collectAsState()
    val installed = Navigator.APPS.filter { (pkg, _) -> apps.any { it.id.pkg == pkg } }
    Group(header = "Navigation app", footer = if (installed.isEmpty()) "No navigation app found." else null) {
        installed.forEachIndexed { i, (pkg, name) ->
            if (i > 0) Separator()
            SettingRow(name, onClick = { update { it.copy(navApp = pkg) } }) {
                if (s.navApp == pkg) Glyph(Icons.check, t.accText, size = 18.dp)
            }
        }
    }
    Group(
        header = "Places open on",
        footer = "\"Where it is\" uses the screen the map is already on, or the main screen if it isn't open. " +
            "With the map on the driver cluster, places open there.",
    ) {
        Box(Modifier.padding(16.dp)) {
            Segmented(
                listOf<Pair<ScreenId?, String>>(
                    null to "Where it is", ScreenId.MAIN to "Main", ScreenId.PASSENGER to "Passenger",
                ),
                s.navScreen, { v -> update { it.copy(navScreen = v) } }, Modifier.fillMaxWidth(),
            )
        }
    }
    PhoneGroup()
    DirectionsGroup()
    RecentPlacesGroup()
}

/** Pairing the driver's iPhone: a QR code for the page, and the link for a Share-menu shortcut. */
@Composable
private fun PhoneGroup() {
    val s = settings()
    val app = Otility.instance
    val t = LocalTokens.current
    val context = LocalContext.current
    val running by app.phone.running.collectAsState()
    val keyVersion by app.prefs.keyVersion.collectAsState()
    val last by app.navigator.last.collectAsState()
    // The car's address changes as it joins and leaves networks.
    val addresses by produceState(app.phone.addresses()) {
        while (true) {
            value = app.phone.addresses()
            delay(4000)
        }
    }
    val ip = addresses.firstOrNull()?.second
    Group(
        header = "From your iPhone",
        footer = "The iPhone and the car need to be on the same Wi-Fi: connect the car to the iPhone's Personal Hotspot, " +
            "or the iPhone to the car's hotspot. Each place carries the pairing code, so other phones can't send one.",
    ) {
        SettingRow("Places from your iPhone", subtitle = "Send a place on the phone and it opens in ${app.navigator.navLabel()}") {
            Switch(s.phoneHandoff, { on ->
                update { it.copy(phoneHandoff = on) }
                PhoneLinkService.sync(context)
            })
        }
        if (s.phoneHandoff) {
            Separator()
            if (ip == null) {
                SettingRow("Not on a Wi-Fi network", subtitle = "Connect the car to the iPhone's Personal Hotspot and the pairing code appears here.")
            } else if (!running) {
                SettingRow("Couldn't start", subtitle = "Another app is using port ${PhoneLink.PORT}. Restart the car's screen and try again.")
            } else {
                val page = remember(ip, keyVersion) { app.phone.pageLink(ip) }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    QrCode(page)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Txt("Pair your iPhone", T.headline)
                        Txt("1. Scan this code with the iPhone camera and open the page.", T.subhead, t.text2)
                        Txt("2. In Safari, tap Share › Add to Home Screen.", T.subhead, t.text2)
                        Txt("3. Type or paste a place and tap Drive there.", T.subhead, t.text2)
                        Spacer(Modifier.height(4.dp))
                        Txt(page, T.footnote.copy(fontFeatureSettings = "tnum"), t.text2)
                    }
                }
                Separator()
                SettingRow(
                    "Share-menu shortcut",
                    subtitle = "Shortcuts › Get Contents of URL › ${app.phone.shortcutLink(ip)} · POST · Form field \"to\" = Shortcut Input · Show in Share Sheet",
                )
            }
            last?.let { r ->
                Separator()
                SettingRow("Last place", subtitle = r.message) {
                    Txt(if (r.ok) "Sent" else "Didn't open", T.footnote, if (r.ok) t.good else t.destructive)
                }
            }
            Separator()
            ActionRow("Make a new pairing code") {
                Alerts.confirm(
                    "Make a new pairing code?",
                    "Phones paired before stop sending places until they scan the new code.",
                    "New Code",
                ) { app.prefs.newPhoneKey() }
            }
        }
    }
}

@Composable
private fun DirectionsGroup() {
    val s = settings()
    val app = Otility.instance
    val t = LocalTokens.current
    val listening by Directions.listening.collectAsState()
    val helper by HelperConnection.helper.collectAsState()
    val turn by Directions.turn.collectAsState()
    val status = when {
        !s.showDirections -> "Shows the next turn on Overview"
        listening && turn != null -> "Now: ${turn!!.instruction}"
        listening -> "Waiting for ${app.navigator.navLabel()} to start a route"
        helper == null -> "Allow Outo in Android's notification access"
        else -> "Starting…"
    }
    val reading by MapScreenReader.running.collectAsState()
    val cluster by app.cluster.status.collectAsState()
    Group(
        header = "Directions",
        footer = "Outo reads the next turn from the navigation app's notification while it guides and, if you " +
            "allow it, from Waze's or Google Maps' screen. It reads only those apps. The HUD shows the turn the " +
            "driver cluster has, so its arrows go to the cluster too.",
    ) {
        SettingRow("Show the next turn", subtitle = status) {
            Switch(s.showDirections, { on -> app.navigator.setDirections(on) })
        }
        if (s.showDirections) {
            val screenStatus = when {
                !s.readMapScreen -> "Waze and Google Maps: the exact distance, the street, the time and distance left"
                reading && turn?.fromScreen == true -> "Reading ${app.catalog[AppId(turn!!.pkg)]?.label ?: "the map"}"
                reading -> "Waiting for Waze or Google Maps to guide"
                helper == null -> "Allow Outo's map reader in Android's accessibility settings"
                else -> "Starting…"
            }
            SettingRow("Read the map's screen", subtitle = screenStatus) {
                Switch(s.readMapScreen, { on -> app.navigator.setReadScreen(on) })
            }
            // The HUD draws the arrow, the distance and the street from the cluster's values.
            SettingRow("Turn arrows on the HUD", subtitle = if (s.hudTurns) cluster else "The next turn, its distance and street on the windshield display") {
                Switch(s.hudTurns, { on -> app.prefs.update { it.copy(hudTurns = on) } })
            }
            if (s.hudTurns) {
                val mapName = turn?.let { app.catalog[AppId(it.pkg)]?.label } ?: "the map"
                val sent = app.roadLimit.current.takeIf { it > 0 }
                SettingRow(
                    "Speed limit from the map",
                    subtitle = when {
                        !s.hudSpeedLimit -> "The HUD shows the car's own speed limit"
                        !s.readMapScreen -> "Needs \"Read the map's screen\""
                        sent != null && app.roadLimit.fromCamera -> "Sending $sent km/h from the car's camera, until $mapName shows a limit again"
                        sent != null -> "Sending $sent km/h from $mapName"
                        else -> "Waze's or Google Maps' limit for the road. A new sign the car's camera reads takes over until the map shows a limit again."
                    },
                ) {
                    Switch(s.hudSpeedLimit, { on -> app.prefs.update { it.copy(hudSpeedLimit = on) } })
                }
            }
            // With the HUD's arrows on, the cluster has them anyway.
            if (!s.hudTurns) {
                SettingRow(
                    "Turn arrows on the driver cluster",
                    subtitle = if (s.clusterTurns) cluster else "The next turn and the time left behind the wheel",
                ) {
                    Switch(s.clusterTurns, { on -> app.prefs.update { it.copy(clusterTurns = on) } })
                }
            }
        }
    }
}

@Composable
private fun RecentPlacesGroup() {
    val app = Otility.instance
    val t = LocalTokens.current
    val places by app.prefs.places.collectAsState()
    if (places.isEmpty()) return
    Group(header = "Recent places", footer = "Tap a place to drive there again.") {
        places.take(6).forEachIndexed { i, p ->
            if (i > 0) Separator()
            SettingRow(p.title, onClick = { app.navigator.go(p) }) {
                Glyph(Icons.navigation, t.accText, size = 16.dp)
            }
        }
        Separator()
        ActionRow("Clear recent places", destructive = true) { app.prefs.clearPlaces() }
    }
}

@Composable
private fun SoundPage() {
    val s = settings()
    val nav = LocalNav.current
    val car = Garage.byId(s.soundCar) ?: Garage.cars.first()
    Group(footer = "The sound follows the car's speed and pedal through a virtual gearbox. The exterior speaker setting is untested on this car.") {
        Toggle("Engine sound", s.soundOn, "Plays while you drive") { st, v -> st.copy(soundOn = v) }
        Separator()
        SettingRow("Car", subtitle = car.engine, onClick = { nav.sheet = SheetKind.SOUND_STUDIO }) {
            Txt(car.name, T.callout, LocalTokens.current.text2)
            Glyph(Icons.chevronRight, LocalTokens.current.text2, size = 14.dp)
        }
        Separator()
        SettingRow("Mode") {
            Segmented(
                listOf(SoundMode.STREET to "Street", SoundMode.SPORT to "Sport", SoundMode.TRACK to "Track"),
                s.soundMode, { m -> update { it.copy(soundMode = m) } }, Modifier.width(280.dp),
            )
        }
        Separator()
        SettingRow("Volume") {
            Slider(s.soundVolume, { v -> update { it.copy(soundVolume = v) } }, Modifier.width(280.dp))
        }
        Separator()
        Toggle("Only in D, R or S", s.soundOnlyDriving, "Off: it plays in P and N too, where the pedal revs it all the way") { st, v -> st.copy(soundOnlyDriving = v) }
        Separator()
        Toggle("Exterior speaker", s.soundOutside) { st, v -> st.copy(soundOutside = v) }
        PedalRow()
    }
    GlassPillButton("Open Sound Studio", { nav.sheet = SheetKind.SOUND_STUDIO }, icon = Icons.sound, filled = true)
}

/**
 * The pedal as the engine sound reads it, live: to check, parked, that a full press
 * reaches 100%. The car doesn't say its pedal's scale; Outo learns it (see VehicleFeed).
 */
@Composable
private fun PedalRow() {
    val v by Otility.instance.vehicle.data.collectAsState()
    val raw = v.pedalRaw ?: return
    Separator()
    SettingRow(
        "Pedal",
        subtitle = if (v.pedalTop > 0) "The car reports $raw; a full press reads ${maxOf(v.pedalTop, 100)}"
        else "Press it all the way once so Outo learns how far it goes",
    ) {
        Txt("${((v.pedal ?: 0f) * 100).roundToInt()}%", T.callout, LocalTokens.current.text2)
    }
}

@Composable
private fun StartupPage() {
    val s = settings()
    val context = LocalContext.current
    Group(footer = "The car also has to let Outo start by itself. Allow it once in the car's settings.") {
        Toggle("Start with the car", s.startWithCar) { st, v -> st.copy(startWithCar = v) }
        Separator()
        SettingRow("Open on") {
            Segmented(
                listOf(Tab.OVERVIEW to "Overview", Tab.SCREENS to "Screens", Tab.APPS to "Apps", Tab.SETTINGS to "Settings"),
                s.startTab, { v -> update { it.copy(startTab = v) } }, Modifier.width(380.dp),
            )
        }
        Separator()
        Toggle("Restore the last layout", s.restoreLayout) { st, v -> st.copy(restoreLayout = v) }
    }
    Group {
        ActionRow("Allow in the car's settings") { openAutoStart(context) }
    }
}

/** BYD's own "apps that may start by themselves" screen, or Otility's app info as a fallback. */
private fun openAutoStart(context: Context) {
    val byd = Intent("android.intent.action.BYD_APPSTARTMANAGEMENT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val ok = runCatching { context.startActivity(byd) }.isSuccess
    if (!ok) {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun ConnectionPage() {
    val app = Otility.instance
    val t = LocalTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val helper by HelperConnection.helper.collectAsState()
    val status by HelperLauncher.status.collectAsState()
    val snapshot by app.screens.snapshot.collectAsState()
    val v by app.vehicle.data.collectAsState()
    val version = remember(helper) { runCatching { helper?.version() }.getOrNull() }

    Group(
        header = "Helper",
        footer = "The helper runs inside the car as the system's shell user. Outo starts it through the car's own debugging service; the first time, the car asks you to allow it. Nothing leaves the car.",
    ) {
        SettingRow(
            if (helper != null) "Connected" else when (status.stage) {
                HelperLauncher.Stage.CONNECTING -> "Connecting…"
                HelperLauncher.Stage.WAITING_FOR_APPROVAL -> "Waiting for you to allow it"
                HelperLauncher.Stage.STARTING -> "Starting…"
                HelperLauncher.Stage.FAILED -> "Couldn't connect"
                else -> "Not connected"
            },
            subtitle = if (helper != null) "Helper version $version" else status.detail.ifEmpty { null },
        ) {
            Box(Modifier.size(10.dp).background(if (helper != null) t.good else t.text2, CircleShape))
        }
        Separator()
        ActionRow(if (helper != null) "Check Again" else "Connect") {
            scope.launch { HelperLauncher.ensure(context) }
        }
    }
    snapshot?.let { snap ->
        Group(header = "Screens the car reports") {
            snap.displays.forEachIndexed { i, d ->
                if (i > 0) Separator()
                SettingRow("#${d.id}  ${d.name.ifEmpty { "Display" }}", subtitle = "${d.w} × ${d.h} · ${d.dpi} dpi")
            }
        }
        Group(header = "Apps on each display") {
            val rows = snap.tasks.filter { it.type == 1 }
            if (rows.isEmpty()) SettingRow("No apps")
            rows.forEachIndexed { i, task ->
                if (i > 0) Separator()
                SettingRow(task.pkg, subtitle = "Display ${task.displayId}${if (task.visible) "" else " · in the background"}")
            }
        }
    }
    if (!v.isEmpty) {
        Group(header = "Car values (as reported)") {
            v.raw.entries.sortedBy { it.key }.forEachIndexed { i, (k, value) ->
                if (i > 0) Separator()
                SettingRow(k) { Txt(value.toString(), T.callout.copy(fontFeatureSettings = "tnum"), t.text2) }
            }
        }
    }
}

private val integrations = listOf(
    Triple("com.waze", "Waze", "Navigation, on any screen or the cluster"),
    Triple("com.google.android.apps.maps", "Google Maps", "Navigation"),
    Triple("com.spotify.music", "Spotify", "Music"),
    Triple("com.google.android.apps.youtube.music", "YouTube Music", "Music"),
    Triple("com.google.android.youtube", "YouTube", "Video, best on the passenger screen"),
    Triple("app.revanced.android.youtube", "YouTube (ReVanced)", "Video, best on the passenger screen"),
    Triple("com.whatsapp", "WhatsApp", "Messages"),
)

@Composable
private fun IntegrationsPage() {
    val app = Otility.instance
    val t = LocalTokens.current
    val apps by app.catalog.apps.collectAsState()
    Group(header = "Apps", footer = "Outo works with any app the car can open. These are the ones it knows best.") {
        integrations.forEachIndexed { i, (pkg, name, what) ->
            if (i > 0) Separator()
            val installed = apps.any { it.id.pkg == pkg }
            SettingRow(name, subtitle = what) {
                Txt(if (installed) "Installed" else "Not installed", T.footnote, if (installed) t.good else t.text2)
            }
        }
    }
    Group(header = "Your phone") {
        val s = settings()
        val nav = LocalNav.current
        SettingRow(
            "iPhone",
            subtitle = if (s.phoneHandoff) "Sends places to the car · set up in Navigation" else "Off · set up in Navigation",
            onClick = { nav.settingsPage = SettingsPage.NAVIGATION },
        ) {
            Glyph(Icons.chevronRight, t.text2, size = 14.dp)
        }
    }
}

/** New versions from GitHub: the switch, what's on offer, and the button that installs it. */
@Composable
private fun UpdatesGroup() {
    val app = Otility.instance
    val t = LocalTokens.current
    val s = settings()
    val state by app.updater.state.collectAsState()
    val v by app.vehicle.data.collectAsState()
    val moving = (v.speedKmh ?: 0) > 0
    Group(
        header = "Updates",
        footer = "Outo looks for a new version on GitHub (${Updater.REPO}) each time the car starts. " +
            "Updating takes a few seconds, while parked; Outo then opens again by itself.",
    ) {
        Toggle("Check for updates automatically", s.autoUpdate) { st, on -> st.copy(autoUpdate = on) }
        Separator()
        @Composable
        fun UpdateButton(label: String) {
            if (moving) Txt("Update when parked", T.footnote, t.text2)
            else GlassPillButton(label, { app.updater.install() }, filled = true)
        }
        when (val u = state) {
            is UpdateState.Available -> SettingRow("Outo ${u.release.version} is available", subtitle = notesLine(u.release.notes)) {
                UpdateButton("Update")
            }
            is UpdateState.Downloading -> SettingRow(
                "Downloading Outo ${u.release.version}…",
                subtitle = "${(u.progress * 100).roundToInt()}%",
            )
            is UpdateState.Installing -> SettingRow(
                "Installing Outo ${u.release.version}…",
                subtitle = "Outo closes and opens again by itself.",
            )
            is UpdateState.Failed -> {
                val r = u.release
                SettingRow(if (r != null) "Outo ${r.version} wasn't installed" else "Couldn't check for updates", subtitle = u.message) {
                    if (r != null) UpdateButton("Try Again")
                }
            }
            UpdateState.Checking -> SettingRow("Checking…", subtitle = "Version ${app.updater.current}")
            is UpdateState.UpToDate -> SettingRow("Outo is up to date", subtitle = "Version ${app.updater.current}")
            UpdateState.Idle -> SettingRow("Version ${app.updater.current}")
        }
        Separator()
        val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
        ActionRow("Check Now", enabled = !busy) { if (!busy) app.updater.checkNow() }
    }
}

/** A release's notes, shortened to a line or two for its row. */
private fun notesLine(notes: String): String? {
    val lines = notes.lineSequence().map { it.trim().trimStart('-', '*', '#', ' ') }.filter { it.isNotBlank() }.take(3).toList()
    if (lines.isEmpty()) return null
    val joined = lines.joinToString(" · ")
    return if (joined.length > 180) joined.take(177).trimEnd() + "…" else joined
}

@Composable
private fun AboutPage() {
    val t = LocalTokens.current
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_otility), null, Modifier.size(96.dp))
        Spacer(Modifier.width(20.dp))
        Column {
            Txt("Outo", T.title1)
            Txt("Version $version", T.callout, t.text2)
            Txt("Made for the BYD Leopard 5", T.footnote, t.text2)
        }
    }
    UpdatesGroup()
    Group(header = "Open-source parts") {
        SettingRow("Backdrop and Capsule", subtitle = "Liquid Glass for Compose, by Kyant · Apache 2.0")
        Separator()
        SettingRow("dadb", subtitle = "The car's debugging connection, by mobile.dev · Apache 2.0")
        Separator()
        SettingRow("Inter", subtitle = "Typeface by Rasmus Andersson · SIL Open Font License")
    }
    val photos = remember { Garage.cars.mapNotNull { c -> CarPhotos.of(c.id)?.let { c.name to it } } }
    Group(header = "Car photos", footer = "From Wikimedia Commons, under the licences shown; each car cut out from its background, cropped and resized.") {
        photos.forEachIndexed { i, (name, photo) ->
            if (i > 0) Separator()
            SettingRow(name, subtitle = "Photo by ${photo.author} · ${photo.license}")
        }
    }
}
