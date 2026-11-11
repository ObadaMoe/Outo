package app.leopard.cockpit.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import app.leopard.cockpit.CrashLog
import app.leopard.cockpit.nav.NavListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** A track a media app has loaded, playing or paused. */
data class Track(
    val pkg: String,
    val title: String,
    val artist: String?,
    val art: Bitmap?,
    /** The artwork's main colour (ARGB), for the card's tint; null for grey artwork or none. */
    val accent: Int?,
    val playing: Boolean,
    val positionMs: Long,
    /** When [positionMs] held, in [SystemClock.elapsedRealtime] time. */
    val at: Long,
    /** How fast the position moves on: 0 while buffering, negative while rewinding. */
    val speed: Float,
    /** Null for live streams. */
    val durationMs: Long?,
    val canPrevious: Boolean,
    val canNext: Boolean,
    /** Why the app couldn't play, in its own words, when it last failed. */
    val problem: String? = null,
) {
    /** Where playback is at [now], moving on while it plays. */
    fun positionAt(now: Long): Long {
        val p = if (playing) positionMs + ((now - at) * speed).toLong() else positionMs
        return p.coerceIn(0, durationMs ?: Long.MAX_VALUE)
    }
}

/**
 * What the media apps are playing, read from their media sessions. Android shows
 * them to Otility because it has notification access (the same access the
 * directions use), so this starts when [NavListener] connects.
 *
 * Read on a thread of its own: each look asks every media app for its state and its
 * track (artwork too), and apps send changes many times a second (a phone over
 * Bluetooth, YouTube). On the main thread that held up the screen and the touches
 * on their way to the apps. A burst of changes is read once.
 */
object NowPlaying {
    private const val TAG = "Otility"

    private val _track = MutableStateFlow<Track?>(null)
    /** The track to show: one that's playing, else the last one paused. Null when no app has one. */
    val track: StateFlow<Track?> = _track.asStateFlow()

    /** Everything below runs here, apart from the buttons, which only read [current]. */
    private val worker = Handler(HandlerThread("otility-media").apply { start() }.looper)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.survive)
    private var context: Context? = null
    private var manager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    @Volatile private var current: MediaController? = null
    /** Changes that come together (a new song brings its state and its track) are read once. */
    private const val SETTLE_MS = 120L
    private val refreshNow = Runnable { runCatching { read() }.onFailure { Log.w(TAG, "now playing", it) } }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener {
        runCatching { watch(it.orEmpty()) }.onFailure { e -> Log.w(TAG, "media sessions", e) }
    }

    fun start(context: Context) {
        if (manager != null) return
        val app = context.applicationContext
        this.context = app
        val m = app.getSystemService(MediaSessionManager::class.java) ?: return
        val listener = ComponentName(app, NavListener::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsChanged, listener, worker)
            manager = m
            worker.post { runCatching { watch(m.getActiveSessions(listener)) } }
        }.onFailure { Log.w(TAG, "media sessions", it) }
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        manager = null
        worker.post { runCatching { watch(emptyList()) } }
    }

    fun playPause() {
        val c = current ?: return
        if (_track.value?.playing == true) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        current?.transportControls?.skipToNext()
    }

    fun previous() {
        current?.transportControls?.skipToPrevious()
    }

    private fun watch(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        val own = context?.packageName
        controllers = list.filter { it.packageName != own }
        controllers.forEach { c -> runCatching { c.registerCallback(callback, worker) } }
        refresh()
    }

    /** Reads again once the changes settle. */
    private fun refresh() {
        worker.removeCallbacks(refreshNow)
        worker.postDelayed(refreshNow, SETTLE_MS)
    }

    /** One app's state and track, asked for once per look. */
    private class Session(val c: MediaController, val state: PlaybackState?, val md: MediaMetadata?)

    private fun read() {
        val sessions = controllers.map { Session(it, it.playbackState, it.metadata) }
        val was = current
        val s = MediaChoice.pick(
            sessions,
            current = was?.let { c -> sessions.firstOrNull { it.c.sessionToken == c.sessionToken } },
            same = { a, b -> a.c.sessionToken == b.c.sessionToken },
            playing = { isPlaying(it.state) },
            hasTrack = { hasTrack(it) },
            // One already on the card stays when it fails (say, play pressed and the
            // app refused), so the card doesn't vanish under the finger.
            keeps = { !titleOf(it.md).isNullOrBlank() },
        )
        current = s?.c
        _track.value = s?.let { read(it) }
    }

    private fun isPlaying(s: PlaybackState?) = when (s?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private fun hasTrack(s: Session): Boolean {
        if (s.state?.state == PlaybackState.STATE_ERROR) return false
        return !titleOf(s.md).isNullOrBlank()
    }

    private fun titleOf(md: MediaMetadata?): String? =
        md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

    private fun read(session: Session): Track? {
        val c = session.c
        val md = session.md ?: return null
        val title = titleOf(md)?.takeIf { it.isNotBlank() } ?: return null
        val artist = listOf(
            MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM,
        ).firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.isNotBlank() } }
        val s = session.state
        val now = SystemClock.elapsedRealtime()
        val actions = s?.actions ?: 0L
        val (art, accent) = artOf(md, "$title|$artist|${md.getString(MediaMetadata.METADATA_KEY_ALBUM)}")
        return Track(
            pkg = c.packageName,
            title = title,
            artist = artist,
            art = art,
            accent = accent,
            playing = isPlaying(s),
            positionMs = s?.position?.coerceAtLeast(0) ?: 0,
            at = s?.lastPositionUpdateTime?.takeIf { it > 0 } ?: now,
            speed = movingSpeed(s?.state, s?.playbackSpeed),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
            // Apps that list no actions at all still usually skip.
            canPrevious = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canNext = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            problem = if (s?.state == PlaybackState.STATE_ERROR) {
                s.errorMessage?.toString()?.takeIf { it.isNotBlank() } ?: "Couldn't play"
            } else null,
        )
    }

    // ---- Artwork ---------------------------------------------------------------

    /**
     * What the artwork last read belongs to, so it isn't scaled and its colour worked
     * out again on every change of the playback state (each read brings a new copy).
     */
    private var artFrom: Any? = null
    private var artDone: Pair<Bitmap?, Int?> = null to null
    private val loading = HashSet<String>()

    private fun artOf(md: MediaMetadata, key: String): Pair<Bitmap?, Int?> {
        val bitmap = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap != null) {
            val from = "$key|${bitmap.width}x${bitmap.height}"
            if (artFrom != from) {
                artFrom = from
                artDone = prepare(bitmap)
            }
            return artDone
        }
        val uri = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.isNotBlank() } } ?: return null to null
        if (artFrom == uri) return artDone
        // Read on the IO pool (a file or a content provider), then shown with the next look.
        val ctx = context ?: return null to null
        if (loading.add(uri)) {
            io.launch {
                val b = runCatching { load(ctx, Uri.parse(uri)) }.getOrNull()
                val done = b?.let(::prepare) ?: (null to null)
                worker.post {
                    loading.remove(uri)
                    artFrom = uri
                    artDone = done
                    refresh()
                }
            }
        }
        return null to null
    }

    private fun load(ctx: Context, uri: Uri): Bitmap? {
        if (uri.scheme !in setOf("content", "android.resource", "file")) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Small enough to keep, and its colour. */
    private fun prepare(b: Bitmap): Pair<Bitmap?, Int?> = runCatching {
        val side = max(b.width, b.height)
        val kept = if (side > ART_PX) {
            Bitmap.createScaledBitmap(b, b.width * ART_PX / side, b.height * ART_PX / side, true)
        } else b.copy(Bitmap.Config.ARGB_8888, false)
        val tiny = Bitmap.createScaledBitmap(kept, 24, 24, true)
        val px = IntArray(24 * 24).also { tiny.getPixels(it, 0, 24, 0, 0, 24, 24) }
        kept to MediaColor.accentOf(px)
    }.getOrDefault(null to null)

    private const val ART_PX = 400
}

/**
 * How fast the position moves on in [state]: the app's own speed while it plays,
 * fast-forwards or rewinds (negative), and still while it buffers, connects or
 * skips (those show as playing, but the song isn't moving yet). An app playing
 * at speed 0 most likely just didn't set it.
 */
internal fun movingSpeed(state: Int?, speed: Float?): Float = when (state) {
    PlaybackState.STATE_PLAYING -> speed?.takeIf { it != 0f } ?: 1f
    PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> speed ?: 0f
    else -> 0f
}

/** Which session to show; kept apart from Android's types so it can be tested. */
internal object MediaChoice {
    /**
     * One that's playing first; else the one already shown, if it [keeps] its track
     * (so the card doesn't jump between paused apps); else the first with a track
     * ([sessions] come most important first, as Android gives them).
     */
    fun <T> pick(
        sessions: List<T>,
        current: T?,
        same: (T, T) -> Boolean,
        playing: (T) -> Boolean,
        hasTrack: (T) -> Boolean,
        keeps: (T) -> Boolean = hasTrack,
    ): T? =
        sessions.firstOrNull { playing(it) && hasTrack(it) }
            ?: current?.let { c -> sessions.firstOrNull { same(it, c) && keeps(it) } }
            ?: sessions.firstOrNull(hasTrack)
}

/** The colour of a picture, for tinting what it sits on. */
internal object MediaColor {
    /**
     * The average of [pixels]' colourful ones (greys and near-black count little),
     * brought to a middling brightness and some saturation so it works as a tint
     * under white text. Null if the picture is all but grey.
     */
    fun accentOf(pixels: IntArray): Int? {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weight = 0.0
        for (p in pixels) {
            val pr = (p shr 16 and 255) / 255.0
            val pg = (p shr 8 and 255) / 255.0
            val pb = (p and 255) / 255.0
            val hi = max(pr, max(pg, pb))
            val lo = min(pr, min(pg, pb))
            val sat = if (hi == 0.0) 0.0 else (hi - lo) / hi
            val w = sat * sat * hi
            r += pr * w; g += pg * w; b += pb * w; weight += w
        }
        if (pixels.isEmpty() || weight < pixels.size * 0.02) return null
        val hsv = hsv(r / weight, g / weight, b / weight)
        return rgb(hsv[0], hsv[1].coerceIn(0.35, 0.8), hsv[2].coerceIn(0.45, 0.72))
    }

    private fun hsv(r: Double, g: Double, b: Double): DoubleArray {
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        val h = when {
            d == 0.0 -> 0.0
            hi == r -> 60 * (((g - b) / d) % 6)
            hi == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        return doubleArrayOf(h, if (hi == 0.0) 0.0 else d / hi, hi)
    }

    private fun rgb(h: Double, s: Double, v: Double): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60).toInt() % 6) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
