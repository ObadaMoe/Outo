package app.leopard.cockpit.nav

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
 * Google Maps or Waze (WhatsApp shares a location as a Google Maps link),
 * a `geo:` link, "25.28, 51.53", 25°17'07"N 51°31'52"E, or an address.
 */
object Places {

    private val COORDS = Regex("""(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)""")

    /**
     * A point written out on its own: "25.2854, 51.5310", Google's old
     * "loc:25.2854,51.5310", or "25.2854,51.5310 (Souq Waqif)" with a name.
     */
    private val POINT_TEXT =
        Regex("""(?:loc:\s*)?\+?(-?\d{1,2}(?:\.\d+)?)\s*,\s*\+?(-?\d{1,3}(?:\.\d+)?)(?:\s*\((.*)\))?""", RegexOption.IGNORE_CASE)

    /** One half of 25°17'07.4"N or 25.2854° N: degrees, then maybe minutes and seconds. */
    private const val HALF = """(\d{1,3}(?:\.\d+)?)\s*°\s*(?:(\d{1,2}(?:\.\d+)?)\s*['′’]\s*)?(?:(\d{1,2}(?:\.\d+)?)\s*(?:["″”]|'')\s*)?"""

    /** A point in degrees, as Google Maps and Apple Maps show a dropped pin. */
    private val DEGREES = Regex("""$HALF([NS])[\s,]*$HALF([EW])""", RegexOption.IGNORE_CASE)

    private val URL_IN_TEXT = Regex("""(https?://\S+|geo:\S+|waze://\S+)""", RegexOption.IGNORE_CASE)

    /** The map's centre in a Google Maps path: /@25.28,51.53,17z. */
    private val AT = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")

    /** Direction marks and zero-width spaces: invisible, but they'd stick to names and numbers. */
    private val INVISIBLE = Regex("[​-‏‪-‮⁠-⁩﻿]")

    /** "Use Waze to drive to Souq Waqif" → "Souq Waqif". */
    private val LEAD_IN = Regex("""^.*?\b(?:drive|driving|directions|navigate|route)\s+to\s+""", RegexOption.IGNORE_CASE)

    /** Links that only point at the real one; followed (when the car is online) before reading. */
    private val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl", "g.co")

    /** Names given when a link says nothing about the place; shared text beats them. */
    internal val PLACEHOLDERS = setOf("Place from Waze", "Place from Apple Maps", "Place from Google Maps")

    /**
     * What apps call a pin that has no name. Not names: when a link can't be read
     * Waze searches for the name, and "Dropped Pin" would find somewhere random.
     */
    private val GENERIC = setOf(
        "location", "my location", "current location", "shared location", "dropped pin", "pin", "marked location",
        "pinned location", "map", "maps", "google maps", "apple maps", "waze",
    )

    private fun generic(s: String): Boolean {
        val l = s.trim().trimEnd(':').trim().lowercase()
        // WhatsApp's live location: only WhatsApp itself can follow it.
        return l in GENERIC || l.startsWith("live location")
    }

    /** A place's name from shared text, or null when the text says nothing about it ("location:", "Dropped Pin"). */
    private fun label(raw: String?): String? {
        val s = raw?.replace(INVISIBLE, "")?.trim()?.trimEnd(':', '-', '–', '—', '·')
            ?.replace(LEAD_IN, "")?.trimStart { !it.isLetterOrDigit() }?.trim() ?: return null
        return if (s.isEmpty() || s.length > 120 || generic(s)) null else s
    }

    fun parse(input: String, resolveShortLinks: Boolean = true): Place? {
        // Shared text often carries invisible direction marks (WhatsApp puts one before "location:").
        val text = input.replace(INVISIBLE, "").replace(' ', ' ').trim()
        if (text.isEmpty()) return null
        if (text.startsWith("BEGIN:VCARD", ignoreCase = true)) return vcard(text, resolveShortLinks)
        val url = URL_IN_TEXT.find(text)?.value?.let(::trimLink)
        if (url != null) {
            fromLink(url, resolveShortLinks)?.let { place ->
                // "Name\nhttps://maps..." or "Name: https://maps..." (how WhatsApp and others share): keep the name.
                val before = text.substringBefore(url).lines().firstNotNullOfOrNull(::label)
                val unnamed = place.name == null || place.name in PLACEHOLDERS
                return if (unnamed && before != null) place.copy(name = before) else place
            }
        }
        // Plain coordinates, as numbers or in degrees (a name may be on another line).
        pointIn(text)?.let { return it }
        if (text.length <= 200) DEGREES.find(text)?.let { m ->
            degrees(m)?.let { p -> return p.copy(name = text.replace(m.value, "").lines().firstNotNullOfOrNull(::label)) }
        }
        // An address or a name (a contact card shares several lines; join them). A link
        // that said nothing about a place is left out, and so are words like "Dropped Pin".
        val lines = text.lines().mapNotNull { l -> URL_IN_TEXT.replace(l, "").trim().trimEnd(':').trim().ifEmpty { null } }
        if (lines.all(::generic)) return null
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
        val name = label(value("FN")) ?: label(value("ORG"))
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
        return point(lat, lng, name)
    }

    private fun point(lat: Double, lng: Double, name: String?): Place? {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        if (lat == 0.0 && lng == 0.0) return null
        return Place(name = name, lat = lat, lng = lng)
    }

    /** [v] if it's nothing but a point ("25.28,51.53", "loc:…", "… (Name)", or in degrees). */
    private fun pointIn(v: String, name: String? = null): Place? {
        val t = v.trim()
        POINT_TEXT.matchEntire(t)?.let { m -> return point(m.groupValues[1], m.groupValues[2], name ?: label(m.groupValues[3])) }
        DEGREES.matchEntire(t)?.let { m -> return degrees(m)?.copy(name = name) }
        return null
    }

    private fun degrees(m: MatchResult): Place? {
        fun half(at: Int): Double? {
            val d = m.groupValues[at].toDoubleOrNull() ?: return null
            val min = m.groupValues[at + 1].toDoubleOrNull() ?: 0.0
            val sec = m.groupValues[at + 2].toDoubleOrNull() ?: 0.0
            if (min >= 60 || sec >= 60) return null
            return d + min / 60 + sec / 3600
        }
        val lat = half(1) ?: return null
        val lng = half(5) ?: return null
        return point(
            if (m.groupValues[4].equals("S", ignoreCase = true)) -lat else lat,
            if (m.groupValues[8].equals("W", ignoreCase = true)) -lng else lng,
            null,
        )
    }

    private fun decode(s: String?): String? =
        s?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it.replace('+', ' ')) }?.trim()?.ifEmpty { null }

    /**
     * A link split into its parts. Read by hand, leniently: shared links aren't
     * always strictly valid (spaces, Arabic, stray "|"), and this way the parsing
     * runs in plain unit tests, where Android's Uri isn't there.
     */
    private class Link(raw: String) {
        val scheme: String?
        val host: String
        val path: String
        /** Everything after "scheme:" (for geo: links). */
        val rest: String
        private val query: String

        init {
            val s = raw.trim().substringBefore('#')
            val colon = s.indexOf(':')
            val sch = if (colon > 0) s.substring(0, colon) else ""
            scheme = if (sch.isNotEmpty() && sch.first().isLetter() && sch.all { it.isLetterOrDigit() || it in "+-." }) sch.lowercase() else null
            rest = if (scheme != null) s.substring(colon + 1) else s
            var r = rest
            if (r.startsWith("//")) {
                val authority = r.substring(2).takeWhile { it != '/' && it != '?' }
                host = authority.substringAfterLast('@').substringBefore(':').lowercase()
                r = r.substring(2 + authority.length)
            } else {
                host = ""
            }
            path = r.substringBefore('?')
            query = r.substringAfter('?', "")
        }

        /** The first non-empty value of [key], decoded ("+" is a space). */
        fun param(key: String): String? = query.split('&').firstNotNullOfOrNull { pair ->
            if (decode(pair.substringBefore('='))?.equals(key, ignoreCase = true) == true) decode(pair.substringAfter('=', "")) else null
        }
    }

    fun fromLink(link: String, resolveShortLinks: Boolean): Place? {
        val l = Link(link)
        val scheme = l.scheme
        val host = l.host.removePrefix("www.")

        if (scheme == "geo") {
            // geo:25.2,51.5?q=25.2,51.5(Name)  or  geo:0,0?q=Some+address
            val q = l.param("q")
            val label = q?.let { Regex("""\((.+)\)$""").find(it)?.groupValues?.get(1) }
            val qCoords = q?.let { COORDS.find(it) }
            if (qCoords != null) point(qCoords.groupValues[1], qCoords.groupValues[2], label)?.let { return it }
            val c = COORDS.find(l.path)
            if (c != null) point(c.groupValues[1], c.groupValues[2], label)?.let { return it }
            return q?.let { Place(query = it) }
        }

        if (scheme == "waze" || host == "waze.com" || host.endsWith(".waze.com")) {
            val q = l.param("q")
            // ll=25.28,51.53, or the live map's to=ll.25.28,51.53.
            val ll = (l.param("ll") ?: l.param("to")?.takeIf { it.startsWith("ll.", ignoreCase = true) }?.drop(3))
                ?.let { COORDS.find(it) }
            val p = ll?.let { point(it.groupValues[1], it.groupValues[2], label(q)) }
            if (p != null) return p.copy(link = link)
            if (q != null) return Place(query = q, link = link)
            // A Waze share link (waze.com/ul/h..., ul?place=...) only Waze can read.
            return Place(name = "Place from Waze", link = link)
        }

        if (host == "maps.apple.com" || host == "maps.apple" || host.endsWith(".maps.apple.com")) {
            if (host == "maps.apple") {
                if (resolveShortLinks) follow(link)?.let { if (it != link) fromLink(it, false)?.let { p -> return p } }
                // Offline: keep the link; only some apps can open it.
                return Place(name = "Place from Apple Maps", link = link)
            }
            val q = l.param("q")?.takeIf { !generic(it) }
            val name = label(l.param("name")) ?: q?.takeIf { pointIn(it) == null }
            // The place itself (?ll=…&q=Dropped%20Pin, /place?coordinate=…, /directions?destination=…).
            l.param("coordinate")?.let { COORDS.find(it) }?.let { c -> point(c.groupValues[1], c.groupValues[2], name)?.let { return it } }
            l.param("ll")?.let { COORDS.find(it) }?.let { c -> point(c.groupValues[1], c.groupValues[2], name)?.let { return it } }
            for (key in listOf("daddr", "destination", "q")) l.param(key)?.let { pointIn(it, name) }?.let { return it }
            val address = l.param("address") ?: l.param("daddr") ?: l.param("destination") ?: q ?: l.param("query")
            if (address != null) return Place(name = name, query = address)
            // Only a search's centre says where: better than nothing.
            for (key in listOf("sll", "center")) {
                val c = l.param(key)?.let { COORDS.find(it) } ?: continue
                point(c.groupValues[1], c.groupValues[2], name)?.let { return it }
            }
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
            val path = l.path
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
            // ?q=25.2854%2C51.5310 (what WhatsApp shares), ?q=Souq+Waqif, ?api=1&query=…
            val ll = (l.param("ll") ?: l.param("center"))?.let(::pointIn)
            for (key in listOf("destination", "daddr", "q", "query")) {
                val v = l.param(key) ?: continue
                pointIn(v, placeName(path))?.let { return it }
                if (generic(v)) continue
                return ll?.copy(name = placeName(path) ?: label(v)) ?: Place(name = placeName(path), query = v)
            }
            ll?.let { return it.copy(name = placeName(path)) }
            placeName(path)?.let { name ->
                // /maps/place/25.2854,51.5310 or /maps/search/25.2854,+51.5310: a point, not a name.
                pointIn(name)?.let { return it }
                val at = AT.find(path)
                return Place(name = name, query = name, lat = at?.groupValues?.get(1)?.toDoubleOrNull(), lng = at?.groupValues?.get(2)?.toDoubleOrNull())
            }
            AT.find(path)?.let { m -> point(m.groupValues[1], m.groupValues[2])?.let { return it } }
            return null
        }

        // Any other link: look for coordinates in it.
        COORDS.find(decode(link) ?: link)?.let { m -> point(m.groupValues[1], m.groupValues[2])?.let { return it } }
        return null
    }

    /** "/maps/place/Souq+Waqif/@25.28,..." or "/maps/search/Souq+Waqif" → "Souq Waqif". */
    private fun placeName(path: String): String? {
        val seg = Regex("""/(?:place|search)/([^/]+)""").find(path)?.groupValues?.get(1) ?: return null
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
