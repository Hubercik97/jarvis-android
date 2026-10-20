package pl.zamirski.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    private fun conv(id: Long, vararg msgs: ChatMessage) = Conversation(id, 1000L, 2000L, msgs.toList())

    @Test
    fun `conversation round trips with polish text and attachment labels`() {
        val c = conv(
            7,
            ChatMessage(Kind.USER, "Która godzina? Zażółć gęślą jaźń", attachmentLabel = "Zdjęcie z kamery"),
            ChatMessage(Kind.ASSISTANT, "Jest 12:30."),
            ChatMessage(Kind.NOTICE, "Brak internetu."),
        )
        val back = ConversationCodec.decode(ConversationCodec.encode(c))!!
        assertEquals(c, back)
    }

    @Test
    fun `images are never written to disk`() {
        val c = conv(1, ChatMessage(Kind.USER, "Co widzisz?", imageBase64 = "QUJDRA==", attachmentLabel = "Zdjęcie"))
        val raw = ConversationCodec.encode(c)
        assertFalse(raw.contains("QUJDRA=="))
        val back = ConversationCodec.decode(raw)!!
        assertNull(back.messages.single().imageBase64)
        assertEquals("Zdjęcie", back.messages.single().attachmentLabel)
    }

    @Test
    fun `corrupted or empty storage is handled`() {
        assertNull(ConversationCodec.decode(null))
        assertNull(ConversationCodec.decode("{oops"))
        assertTrue(ConversationCodec.decodeList("[1, {\"x\":2}]").isEmpty())
        assertTrue(ConversationCodec.decodeList("nie json").isEmpty())
    }

    @Test
    fun `unknown message kinds and blank texts are skipped`() {
        val raw = """{"id":3,"startedAt":1,"updatedAt":2,"messages":[
            {"kind":"user","text":"Cześć"},{"kind":"robot","text":"?"},{"kind":"assistant","text":"  "}]}"""
        val c = ConversationCodec.decode(raw)!!
        assertEquals(listOf(ChatMessage(Kind.USER, "Cześć")), c.messages)
    }

    @Test
    fun `archive keeps newest first, replaces same id and is capped`() {
        var archive = emptyList<Conversation>()
        for (i in 1..(ConversationCodec.MAX_ARCHIVED + 5)) {
            archive = ConversationCodec.prepend(archive, conv(i.toLong(), ChatMessage(Kind.USER, "rozmowa $i")))
        }
        assertEquals(ConversationCodec.MAX_ARCHIVED, archive.size)
        assertEquals((ConversationCodec.MAX_ARCHIVED + 5).toLong(), archive.first().id)

        val updated = conv(archive[3].id, ChatMessage(Kind.USER, "zmieniona"))
        val again = ConversationCodec.prepend(archive, updated)
        assertEquals(ConversationCodec.MAX_ARCHIVED, again.size)
        assertEquals(updated, again.first())
        assertEquals(1, again.count { it.id == updated.id })

        val roundTrip = ConversationCodec.decodeList(ConversationCodec.encodeList(again))
        assertEquals(again, roundTrip)
    }

    @Test
    fun `only the last messages are stored`() {
        val many = (1..(ConversationCodec.MAX_MESSAGES + 10)).map { ChatMessage(Kind.USER, "wiadomość $it") }
        val back = ConversationCodec.decode(ConversationCodec.encode(Conversation(1, 1, 1, many)))!!
        assertEquals(ConversationCodec.MAX_MESSAGES, back.messages.size)
        assertEquals("wiadomość ${ConversationCodec.MAX_MESSAGES + 10}", back.messages.last().text)
    }

    @Test
    fun `title comes from the first user message`() {
        assertEquals("Rozmowa bez treści", conv(1, ChatMessage(Kind.ASSISTANT, "Cześć")).title)
        assertEquals("Jaka pogoda?", conv(1, ChatMessage(Kind.USER, "Jaka pogoda?\ndruga linia")).title)
        val long = "a".repeat(100)
        val title = conv(1, ChatMessage(Kind.USER, long)).title
        assertEquals(Conversation.TITLE_MAX + 1, title.length)
        assertTrue(title.endsWith("…"))
        assertFalse(conv(1, ChatMessage(Kind.NOTICE, "Błąd")).hasUserContent)
    }
}
