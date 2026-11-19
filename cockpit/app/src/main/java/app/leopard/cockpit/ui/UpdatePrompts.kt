package app.leopard.cockpit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.leopard.cockpit.Otility
import app.leopard.cockpit.update.Release
import app.leopard.cockpit.update.UpdateState
import kotlinx.coroutines.delay

/**
 * The update pop-ups ("Ask with pop-ups" in Settings › Updates): when the check Outo
 * makes by itself finds a new version, an alert offers to download it; once it's
 * downloaded, another offers to install it. Both wait until the car is parked and no
 * other alert is up. "Later" leaves the version in Settings until the car starts
 * again, and asks once more then.
 */
@Composable
fun UpdatePrompts() {
    val app = Otility.instance
    val settings by app.prefs.settings.collectAsState()
    val state by app.updater.state.collectAsState()
    val vehicle by app.vehicle.data.collectAsState()
    val alert by Alerts.current.collectAsState()
    val parked = (vehicle.speedKmh ?: 0) <= 0
    val on = settings.autoUpdate && settings.updatePrompts
    LaunchedEffect(state, parked, alert, on) {
        if (!on || !parked || alert != null) return@LaunchedEffect
        // Not the moment something else shows or the car comes to a stop.
        delay(SETTLE_MS)
        val updater = app.updater
        when (val s = state) {
            is UpdateState.Available -> {
                val r = s.release
                if (!s.automatic || !updater.askedAvailable.add(r.version)) return@LaunchedEffect
                Alerts.show(
                    Alert(
                        "Outo ${r.version} Is Available",
                        listOfNotNull(whatsNew(r.notes), sizeLine(r)).joinToString("\n\n"),
                        listOf(
                            AlertButton("Later", Role.CANCEL),
                            AlertButton("Download") {
                                updater.promptedDownload = r.version
                                updater.download()
                            },
                        ),
                    ),
                )
            }
            is UpdateState.Ready -> {
                val r = s.release
                if (!updater.askedReady.add(r.version)) return@LaunchedEffect
                Alerts.show(
                    Alert(
                        "Install Outo ${r.version}?",
                        "It's downloaded. Outo closes for a few seconds while it installs, then opens again by itself.",
                        listOf(AlertButton("Later", Role.CANCEL), AlertButton("Install") { updater.install() }),
                    ),
                )
            }
            is UpdateState.Failed -> {
                val r = s.release ?: return@LaunchedEffect
                // Only a download a pop-up started; Settings shows its own.
                if (updater.promptedDownload != r.version) return@LaunchedEffect
                updater.promptedDownload = null
                Alerts.show(
                    Alert(
                        "Outo ${r.version} Didn't Download",
                        s.message,
                        listOf(
                            AlertButton("Close", Role.CANCEL),
                            AlertButton("Try Again") {
                                updater.promptedDownload = r.version
                                updater.download()
                            },
                        ),
                    ),
                )
            }
            else -> Unit
        }
    }
}

private const val SETTLE_MS = 1_500L

/** The release's headings ("**Light mode:** …" → "Light mode"), or its first line, for the alert. */
private fun whatsNew(notes: String): String? {
    val heads = Regex("""\*\*(.+?)\*\*""").findAll(notes).map { it.groupValues[1].trim().trimEnd(':', '.') }
        .filter { it.isNotBlank() }.take(4).toList()
    if (heads.isNotEmpty()) return "New: " + heads.joinToString(" · ")
    return notes.lineSequence().map { it.trim().trimStart('-', '*', '#', ' ') }.firstOrNull { it.isNotBlank() }
        ?.let { if (it.length > 140) it.take(139) + "…" else it }
}

private fun sizeLine(r: Release): String =
    if (r.size > 0) "Download it now? (%.0f MB)".format(r.size / 1_048_576.0) else "Download it now?"
