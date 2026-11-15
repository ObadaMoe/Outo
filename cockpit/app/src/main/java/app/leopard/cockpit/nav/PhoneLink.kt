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
import app.leopard.cockpit.ui.Alert
import app.leopard.cockpit.ui.AlertButton
import app.leopard.cockpit.ui.Alerts
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
 * Personal Hotspot, or the car's own hotspot). The phone's "Send to Car"
 * shortcut (in its Share menu) or a small page sends the place here; the page
 * also walks through making the shortcut. Every request carries the pairing
 * code shown in Settings.
 */
class PhoneLink(private val app: Otility) {

    companion object {
        const val PORT = 8686
        private const val TAG = "Otility"
        private const val MAX_BODY = 16 * 1024
        /** Wi-Fi interface names: joined to a network (wlan) or the car's hotspot (ap, swlan). */
        private val WIFI = listOf("wlan", "ap", "swlan")
        /** The page's test place: a link back to this car, shared like any place. */
        private val TEST_PLACE = Regex("""https?://[^/\s]+:$PORT/test\b""")
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
            // The test place's link, tapped instead of shared. Says nothing about the pairing.
            req.method == "GET" && req.path == "/test" -> send(out, 200, "text/html; charset=utf-8", testPage())
            req.path == "/go" && (req.method == "POST" || req.method == "GET") -> {
                if (!keyOk) return refuse(out, req)
                if (tooMany(recent, 20)) return send(out, 429, "text/plain; charset=utf-8", "Too many places at once. Try again in a minute.")
                // Field names in any case: the iPhone keyboard may have typed the shortcut's "to" as "To".
                val fields = req.params.mapKeys { it.key.trim().lowercase() }
                val text = listOf("to", "place", "q", "url", "text", "address").firstNotNullOfOrNull { fields[it]?.takeIf(String::isNotBlank) }
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
        if (TEST_PLACE.containsMatchIn(text)) return test()
        val place = Places.parse(text) ?: return Navigator.Result(false, "That doesn't look like a place.")
        // Bring Otility forward, then hand over on the main thread. Not for a place
        // going to the map on the driver cluster: the main screen stays as it is.
        if (!app.navigator.toCluster()) runCatching { MainActivity.start(app) }
        return runBlocking { withContext(Dispatchers.Main) { app.navigator.go(place, screen) } }
    }

    /**
     * The test place: nothing opens in the map, the car only says it arrived, so
     * the driver sees the whole path (Wi-Fi, pairing, shortcut) works.
     */
    private fun test(): Navigator.Result {
        val navApp = navName()
        Alerts.show(
            Alert(
                "Send to Car works",
                "Places you share on your iPhone will open in ${navApp ?: "the map"} here.",
                listOf(AlertButton("OK")),
            ),
        )
        runCatching { MainActivity.start(app) }
        return Navigator.Result(true, "The car got it. Places you send will open in ${navApp ?: "the car"}.")
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

    /** The app places open in, for the page ("Waze"), or null when none is set. */
    private fun navName(): String? = app.navigator.navLabel().takeIf { it != "Navigation" }

    /**
     * The phone's page. Paired, it walks through making the "Send to Car" shortcut
     * (the iPhone's Share menu then sends places here), lets the driver test it,
     * and keeps a box to type or paste a place. Everything is inline: the car is
     * often offline, so nothing can come from the internet.
     */
    private fun page(key: String?): String {
        if (key == null) return shell(
            """
            <header class="hero">
              <img class="appicon" src="/icon.png" alt="" width="64" height="64">
              <h1>Send to Car</h1>
              <p class="lede">This iPhone isn't paired yet.</p>
            </header>
            <div class="card pad">
              <p>In the car, open <b>Outo › Settings › Navigation</b> and scan the code there with the iPhone camera.</p>
            </div>
            """,
        )
        val navApp = navName()?.let(::esc)
        val opensIn = if (navApp != null) "opens in $navApp in the car" else "opens in the car"
        val usual = app.prefs.settings.value.navScreen
        val screens = listOf(null to "Usual", ScreenId.MAIN to "Main", ScreenId.PASSENGER to "Passenger")
        val places = app.prefs.places.value.take(6)
        // One action per step, in the Shortcuts app's own words. Show in Share Sheet
        // comes after the action is made: turned on first, Shortcuts can fill the
        // new action's URL with Shortcut Input, and step 5 would no longer match.
        val steps = listOf(
            """<p><b>Copy this link.</b> The shortcut sends places to it.</p>
               <input id="link" class="link" readonly aria-label="Link for the shortcut" value="">
               <button class="btn primary" id="copy" type="button">Copy Link</button>
               <p id="copymsg" class="status" role="status"></p>""",
            """<p><b>Open Shortcuts.</b> A new shortcut opens.</p>
               <a class="btn tinted" href="shortcuts://create-shortcut">Open Shortcuts</a>
               <p class="sub">Or open the Shortcuts app and tap <b>+</b>.</p>""",
            "<p>Tap <b>New Shortcut</b> at the top, tap <b>Rename</b>, and type <b>Send to Car</b>.</p>",
            "<p>Tap <b>Add Action</b>, search for <b>Get Contents of URL</b>, and tap it.</p>",
            "<p>Tap <b>URL</b> in the action and paste the link.</p>",
            "<p>Tap <b>›</b> on the action to show more. Tap <b>GET</b> and choose <b>POST</b>.</p>",
            "<p>Next to Request Body, tap <b>JSON</b> and choose <b>Form</b>.</p>",
            "<p>Tap <b>Add new field</b>, choose <b>Text</b>, and type <code>to</code> as the key.</p>",
            "<p>Tap <b>ⓘ</b>, turn on <b>Show in Share Sheet</b>, and tap <b>Done</b>.</p>",
            "<p>Back in the action, tap <b>Text</b> next to <code>to</code> and choose <b>Shortcut Input</b>. Tap <b>Done</b>.</p>",
        )
        return shell(
            """
            <header class="hero">
              <img class="appicon" src="/icon.png" alt="" width="64" height="64">
              <h1>Send to Car</h1>
              <p class="lede">Share a place from WhatsApp, Waze, Google Maps or Apple Maps, and it $opensIn.</p>
            </header>

            <details id="setup" class="block" open>
              <summary><span class="h2">Set it up</span><span class="hint">Once, in about two minutes</span></summary>
              <ol class="card steps">
                ${steps.mapIndexed { i, s -> """<li><span class="n">${i + 1}</span><div class="s">$s</div></li>""" }.joinToString("")}
              </ol>
              <p class="caption">When you're done, the shortcut looks like this:</p>
              <div class="mock" aria-hidden="true">
                <div class="act">Receive <i>Any</i> input from <i>Share Sheet</i></div>
                <div class="act">Get contents of <i id="mocklink">the link</i>
                  <div class="kv"><span>Method</span><i>POST</i></div>
                  <div class="kv"><span>Request Body</span><i>Form</i></div>
                  <div class="kv"><span>to</span><i class="var">Shortcut Input</i></div>
                </div>
              </div>
            </details>
            <script>try { if (document.documentElement.classList.contains('ready')) document.getElementById('setup').open = false; } catch (e) {}</script>

            <section id="try" class="block">
              <h2 class="h2">Try it</h2>
              <div class="card pad">
                <button class="btn primary" id="test" type="button">Send a Test Place</button>
                <p id="testmsg" class="status" role="status"></p>
                <p class="sub">To try the shortcut too, tap and hold <a href="/test">this test place</a>, tap <b>Share</b>, then <b>Send to Car</b>. The car says when it arrives.</p>
              </div>
            </section>

            <section id="use" class="block">
              <h2 class="h2">How to use it</h2>
              <div class="card list">
                <div class="row">
                  <span class="badge green"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3C6.9 3 3 6.6 3 11c0 2.2 1 4.2 2.6 5.6L5 21l4.1-2.1c.9.3 1.9.4 2.9.4 5.1 0 9-3.6 9-8.1S17.1 3 12 3z"/></svg></span>
                  <div><b>From WhatsApp</b><p>Open the location in the chat, tap <b>Share</b>, then <b>Send to Car</b>.</p></div>
                </div>
                <div class="row">
                  <span class="badge blue"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/></svg></span>
                  <div><b>From Waze, Google Maps or Apple Maps</b><p>Open a place, tap <b>Share</b>, then <b>Send to Car</b>.</p></div>
                </div>
              </div>
              <p class="foot">Send to Car not in WhatsApp's list? Open the location in Google Maps or Waze, and share it from there. To keep it near the top, scroll to the end of the Share menu and tap <b>Edit Actions</b>.</p>
            </section>

            <section id="type" class="block">
              <h2 class="h2"><span class="first">Or type a place</span><span class="later">Where to?</span></h2>
              <form id="f" autocomplete="off">
                <input id="to" name="to" placeholder="Address, place, or a maps link" enterkeyhint="go" autocapitalize="words" required>
                <div class="seg" role="radiogroup" aria-label="Screen">
                  ${screens.joinToString("") { (id, label) ->
                      val v = id?.name ?: ""
                      val on = if (id == usual || (id == null && usual == null)) " checked" else ""
                      """<label><input type="radio" name="screen" value="$v"$on><span>$label</span></label>"""
                  }}
                </div>
                <button class="btn primary" type="submit">Drive There</button>
              </form>
              <p id="msg" class="status" role="status"></p>
              ${if (places.isEmpty()) "" else """<p class="caption">Recent</p><ul class="card recent">${places.joinToString("") { p ->
                  """<li><button class="again" type="button" data-to="${esc(placeText(p))}">${esc(p.title)}</button></li>"""
              }}</ul>"""}
              <p class="foot">Tip: in Safari, tap Share › Add to Home Screen to keep this page one tap away.</p>
            </section>

            <script>
              const K = ${JSONObject.quote(key)};
              const LINK = location.origin + '/go?k=' + encodeURIComponent(K);
              const byId = id => document.getElementById(id);
              byId('link').value = LINK;
              byId('mocklink').textContent = LINK;
              function say(el, text, kind) { el.textContent = text; el.className = 'status ' + (kind || ''); }

              // The page is plain http, so the clipboard API isn't there: select the
              // field and copy the old way, which Safari still allows on a tap.
              function copyLink() {
                const box = byId('link');
                box.focus();
                box.select();
                box.setSelectionRange(0, box.value.length);
                let ok = false;
                try { ok = document.execCommand('copy'); } catch (e) {}
                box.blur();
                try { window.getSelection().removeAllRanges(); } catch (e) {}
                return ok;
              }
              let copyTimer = 0;
              byId('copy').addEventListener('click', () => {
                const b = byId('copy');
                if (copyLink()) {
                  b.textContent = 'Copied'; b.classList.add('done');
                  say(byId('copymsg'), 'Now open Shortcuts.', 'ok');
                  clearTimeout(copyTimer);
                  copyTimer = setTimeout(() => { b.textContent = 'Copy Link'; b.classList.remove('done'); }, 2500);
                } else {
                  say(byId('copymsg'), 'Tap and hold the link above, then tap Copy.', 'bad');
                }
              });

              async function post(to, screen) {
                const r = await fetch('/go?k=' + encodeURIComponent(K), {
                  method: 'POST',
                  headers: { 'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
                  body: new URLSearchParams({ to: to, screen: screen || '' }),
                });
                return r.json();
              }
              async function send(el, to, screen) {
                say(el, 'Sending…');
                try {
                  const j = await post(to, screen);
                  say(el, j.message, j.ok ? 'ok' : 'bad');
                  return j.ok;
                } catch (e) {
                  say(el, "Couldn't reach the car. Is this iPhone on the same Wi-Fi or hotspot as the car?", 'bad');
                  return false;
                }
              }
              byId('test').addEventListener('click', async () => {
                // Next time, the page opens on the place box with the steps folded away.
                if (await send(byId('testmsg'), location.origin + '/test')) try { localStorage.setItem('outo.ready', '1'); } catch (e) {}
              });
              byId('f').addEventListener('submit', e => {
                e.preventDefault();
                const v = byId('to').value.trim();
                const screen = (document.querySelector('input[name=screen]:checked') || {}).value || '';
                if (v) send(byId('msg'), v, screen);
              });
              document.querySelectorAll('.again').forEach(b => b.addEventListener('click', () => {
                const screen = (document.querySelector('input[name=screen]:checked') || {}).value || '';
                send(byId('msg'), b.dataset.to, screen);
              }));
            </script>
            """,
        )
    }

    /** What the test link shows if it's tapped rather than shared. */
    private fun testPage() = shell(
        """
        <header class="hero">
          <img class="appicon" src="/icon.png" alt="" width="64" height="64">
          <h1>Test place</h1>
          <p class="lede">This link is for trying Send to Car.</p>
        </header>
        <div class="card pad">
          <p>Go back, then tap and hold the link instead of tapping it. Tap <b>Share</b>, then <b>Send to Car</b>.</p>
        </div>
        """,
    )

    private fun shell(body: String): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="format-detection" content="telephone=no">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Outo">
<meta name="color-scheme" content="light dark">
<link rel="apple-touch-icon" href="/icon.png">
<title>Send to Car</title>
<script>try { if (navigator.standalone || localStorage.getItem('outo.ready')) document.documentElement.classList.add('ready'); } catch (e) {}</script>
<style>
  :root { --bg:#f2f2f7; --card:#fff; --line:rgba(60,60,67,.16); --text:#000; --text2:rgba(60,60,67,.64); --fill:rgba(118,118,128,.12);
    --acc:#007aff; --acc-soft:rgba(0,122,255,.12); --good:#248a3d; --bad:#d70015; --green:#34c759; --mock:#e5e5ea; }
  @media (prefers-color-scheme: dark) { :root { --bg:#000; --card:#1c1c1e; --line:rgba(84,84,88,.5); --text:#fff; --text2:rgba(235,235,245,.62);
    --fill:rgba(118,118,128,.24); --acc:#0a84ff; --acc-soft:rgba(10,132,255,.2); --good:#30d158; --bad:#ff453a; --green:#30d158; --mock:#2c2c2e; } }
  * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  html { -webkit-text-size-adjust:100%; }
  body { margin:0; background:var(--bg); color:var(--text); font:17px/1.42 -apple-system, BlinkMacSystemFont, "SF Pro Text", "Helvetica Neue", sans-serif; }
  main { display:flex; flex-direction:column; max-width:560px; margin:0 auto;
    padding:calc(env(safe-area-inset-top) + 28px) max(16px, env(safe-area-inset-right)) calc(env(safe-area-inset-bottom) + 40px) max(16px, env(safe-area-inset-left)); }
  .hero { order:-2; text-align:center; margin:0 0 8px; }
  .ready #type { order:-1; }
  .later, .ready .first { display:none; } .ready .later { display:inline; }
  .appicon { display:block; width:64px; height:64px; margin:0 auto 12px; border-radius:15px; }
  h1 { font-size:34px; line-height:1.12; letter-spacing:-.02em; margin:0 0 8px; }
  .lede { color:var(--text2); margin:0 auto; max-width:24em; }
  .block { margin-top:32px; }
  .h2 { display:block; font-size:22px; font-weight:700; letter-spacing:-.01em; margin:0 4px 10px; }
  summary { list-style:none; cursor:pointer; position:relative; padding-right:28px; margin-bottom:10px; }
  summary::-webkit-details-marker { display:none; }
  summary .h2 { margin-bottom:2px; }
  summary::after { content:""; position:absolute; right:8px; top:9px; width:9px; height:9px; border-right:2.5px solid var(--text2); border-bottom:2.5px solid var(--text2); transform:rotate(45deg); transition:transform .2s; }
  details:not([open]) summary::after { transform:rotate(-45deg); top:11px; }
  .hint { display:block; color:var(--text2); font-size:15px; margin:0 4px; }
  .card { background:var(--card); border-radius:14px; margin:0; padding:0; list-style:none; overflow:hidden; }
  .pad { padding:16px; }
  .hero + .card { margin-top:24px; }
  .card p, .s p { margin:0; }
  .steps li { display:flex; gap:12px; padding:14px 16px 14px 14px; position:relative; }
  .steps li + li::before { content:""; position:absolute; top:0; left:52px; right:0; border-top:1px solid var(--line); }
  .n { flex:none; width:26px; height:26px; border-radius:13px; background:var(--acc); color:#fff; font-size:15px; font-weight:600; line-height:26px; text-align:center; font-variant-numeric:tabular-nums; margin-top:-1px; }
  .s { flex:1; min-width:0; }
  .s .btn, .s .link { margin-top:12px; }
  b { font-weight:600; }
  code { font:600 16px ui-monospace, "SF Mono", Menlo, monospace; background:var(--fill); border-radius:6px; padding:1px 6px; }
  .link { display:block; width:100%; font:15px ui-monospace, "SF Mono", Menlo, monospace; color:var(--text); background:var(--fill); border:0; border-radius:10px; padding:12px; outline:none; text-overflow:ellipsis; -webkit-user-select:text; user-select:text; }
  .btn { display:block; width:100%; min-height:50px; font:inherit; font-weight:600; text-align:center; text-decoration:none; border:0; border-radius:14px; padding:14px 16px; cursor:pointer; transition:transform .1s, background-color .2s; }
  .btn:active { transform:scale(.98); }
  .primary { background:var(--acc); color:#fff; }
  .primary.done { background:var(--green); }
  .tinted { background:var(--acc-soft); color:var(--acc); }
  .sub { color:var(--text2); font-size:15px; margin-top:10px !important; }
  .status { min-height:0; margin:0; font-size:15px; color:var(--text2); }
  .status:not(:empty) { margin-top:10px; }
  .status.ok { color:var(--good); } .status.bad { color:var(--bad); }
  a { color:var(--acc); }
  .caption { color:var(--text2); font-size:15px; margin:18px 4px 8px; }
  .mock { background:var(--mock); border-radius:14px; padding:10px; display:grid; gap:8px; font-size:15px; }
  .act { background:var(--card); border-radius:11px; padding:11px 12px; overflow-wrap:anywhere; }
  .act i { font-style:normal; color:var(--acc); background:var(--acc-soft); border-radius:6px; padding:1px 6px; }
  .kv { display:flex; justify-content:space-between; gap:12px; border-top:1px solid var(--line); margin-top:10px; padding-top:10px; }
  .kv span { color:var(--text2); }
  .act .var { color:#fff; background:var(--acc); }
  .list .row { display:flex; gap:14px; align-items:flex-start; padding:14px 16px; position:relative; }
  .list .row + .row::before { content:""; position:absolute; top:0; left:60px; right:0; border-top:1px solid var(--line); }
  .list p { color:var(--text2); font-size:15px; margin-top:2px; }
  .badge { flex:none; width:30px; height:30px; border-radius:8px; display:grid; place-items:center; }
  .badge svg { width:18px; height:18px; fill:#fff; }
  .green { background:var(--green); } .blue { background:var(--acc); }
  .foot { color:var(--text2); font-size:13px; line-height:1.4; margin:10px 16px 0; }
  #to { width:100%; font:inherit; color:var(--text); background:var(--card); border:1px solid transparent; border-radius:14px; padding:14px 16px; outline:none; }
  #to:focus { border-color:var(--acc); }
  .seg { display:flex; gap:2px; background:var(--fill); border-radius:10px; padding:2px; margin:12px 0; }
  .seg label { flex:1; text-align:center; }
  .seg input { position:absolute; opacity:0; pointer-events:none; }
  .seg span { display:block; padding:7px 0; border-radius:8px; font-size:15px; font-weight:500; color:var(--text); }
  .seg input:checked + span { background:var(--card); font-weight:600; box-shadow:0 1px 3px rgba(0,0,0,.12); }
  @media (prefers-color-scheme: dark) { .seg input:checked + span { background:#636366; } }
  .recent li + li { border-top:1px solid var(--line); }
  .again { display:block; width:100%; min-height:48px; text-align:left; font:inherit; color:var(--acc); background:none; border:0; padding:13px 16px; cursor:pointer; }
  @media (prefers-reduced-motion: reduce) { * { transition:none !important; } }
</style></head>
<body><main>$body</main></body></html>"""

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
