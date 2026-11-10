package app.leopard.cockpit.ui.overview

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import app.leopard.cockpit.ui.screens.SearchField
import androidx.compose.ui.text.style.TextAlign
import app.leopard.cockpit.ui.scrollEdges
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.leopard.cockpit.Otility
import app.leopard.cockpit.data.Settings
import app.leopard.cockpit.data.SoundMode
import app.leopard.cockpit.engine.EngineSynth
import app.leopard.cockpit.engine.Garage
import app.leopard.cockpit.ui.ActionRow
import app.leopard.cockpit.ui.Capsule
import app.leopard.cockpit.ui.GlassKind
import app.leopard.cockpit.ui.Glyph
import app.leopard.cockpit.ui.Group
import app.leopard.cockpit.ui.Icons
import app.leopard.cockpit.ui.Segmented
import app.leopard.cockpit.ui.Separator
import app.leopard.cockpit.ui.SettingRow
import app.leopard.cockpit.ui.SheetHeader
import app.leopard.cockpit.ui.Slider
import app.leopard.cockpit.ui.Switch
import app.leopard.cockpit.ui.Txt
import app.leopard.cockpit.ui.glass
import app.leopard.cockpit.ui.rounded
import app.leopard.cockpit.ui.theme.LocalTokens
import app.leopard.cockpit.ui.theme.T
import java.text.Normalizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sound Studio: pick a car, shape its sound, and hear it. Hold the pedal to rev
 * in place, or switch to Drive to hear it pull through the gears.
 */
@Composable
fun SoundStudio(onClose: () -> Unit) {
    val app = Otility.instance
    val synth = app.synth
    val t = LocalTokens.current
    val haptic = LocalHapticFeedback.current
    val s by app.prefs.settings.collectAsState()
    var preview by remember { mutableStateOf(EngineSynth.Preview.REV) }
    var holding by remember { mutableStateOf(false) }
    var rpm by remember { mutableFloatStateOf(0f) }
    var gear by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(0f) }

    // The studio plays its own preview while it's open and Otility is on screen. With
    // another app in front (the car's Home, say) it goes quiet, and the engine sound
    // goes back to following the car until Otility comes back.
    LifecycleStartEffect(Unit) {
        app.sound.studioOpen.value = true
        app.sound.apply(app.prefs.settings.value)
        synth.external = false
        synth.pedal = 0f
        synth.start()
        onStopOrDispose {
            synth.pedal = 0f
            synth.stop()
            app.sound.studioOpen.value = false
        }
    }
    LaunchedEffect(s) { app.sound.apply(s) }
    LaunchedEffect(preview) { synth.preview = preview }
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { }
            rpm = synth.rpm
            gear = synth.gear
            speed = synth.speedKmh
        }
    }
    val car = Garage.byId(s.soundCar) ?: Garage.cars.first()

    // While the sound is being shaped, the engine revs and lets go by itself, so each
    // change is heard as it's made (crackle only comes on letting go). It stops a moment
    // after the last change, and leaves the pedal alone while it's held.
    val scope = rememberCoroutineScope()
    val lastShaped = remember { longArrayOf(0L) }
    var audition by remember { mutableStateOf<Job?>(null) }
    fun hear() {
        lastShaped[0] = SystemClock.uptimeMillis()
        if (audition?.isActive == true) return
        audition = scope.launch {
            try {
                while (SystemClock.uptimeMillis() - lastShaped[0] < 1_200) {
                    if (!holding) synth.pedal = 0.8f
                    delay(800)
                    if (!holding) synth.pedal = 0f
                    delay(1_000)
                }
            } finally {
                if (!holding) synth.pedal = 0f
            }
        }
    }
    fun shape(change: (Settings) -> Settings) {
        app.prefs.update(change)
        hear()
    }

    Column(Modifier.fillMaxSize()) {
        SheetHeader("Sound Studio", onClose)
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            // The garage: each car on glass tinted in its own colour, deeper for the one chosen.
            // The search matches the name or the engine ("camaro", "v12", "turbo"), every word.
            val garage = rememberLazyListState()
            var query by remember { mutableStateOf("") }
            val words = plain(query).split(' ').filter { it.isNotBlank() }
            val shown = Garage.cars.filter { c -> plain(c.name + " " + c.engine).let { text -> words.all { it in text } } }
            LaunchedEffect(query) { garage.scrollToItem(0) }
            Column(Modifier.width(320.dp).fillMaxHeight()) {
                SearchField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search cars")
                Spacer(Modifier.height(10.dp))
                if (shown.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().clip(rounded(20.dp)).background(t.inset).padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt("No Results", T.title2)
                            Txt("No car matches “${query.trim()}”.", T.subhead.copy(textAlign = TextAlign.Center), t.text2, maxLines = 3)
                        }
                    }
                } else LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .clip(rounded(20.dp))
                        .background(t.inset)
                        .scrollEdges(garage),
                    state = garage,
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(shown, key = { it.id }) { c ->
                        val on = c.id == car.id
                        val tint = CarPhotos.of(c.id)?.tint ?: t.text2
                        val shape = rounded(14.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(tint.copy(alpha = if (on) 0.55f else 0.24f), tint.copy(alpha = if (on) 0.16f else 0.03f)),
                                    ),
                                )
                                .then(if (on) Modifier.border(1.dp, tint.copy(alpha = 0.7f), shape) else Modifier)
                                .clickable { app.prefs.update { it.copy(soundCar = c.id) } }
                                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CarPicture(c.id, Modifier.size(width = 92.dp, height = 52.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(c.name, T.callout.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                                Txt(c.engine, T.footnote, t.text2, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Gauge(rpm, car.idleRpm.toFloat(), car.redlineRpm.toFloat(), gear, Modifier.size(210.dp))
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Txt(car.name, T.title2, maxLines = 2)
                        Txt(car.engine + " · " + car.horsepower + " hp", T.footnote, t.text2, maxLines = 2)
                        Segmented(
                            listOf(EngineSynth.Preview.REV to "Rev", EngineSynth.Preview.DRIVE to "Drive"),
                            preview, { preview = it }, Modifier.width(240.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val press by animateFloatAsState(if (holding) 0.92f else 1f, spring(0.55f, 600f), label = "pedal")
                            Box(
                                Modifier
                                    .size(96.dp)
                                    .graphicsLayer { scaleX = press; scaleY = press }
                                    .then(if (holding) Modifier.background(t.fill, Capsule) else Modifier.glass(Capsule, GlassKind.Button))
                                    .pointerInput(Unit) {
                                        detectTapGestures(onPress = {
                                            holding = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            synth.pedal = 1f
                                            tryAwaitRelease()
                                            synth.pedal = 0f
                                            holding = false
                                        })
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Glyph(Icons.gauge, if (holding) t.onFill else t.text, size = 26.dp)
                                    Txt("Hold", T.caption, if (holding) t.onFill else t.text2)
                                }
                            }
                            Spacer(Modifier.width(18.dp))
                            if (preview == EngineSynth.Preview.DRIVE) {
                                Column {
                                    Txt("%d km/h".format(speed.toInt()), T.numberM)
                                    Txt("Gear ${gear.coerceAtLeast(1)}", T.footnote, t.text2)
                                }
                            } else {
                                Txt("Hold the pedal to rev.", T.footnote, t.text2)
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    CarPicture(
                        car.id, Modifier.size(width = 180.dp, height = 110.dp),
                        glow = (CarPhotos.of(car.id)?.tint ?: t.text2).copy(alpha = 0.45f),
                    )
                }
                // Back to how the sound started out: the volume is left as it is.
                val fresh = Settings()
                val shaped = s.soundMode != fresh.soundMode || s.soundPitch != fresh.soundPitch ||
                    s.soundRoughness != fresh.soundRoughness || s.soundCrackle != fresh.soundCrackle
                Group(header = "Sound", footer = "Reset leaves the volume as it is.") {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Segmented(
                            listOf(SoundMode.STREET to "Street", SoundMode.SPORT to "Sport", SoundMode.TRACK to "Track"),
                            s.soundMode, { m -> shape { it.copy(soundMode = m) } }, Modifier.fillMaxWidth(),
                        )
                    }
                    Separator()
                    LabeledSlider("Volume", s.soundVolume) { v -> shape { it.copy(soundVolume = v) } }
                    Separator()
                    LabeledSlider("Pitch", (s.soundPitch - 0.7f) / 0.7f) { v -> shape { it.copy(soundPitch = 0.7f + v * 0.7f) } }
                    Separator()
                    LabeledSlider("Roughness", s.soundRoughness) { v -> shape { it.copy(soundRoughness = v) } }
                    Separator()
                    LabeledSlider("Crackle", s.soundCrackle) { v -> shape { it.copy(soundCrackle = v) } }
                    Separator()
                    ActionRow("Reset", enabled = shaped) {
                        shape {
                            it.copy(
                                soundMode = fresh.soundMode, soundPitch = fresh.soundPitch,
                                soundRoughness = fresh.soundRoughness, soundCrackle = fresh.soundCrackle,
                            )
                        }
                    }
                }
                Group(header = "While driving", footer = "The engine sound follows the car's speed and pedal through a virtual gearbox. The exterior speaker setting is untested on this car.") {
                    SettingRow("Engine sound", subtitle = "Plays while you drive") {
                        Switch(s.soundOn, { on -> app.prefs.update { it.copy(soundOn = on) } })
                    }
                    Separator()
                    SettingRow("Only in D, R or S") {
                        Switch(s.soundOnlyDriving, { on -> app.prefs.update { it.copy(soundOnlyDriving = on) } })
                    }
                    Separator()
                    SettingRow("Exterior speaker") {
                        Switch(s.soundOutside, { on -> app.prefs.update { it.copy(soundOutside = on) } })
                    }
                }
            }
        }
    }
}

/** Lower case without accents, so "huracan" finds the Huracán. */
private fun plain(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/**
 * The car, cut out, on a soft oval glow so that even a black car stands out from the
 * glass: light in dark mode, a faint shade in light mode, or [glow] when given.
 */
@Composable
private fun CarPicture(id: String, modifier: Modifier, glow: Color? = null) {
    val t = LocalTokens.current
    val photo = CarPhotos.of(id)
    if (photo == null) { Box(modifier); return }
    val halo = glow ?: if (t.dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.08f)
    Box(
        modifier.drawBehind {
            scale(scaleX = 1f, scaleY = 0.55f) {
                drawCircle(Brush.radialGradient(listOf(halo, Color.Transparent), center, size.width / 2f), size.width / 2f, center)
            }
        },
    ) {
        Image(painterResource(photo.res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, T.callout, modifier = Modifier.width(110.dp))
        Slider(value.coerceIn(0f, 1f), onChange, Modifier.weight(1f))
    }
}

/** A rev counter: a 270° arc with the red zone at the top end. */
@Composable
private fun Gauge(rpm: Float, idle: Float, red: Float, gear: Int, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val max = red * 1.08f
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            val start = 135f
            val sweep = 270f
            drawArc(t.track, start, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            val redFrom = red / max
            drawArc(t.alert.copy(alpha = 0.55f), start + sweep * redFrom, sweep * (1f - redFrom), false, Offset(inset, inset), arc, style = Stroke(stroke))
            val f = (rpm / max).coerceIn(0f, 1f)
            drawArc(if (rpm >= red * 0.97f) t.alert else t.acc, start, sweep * f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("%,d".format(rpm.toInt().coerceAtLeast(0)), T.numberL)
            Txt("rpm", T.footnote, t.text2)
        }
    }
}
