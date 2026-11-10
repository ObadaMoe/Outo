package app.leopard.cockpit.ui.screens

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import app.leopard.cockpit.car.CarBridge
import app.leopard.cockpit.car.HostManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import app.leopard.cockpit.Otility
import app.leopard.cockpit.R
import app.leopard.cockpit.car.HelperConnection
import app.leopard.cockpit.data.AppInfo
import app.leopard.cockpit.data.AppView
import app.leopard.cockpit.screens.AppId
import app.leopard.cockpit.ui.AppIcon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * An app hosted inside Otility: its own hidden display draws into this view.
 * [width] × [height] is the app's real size in pixels; the view may be drawn
 * smaller (on the dashboard) and the picture is scaled.
 */
@Composable
fun HostSurface(
    app: AppId, component: String, width: Int, height: Int, modifier: Modifier = Modifier,
    direct: Boolean = false,
) {
    // A view per app: its listener belongs to one app. Without this, the floating
    // window (or the map widget) changing app would keep showing the old one.
    key(app) { HostView(app, component, width, height, modifier, direct) }
}

/**
 * [direct]: the app's pictures go straight to the car's screen (see [HostFeed]). Only
 * while no glass blurs it: zoomed in on it, say, or on the still dashboard.
 */
@Composable
private fun HostView(app: AppId, component: String, width: Int, height: Int, modifier: Modifier, direct: Boolean) {
    val hosts = Otility.instance.screens.hosts
    val w = width.coerceAtLeast(2) and 1.inv()
    val h = height.coerceAtLeast(2) and 1.inv()
    val dpi = hostDpi(app, w, h)
    val feed = remember { HostFeed(app, hosts) }
    feed.component = component
    feed.w = w; feed.h = h; feed.dpi = dpi
    // The SurfaceView stays a moment after [direct] ends: until the TextureView, given
    // the app's pictures again, has a new one. Otherwise it would show its last one,
    // from before the SurfaceView took over (minutes old, perhaps).
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(direct) {
        if (direct) {
            lingering = true
            feed.enterDirect()
        } else if (lingering) {
            feed.leaveDirect()
            lingering = false
        }
    }

    // The views are laid out at the app's real size and scaled down to fit. Android
    // sets a TextureView's buffer to the view's own size from time to time, and the
    // hidden display draws into that buffer, so the two have to agree.
    // Black behind the app once its pictures are in, rather than its poster: zoomed in,
    // the picture can fall a pixel short of the edge, and the poster's color showed there.
    Box(modifier.clipToBounds().then(if (feed.shown) Modifier.background(Color.Black) else Modifier).actualSize(w, h)) {
        // Under the TextureView, which covers it until its first pictures are in.
        if (direct || lingering) AndroidView(factory = { ctx -> feed.directView(ctx) }, modifier = Modifier.fillMaxSize())
        AndroidView(factory = { ctx -> feed.textureView(ctx) }, modifier = Modifier.fillMaxSize())
    }
    LaunchedEffect(w, h, dpi) { feed.resize(w, h, dpi) }
}

/**
 * Where a hosted app's hidden display draws. Two views, one at a time:
 * - **A TextureView**, drawn as part of Otility's own picture, so it scales, clips and
 *   rounds with everything around it, and Otility hears of each picture. But each
 *   picture the app draws makes Otility draw its whole screen again, a frame later:
 *   the app feels like a projection.
 * - **A SurfaceView**, its own layer on the car's screen: the app's pictures go
 *   straight to the screen, as an app opened on its own does, and Otility draws
 *   nothing. Used while no glass needs to blur it (zoomed in, or the still dashboard).
 *   It moves and scales with the camera as the TextureView does.
 *
 * The switch: the SurfaceView sits under the TextureView; once the TextureView has
 * shown a picture (so the app is drawing), the display moves to the SurfaceView, and
 * a moment later, its first pictures in, the TextureView is hidden. When the
 * SurfaceView goes (Otility leaves the screen, or the app is no longer direct), the
 * display moves back, and the app keeps running.
 *
 * Everything here runs on the main thread.
 */
private class HostFeed(val app: AppId, val hosts: HostManager) {
    var component = ""
    var w = 2
    var h = 2
    var dpi = 160

    private var texture: TextureView? = null
    private var textureSurface: Surface? = null
    private var textureFit: (Int, Int) -> Unit = { _, _ -> }
    /** Pictures since the TextureView got its buffer. */
    private var frames = 0
    private var revived = 0
    /** The app has shown a picture here. */
    var shown by mutableStateOf(false)
        private set

    private var direct: SurfaceView? = null
    private var directAlive = false
    /** The display draws into the SurfaceView. */
    private var directFed = false
    /** Leaving the SurfaceView: the TextureView's next picture. */
    private var fresh: CompletableDeferred<Unit>? = null

    fun textureView(ctx: Context): TextureView = TextureView(ctx).apply {
        isOpaque = false
        texture = this
        val tv = this
        // A display always sends a first picture once it has the view's buffer. None at
        // all means Android left the display's layers hidden: make the app a new one
        // (three times at most; it sometimes takes two).
        val check = object : Runnable {
            override fun run() {
                if (textureSurface == null || frames > 0 || directFed || revived >= 3) return
                hosts.revive(app) { done ->
                    tv.post {
                        if (done) revived++
                        if (textureSurface != null) tv.postDelayed(this, if (done) FIRST_FRAME_MS * 2 else FIRST_FRAME_MS)
                    }
                }
            }
        }
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, vw: Int, vh: Int) {
                st.setDefaultBufferSize(w, h)
                val s = Surface(st)
                textureSurface = s
                textureFit = { bw, bh -> runCatching { st.setDefaultBufferSize(bw, bh) } }
                frames = 0
                if (directFed) return // it takes over again if the SurfaceView goes
                hosts.attach(app, component, s, w, h, dpi, fit = textureFit)
                tv.removeCallbacks(check)
                tv.postDelayed(check, FIRST_FRAME_MS)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, vw: Int, vh: Int) {
                // The view's size changed; the app's picture keeps its own size.
                st.setDefaultBufferSize(w, h)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                tv.removeCallbacks(check)
                val s = textureSurface
                if (!directFed) hosts.detach(app, s)
                s?.release()
                textureSurface = null
                textureFit = { _, _ -> }
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                frames++
                fresh?.complete(Unit)
                if (frames == 1) {
                    shown = true
                    feedDirect()
                }
            }
        }
    }

    fun directView(ctx: Context): SurfaceView = SurfaceView(ctx).apply {
        direct = this
        // SurfaceView's own default is 16-bit colour.
        holder.setFormat(PixelFormat.RGBA_8888)
        holder.setFixedSize(w, h)
        val sv = this
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                directAlive = true
                feedDirect()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                directAlive = false
                if (!directFed) return
                directFed = false
                texture?.alpha = 1f
                val s = textureSurface
                // Not a new display if the app has gone meanwhile (closed, or on another screen).
                if (s != null) hosts.attach(app, component, s, w, h, dpi, create = false, fit = textureFit)
                else hosts.detach(app, holder.surface)
            }
        })
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // No longer direct: the SurfaceView is gone for good (its surface went just before).
                if (direct === sv) direct = null
            }
        })
    }

    /** Moves the display to the SurfaceView, once it's there and the app is drawing. */
    private fun feedDirect() {
        val sv = direct ?: return
        if (!directAlive || directFed || frames == 0) return
        directFed = true
        hosts.attach(app, component, sv.holder.surface, w, h, dpi, create = false) { bw, bh ->
            sv.post { if (direct === sv) sv.holder.setFixedSize(bw, bh) }
        }.invokeOnCompletion {
            sv.postDelayed({ if (directFed && direct === sv) texture?.alpha = 0f }, HANDOVER_MS)
        }
    }

    /** Back on the SurfaceView, if it's still there (it is while lingering). */
    fun enterDirect() = feedDirect()

    /**
     * Gives the display back to the TextureView, and returns once that has a new picture
     * (or after [LEAVE_MS], if none comes); then the TextureView shows again and the
     * SurfaceView can go. Until then the SurfaceView keeps its last picture, which is
     * up to date.
     */
    suspend fun leaveDirect() {
        if (!directFed) return
        // No TextureView to go back to: the SurfaceView's end takes the display along.
        val s = textureSurface ?: return
        directFed = false
        val next = CompletableDeferred<Unit>()
        fresh = next
        try {
            hosts.attach(app, component, s, w, h, dpi, create = false, fit = textureFit)
            withTimeoutOrNull(LEAVE_MS) { next.await() }
        } finally {
            if (fresh === next) fresh = null
        }
        texture?.alpha = 1f
    }

    fun resize(w: Int, h: Int, dpi: Int) {
        texture?.surfaceTexture?.setDefaultBufferSize(w, h)
        direct?.holder?.setFixedSize(w, h)
        hosts.resize(app, w, h, dpi)
    }
}

/** How long the SurfaceView waits, on leaving it, for the TextureView's first new picture. */
private const val LEAVE_MS = 300L

/** How long a hosted app's display may take to send its first picture. */
private const val FIRST_FRAME_MS = 2500L

/**
 * How long the TextureView stays over the SurfaceView once the display draws into
 * the SurfaceView: until its first pictures are surely in (a few frames).
 */
private const val HANDOVER_MS = 120L

/**
 * An app on the main screen while the dashboard shows only its icon: it keeps
 * running in its hidden display (started here if it isn't yet), drawing nothing.
 */
@Composable
fun HostedApp(app: AppId, component: String, width: Int, height: Int) {
    val hosts = Otility.instance.screens.hosts
    val helper by HelperConnection.helper.collectAsState()
    val w = width.coerceAtLeast(2) and 1.inv()
    val h = height.coerceAtLeast(2) and 1.inv()
    val dpi = hostDpi(app, w, h)
    LaunchedEffect(app, w, h, dpi, helper) { hosts.prepare(app, component, w, h, dpi) }
}

/** The density an app runs at: the car's, or higher in Phone view so the app picks its phone layout. */
@Composable
private fun hostDpi(app: AppId, w: Int, h: Int): Int {
    val views by Otility.instance.prefs.appViews.collectAsState()
    val density = LocalContext.current.resources.displayMetrics.densityDpi
    return if (views[app] == AppView.PHONE) max(density, (minOf(w, h) * 160) / 420) else density
}

/** Measures the content at exactly [w] × [h] pixels, centred, and scales it to fill the space it's given. */
private fun Modifier.actualSize(w: Int, h: Int) = layout { measurable, constraints ->
    val outW = if (constraints.hasBoundedWidth) constraints.maxWidth else w
    val outH = if (constraints.hasBoundedHeight) constraints.maxHeight else h
    val p = measurable.measure(Constraints.fixed(w, h))
    layout(outW, outH) {
        p.placeWithLayer((outW - w) / 2, (outH - h) / 2) {
            scaleX = outW / w.toFloat()
            scaleY = outH / h.toFloat()
        }
    }
}

/**
 * Another screen's live picture (the cluster or the passenger screen). [direct]: it goes
 * straight to the car's screen, as an app's picture does (see [MirrorFeed]).
 */
@Composable
fun MirrorSurface(displayId: Int, width: Int, height: Int, modifier: Modifier = Modifier, direct: Boolean = false) {
    val bridge = Otility.instance.bridge
    val feed = remember(displayId) { MirrorFeed(bridge, displayId, width, height) }
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(direct) {
        if (direct) {
            lingering = true
            feed.enterDirect()
        } else if (lingering) {
            feed.leaveDirect()
            lingering = false
        }
    }
    Box(modifier) {
        if (direct || lingering) AndroidView(factory = { ctx -> feed.directView(ctx) }, modifier = Modifier.fillMaxSize())
        AndroidView(factory = { ctx -> feed.textureView(ctx) }, modifier = Modifier.fillMaxSize())
    }
}

/**
 * One mirror of a display into one surface. The helper makes it a moment later; [stop]
 * may come first, and then it's taken down as soon as it's made.
 */
private class MirrorRun(private val bridge: CarBridge, displayId: Int, surface: Surface, w: Int, h: Int) {
    private val lock = Any()
    private var stopped = false
    private var id = -1

    init {
        bridge.startMirror(displayId, surface, w, h) { made ->
            val orphan = synchronized(lock) { if (stopped) true else { id = made; false } }
            if (orphan) bridge.stopMirror(made)
        }
    }

    fun stop() {
        val made = synchronized(lock) { stopped = true; id.also { id = -1 } }
        bridge.stopMirror(made)
    }
}

/**
 * Where another screen's mirror draws: a TextureView, or a SurfaceView once it may go
 * straight to the car's screen, handed over as in [HostFeed]. Each view has its own
 * mirror; both run only for the moment of a handover. Runs on the main thread.
 */
private class MirrorFeed(val bridge: CarBridge, val displayId: Int, val w: Int, val h: Int) {
    private var texture: TextureView? = null
    private var textureSurface: Surface? = null
    private var textureRun: MirrorRun? = null
    /** Pictures since the TextureView's mirror started. */
    private var frames = 0
    private var fresh: CompletableDeferred<Unit>? = null

    private var direct: SurfaceView? = null
    private var directAlive = false
    private var directRun: MirrorRun? = null
    private var wanted = false

    fun textureView(ctx: Context): TextureView = TextureView(ctx).apply {
        isOpaque = false
        texture = this
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, vw: Int, vh: Int) {
                st.setDefaultBufferSize(w, h)
                textureSurface = Surface(st)
                if (directRun == null) startTexture()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, vw: Int, vh: Int) {
                st.setDefaultBufferSize(w, h)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stopTexture()
                textureSurface?.release()
                textureSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                frames++
                fresh?.complete(Unit)
                if (frames == 1) feedDirect()
            }
        }
    }

    fun directView(ctx: Context): SurfaceView = SurfaceView(ctx).apply {
        direct = this
        holder.setFormat(PixelFormat.RGBA_8888)
        holder.setFixedSize(w, h)
        val sv = this
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                directAlive = true
                feedDirect()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                directAlive = false
                val run = directRun ?: return
                directRun = null
                run.stop()
                texture?.alpha = 1f
                if (textureRun == null) startTexture()
            }
        })
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                if (direct === sv) direct = null
            }
        })
    }

    private fun startTexture() {
        val s = textureSurface ?: return
        frames = 0
        textureRun = MirrorRun(bridge, displayId, s, w, h)
    }

    private fun stopTexture() {
        textureRun?.stop()
        textureRun = null
    }

    /** Starts the SurfaceView's mirror once it's there and the TextureView's shows. */
    private fun feedDirect() {
        val sv = direct ?: return
        if (!wanted || !directAlive || directRun != null || frames == 0) return
        val run = MirrorRun(bridge, displayId, sv.holder.surface, w, h)
        directRun = run
        // Its first pictures surely in, the TextureView's mirror can go.
        sv.postDelayed({
            if (directRun === run) {
                texture?.alpha = 0f
                stopTexture()
            }
        }, MIRROR_HANDOVER_MS)
    }

    fun enterDirect() {
        wanted = true
        // Back before a handover to the TextureView had finished: the SurfaceView's
        // mirror still runs, so the TextureView's can go again.
        if (directRun != null) {
            texture?.alpha = 0f
            stopTexture()
        } else feedDirect()
    }

    /** The TextureView's mirror again; returns once it has a picture (or after [LEAVE_MS]). */
    suspend fun leaveDirect() {
        wanted = false
        val run = directRun ?: return
        if (textureSurface == null) return // the SurfaceView's end stops its mirror
        val next = CompletableDeferred<Unit>()
        fresh = next
        try {
            if (textureRun == null) startTexture()
            withTimeoutOrNull(LEAVE_MS) { next.await() }
        } finally {
            if (fresh === next) fresh = null
        }
        texture?.alpha = 1f
        if (directRun === run) {
            directRun = null
            run.stop()
        }
    }
}

/** How long a new mirror is given to draw its first pictures before the old one goes. */
private const val MIRROR_HANDOVER_MS = 200L

/**
 * Stands in for an app's picture: its icon on a deep wash of its own color.
 * Shown while an app starts, when live previews are off, and in preview mode.
 */
@Composable
fun AppPoster(app: AppInfo?, modifier: Modifier = Modifier) {
    val tint = app?.let { Color(it.tint) } ?: Color(0xFF3A3D45)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(lerp(tint, Color.Black, 0.45f), lerp(tint, Color.Black, 0.78f)),
                ),
            ),
    ) {
        val size = min(maxWidth, maxHeight) * 0.36f
        if (app != null) AppIcon(app, size, Modifier.align(Alignment.Center))
    }
}

/** An idle screen: true black with the emblem, which dims after a while. */
@Composable
fun IdleScreen(showEmblem: Boolean, dimAfterSeconds: Int, modifier: Modifier = Modifier) {
    var dim by remember { mutableStateOf(false) }
    LaunchedEffect(dimAfterSeconds) {
        dim = false
        if (dimAfterSeconds > 0) {
            delay(dimAfterSeconds * 1000L)
            dim = true
        }
    }
    val alpha by animateFloatAsState(if (dim) 0.32f else 1f, tween(1400), label = "emblem")
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        if (showEmblem) {
            Image(
                painterResource(R.drawable.emblem),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(min(maxHeight * 0.46f, maxWidth * 0.3f))
                    .graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

@Composable
internal fun OnDispose(block: () -> Unit) {
    DisposableEffect(Unit) { onDispose(block) }
}
