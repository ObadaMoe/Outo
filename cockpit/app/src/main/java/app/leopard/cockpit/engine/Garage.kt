package app.leopard.cockpit.engine

/**
 * The engine-sound garage.
 *
 * Each car is described by public facts about its engine (layout, size, induction,
 * power, idle and redline, gearbox). The synthesizer turns these into sound; the
 * tuning that makes each one sound like itself is ours and lives in its [Tone].
 * Recordings can later replace or blend with the synth per car.
 *
 * Figures are from manufacturer specs as commonly published. Fields marked
 * "approx." are typical values, not official, and are worth checking when tuning.
 */
data class EngineSpec(
    val id: String,
    val name: String,
    /** Short label for the card, e.g. "V6 · 3.8 L twin-turbo". */
    val engine: String,
    val layout: Layout,
    val cylinders: Int,
    val displacementL: Float,
    val induction: Induction,
    val horsepower: Int,
    /** approx. */
    val idleRpm: Int,
    val redlineRpm: Int,
    val gears: Int,
    /** What makes this engine sound like itself (our own sound design). */
    val tone: Tone,
)

enum class Layout {
    INLINE,
    V,
    /** Flat-plane crank V8: high-pitched, even scream (Ferrari). */
    V_FLAT_PLANE,
    /** Cross-plane crank V8: the uneven "burble" (Mustang, AMG). */
    V_CROSS_PLANE,
    FLAT,
    W,
    ROTARY,
}

enum class Induction(val turbo: Boolean) {
    NATURAL(false), TURBO(true), TWIN_TURBO(true), QUAD_TURBO(true),
    /** Belt-driven: its whine is geared to the crank, and there's no blow-off. */
    SUPERCHARGED(false),
}

/** The driver's own adjustments from the Sound Studio, on top of the car's [Tone]. */
data class Voice(
    val pitch: Float = 1f,
    val roughness: Float = 0.3f,
    val crackle: Float = 0.3f,
)

/**
 * One engine's character, our own sound design from what the real engine is like.
 * Only the facts in [EngineSpec] come from the maker; these are by ear.
 */
data class Tone(
    /** The exhaust's main resonance, Hz: low and boomy for a big V8, high for a screaming V12. */
    val body: Float,
    /** How sharp each firing is, 0 soft and round … 1 hard and raspy. */
    val bite: Float,
    /** Uneven firing, heard as a cross-plane V8's burble or a five's warble: 0 smooth … 1 lumpy. */
    val lump: Float,
    /** Seconds from idle to the redline with the pedal held down (light, high-revving engines are quick). */
    val revUp: Float,
    /** Turbo (or supercharger) whine and whoosh, 0 … 1. */
    val whine: Float = 0f,
    /** Intake howl at high revs, 0 … 1. */
    val howl: Float = 0f,
    /** How hard the exhaust is pushed: 1 is stock; more is a louder, grittier, open-pipe roar. */
    val drive: Float = 1f,
    /** A big cam's lopey idle, the muscle-car chop: 0 … 1. Gone once the revs are up. */
    val lope: Float = 0f,
    /** Extra low end, the chest thump under the note: 0 … 1. */
    val sub: Float = 0f,
    /** How open the exhaust is, Hz at Sport (Street is quieter, Track more open). */
    val muffler: Float = 2600f,
    /** Loudness, so every car plays about as loud (the muscle cars a good deal louder, on purpose). */
    val gain: Float = 1f,
)

object Garage {
    val cars: List<EngineSpec> = listOf(
        EngineSpec(
            "nissan_gtr", "Nissan GT-R", "V6 · 3.8 L twin-turbo",
            Layout.V, 6, 3.8f, Induction.TWIN_TURBO, 565,
            idleRpm = 750, redlineRpm = 7_100, gears = 6,
            tone = Tone(body = 150f, bite = 0.35f, lump = 0.16f, revUp = 0.9f, whine = 0.8f, howl = 0.1f, muffler = 1900f, gain = 0.93f),
        ),
        EngineSpec(
            "lamborghini_huracan", "Lamborghini Huracán", "V10 · 5.2 L",
            Layout.V, 10, 5.2f, Induction.NATURAL, 631,
            idleRpm = 1_000, redlineRpm = 8_500, gears = 7,
            tone = Tone(body = 320f, bite = 0.85f, lump = 0.06f, revUp = 0.55f, howl = 0.6f, muffler = 4200f, gain = 0.72f),
        ),
        EngineSpec(
            "lamborghini_aventador_svj", "Lamborghini Aventador SVJ", "V12 · 6.5 L",
            Layout.V, 12, 6.5f, Induction.NATURAL, 759,
            idleRpm = 1_000, redlineRpm = 8_700, gears = 7,
            tone = Tone(body = 380f, bite = 0.9f, lump = 0.03f, revUp = 0.6f, howl = 0.7f, muffler = 4800f, gain = 0.68f),
        ),
        EngineSpec(
            "ferrari_458", "Ferrari 458 Italia", "V8 · 4.5 L flat-plane",
            Layout.V_FLAT_PLANE, 8, 4.5f, Induction.NATURAL, 562,
            idleRpm = 1_000, redlineRpm = 9_000, gears = 7,
            tone = Tone(body = 300f, bite = 0.9f, lump = 0.04f, revUp = 0.5f, howl = 0.6f, muffler = 4400f, gain = 0.78f),
        ),
        EngineSpec(
            "porsche_911_gt3", "Porsche 911 GT3", "Flat-6 · 4.0 L",
            Layout.FLAT, 6, 4.0f, Induction.NATURAL, 502,
            idleRpm = 900, redlineRpm = 9_000, gears = 7,
            tone = Tone(body = 240f, bite = 0.8f, lump = 0.1f, revUp = 0.5f, howl = 0.85f, muffler = 3800f, gain = 0.87f),
        ),
        EngineSpec(
            "ford_mustang_gt", "Ford Mustang GT", "V8 · 5.0 L cross-plane",
            Layout.V_CROSS_PLANE, 8, 5.0f, Induction.NATURAL, 460,
            idleRpm = 650, redlineRpm = 7_500, gears = 6,
            tone = Tone(body = 95f, bite = 0.45f, lump = 0.55f, revUp = 0.95f, howl = 0.15f, muffler = 1800f, gain = 0.98f),
        ),
        EngineSpec(
            "ford_shelby_gt500", "Ford Mustang Shelby GT500", "V8 · 5.2 L supercharged",
            Layout.V_CROSS_PLANE, 8, 5.2f, Induction.SUPERCHARGED, 760,
            idleRpm = 700, redlineRpm = 7_500, gears = 7,
            tone = Tone(body = 82f, bite = 0.6f, lump = 0.7f, revUp = 0.85f, whine = 0.8f, howl = 0.2f, drive = 2f, lope = 0.25f, sub = 0.6f, muffler = 1900f, gain = 1f),
        ),
        EngineSpec(
            "chevrolet_camaro_ss_1969", "Chevrolet Camaro SS 396 (1969)", "V8 · 6.5 L big-block",
            Layout.V_CROSS_PLANE, 8, 6.5f, Induction.NATURAL, 375,
            idleRpm = 850, redlineRpm = 6_000, gears = 4,  // redline approx.; hp as rated then
            tone = Tone(body = 68f, bite = 0.6f, lump = 0.95f, revUp = 1.1f, howl = 0.25f, drive = 2.6f, lope = 1f, sub = 1f, muffler = 1500f, gain = 1.1f),
        ),
        EngineSpec(
            "chevrolet_camaro_zl1", "Chevrolet Camaro ZL1", "V8 · 6.2 L supercharged",
            Layout.V_CROSS_PLANE, 8, 6.2f, Induction.SUPERCHARGED, 650,
            idleRpm = 700, redlineRpm = 6_500, gears = 10,
            tone = Tone(body = 75f, bite = 0.55f, lump = 0.75f, revUp = 0.95f, whine = 0.7f, howl = 0.15f, drive = 2.2f, lope = 0.35f, sub = 0.8f, muffler = 1700f, gain = 1.05f),
        ),
        EngineSpec(
            "dodge_challenger_hellcat", "Dodge Challenger SRT Hellcat", "V8 · 6.2 L supercharged",
            Layout.V_CROSS_PLANE, 8, 6.2f, Induction.SUPERCHARGED, 717,
            idleRpm = 700, redlineRpm = 6_200, gears = 8,
            tone = Tone(body = 80f, bite = 0.5f, lump = 0.6f, revUp = 1f, whine = 0.9f, howl = 0.1f, drive = 1.8f, lope = 0.3f, sub = 0.5f, muffler = 1700f, gain = 0.93f),
        ),
        EngineSpec(
            "mercedes_amg_gt_r", "Mercedes-AMG GT R", "V8 · 4.0 L twin-turbo",
            Layout.V_CROSS_PLANE, 8, 4.0f, Induction.TWIN_TURBO, 577,
            idleRpm = 750, redlineRpm = 7_200, gears = 7,
            tone = Tone(body = 110f, bite = 0.55f, lump = 0.4f, revUp = 0.85f, whine = 0.5f, howl = 0.1f, muffler = 2200f, gain = 1.03f),
        ),
        EngineSpec(
            "toyota_supra_a80", "Toyota Supra A80", "Inline-6 · 3.0 L twin-turbo",
            Layout.INLINE, 6, 3.0f, Induction.TWIN_TURBO, 320,
            idleRpm = 700, redlineRpm = 6_800, gears = 6,
            tone = Tone(body = 230f, bite = 0.6f, lump = 0.04f, revUp = 0.9f, whine = 1f, howl = 0.15f, muffler = 2600f, gain = 0.93f),
        ),
        EngineSpec(
            "mazda_rx7_fd", "Mazda RX-7 FD", "Twin-rotor · 1.3 L twin-turbo",
            Layout.ROTARY, 2, 1.3f, Induction.TWIN_TURBO, 255,
            idleRpm = 800, redlineRpm = 8_000, gears = 5,
            tone = Tone(body = 360f, bite = 0.95f, lump = 0.08f, revUp = 0.45f, whine = 0.7f, howl = 0.3f, muffler = 3200f, gain = 0.93f),
        ),
        EngineSpec(
            "audi_rs3", "Audi RS 3", "Inline-5 · 2.5 L turbo",
            Layout.INLINE, 5, 2.5f, Induction.TURBO, 401,
            idleRpm = 800, redlineRpm = 7_000, gears = 7,
            tone = Tone(body = 200f, bite = 0.6f, lump = 0.42f, revUp = 0.7f, whine = 0.5f, howl = 0.2f, muffler = 2600f, gain = 0.9f),
        ),
        EngineSpec(
            "f1_v10_2004", "Formula 1 V10 (2004)", "V10 · 3.0 L",
            Layout.V, 10, 3.0f, Induction.NATURAL, 900,
            idleRpm = 4_000, redlineRpm = 19_000, gears = 7,
            tone = Tone(body = 650f, bite = 1f, lump = 0.02f, revUp = 0.35f, howl = 0.9f, muffler = 7000f, gain = 0.64f),
        ),
        EngineSpec(
            "bugatti_chiron", "Bugatti Chiron", "W16 · 8.0 L quad-turbo",
            Layout.W, 16, 8.0f, Induction.QUAD_TURBO, 1_479,
            idleRpm = 800, redlineRpm = 6_700, gears = 7,
            tone = Tone(body = 140f, bite = 0.6f, lump = 0.05f, revUp = 1.4f, whine = 0.8f, howl = 0.1f, muffler = 2400f, gain = 1.33f),
        ),
        EngineSpec(
            "lexus_lfa", "Lexus LFA", "V10 · 4.8 L",
            Layout.V, 10, 4.8f, Induction.NATURAL, 553,
            idleRpm = 1_000, redlineRpm = 9_000, gears = 6,
            tone = Tone(body = 480f, bite = 0.95f, lump = 0.03f, revUp = 0.4f, howl = 1f, muffler = 5600f, gain = 0.83f),
        ),
        EngineSpec(
            "porsche_carrera_gt", "Porsche Carrera GT", "V10 · 5.7 L",
            Layout.V, 10, 5.7f, Induction.NATURAL, 603,
            idleRpm = 950, redlineRpm = 8_400, gears = 6,
            tone = Tone(body = 360f, bite = 1f, lump = 0.05f, revUp = 0.45f, howl = 0.2f, muffler = 6000f, gain = 0.76f),
        ),
        EngineSpec(
            "bmw_m5_e60", "BMW M5 (E60)", "V10 · 5.0 L",
            Layout.V, 10, 5.0f, Induction.NATURAL, 500,
            idleRpm = 800, redlineRpm = 8_250, gears = 7,
            tone = Tone(body = 270f, bite = 0.75f, lump = 0.1f, revUp = 0.6f, howl = 0.55f, muffler = 3600f, gain = 0.72f),
        ),
        EngineSpec(
            "ferrari_812_superfast", "Ferrari 812 Superfast", "V12 · 6.5 L",
            Layout.V, 12, 6.5f, Induction.NATURAL, 789,
            idleRpm = 1_000, redlineRpm = 8_900, gears = 7,
            tone = Tone(body = 450f, bite = 0.85f, lump = 0.02f, revUp = 0.55f, howl = 0.9f, muffler = 5200f, gain = 0.81f),
        ),
        EngineSpec(
            "aston_martin_valkyrie", "Aston Martin Valkyrie", "V12 · 6.5 L hybrid",
            Layout.V, 12, 6.5f, Induction.NATURAL, 1_139,
            idleRpm = 1_400, redlineRpm = 11_100, gears = 7,  // hp with its hybrid motor
            tone = Tone(body = 560f, bite = 1f, lump = 0.02f, revUp = 0.4f, howl = 1f, muffler = 6500f, gain = 0.74f),
        ),
        EngineSpec(
            "mclaren_720s", "McLaren 720S", "V8 · 4.0 L twin-turbo flat-plane",
            Layout.V_FLAT_PLANE, 8, 4.0f, Induction.TWIN_TURBO, 710,
            idleRpm = 800, redlineRpm = 8_500, gears = 7,
            tone = Tone(body = 190f, bite = 0.65f, lump = 0.06f, revUp = 0.6f, whine = 0.7f, howl = 0.3f, muffler = 3000f, gain = 0.84f),
        ),
        EngineSpec(
            "corvette_z06_c8", "Chevrolet Corvette Z06 (C8)", "V8 · 5.5 L flat-plane",
            Layout.V_FLAT_PLANE, 8, 5.5f, Induction.NATURAL, 670,
            idleRpm = 900, redlineRpm = 8_600, gears = 8,
            tone = Tone(body = 250f, bite = 0.85f, lump = 0.04f, revUp = 0.55f, howl = 0.7f, muffler = 4000f, gain = 0.71f),
        ),
        EngineSpec(
            "nissan_patrol_y62", "Nissan Patrol (Y62)", "V8 · 5.6 L",
            Layout.V_CROSS_PLANE, 8, 5.6f, Induction.NATURAL, 400,
            idleRpm = 600, redlineRpm = 6_000, gears = 7,  // redline approx.
            tone = Tone(body = 85f, bite = 0.4f, lump = 0.5f, revUp = 1.3f, howl = 0.1f, muffler = 1500f, gain = 1.05f),
        ),
        EngineSpec(
            "toyota_land_cruiser_v8", "Toyota Land Cruiser V8 (200)", "V8 · 5.7 L",
            Layout.V_CROSS_PLANE, 8, 5.7f, Induction.NATURAL, 381,
            idleRpm = 600, redlineRpm = 5_600, gears = 6,  // redline approx.
            tone = Tone(body = 125f, bite = 0.2f, lump = 0.15f, revUp = 1.4f, howl = 0.05f, muffler = 1300f, gain = 1.03f),
        ),
        EngineSpec(
            "subaru_wrx_sti", "Subaru WRX STI", "Flat-4 · 2.5 L turbo",
            Layout.FLAT, 4, 2.5f, Induction.TURBO, 305,
            idleRpm = 750, redlineRpm = 6_700, gears = 6,
            tone = Tone(body = 160f, bite = 0.5f, lump = 0.55f, revUp = 0.8f, whine = 0.6f, howl = 0.15f, muffler = 2200f, gain = 0.92f),
        ),
        EngineSpec(
            "honda_s2000", "Honda S2000", "Inline-4 · 2.0 L",
            Layout.INLINE, 4, 2.0f, Induction.NATURAL, 240,
            idleRpm = 800, redlineRpm = 9_000, gears = 6,
            tone = Tone(body = 300f, bite = 0.85f, lump = 0.03f, revUp = 0.45f, howl = 0.8f, muffler = 3600f, gain = 0.76f),
        ),
        EngineSpec(
            "nissan_skyline_r34", "Nissan Skyline GT-R (R34)", "Inline-6 · 2.6 L twin-turbo",
            Layout.INLINE, 6, 2.6f, Induction.TWIN_TURBO, 276,
            idleRpm = 900, redlineRpm = 8_000, gears = 6,
            tone = Tone(body = 250f, bite = 0.65f, lump = 0.03f, revUp = 0.75f, whine = 0.9f, howl = 0.2f, muffler = 2900f, gain = 0.98f),
        ),
        EngineSpec(
            "bmw_m3_e46", "BMW M3 (E46)", "Inline-6 · 3.2 L",
            Layout.INLINE, 6, 3.2f, Induction.NATURAL, 333,
            idleRpm = 850, redlineRpm = 8_000, gears = 6,
            tone = Tone(body = 270f, bite = 0.75f, lump = 0.03f, revUp = 0.6f, howl = 0.75f, muffler = 3400f, gain = 0.9f),
        ),
    )

    fun byId(id: String): EngineSpec? = cars.firstOrNull { it.id == id }
}
