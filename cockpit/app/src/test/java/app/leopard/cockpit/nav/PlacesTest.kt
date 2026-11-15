package app.leopard.cockpit.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What phones share for a place, and what the car makes of it. Never touches the network. */
class PlacesTest {

    private fun parse(text: String) = Places.parse(text, resolveShortLinks = false)

    private fun assertPoint(lat: Double, lng: Double, p: Place?, name: String? = null) {
        assertNotNull("no place", p)
        assertTrue("no point in $p", p!!.hasPoint)
        assertEquals(lat, p.lat!!, 1e-6)
        assertEquals(lng, p.lng!!, 1e-6)
        assertEquals(name, p.name)
    }

    // ---- WhatsApp ----------------------------------------------------------------

    @Test fun whatsAppLocation() {
        // A location shared or forwarded out of WhatsApp: a Google Maps link, comma encoded.
        assertPoint(25.2854, 51.531, parse("https://maps.google.com/maps?q=25.2854%2C51.5310&z=17&hl=en"))
        assertPoint(25.2854, 51.531, parse("https://maps.google.com/?q=25.2854,51.5310"))
        assertPoint(25.2854, 51.531, parse("http://maps.google.com/maps?q=25.2854,51.5310&ll=25.2854,51.5310&z=16"))
    }

    @Test fun whatsAppPlaceWithName() {
        // A place (not a bare pin) comes with its name, and sometimes its address, first.
        assertPoint(25.2854, 51.531, parse("Souq Waqif\nhttps://maps.google.com/maps?q=25.2854%2C51.5310&z=17&hl=en"), "Souq Waqif")
        assertPoint(25.2854, 51.531, parse("Souq Waqif\nAl Souq, Doha\n\nhttps://maps.google.com/?q=25.2854,51.5310"), "Souq Waqif")
        assertPoint(25.2854, 51.531, parse("Souq Waqif: https://maps.google.com/?q=25.2854,51.5310"), "Souq Waqif")
    }

    @Test fun whatsAppLabelIsNotAName() {
        // As in an exported chat, with the invisible direction mark WhatsApp puts first.
        assertPoint(25.2854, 51.531, parse("‎location: https://maps.google.com/?q=25.285400,51.531000"))
        assertPoint(25.2854, 51.531, parse("Location: https://maps.google.com/?q=25.2854,51.5310"))
    }

    @Test fun whatsAppLiveLocationIsNotAPlace() {
        // Only WhatsApp can follow a live location: don't send Waze to search for the words.
        assertNull(parse("Live location"))
        assertNull(parse("‎Live location ended"))
        // With its last point as a link, that point is used.
        assertPoint(25.2854, 51.531, parse("Live location\nhttps://maps.google.com/?q=25.2854,51.5310"))
    }

    // ---- Apple Maps ----------------------------------------------------------------

    @Test fun appleMapsDroppedPin() {
        // "Dropped Pin" says nothing about the place: no name, just the point.
        assertPoint(25.2854, 51.531, parse("https://maps.apple.com/?ll=25.285400,51.531000&q=Dropped%20Pin"))
        assertPoint(25.2854, 51.531, parse("https://maps.apple.com/?ll=25.2854,51.5310&q=Dropped%20Pin&t=m"))
        // Shared with a line of text before it, that line names it.
        assertPoint(25.2854, 51.531, parse("Near Souq Waqif\nhttps://maps.apple.com/?ll=25.2854,51.5310&q=Dropped%20Pin"), "Near Souq Waqif")
    }

    @Test fun appleMapsPlaces() {
        assertPoint(25.2854, 51.531, parse("https://maps.apple.com/?address=Al%20Souq,%20Doha&ll=25.2854,51.5310&q=Souq%20Waqif"), "Souq Waqif")
        assertPoint(25.2854, 51.531, parse("https://maps.apple.com/place?coordinate=25.2854,51.5310&name=Souq%20Waqif"), "Souq Waqif")
        assertPoint(25.2854, 51.531, parse("https://maps.apple.com/directions?destination=25.2854,51.5310&mode=driving"))
        assertEquals("Al Souq, Doha", parse("https://maps.apple.com/place?address=Al%20Souq,%20Doha&place-id=I1234")!!.query)
        // A search near somewhere: the search, not the map's centre.
        assertEquals("Souq Waqif", parse("https://maps.apple.com/?q=Souq%20Waqif&sll=25.29,51.53")!!.query)
    }

    @Test fun appleMapsContactCard() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:;Dropped Pin;;;\r\nFN:Dropped Pin\r\n" +
            "item1.URL;type=pref:http://maps.apple.com/?ll=25.2854,51.5310&q=Dropped%20Pin\r\nitem1.X-ABLabel:map url\r\nEND:VCARD"
        assertPoint(25.2854, 51.531, parse(card))
        val named = card.replace("FN:Dropped Pin", "FN:Souq Waqif")
        assertPoint(25.2854, 51.531, parse(named), "Souq Waqif")
    }

    // ---- Google Maps ---------------------------------------------------------------

    @Test fun googleMapsPlace() {
        val link = "https://www.google.com/maps/place/Souq+Waqif/@25.2868,51.5330,17z/data=!3m1!4b1!4m6!3m5!1s0x3e45c5:0x1!8m2!3d25.2867!4d51.5331!16s%2Fm%2F0abc"
        assertPoint(25.2867, 51.5331, parse(link), "Souq Waqif")
        assertPoint(25.2854, 51.531, parse("https://www.google.com/maps/search/?api=1&query=25.2854,51.5310"))
        assertPoint(25.2854, 51.531, parse("https://www.google.com/maps/search/25.2854,+51.5310"))
        assertPoint(25.2854, 51.531, parse("https://maps.google.com/maps?q=loc:25.2854,51.5310"))
        assertPoint(25.2854, 51.531, parse("https://www.google.com/maps/@25.2854,51.5310,15z"))
        assertEquals("Souq Waqif", parse("https://www.google.com/maps/search/?api=1&query=Souq+Waqif")!!.query)
    }

    @Test fun googleMapsShortLinkOffline() {
        // Offline the car can't follow it: the name in the text is what Waze searches for.
        val p = parse("Souq Waqif\nhttps://maps.app.goo.gl/AbC123xyz?g_st=ic")!!
        assertEquals("Souq Waqif", p.name)
        assertEquals("https://maps.app.goo.gl/AbC123xyz?g_st=ic", p.link)
        // "Dropped pin" isn't a name to search for: the link is kept for the map app.
        assertEquals("Place from Google Maps", parse("Dropped pin\nhttps://maps.app.goo.gl/AbC123xyz")!!.name)
    }

    // ---- Waze ----------------------------------------------------------------------

    @Test fun wazeLinks() {
        val ll = "https://waze.com/ul?ll=25.2854,51.5310&navigate=yes"
        assertPoint(25.2854, 51.531, parse(ll))
        assertEquals(ll, parse(ll)!!.link)
        assertPoint(
            25.2854, 51.531,
            parse("https://ul.waze.com/ul?place=ChIJabc&ll=25.28540000%2C51.53100000&navigate=yes&utm_campaign=default&utm_source=waze_website&utm_medium=lm_share_location"),
        )
        assertPoint(25.2854, 51.531, parse("https://www.waze.com/live-map/directions?to=ll.25.2854%2C51.531"))
        // Only Waze can read a place id: the link is kept as it came.
        val place = parse("https://ul.waze.com/ul?place=ChIJabc&navigate=yes")!!
        assertEquals("Place from Waze", place.name)
        assertEquals("https://ul.waze.com/ul?place=ChIJabc&navigate=yes", place.link)
        // Waze's share text names the place before its link.
        assertEquals("Souq Waqif", parse("Use Waze to drive to Souq Waqif: https://waze.com/ul/hsv8tx1234")!!.name)
        // A look-alike host isn't Waze.
        assertNull(parse("https://notwaze.com/ul")?.link)
    }

    // ---- Typed or pasted -----------------------------------------------------------

    @Test fun typedPoints() {
        assertPoint(25.2854, 51.531, parse("25.2854, 51.5310"))
        assertPoint(-33.8568, 151.2153, parse("-33.8568,151.2153"))
        assertPoint(25.2854, 51.531, parse("25.2854,51.5310 (Souq Waqif)"), "Souq Waqif")
        // Degrees, minutes and seconds, as Google Maps shows a pin.
        assertPoint(25.285389, 51.531, parse("25°17'07.4\"N 51°31'51.6\"E"))
        assertPoint(25.2854, 51.531, parse("25.2854° N, 51.5310° E"))
        assertPoint(-33.8568, 151.2153, parse("33.8568° S, 151.2153° E"))
        assertPoint(25.2854, 51.531, parse("geo:25.2854,51.5310?q=25.2854,51.5310(Souq%20Waqif)"), "Souq Waqif")
    }

    @Test fun typedAddresses() {
        assertEquals("Souq Waqif, Doha", parse("Souq Waqif, Doha")!!.query)
        assertEquals("Souq Waqif, Al Souq, Doha", parse("Souq Waqif\nAl Souq\nDoha")!!.query)
        // A link that says nothing about a place is left out of the search.
        assertEquals("Souq Waqif", parse("Souq Waqif: https://foursquare.com/v/abcdef")!!.query)
        assertNull(parse("Dropped Pin"))
        assertNull(parse("   "))
    }
}
