package app.leopard.cockpit.engine

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EngineSynthTest {

    /** Idles a second, holds the pedal down [hold] seconds, then lets go for 1.5 s. */
    private fun holdRev(car: EngineSpec, hold: Float = 3f, voice: Voice = Voice()): ShortArray {
        val synth = EngineSynth()
        synth.spec = car
        synth.voice = voice
        synth.preview = EngineSynth.Preview.REV
        val blocks = ((1f + hold + 1.5f) * SR / EngineSynth.BLOCK).toInt()
        val out = ShortArray(blocks * EngineSynth.BLOCK)
        val buf = ShortArray(EngineSynth.BLOCK)
        for (b in 0 until blocks) {
            val t = b * EngineSynth.BLOCK / SR.toFloat()
            synth.pedal = if (t >= 1f && t < 1f + hold) 1f else 0f
            synth.next(buf)
            buf.copyInto(out, b * EngineSynth.BLOCK)
        }
        return out
    }

    private fun rms(x: ShortArray, from: Float, to: Float): Float {
        val a = (from * SR).toInt()
        val b = (to * SR).toInt().coerceAtMost(x.size)
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / (b - a)).toFloat()
    }

    /** Held at the redline, every engine keeps sounding (it used to cut out). */
    @Test
    fun holdsItsTopNote() {
        for (car in Garage.cars) {
            val x = holdRev(car)
            val revving = rms(x, 1.2f, 1.8f)
            val top = rms(x, 3f, 4f)
            assertTrue("${car.id}: top $top vs revving $revving", top > revving * 0.6f)
        }
    }

    /** Writes each car's hold to a WAV file, to listen to or measure. Only with OTILITY_WAV_DIR set. */
    @Test
    fun dumpHolds() {
        val dir = System.getenv("OTILITY_WAV_DIR")
        assumeTrue(dir != null)
        File(dir!!).mkdirs()
        for (car in Garage.cars) {
            val x = holdRev(car)
            writeWav(File(dir, "${car.id}.wav"), x)
            println("%-28s idle %5.0f  rev %5.0f  top %5.0f  after %5.0f".format(
                car.id, rms(x, 0.5f, 1f), rms(x, 1.2f, 1.8f), rms(x, 3f, 4f), rms(x, 4.2f, 4.8f),
            ))
        }
    }

    /** Each Sound Studio slider at its two ends, on two cars. Only with OTILITY_WAV_DIR set. */
    @Test
    fun dumpSliders() {
        val dir = System.getenv("OTILITY_WAV_DIR")
        assumeTrue(dir != null)
        File(dir!!).mkdirs()
        val ends = mapOf(
            "base" to Voice(),
            "pitch_lo" to Voice(pitch = 0.7f), "pitch_hi" to Voice(pitch = 1.4f),
            "rough_lo" to Voice(roughness = 0f), "rough_hi" to Voice(roughness = 1f),
            "crackle_lo" to Voice(crackle = 0f), "crackle_hi" to Voice(crackle = 1f),
        )
        for (id in listOf("ford_mustang_gt", "porsche_911_gt3")) {
            val car = Garage.byId(id)!!
            for ((name, v) in ends) writeWav(File(dir, "${id}__$name.wav"), holdRev(car, voice = v))
        }
    }

    private fun writeWav(f: File, x: ShortArray) {
        RandomAccessFile(f, "rw").use { w ->
            w.setLength(0)
            fun i32(v: Int) { w.write(v and 0xff); w.write(v shr 8 and 0xff); w.write(v shr 16 and 0xff); w.write(v ushr 24) }
            fun i16(v: Int) { w.write(v and 0xff); w.write(v shr 8 and 0xff) }
            w.writeBytes("RIFF"); i32(36 + x.size * 2); w.writeBytes("WAVEfmt ")
            i32(16); i16(1); i16(1); i32(SR); i32(SR * 2); i16(2); i16(16)
            w.writeBytes("data"); i32(x.size * 2)
            val bytes = ByteArray(x.size * 2)
            for (i in x.indices) { bytes[2 * i] = (x[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (x[i].toInt() shr 8).toByte() }
            w.write(bytes)
        }
    }

    private companion object {
        const val SR = 48_000
    }
}
