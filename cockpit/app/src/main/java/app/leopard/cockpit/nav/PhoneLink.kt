package app.leopard.cockpit.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import app.leopard.cockpit.MainActivity
import app.leopard.cockpit.Otility
import app.leopard.cockpit.R
import app.leopard.cockpit.screens.ScreenId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Receives places from the driver's iPhone over the car's Wi-Fi (the iPhone's
 * Personal Hotspot, or the car's own hotspot). The phone opens a small page
 * (or runs a Shortcut from the Share menu) that sends the place here; every
 * request carries the pairing code shown in Settings.
 */
class PhoneLink(private val app: Otility) {

    companion object {
        const val PORT = 8686
        private const val TAG = "Otility"
        private const val MAX_BODY = 16 * 1024
        /** Wi-Fi interface names: joined to a network (wlan) or the car's hotspot (ap, swlan). */
        private val WIFI = listOf("wlan", "ap", "swlan")
    }

    private var server: ServerSocket? = null

    /** Three requests at a time and a short queue; anything more is turned away at once. */
    private val pool = ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Times of recent places, to turn away a flood. */
    private val recent = ArrayDeque<Long>()

    /** Wrong pairing codes per address in the last minute (code guessing). */
    private val misses = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun start() {
        if (server != null) return
        val s = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        }.getOrElse {
            Log.w(TAG, "phone link: port $PORT is busy", it)
            return
        }
        server = s
        _running.value = true
        Log.i(TAG, "phone link listening on $PORT")
        thread(name = "otility-phone", isDaemon = true) {
            while (!s.isClosed) {
                val c = try {
                    s.accept()
                } catch (t: Throwable) {
                    if (s.isClosed) break
                    // Out of file handles, a network change: wait a moment and keep serving.
                    Log.w(TAG, "phone link accept", t)
                    Thread.sleep(500)
                    continue
                }
                if (!fromNearby(c.inetAddress)) {
                    runCatching { c.close() }
                    continue
                }
                runCatching { pool.execute { handle(c) } }.onFailure { runCatching { c.close() } }
            }
            synchronized(this) {
                if (server === s) {
                    server = null
                    _running.value = false
                }
            }
        }
    }

    /** Only phones on the car's own networks (hotspot, Wi-Fi), never the internet side. */
    private fun fromNearby(a: InetAddress?): Boolean = when {
        a == null -> false
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress -> true
        // IPv6 unique local addresses (fc00::/7).
        a is Inet6Address -> (a.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        _running.value = false
    }

    /**
     * The car's addresses on Wi-Fi (joined to the iPhone's hotspot first, then its
     * own hotspot). Only Wi-Fi: the car's internal networks are no use to the phone,
     * so the pairing code never shows one.
     */
    fun addresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { nic -> nic.isUp && !nic.isLoopback && WIFI.any { nic.name.startsWith(it) } }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to it.hostAddress!! } }
            .sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else 1 }
    }.getOrDefault(emptyList())

    fun pageLink(ip: String) = "http://$ip:$PORT/?k=${app.prefs.phoneKey}"

    fun shortcutLink(ip: String) = "http://$ip:$PORT/go?k=${app.prefs.phoneKey}"

    // ---- HTTP -----------------------------------------------------------------

    private class Request(
        val method: String,
        val path: String,
        val params: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
        val from: String,
    )

    private fun handle(socket: Socket) {
        // Closing the socket can fail too (the phone gone): inside, so the worker survives it.
        runCatching {
            socket.use { s ->
                // Each read waits at most 3 s, and the whole request has 8 s, so a
                // phone that sends a byte now and then can't hold a worker.
                s.soTimeout = 3000
                val deadline = System.currentTimeMillis() + 8000
                val from = s.inetAddress?.hostAddress ?: "?"
                val req = read(BufferedInputStream(s.getInputStream()), deadline, from) ?: return
                respond(s.getOutputStream(), req)
            }
        }.onFailure { Log.w(TAG, "phone link request", it) }
    }

    private fun read(input: InputStream, deadline: Long, from: String): Request? {
        val head = readHead(input, deadline) ?: return null
        val lines = head.split("\r\n")
        val first = lines.first().split(' ')
        if (first.size < 2) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            if (System.currentTimeMillis() > deadline) return null
            val n = input.read(body, got, length - got)
            if (n < 0) break
            got += n
        }
        val bodyText = String(body, 0, got, Charsets.UTF_8)
        val target = first[1]
        val path = target.substringBefore('?')
        val params = LinkedHashMap<String, String>()
        if ('?' in target) params += form(target.substringAfter('?'))
        val rawType = headers["content-type"] ?: ""
        val type = rawType.lowercase()
        when {
            type.startsWith("application/x-www-form-urlencoded") -> params += form(bodyText)
            type.startsWith("application/json") -> runCatching {
                val o = JSONObject(bodyText)
                o.keys().forEach { k -> params[k] = o.optString(k) }
            }
            // The boundary is case-sensitive: read it from the header as sent.
            type.startsWith("multipart/form-data") -> params += multipart(bodyText, rawType)
        }
        return Request(first[0].uppercase(), path, params, headers, bodyText, from)
    }

    /** Reads up to the blank line after the headers. */
    private fun readHead(input: InputStream, deadline: Long): String? {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while (out.size() < 8192) {
            if (System.currentTimeMillis() > deadline) return null
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd()
        }
        return null
    }

    private fun form(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate { pair ->
        val k = pair.substringBefore('=')
        val v = pair.substringAfter('=', "")
        dec(k) to dec(v)
    }

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Shortcuts sends "Form" bodies as multipart; only text fields matter here. */
    private fun multipart(body: String, type: String): Map<String, String> {
        val boundary = Regex("""boundary=(?:"([^"]+)"|([^;\s]+))""", RegexOption.IGNORE_CASE).find(type)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: return emptyMap()
        val out = HashMap<String, String>()
        for (part in body.split("--$boundary")) {
            if (part.startsWith("--")) break // the closing delimiter
            val split = part.indexOf("\r\n\r\n")
            if (split < 0) continue
            val partHead = part.substring(0, split)
            // name="…", not filename="…".
            val name = Regex("""(?:^|[;\s])name="([^"]*)"""").find(partHead)?.groupValues?.get(1) ?: continue
            out[name] = part.substring(split + 4).removeSuffix("\r\n")
        }
        return out
    }

    private fun respond(out: OutputStream, req: Request) {
        val keyOk = keyMatches(req.params["k"] ?: req.headers["x-otility-key"], req.from)
        when {
            req.method == "GET" && (req.path == "/" || req.path == "/index.html") ->
                send(out, 200, "text/html; charset=utf-8", page(if (keyOk) app.prefs.phoneKey else null))
            req.method == "GET" && req.path == "/icon.png" -> send(out, 200, "image/png", icon())
            req.path == "/go" && (req.method == "POST" || req.method == "GET") -> {
                if (!keyOk) return refuse(out, req)
                if (tooMany(recent, 20)) return send(out, 429, "text/plain; charset=utf-8", "Too many places at once. Try again in a minute.")
                val text = listOf("to", "place", "q", "url", "text", "address").firstNotNullOfOrNull { req.params[it]?.takeIf(String::isNotBlank) }
                    ?: req.body.takeIf { it.isNotBlank() && !req.headers["content-type"].orEmpty().contains("form") }
                val screen = req.params["screen"]?.let { s -> ScreenId.entries.firstOrNull { it.name.equals(s, true) } }
                val result = go(text, screen)
                if (wantsJson(req)) {
                    send(out, if (result.ok) 200 else 422, "application/json", JSONObject().put("ok", result.ok).put("message", result.message).toString())
                } else {
                    send(out, if (result.ok) 200 else 422, "text/plain; charset=utf-8", result.message)
                }
            }
            // Debug builds only: how a shared text is read, without opening anything.
            req.path == "/parse" && app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 -> {
                val place = Places.parse(req.params["to"] ?: req.body)
                send(out, 200, "application/json", (place?.toJson()?.put("title", place.title) ?: JSONObject()).toString())
            }
            req.method == "GET" && req.path == "/places" -> {
                if (!keyOk) return refuse(out, req)
                send(out, 200, "application/json", JSONArray(app.prefs.places.value.take(8).map { it.toJson() }).toString())
            }
            else -> send(out, 404, "text/plain; charset=utf-8", "Not here.")
        }
    }

    private fun refuse(out: OutputStream, req: Request) {
        val msg = "This phone isn't paired. Scan the code in Outo › Settings › Navigation."
        if (wantsJson(req)) send(out, 403, "application/json", JSONObject().put("ok", false).put("message", msg).toString())
        else send(out, 403, "text/plain; charset=utf-8", msg)
    }

    private fun wantsJson(req: Request) =
        req.headers["accept"].orEmpty().contains("application/json") || req.headers["content-type"].orEmpty().contains("json")

    @Synchronized
    private fun tooMany(log: ArrayDeque<Long>, limit: Int): Boolean {
        val now = System.currentTimeMillis()
        while (log.isNotEmpty() && now - log.first() > 60_000) log.removeFirst()
        log.addLast(now)
        return log.size > limit
    }

    /**
     * After 10 wrong codes in a minute from one address, that address is refused
     * even with the right one for a while. Only wrong codes count (a request with
     * no code isn't a guess), and only per address, so a stranger on the hotspot
     * can't lock the paired phone out.
     */
    @Synchronized
    private fun keyMatches(given: String?, from: String): Boolean {
        if (given.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        misses.values.forEach { log -> while (log.isNotEmpty() && now - log.first() > 60_000) log.removeFirst() }
        misses.values.removeAll { it.isEmpty() }
        val log = misses[from]
        if (log != null && log.size >= 10) return false
        val ok = MessageDigest.isEqual(given.trim().toByteArray(), app.prefs.phoneKey.toByteArray())
        if (!ok && misses.size < 256) misses.getOrPut(from) { ArrayDeque() }.addLast(now)
        return ok
    }

    private fun go(text: String?, screen: ScreenId?): Navigator.Result {
        if (!app.prefs.settings.value.phoneHandoff) return Navigator.Result(false, "Places from the phone are switched off in Outo.")
        if (text.isNullOrBlank()) return Navigator.Result(false, "Nothing was sent. Share a place or type an address.")
        val place = Places.parse(text) ?: return Navigator.Result(false, "That doesn't look like a place.")
        // Bring Otility forward, then hand over on the main thread. Not for a place
        // going to the map on the driver cluster: the main screen stays as it is.
        if (!app.navigator.toCluster()) runCatching { MainActivity.start(app) }
        return runBlocking { withContext(Dispatchers.Main) { app.navigator.go(place, screen) } }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String) = send(out, code, type, body.toByteArray(Charsets.UTF_8))

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; else -> "OK" }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    // ---- The page ---------------------------------------------------------------

    private val iconPng: ByteArray by lazy {
        val d = ContextCompat.getDrawable(app, R.drawable.ic_otility)!!
        val bmp = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 180, 180)
        d.draw(Canvas(bmp))
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun icon() = iconPng

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun page(key: String?): String {
        val navApp = esc(app.navigator.navLabel())
        val usual = app.prefs.settings.value.navScreen
        val screens = listOf(null to "Usual", ScreenId.MAIN to "Main", ScreenId.PASSENGER to "Passenger")
        val places = app.prefs.places.value.take(6)
        val body = if (key == null) """
            <h1>Outo</h1>
            <p class="note">This phone isn't paired yet. In the car, open Outo › Settings › Navigation and scan the code with the iPhone camera.</p>
        """ else """
            <h1>Where to?</h1>
            <p class="note">Opens in $navApp in the car.</p>
            <form id="f" autocomplete="off">
              <input id="to" name="to" placeholder="Address, place, or a maps link" enterkeyhint="go" autocapitalize="words" required>
              <div class="seg" role="radiogroup" aria-label="Screen">
                ${screens.joinToString("") { (id, label) ->
                    val v = id?.name ?: ""
                    val on = if (id == usual || (id == null && usual == null)) " checked" else ""
                    """<label><input type="radio" name="screen" value="$v"$on><span>$label</span></label>"""
                }}
              </div>
              <button type="submit">Drive there</button>
            </form>
            <p id="msg" role="status"></p>
            ${if (places.isEmpty()) "" else """<h2>Recent</h2><ul>${places.joinToString("") { p ->
                """<li><button class="again" data-to="${esc(placeText(p))}">${esc(p.title)}</button></li>"""
            }}</ul>"""}
            <details><summary>Send from the Share menu</summary>
              <p class="note">In the Shortcuts app, make a shortcut with one action, <b>Get Contents of URL</b>: URL <code>/go?k=…</code> (the full address is in Otility › Settings › Navigation), Method POST, Request Body Form with a text field <code>to</code> set to <i>Shortcut Input</i>. Turn on <b>Show in Share Sheet</b>. Then share any place from Maps, Google Maps or Contacts to it.</p>
            </details>
            <script>
              const K = ${JSONObject.quote(key)};
              const msg = document.getElementById('msg');
              async function send(to) {
                const screen = (document.querySelector('input[name=screen]:checked') || {}).value || '';
                msg.textContent = 'Sending…'; msg.className = '';
                try {
                  const r = await fetch('/go?k=' + encodeURIComponent(K), {
                    method: 'POST',
                    headers: { 'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: new URLSearchParams({ to, screen }),
                  });
                  const j = await r.json();
                  msg.textContent = j.message; msg.className = j.ok ? 'ok' : 'bad';
                } catch (e) {
                  msg.textContent = "Couldn't reach the car. Is this phone on the same Wi-Fi or hotspot?"; msg.className = 'bad';
                }
              }
              document.getElementById('f').addEventListener('submit', e => { e.preventDefault(); const v = document.getElementById('to').value.trim(); if (v) send(v); });
              document.querySelectorAll('.again').forEach(b => b.addEventListener('click', () => send(b.dataset.to)));
            </script>
        """
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Outo">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="color-scheme" content="dark light">
<link rel="apple-touch-icon" href="/icon.png">
<title>Outo</title>
<style>
  :root { --bg:#0a0b0d; --card:rgba(255,255,255,.07); --line:rgba(255,255,255,.12); --text:#f5f5f7; --text2:#b4b4bb; --acc:#0a84ff; --good:#30d158; --bad:#ff453a; }
  @media (prefers-color-scheme: light) { :root { --bg:#eef0f3; --card:rgba(255,255,255,.8); --line:rgba(0,0,0,.08); --text:#1d1d1f; --text2:#55555a; --acc:#007aff; --good:#248a3d; --bad:#d70015; } }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { margin:0; background:var(--bg); color:var(--text); font:17px/1.4 -apple-system, BlinkMacSystemFont, "SF Pro Text", Inter, sans-serif; }
  main { max-width:520px; margin:0 auto; padding: calc(env(safe-area-inset-top) + 28px) 16px calc(env(safe-area-inset-bottom) + 32px); }
  h1 { font-size:34px; letter-spacing:-.02em; margin:0 0 4px; }
  h2 { font-size:13px; letter-spacing:.04em; text-transform:uppercase; color:var(--text2); margin:28px 4px 8px; }
  .note { color:var(--text2); font-size:15px; margin:0 0 20px; }
  input[type=text], input:not([type]) , #to { width:100%; font:inherit; color:var(--text); background:var(--card); border:1px solid var(--line); border-radius:14px; padding:14px 16px; outline:none; }
  #to:focus { border-color:var(--acc); }
  .seg { display:flex; gap:4px; background:var(--card); border:1px solid var(--line); border-radius:999px; padding:4px; margin:12px 0; }
  .seg label { flex:1; text-align:center; }
  .seg input { position:absolute; opacity:0; pointer-events:none; }
  .seg span { display:block; padding:8px 0; border-radius:999px; font-size:15px; color:var(--text2); }
  .seg input:checked + span { background:var(--acc); color:#fff; font-weight:600; }
  button[type=submit] { width:100%; font:inherit; font-weight:600; color:#fff; background:var(--acc); border:0; border-radius:999px; padding:15px; }
  button:active { transform:scale(.98); }
  #msg { min-height:1.4em; margin:14px 4px 0; font-size:15px; }
  #msg.ok { color:var(--good); } #msg.bad { color:var(--bad); }
  ul { list-style:none; margin:0; padding:0; background:var(--card); border:1px solid var(--line); border-radius:14px; overflow:hidden; }
  li + li { border-top:1px solid var(--line); }
  .again { width:100%; text-align:left; font:inherit; color:var(--text); background:none; border:0; padding:13px 16px; }
  details { margin-top:28px; color:var(--text2); font-size:15px; }
  summary { cursor:pointer; color:var(--acc); }
  code { font-size:13px; }
</style></head>
<body><main>$body</main></body></html>"""
    }

    /** What to send again for a recent place. */
    private fun placeText(p: Place): String = when {
        p.hasPoint -> "${p.lat},${p.lng}"
        p.query != null -> p.query
        p.link != null -> p.link
        else -> p.title
    }
}

/**
 * Keeps Otility running in the background while places from the phone are
 * switched on, so a place still arrives when another app is in front.
 */
class PhoneLinkService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("phone", "Places from your phone", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(this, 0, MainActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
        notification = Notification.Builder(this, "phone")
            .setSmallIcon(R.drawable.ic_otility)
            .setContentTitle("Ready for places from your phone")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        (application as Otility).phone.start()
    }

    private lateinit var notification: Notification

    // Every startForegroundService() needs its own startForeground(), or Android
    // stops the app; so it's here rather than once in onCreate.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(1, notification) }.onFailure { Log.w("Otility", "phone link in the foreground", it) }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as Otility).phone.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Starts or stops the service to match the setting. */
        fun sync(context: Context) {
            val app = context.applicationContext as Otility
            val intent = Intent(context, PhoneLinkService::class.java)
            if (app.prefs.settings.value.phoneHandoff) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure {
                        // Not allowed from the background right now: run without the service.
                        Log.w("Otility", "phone link service", it)
                        app.phone.start()
                    }
            } else {
                context.stopService(intent)
                app.phone.stop()
            }
        }
    }
}
