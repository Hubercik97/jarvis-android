package pl.zamirski.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {

    private val src = listOf(
        Source(1, "Nowości AI — październik", "https://example.com/ai", "2026-10-20"),
        Source(2, "Sklep B", "https://sklep.pl/p?id=1", null),
    )

    @Test
    fun `answer with sources round trips for history and clickable list`() {
        val stored = Sources.withSources("Najnowsze modele [1], cena 99 zł [2].", src)
        assertTrue(stored.contains("Źródła:"))
        val (body, parsed) = Sources.split(stored)
        assertEquals("Najnowsze modele [1], cena 99 zł [2].", body)
        assertEquals(listOf(Source(1, "Nowości AI - październik", "https://example.com/ai", "2026-10-20"), src[1]), parsed)
        assertEquals("x" to emptyList<Source>(), Sources.split("x"))
    }

    @Test
    fun `spoken text has no citations, urls or source list`() {
        val stored = Sources.withSources("Cena to 99 zł [2], więcej na https://sklep.pl [1, 2].", src)
        val spoken = Sources.speakable(stored)
        assertEquals("Cena to 99 zł, więcej na.", spoken)
        assertFalse(spoken.contains("Źródła"))
    }

    @Test
    fun `server response with sources is parsed and unsafe urls are dropped`() {
        val r = ChatProtocol.parseResponse(
            """{"reply":"Tak [1].","sources":[
               {"n":1,"title":"A","url":"https://a.pl","date":"2026-10-20"},
               {"n":2,"title":"B","url":"javascript:alert(1)"},
               {"n":3,"title":"C","url":"https://c.pl","date":"wczoraj"}]}""",
        )
        assertEquals(listOf(Source(1, "A", "https://a.pl", "2026-10-20"), Source(3, "C", "https://c.pl", null)), r.sources)
    }

    @Test
    fun `health reports search, memory and cloud voice`() {
        val h = ChatProtocol.parseHealth("""{"ok":true,"configured":true,"model":"m","auth":"ok","memory":true,"search":true,"tts":false}""")
        assertTrue(h.search)
        assertTrue(h.memory)
        assertFalse(h.tts)
    }
}
