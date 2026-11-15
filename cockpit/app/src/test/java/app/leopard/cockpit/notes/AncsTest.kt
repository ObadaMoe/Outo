package app.leopard.cockpit.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AncsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** One attribute as the iPhone sends it: ID, 2-byte length, value. */
    private fun ByteArrayOutputStream.attr(id: Int, value: String) {
        val b = value.toByteArray(Charsets.UTF_8)
        write(id); write(b.size and 0xFF); write(b.size shr 8)
        write(b, 0, b.size)
    }

    private fun noteAnswer(uid: Int, vararg attrs: Pair<Int, String>): ByteArray = ByteArrayOutputStream().apply {
        write(Ancs.GET_NOTIFICATION_ATTRIBUTES)
        write(uid and 0xFF); write(uid shr 8 and 0xFF); write(uid shr 16 and 0xFF); write(uid shr 24 and 0xFF)
        attrs.forEach { (id, v) -> attr(id, v) }
    }.toByteArray()

    @Test fun event() {
        // Added, silent and pre-existing, social, count 3, uid 0x01020304.
        val e = Ancs.event(bytes(0, 5, 4, 3, 4, 3, 2, 1))!!
        assertEquals(Ancs.ADDED, e.id)
        assertEquals(Ancs.SOCIAL, e.category)
        assertEquals(3, e.count)
        assertEquals(0x01020304, e.uid)
        assertTrue(e.silent)
        assertTrue(e.preExisting)
        assertFalse(e.important)
        assertFalse(e.canDismiss)
        assertTrue(Ancs.event(bytes(2, Ancs.NEGATIVE_ACTION, 0, 0, 0xFF, 0, 0, 0))!!.canDismiss)
        assertNull(Ancs.event(bytes(0, 0, 0)))
    }

    @Test fun noteCommand() {
        val c = Ancs.noteCommand(0x0A0B0C0D)
        assertArrayEquals(
            bytes(
                0, 0x0D, 0x0C, 0x0B, 0x0A,
                Ancs.APP_IDENTIFIER,
                Ancs.TITLE, Ancs.TITLE_MAX, 0,
                Ancs.SUBTITLE, Ancs.TITLE_MAX, 0,
                Ancs.MESSAGE, Ancs.MESSAGE_MAX and 0xFF, Ancs.MESSAGE_MAX shr 8,
                Ancs.DATE,
            ),
            c,
        )
    }

    @Test fun appAndActionCommands() {
        assertArrayEquals(bytes(1, 'a'.code, '.'.code, 'b'.code, 0, 0), Ancs.appCommand("a.b"))
        assertArrayEquals(bytes(2, 7, 0, 0, 0, Ancs.ACTION_NEGATIVE), Ancs.actionCommand(7, Ancs.ACTION_NEGATIVE))
    }

    @Test fun answerInOnePacket() {
        val a = Ancs.Answers()
        a.expect(Ancs.Asked.Note(42))
        val got = a.feed(
            noteAnswer(
                42,
                Ancs.APP_IDENTIFIER to "net.whatsapp.WhatsApp", Ancs.TITLE to "Sam",
                Ancs.SUBTITLE to "", Ancs.MESSAGE to "On my way ✨", Ancs.DATE to "20261115T093000",
            ),
        ) as Ancs.Answer.Note
        assertEquals(42, got.uid)
        assertEquals("net.whatsapp.WhatsApp", got.values[Ancs.APP_IDENTIFIER])
        assertEquals("Sam", got.values[Ancs.TITLE])
        assertEquals("", got.values[Ancs.SUBTITLE])
        assertEquals("On my way ✨", got.values[Ancs.MESSAGE])
        assertFalse(a.waiting)
    }

    @Test fun answerOverManyPackets() {
        val whole = noteAnswer(
            9,
            Ancs.APP_IDENTIFIER to "com.apple.MobileSMS", Ancs.TITLE to "Alex",
            Ancs.SUBTITLE to "Group", Ancs.MESSAGE to "x".repeat(300), Ancs.DATE to "20261115T093000",
        )
        val a = Ancs.Answers()
        a.expect(Ancs.Asked.Note(9))
        // Cut anywhere, even inside a length: nothing until the last piece.
        val cuts = listOf(0, 3, 7, 20, 21, 150, whole.size)
        var got: Ancs.Answer? = null
        cuts.zipWithNext().forEachIndexed { i, (from, to) ->
            got = a.feed(whole.copyOfRange(from, to))
            if (i < cuts.size - 2) assertNull(got)
        }
        val n = got as Ancs.Answer.Note
        assertEquals("x".repeat(300), n.values[Ancs.MESSAGE])
        assertEquals("Group", n.values[Ancs.SUBTITLE])
    }

    @Test fun appAnswer() {
        val packet = ByteArrayOutputStream().apply {
            write(Ancs.GET_APP_ATTRIBUTES)
            val id = "ph.telegra.Telegraph".toByteArray()
            write(id, 0, id.size); write(0)
            attr(Ancs.DISPLAY_NAME, "Telegram")
        }.toByteArray()
        val a = Ancs.Answers()
        a.expect(Ancs.Asked.App("ph.telegra.Telegraph"))
        assertNull(a.feed(packet.copyOfRange(0, 10)))
        val got = a.feed(packet.copyOfRange(10, packet.size)) as Ancs.Answer.App
        assertEquals("ph.telegra.Telegraph", got.appId)
        assertEquals("Telegram", got.values[Ancs.DISPLAY_NAME])
    }

    @Test fun packetsNobodyAskedFor() {
        val a = Ancs.Answers()
        assertNull(a.feed(noteAnswer(1, Ancs.APP_IDENTIFIER to "x")))
        a.expect(Ancs.Asked.Note(1))
        a.reset()
        assertFalse(a.waiting)
        assertNull(a.feed(noteAnswer(1, Ancs.APP_IDENTIFIER to "x")))
    }

    @Test fun date() {
        val ms = Ancs.date("20261115T093000")
        assertNotNull(ms)
        val back = java.time.Instant.ofEpochMilli(ms!!).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(java.time.LocalDateTime.of(2026, 11, 15, 9, 30, 0), back)
        assertNull(Ancs.date("2026"))
        assertNull(Ancs.date(null))
        assertNull(Ancs.date("notadate-at-all"))
    }

    @Test fun phoneNotesKeepTheLatestFirst() {
        PhoneNotes.clear()
        PhoneNotes.put(PhoneNote(1, "a", "One", at = 1000), loud = false)
        PhoneNotes.put(PhoneNote(2, "a", "Two", at = 2000), loud = true)
        assertEquals(listOf(2, 1), PhoneNotes.list.value.map { it.uid })
        assertEquals(2, PhoneNotes.banner.value?.uid)
        // Changed on the phone: replaced, not doubled.
        PhoneNotes.put(PhoneNote(1, "a", "One again", at = 3000), loud = false)
        assertEquals(listOf(1, 2), PhoneNotes.list.value.map { it.uid })
        PhoneNotes.remove(2)
        assertNull(PhoneNotes.banner.value)
        assertEquals(listOf(1), PhoneNotes.list.value.map { it.uid })
        PhoneNotes.clear()
    }
}
