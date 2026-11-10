package app.leopard.cockpit.nav

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * A place to drive to: a point on the map, an address or name to search for,
 * or both. [link] keeps a navigation app's own link (a Waze link) as it came.
 */
data class Place(
    val name: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val query: String? = null,
    val link: String? = null,
    val at: Long = System.currentTimeMillis(),
) {
    val hasPoint: Boolean get() = lat != null && lng != null

    /** What to call it in lists. */
    val title: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: query?.takeIf { it.isNotBlank() }
            ?: if (hasPoint) "%.5f, %.5f".format(Locale.US, lat, lng) else "Shared place"

    /** The same place, for "same place again?" checks. */
    fun sameAs(o: Place): Boolean =
        if (hasPoint && o.hasPoint) Math.abs(lat!! - o.lat!!) < 1e-4 && Math.abs(lng!! - o.lng!!) < 1e-4
        else title.equals(o.title, ignoreCase = true)

    fun toJson(): JSONObject = JSONObject().apply {
        name?.let { put("name", it) }
        lat?.let { put("lat", it) }
        lng?.let { put("lng", it) }
        query?.let { put("q", it) }
        link?.let { put("link", it) }
        put("at", at)
    }

    companion object {
        fun fromJson(o: JSONObject) = Place(
            name = o.optString("name").ifEmpty { null },
            lat = if (o.has("lat")) o.getDouble("lat") else null,
            lng = if (o.has("lng")) o.getDouble("lng") else null,
            query = o.optString("q").ifEmpty { null },
            link = o.optString("link").ifEmpty { null },
            at = o.optLong("at", System.currentTimeMillis()),
        )
    }
}

/**
 * Turns whatever the phone shared into a [Place]: a link from Apple Maps,
 * Google Maps or Waze, a `geo:` link, "25.28, 51.53", or an address.
 */
object Places {

    private val COORDS = Regex("""(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)""")
    private val URL_IN_TEXT = Regex("""(https?://\S+|geo:\S+|waze://\S+)""", RegexOption.IGNORE_CASE)

    /** Links that only point at the real one; followed (when the car is online) before reading. */
    private val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl", "g.co")

    /** Names given when a link says nothing about the place; shared text beats them. */
    internal val PLACEHOLDERS = setOf("Place from Waze", "Place from Apple Maps", "Place from Google Maps")

    fun parse(input: String, resolveShortLinks: Boolean = true): Place? {
        val text = input.trim().replace(' ', ' ')
        if (text.isEmpty()) return null
        if (text.startsWith("BEGIN:VCARD", ignoreCase = true)) return vcard(text, resolveShortLinks)
        val url = URL_IN_TEXT.find(text)?.value?.let(::trimLink)
        if (url != null) {
            fromLink(url, resolveShortLinks)?.let { place ->
                // "Name\nhttps://maps..." (how some apps share): keep the name.
                val before = text.substringBefore(url).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                val unnamed = place.name == null || place.name in PLACEHOLDERS
                return if (unnamed && before != null && before.length <= 120) place.copy(name = before) else place
            }
        }
        // Plain coordinates.
        val whole = COORDS.matchEntire(text)
        if (whole != null) point(whole.groupValues[1], whole.groupValues[2])?.let { return it }
        // An address or a name (a contact card shares several lines; join them).
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("http", true) }
        val query = lines.joinToString(", ").take(300)
        return if (query.isEmpty()) null else Place(query = query)
    }

    /**
     * A contact card (Apple Maps shares a place this way): its name, its map
     * link, or its address.
     */
    private fun vcard(text: String, resolveShortLinks: Boolean): Place? {
        // Folded lines continue with a space.
        val lines = text.replace(Regex("""\r?\n[ \t]"""), "").lines()
        fun value(field: String) = lines.firstOrNull { l ->
            // "item1.URL;type=pref:http://…" → URL
            val key = l.substringBefore(':').substringBefore(';').substringAfterLast('.')
            key.equals(field, ignoreCase = true)
        }?.substringAfter(':')?.replace("\\,", ",")?.replace("\\;", ";")?.replace("\\n", " ")?.trim()?.ifEmpty { null }
        val name = value("FN") ?: value("ORG")
        val link = value("URL")?.let(::trimLink)
        val address = value("ADR")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(", ")?.ifEmpty { null }
        link?.let { fromLink(it, resolveShortLinks) }?.let { p ->
            return if (name != null) p.copy(name = name) else p
        }
        return when {
            address != null -> Place(name = name, query = address)
            name != null -> Place(query = name)
            else -> null
        }
    }

    /** Drops punctuation a sentence put after a link, but keeps a closing ")" that belongs to it. */
    private fun trimLink(link: String): String {
        var l = link.trimEnd('.', ',', '>', '"', '\'', ';', '!')
        while (l.endsWith(")") && l.count { it == ')' } > l.count { it == '(' }) l = l.dropLast(1)
        return l
    }

    private fun point(a: String, b: String, name: String? = null): Place? {
        val lat = a.toDoubleOrNull() ?: return null
        val lng = b.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        if (lat == 0.0 && lng == 0.0) return null
        return Place(name = name, lat = lat, lng = lng)
    }

    private fun decode(s: String?): String? =
        s?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it.replace('+', ' ')) }?.trim()?.ifEmpty { null }

    fun fromLink(link: String, resolveShortLinks: Boolean): Place? {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: ""

        if (scheme == "geo") {
            // geo:25.2,51.5?q=25.2,51.5(Name)  or  geo:0,0?q=Some+address
            val ssp = uri.schemeSpecificPart ?: ""
            val coords = ssp.substringBefore('?')
            val q = decode(ssp.substringAfter("q=", "").substringBefore('&'))
            val label = q?.let { Regex("""\((.+)\)$""").find(it)?.groupValues?.get(1) }
            val qCoords = q?.let { COORDS.find(it) }
            if (qCoords != null) point(qCoords.groupValues[1], qCoords.groupValues[2], label)?.let { return it }
            val c = COORDS.find(coords)
            if (c != null) point(c.groupValues[1], c.groupValues[2], label)?.let { return it }
            return q?.let { Place(query = it) }
        }

        if (scheme == "waze" || host.endsWith("waze.com")) {
            val ll = uri.getQueryParameter("ll")?.let { COORDS.find(it) }
            val q = uri.getQueryParameter("q")
            val p = ll?.let { point(it.groupValues[1], it.groupValues[2], q) }
            if (p != null) return p.copy(link = link)
            if (q != null) return Place(query = q, link = link)
            // A Waze share link (waze.com/ul/h...) only Waze can read.
            return Place(name = "Place from Waze", link = link)
        }

        if (host == "maps.apple.com" || host == "maps.apple" || host.endsWith(".maps.apple.com")) {
            if (host == "maps.apple") {
                if (resolveShortLinks) follow(link)?.let { if (it != link) fromLink(it, false)?.let { p -> return p } }
                // Offline: keep the link; only some apps can open it.
                return Place(name = "Place from Apple Maps", link = link)
            }
            val name = uri.getQueryParameter("name") ?: uri.getQueryParameter("q")?.takeIf { COORDS.matchEntire(it) == null }
            for (key in listOf("coordinate", "ll", "daddr", "sll", "center")) {
                val v = uri.getQueryParameter(key) ?: continue
                val c = COORDS.find(v) ?: continue
                point(c.groupValues[1], c.groupValues[2], name)?.let { return it }
            }
            val address = uri.getQueryParameter("address") ?: uri.getQueryParameter("daddr") ?: uri.getQueryParameter("q")
            if (address != null) return Place(name = name, query = address)
            return null
        }

        val google = host.startsWith("google.") || host.startsWith("maps.google.") || host == "maps.app.goo.gl" ||
            host == "goo.gl" || host == "g.co"
        if (google) {
            if (host in SHORT_HOSTS) {
                if (resolveShortLinks) follow(link)?.let { if (it != link) fromLink(it, false)?.let { p -> return p } }
                // Couldn't follow it (offline): Google Maps can still open the link itself.
                return Place(name = "Place from Google Maps", link = link)
            }
            val path = uri.path ?: ""
            val decoded = decode(link) ?: link
            // Directions (/maps/dir/Home/Souq+Waqif/@…): the last stop, not the map's
            // centre. Its point is the last !1d(lng)!2d(lat) pair, else its name.
            if ("/dir/" in path) {
                val stops = path.substringAfter("/dir/").split('/')
                    .filter { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("data=") }
                val dest = stops.lastOrNull()?.let(::decode)
                val destPoint = dest?.let { COORDS.matchEntire(it) }
                val name = dest?.takeIf { destPoint == null }
                Regex("""!1d(-?\d+\.\d+)!2d(-?\d+\.\d+)""").findAll(decoded).lastOrNull()?.let { m ->
                    point(m.groupValues[2], m.groupValues[1], name)?.let { return it }
                }
                if (destPoint != null) point(destPoint.groupValues[1], destPoint.groupValues[2])?.let { return it }
                if (name != null) return Place(query = name)
            }
            // The pin itself: ...!3d25.28!4d51.53
            Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""").find(decoded)?.let { m ->
                point(m.groupValues[1], m.groupValues[2], placeName(path))?.let { return it }
            }
            for (key in listOf("destination", "daddr", "q", "query", "ll")) {
                val v = uri.getQueryParameter(key) ?: continue
                val c = COORDS.matchEntire(v.trim())
                if (c != null) point(c.groupValues[1], c.groupValues[2], placeName(path))?.let { return it }
                return Place(name = placeName(path), query = v)
            }
            placeName(path)?.let { name ->
                val at = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""").find(path)
                return Place(name = name, query = name, lat = at?.groupValues?.get(1)?.toDoubleOrNull(), lng = at?.groupValues?.get(2)?.toDoubleOrNull())
            }
            Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""").find(path)?.let { m -> point(m.groupValues[1], m.groupValues[2])?.let { return it } }
            return null
        }

        // Any other link: look for coordinates in it.
        COORDS.find(decode(link) ?: link)?.let { m -> point(m.groupValues[1], m.groupValues[2])?.let { return it } }
        return null
    }

    /** "/maps/place/Souq+Waqif/@25.28,..." → "Souq Waqif". */
    private fun placeName(path: String): String? {
        val seg = Regex("""/place/([^/]+)""").find(path)?.groupValues?.get(1) ?: return null
        return decode(seg)
    }

    /** Follows a short link's redirects (a few hops) and returns where it ends. */
    private fun follow(link: String): String? = runCatching {
        var current = link
        repeat(4) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Outo")
            }
            try {
                val code = c.responseCode
                val next = c.getHeaderField("Location")
                if (code !in 300..399 || next == null) return@runCatching current
                current = URL(URL(current), next).toString()
            } finally {
                c.disconnect()
            }
        }
        current
    }.getOrNull()

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
