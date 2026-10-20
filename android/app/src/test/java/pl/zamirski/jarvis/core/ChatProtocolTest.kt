package pl.zamirski.jarvis.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatProtocolTest {

    @Test
    fun `request skips app notices and leading assistant messages`() {
        val history = listOf(
            ChatMessage(Kind.NOTICE, "Witaj"),
            ChatMessage(Kind.ASSISTANT, "stara odpowiedź"),
            ChatMessage(Kind.USER, "Jaka jest pogoda?"),
            ChatMessage(Kind.NOTICE, "Brak internetu"),
        )
        val json = JSONObject(ChatProtocol.buildRequest(history, "0.2.1"))
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("Jaka jest pogoda?", messages.getJSONObject(0).getString("content"))
        assertEquals("jarvis-android", json.getJSONObject("client").getString("app"))
    }

    @Test
    fun `request keeps polish characters`() {
        val json = JSONObject(ChatProtocol.buildRequest(listOf(ChatMessage(Kind.USER, "Zażółć gęślą jaźń"))))
        assertEquals("Zażółć gęślą jaźń", json.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun `request is limited to recent history`() {
        val history = (1..50).map { i ->
            ChatMessage(if (i % 2 == 1) Kind.USER else Kind.ASSISTANT, "wiadomość $i")
        }
        val messages = JSONObject(ChatProtocol.buildRequest(history)).getJSONArray("messages")
        assertTrue(messages.length() <= ChatProtocol.MAX_HISTORY)
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("wiadomość 50", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    @Test
    fun `reply is parsed`() {
        assertEquals("Dzień dobry!", ChatProtocol.parseReply("""{"reply":" Dzień dobry! "}"""))
    }

    @Test
    fun `server error message is surfaced`() {
        try {
            ChatProtocol.parseReply("""{"error":"Brak klucza"}""")
            fail("expected exception")
        } catch (e: ProtocolException) {
            assertEquals("Brak klucza", e.message)
        }
    }

    @Test(expected = ProtocolException::class)
    fun `garbage response is rejected`() {
        ChatProtocol.parseReply("<html>502</html>")
    }

    @Test
    fun `health is parsed`() {
        val h = ChatProtocol.parseHealth("""{"ok":true,"configured":false,"model":"m","auth":"invalid"}""")
        assertTrue(h.ok)
        assertFalse(h.configured)
        assertEquals("m", h.model)
        assertEquals("invalid", h.auth)
    }

    @Test
    fun `health reports server without app token and treats missing field as not authorized`() {
        assertEquals("missing", ChatProtocol.parseHealth("""{"ok":true,"configured":true,"model":"m","auth":"missing"}""").auth)
        assertEquals("invalid", ChatProtocol.parseHealth("""{"ok":true,"configured":true,"model":"m"}""").auth)
    }

    @Test
    fun `parseError tolerates empty and non json bodies`() {
        assertEquals(null, ChatProtocol.parseError(null))
        assertEquals(null, ChatProtocol.parseError("oops"))
        assertEquals("x", ChatProtocol.parseError("""{"error":"x"}"""))
    }
}
