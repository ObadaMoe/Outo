package app.leopard.cockpit.engine

import app.leopard.cockpit.car.CarBridge
import app.leopard.cockpit.data.Prefs
import app.leopard.cockpit.data.Settings
import app.leopard.cockpit.data.VehicleData
import app.leopard.cockpit.data.VehicleFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow

/**
 * Connects the engine sound to the settings and to the car: while driving, the
 * car's speed and pedal turn into engine revs through a virtual gearbox. The
 * Sound Studio takes over the synth while it's open.
 */
class SoundDriver(
    private val synth: EngineSynth,
    private val prefs: Prefs,
    private val feed: VehicleFeed,
    private val bridge: CarBridge,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The Sound Studio is open and playing its own preview. */
    val studioOpen = MutableStateFlow(false)

    private var live = false
    private var outsideOn = false
    private var gear = 1
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(prefs.settings, feed.data, studioOpen) { s, v, studio -> Triple(s, v, studio) }
                .collect { (s, v, studio) -> update(s, v, studio) }
        }
    }

    /** Applies the chosen car and voice to the synth. */
    fun apply(s: Settings) {
        val spec = Garage.byId(s.soundCar) ?: Garage.cars.first()
        if (synth.spec.id != spec.id) synth.spec = spec
        val voice = Voice(s.soundPitch, s.soundRoughness, s.soundCrackle)
        if (synth.voice != voice) synth.voice = voice
        if (synth.mode != s.soundMode.ordinal) synth.mode = s.soundMode.ordinal
        synth.volume = s.soundVolume
    }

    private fun update(s: Settings, v: VehicleData, studio: Boolean) {
        apply(s)
        feed.fast = s.soundOn && bridge.connected
        val shouldPlay = s.soundOn && bridge.connected && !v.isEmpty && (!s.soundOnlyDriving || v.moving) && !studio
        if (shouldPlay) {
            synth.external = true
            val pedal = v.pedal ?: 0f
            synth.pedal = pedal
            synth.externalRpm = virtualRpm(synth.spec, (v.speedKmh ?: 0).toFloat(), pedal, parked = v.gear == "P" || v.gear == "N")
            if (!synth.isPlaying) synth.start()
            live = true
        } else if (live) {
            live = false
            if (!studio) synth.stop()
        }
        val wantOutside = live && s.soundOutside
        // Only with the helper there to set it: while it's gone the car keeps what it
        // was last set to, and it's set again once the helper is back.
        if (wantOutside != outsideOn && bridge.connected) {
            outsideOn = wantOutside
            bridge.exteriorSpeaker(wantOutside)
        }
    }

    /**
     * Engine revs for a road speed, with gear changes that follow the pedal. [parked]:
     * in P or N, where the pedal revs it all the way, as a real engine out of gear does.
     */
    private fun virtualRpm(spec: EngineSpec, speedKmh: Float, pedal: Float, parked: Boolean): Float {
        val idle = spec.idleRpm.toFloat()
        val red = spec.redlineRpm.toFloat()
        if (speedKmh < 3f) {
            gear = 1
            // In gear and still (on the brake, or just pulling away): a little, as a clutch would.
            return idle + pedal * (red - idle) * (if (parked) 0.97f else 0.35f)
        }
        val n = spec.gears
        fun ratio(g: Int) = 3.6f * (0.72f / 3.6f).pow((g - 1) / (n - 1f))
        val wheelRpm = speedKmh / 3.6f / (2f * PI.toFloat() * 0.33f) * 60f
        fun rpmIn(g: Int) = wheelRpm * ratio(g) * 3.7f
        val up = idle + (red - idle) * (0.35f + 0.6f * pedal)
        val down = idle * 1.5f + (red - idle) * 0.15f * pedal
        gear = gear.coerceIn(1, n)
        while (rpmIn(gear) > up && gear < n) gear++
        while (rpmIn(gear) < down && gear > 1) gear--
        return rpmIn(gear).coerceIn(idle, red)
    }
}
