package app.leopard.cockpit.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Our own engine-sound synthesizer.
 *
 * Each cylinder firing is a short pressure pulse. The pulses go through two
 * resonators per exhaust bank (the pipes) and a muffler filter. Uneven firing
 * (a cross-plane V8, a five) gives the burble; dense even firing (a V10 or V12)
 * becomes a scream at high revs. On top: intake noise and howl, turbo whine,
 * whoosh and blow-off, and lift-off crackle. Each car's [Tone] sets how deep,
 * hard, lumpy and quick it is. Held at the redline, it holds its note.
 *
 * Input comes either from the car (speed, pedal, gear) or from the preview's
 * virtual pedal.
 */
class EngineSynth {

    enum class Preview { REV, DRIVE }

    // ---- Controls (written from the UI thread, read by the audio thread) ----
    @Volatile var spec: EngineSpec = Garage.cars.first()
        set(v) { field = v; rebuild = true }
    @Volatile var voice: Voice = Voice()
        set(v) { field = v; rebuild = true }
    /** 0 = street (quiet, muffled) … 2 = track (loud, open). */
    @Volatile var mode: Int = 1
        set(v) { field = v; rebuild = true }
    @Volatile var volume: Float = 0.6f
    @Volatile var pedal: Float = 0f
    @Volatile var preview: Preview = Preview.REV
    /** When set, the car drives the sound: rpm and pedal come from it. */
    @Volatile var external: Boolean = false
    @Volatile var externalRpm: Float = 0f

    /** Live values for the UI's gauge. */
    @Volatile var rpm: Float = 0f; private set
    @Volatile var gear: Int = 1; private set
    @Volatile var speedKmh: Float = 0f; private set

    private val sr = 48_000
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var rebuild = true
    /** When Android last refused an audio track ([SystemClock.elapsedRealtime]). */
    @Volatile private var refusedAt = -60_000L

    val isPlaying get() = running

    fun start() {
        if (running) return
        // Asked again with each reading of the car (ten a second): after a refusal, wait
        // a while before asking Android again.
        if (SystemClock.elapsedRealtime() - refusedAt < 5_000) return
        // The car's audio may refuse a track, or a low-latency one: then try a plain one,
        // and failing that stay quiet rather than take Otility down with it.
        val t = newTrack(lowLatency = true) ?: newTrack(lowLatency = false)
        if (t == null) {
            refusedAt = SystemClock.elapsedRealtime()
            return
        }
        track = t
        running = true
        rpm = spec.idleRpm.toFloat()
        thread = Thread({ loop(t) }, "engine-synth").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** A playing track, or null if Android won't make or start one. */
    private fun newTrack(lowLatency: Boolean): AudioTrack? {
        var t: AudioTrack? = null
        return try {
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setBufferSizeInBytes(max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply { if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
                .build()
            check(t.state == AudioTrack.STATE_INITIALIZED) { "track not initialized" }
            t.play()
            t
        } catch (e: Exception) {
            Log.w("Otility", "engine sound: no audio track (low latency: $lowLatency)", e)
            runCatching { t?.release() }
            null
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track?.run { runCatching { stop() }; release() }
        track = null
    }

    // ---- DSP state -----------------------------------------------------------

    private class Biquad {
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        fun bandpass(sr: Int, f: Float, q: Float) = set(sr, f, q) { w, alpha ->
            floatArrayOf(alpha, 0f, -alpha)
        }

        fun lowpass(sr: Int, f: Float, q: Float) = set(sr, f, q) { w, _ ->
            val c = cos(w).toFloat()
            floatArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2)
        }

        fun highpass(sr: Int, f: Float, q: Float) = set(sr, f, q) { w, _ ->
            val c = cos(w).toFloat()
            floatArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2)
        }

        private inline fun set(sr: Int, f: Float, q: Float, num: (Double, Float) -> FloatArray) {
            val w = 2 * PI * min(f, sr * 0.45f) / sr
            val alpha = (sin(w) / (2 * q)).toFloat()
            val a0 = 1 + alpha
            val n = num(w, alpha)
            b0 = n[0] / a0; b1 = n[1] / a0; b2 = n[2] / a0
            a1 = (-2 * cos(w)).toFloat() / a0; a2 = (1 - alpha) / a0
        }

        fun run(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private val rnd = Random(7)
    private val bankRes1 = Array(2) { Biquad() }
    private val bankRes2 = Array(2) { Biquad() }
    private val muffler = Biquad()
    private val dcBlock = Biquad()
    private val intakeBp = Biquad()
    private val howlBp = Biquad()
    private val popLp = Biquad()
    private val blowHp = Biquad()
    private val whooshBp = Biquad()
    private val subLp = Biquad()

    /** Firing events within one cycle: position 0..1, which exhaust bank, and how strong. */
    private var events = FloatArray(0)
    /** This cycle's firing positions: [events] nudged a little at random on a rough engine. */
    private var eventsNow = FloatArray(0)
    private var eventBank = IntArray(0)
    private var eventGain = FloatArray(0)
    private var cycleRevs = 2f
    private var pulseDecay = 0.004f

    private var cyclePhase = 0.0
    private var nextEvent = 0
    private val pulseEnv = FloatArray(2)
    private var popEnv = 0f
    private var blowEnv = 0f
    private var boost = 0f
    private var turboPhase = 0.0
    private var liftTimer = 0f
    /** The pedal's recent high point, to tell when it's let go after being pressed. */
    private var recentPedal = 0f
    private var popPhase = 0.0
    private var speedMs = 0f
    private var smoothPedal = 0f
    /** A slow, small wander in the revs, so a held note isn't machine-steady. */
    private var wander = 0f
    /** A big cam's lope: the slow hunt of the idle, and how hard this cycle fires. */
    private var lopePhase = 0.0
    private var lopeCycle = 0
    private var cycleGain = 1f

    private fun rebuildFilters() {
        val s = spec
        val v = voice
        val tone = s.tone
        val body = tone.body * v.pitch
        // A V's two sides have their own pipes and sound a little apart, more so the
        // lumpier it is; an inline engine or a rotary has one.
        val split = if (s.layout == Layout.INLINE || s.layout == Layout.ROTARY) 1f else 1.03f + 0.12f * tone.lump
        for (b in 0..1) {
            val f = if (b == 0) body else body * split
            bankRes1[b].bandpass(sr, f, 1.6f)
            bankRes2[b].bandpass(sr, f * 2.4f, 2.4f)
        }
        val open = tone.muffler * when (mode) { 0 -> 0.55f; 1 -> 1f; else -> 1.6f }
        muffler.lowpass(sr, open, 0.75f)
        dcBlock.highpass(sr, 25f, 0.7f)
        intakeBp.bandpass(sr, 700f + 500f * v.pitch, 0.8f)
        popLp.lowpass(sr, 2400f, 0.9f)
        blowHp.highpass(sr, 1800f, 0.7f)
        whooshBp.bandpass(sr, 4200f, 0.9f)
        subLp.lowpass(sr, 160f, 0.8f)

        cycleRevs = if (s.layout == Layout.ROTARY) 1f else 2f
        // Sharp pulses for a hard, high-revving engine; soft ones for a big, lazy one.
        pulseDecay = 0.006f - 0.0052f * tone.bite
        val n = s.cylinders
        // Which bank each firing exhausts into, in firing order.
        val banks: IntArray = when (s.layout) {
            Layout.V_CROSS_PLANE -> intArrayOf(0, 1, 0, 0, 1, 0, 1, 1) // R L R R L R L L
            Layout.INLINE, Layout.ROTARY -> IntArray(n) { 0 }
            else -> IntArray(n) { it % 2 }
        }
        // Uneven firing: each firing of the cycle a little stronger or weaker, earlier or
        // later, the same way every cycle. That repeat, at half the crank speed, is what
        // the ear hears as a burble.
        val (amp, shift) = unevenness(s)
        events = FloatArray(n) { ((it + tone.lump * 0.22f * shift[it]) / n).coerceIn(0f, 0.999f) }
        eventGain = FloatArray(n) { (1f + tone.lump * amp[it]).coerceAtLeast(0.1f) }
        eventBank = IntArray(n) { banks[it % banks.size] }
        eventsNow = events.copyOf()
        // Carry on from where the cycle is: starting its events over would fire every
        // one already past at once, a crack on each move of a slider.
        nextEvent = events.indexOfFirst { it >= cyclePhase }.let { if (it < 0) n else it }
        rebuild = false
    }

    /** Per firing, how much stronger (amp) and how much later (shift) it is, both about -1..1. */
    private fun unevenness(s: EngineSpec): Pair<FloatArray, FloatArray> {
        val n = s.cylinders
        val (amp, shift) = when {
            s.layout == Layout.V_CROSS_PLANE && n == 8 -> floatArrayOf(0.9f, -0.6f, 0.5f, -0.9f, 0.7f, -0.3f, 0.2f, -0.5f) to
                floatArrayOf(0f, 0.6f, -0.4f, 0.3f, -0.6f, 0.5f, -0.2f, -0.2f)
            s.layout == Layout.INLINE && n == 5 -> floatArrayOf(0.7f, -0.5f, 0.2f, -0.8f, 0.4f) to
                floatArrayOf(0f, 0.5f, -0.5f, 0.3f, -0.3f)
            // A boxer four with unequal-length headers: its pulses bunch up in pairs, the rumble.
            s.layout == Layout.FLAT && n == 4 -> floatArrayOf(0.7f, -0.5f, 0.6f, -0.8f) to
                floatArrayOf(0f, -0.7f, 0.2f, -0.6f)
            else -> {
                val r = Random(s.id.hashCode())
                FloatArray(n) { r.nextFloat() * 2f - 1f } to FloatArray(n) { r.nextFloat() * 2f - 1f }
            }
        }
        val mean = amp.average().toFloat()
        return FloatArray(n) { amp[it] - mean } to shift
    }

    // ---- Audio loop ----------------------------------------------------------

    private fun loop(t: AudioTrack) {
        val buf = ShortArray(BLOCK)
        while (running) {
            next(buf)
            // A track Android has dropped answers at once with an error: wait a little
            // instead of spinning a core.
            if (t.write(buf, 0, BLOCK) < 0) Thread.sleep(20)
        }
    }

    /** The next [BLOCK] samples (48 kHz mono), with no track: the audio loop, and tests. */
    internal fun next(buf: ShortArray) {
        if (rebuild) rebuildFilters()
        control(BLOCK.toFloat() / sr)
        render(buf)
    }

    companion object {
        /** Samples per pass of the audio loop (about 11 ms). */
        const val BLOCK = 512
    }

    /** Updates rpm, gear and boost once per buffer. */
    private fun control(dt: Float) {
        val s = spec
        val idle = s.idleRpm.toFloat()
        val red = s.redlineRpm.toFloat()
        smoothPedal += (pedal - smoothPedal) * min(1f, dt * 12f)
        val p = smoothPedal

        if (external) {
            rpm += (max(idle, externalRpm) - rpm) * min(1f, dt * 10f)
        } else when (preview) {
            Preview.REV -> {
                // Up to the redline and held there for as long as the pedal is: the
                // engine keeps singing its top note rather than cutting out.
                val target = idle + p * (red - idle)
                val span = red - idle
                val rate = if (target > rpm) span / s.tone.revUp else span / (s.tone.revUp * 1.8f)
                rpm = approach(rpm, target, rate * dt)
                speedMs = 0f; gear = 0
            }
            Preview.DRIVE -> drive(dt, p, idle, red)
        }

        rpm = rpm.coerceAtMost(red)
        wander += ((rnd.nextFloat() * 2f - 1f) - wander) * min(1f, dt * 6f)
        lopePhase = (lopePhase + dt * 2 * PI * 1.3) % (2 * PI)

        // Lift-off: crackle on the overrun, and a turbo's blow-off. Read from the pedal as
        // it is, not the smoothed one (which never drops fast enough to count): let go
        // within a moment of having it down.
        val raw = pedal
        recentPedal = max(raw, recentPedal - dt * 1.5f)
        if (recentPedal > 0.5f && raw < 0.15f && rpm > 2500) {
            liftTimer = 1.6f
            if (boost > 0.45f && s.induction.turbo) blowEnv = boost
            recentPedal = raw
        }
        liftTimer = max(0f, liftTimer - dt)

        val rpmFrac = ((rpm - idle) / (red - idle)).coerceIn(0f, 1f)
        // A turbo spools up with load; a supercharger turns with the crank, so it's there at once.
        val boostTarget = when {
            s.induction == Induction.NATURAL -> 0f
            s.induction.turbo -> (p * (0.25f + rpmFrac)).coerceIn(0f, 1f)
            else -> rpmFrac * (0.35f + 0.65f * p)
        }
        val spool = if (!s.induction.turbo) 10f else if (boostTarget > boost) 1.6f else 4f
        boost += (boostTarget - boost) * min(1f, dt * spool)
        speedKmh = speedMs * 3.6f
        // The intake's roar climbs with the revs.
        howlBp.bandpass(sr, (450f + 2600f * rpmFrac) * voice.pitch, 4f)
    }

    private fun drive(dt: Float, p: Float, idle: Float, red: Float) {
        val s = spec
        val ratios = gearRatios(s.gears)
        if (gear < 1) gear = 1
        val final = 3.7f
        val wheel = 0.33f
        val power = s.horsepower * 745.7f
        val mass = 1700f
        val wheelRpm = speedMs / (2 * PI.toFloat() * wheel) * 60f
        var engineRpm = wheelRpm * ratios[gear - 1] * final
        // Clutch slip from a standstill.
        if (engineRpm < idle + 900 * p) engineRpm = idle + 900 * p * (1 - min(1f, speedMs / 3f))
        val torqueCurve = 0.55f + 0.45f * sin(PI.toFloat() * (engineRpm / red).coerceIn(0.05f, 1f))
        val drivePower = if (rpm >= red) 0f else p * power * torqueCurve
        val force = drivePower / max(speedMs, 2.5f)
        val drag = 0.42f * speedMs * speedMs + 180f + (if (p < 0.05f) 900f else 0f)
        speedMs = max(0f, speedMs + (force - drag) / mass * dt)
        // Shifts.
        if (engineRpm > red * 0.97f && gear < s.gears) gear++
        else if (gear > 1 && engineRpm < idle * 1.8f) gear--
        rpm = approach(rpm, engineRpm.coerceIn(idle, red), (red - idle) * 3f * dt)
    }

    private fun gearRatios(n: Int): FloatArray {
        val first = 3.6f; val last = 0.72f
        return FloatArray(n) { i -> first * (last / first).pow(i / (n - 1f)) }
    }

    private fun approach(x: Float, target: Float, step: Float) =
        if (x < target) min(target, x + step) else max(target, x - step)

    private fun render(out: ShortArray) {
        val s = spec
        val v = voice
        val tone = s.tone
        val idle = s.idleRpm.toFloat()
        val red = s.redlineRpm.toFloat()
        val rpmFrac = ((rpm - idle) / (red - idle)).coerceIn(0f, 1f)
        val p = smoothPedal
        // Pitch moves the whole engine up or down. What's heard also wanders a hair
        // around the revs, more on a rough engine.
        // A big cam lopes at idle: the revs hunt and the cycles chop, strong, weak, strong.
        // It smooths out as the revs come up and the cam comes on.
        val lopeAmt = tone.lope * (1f - rpmFrac * 4f).coerceIn(0f, 1f)
        val hunt = 1f + lopeAmt * 0.05f * sin(lopePhase).toFloat()
        val heard = rpm * v.pitch * hunt * (1f + wander * (0.003f + 0.012f * v.roughness))
        val cyclesPerSec = heard / 60.0 / cycleRevs
        val inc = cyclesPerSec / sr
        val decay = exp(-1.0 / (pulseDecay * sr)).toFloat()
        val load = (0.35f + 0.65f * p) * (0.55f + 0.6f * rpmFrac)
        val modeGain = when (mode) { 0 -> 0.55f; 1 -> 0.8f; else -> 1f }
        val crackleChance = v.crackle * (0.015f + 0.03f * mode)
        val popDecay = exp(-1.0 / (0.014 * sr)).toFloat()
        val popInc = 70.0 / sr
        // Squared, so the default (0.3) stays close to smooth and the top end is plainly rough.
        val slotJitter = (v.roughness * v.roughness * 0.12f + lopeAmt * 0.05f) / max(1, events.size)
        // The firing pulses' own bass, under the pipes' note: the chest thump of a big V8.
        val subAmt = tone.sub * 3f
        val resonance = 1.1f - 0.5f * tone.bite
        val upper = 0.25f + 0.5f * tone.bite
        val direct = 0.35f * tone.bite * tone.bite
        val intakeAmt = (if (s.induction == Induction.NATURAL) 0.05f else 0.025f) * p * (0.3f + rpmFrac)
        val howlAmt = tone.howl * 0.9f * p * rpmFrac * rpmFrac
        // A turbo's whine climbs with its boost; a supercharger's is geared to the crank.
        val supercharged = s.induction == Induction.SUPERCHARGED
        val turboFreq = if (supercharged) heard / 60f * 14f else (2500f + 6500f * boost) * v.pitch
        val turboInc = turboFreq / sr
        val whineAmt = if (supercharged) 0.07f * tone.whine * boost else 0.05f * tone.whine * boost * boost
        val whooshAmt = if (supercharged) 0f else 0.12f * tone.whine * boost
        val vol = volume * 0.9f * tone.gain
        // How hard the exhaust is pushed: a muscle car's open pipes clip into a loud, gritty roar.
        val drive = ((1.2f + 0.8f * mode) * tone.drive).coerceAtMost(5f)
        val norm = tanh(drive)

        for (i in out.indices) {
            cyclePhase += inc
            if (cyclePhase >= 1.0) {
                cyclePhase -= 1.0
                nextEvent = 0
                // A rough engine doesn't fire like clockwork: each firing a touch early or late.
                for (k in events.indices) eventsNow[k] = (events[k] + slotJitter * (rnd.nextFloat() * 2f - 1f)).coerceIn(0f, 0.999f)
                cycleGain = if (lopeAmt > 0f) {
                    lopeCycle++
                    (1f + lopeAmt * (0.6f * sin(lopeCycle * 2.3f) + 0.5f * (rnd.nextFloat() - 0.5f))).coerceAtLeast(0.25f)
                } else 1f
            }
            while (nextEvent < eventsNow.size && cyclePhase >= eventsNow[nextEvent]) {
                val bank = eventBank[nextEvent]
                // ... and unevenly strong, now and then one barely catching.
                var jitter = 1f + v.roughness * (rnd.nextFloat() - 0.5f) * 1.4f
                if (rnd.nextFloat() < v.roughness * 0.05f + lopeAmt * 0.08f) jitter *= 0.3f
                pulseEnv[bank] += load * eventGain[nextEvent] * jitter * cycleGain
                if (liftTimer > 0f && rpm > 2200 && rnd.nextFloat() < crackleChance) {
                    popEnv = 0.6f + 0.6f * rnd.nextFloat()
                    popPhase = 0.0
                }
                nextEvent++
            }

            var x = 0f
            var raw = 0f
            for (b in 0..1) {
                val e = pulseEnv[b]
                // Excitation: the pulse plus a little noise for texture.
                val exc = e * (0.8f + 0.4f * (rnd.nextFloat() - 0.5f) * v.roughness)
                x += bankRes1[b].run(exc) * resonance + bankRes2[b].run(exc) * upper
                raw += exc
                pulseEnv[b] = e * decay
            }
            // The firing itself, straight through: what carries a hard engine's pitch.
            x += raw * direct
            x = muffler.run(x) * 3.2f
            if (subAmt > 0f) x += subLp.run(raw) * subAmt

            // Intake: a little hiss, and on a howling engine a roar that climbs with the revs.
            x += intakeBp.run(rnd.nextFloat() - 0.5f) * intakeAmt
            if (howlAmt > 0f) x += howlBp.run(rnd.nextFloat() - 0.5f) * howlAmt * (0.6f + 0.4f * min(1f, raw))

            // Turbo whine, whoosh and blow-off.
            if (tone.whine > 0f) {
                turboPhase += turboInc
                if (turboPhase > 1.0) turboPhase -= 1.0
                x += sin(2 * PI * turboPhase).toFloat() * whineAmt
                if (supercharged) x += sin(4 * PI * turboPhase).toFloat() * whineAmt * 0.45f
                x += whooshBp.run(rnd.nextFloat() - 0.5f) * whooshAmt
                if (blowEnv > 0.001f) {
                    x += blowHp.run(rnd.nextFloat() - 0.5f) * 0.25f * blowEnv * tone.whine
                    blowEnv *= 0.99985f
                }
            }

            // Crackle: a crack of burning fuel in the exhaust, with a thump under it.
            if (popEnv > 0.001f) {
                popPhase += popInc
                if (popPhase > 1.0) popPhase -= 1.0
                x += popLp.run((rnd.nextFloat() - 0.5f) * 2f) * popEnv * 2.2f + sin(2 * PI * popPhase).toFloat() * popEnv * 1.2f
                popEnv *= popDecay
            }

            x = dcBlock.run(x) * modeGain
            val y = tanh(x * drive) / norm * vol
            out[i] = (y.coerceIn(-1f, 1f) * 32000).toInt().toShort()
        }
    }
}
