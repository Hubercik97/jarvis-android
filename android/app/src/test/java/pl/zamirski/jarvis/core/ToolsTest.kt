package pl.zamirski.jarvis.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    private fun call(tool: String, vararg args: Pair<String, Any?>) = ToolCall("toolu_1", tool, mapOf(*args))

    @Test
    fun `open_app with a name is valid and trimmed`() {
        val v = ToolRegistry.validate(call(ToolRegistry.OPEN_APP, "name" to "  Spotify "))
        assertEquals(ToolValidation.Ok(mapOf("name" to "Spotify")), v)
        assertFalse(ToolRegistry.needsConfirmation(call(ToolRegistry.OPEN_APP, "name" to "Spotify")))
    }

    @Test
    fun `unknown tools, missing, wrong-type, extra or too long params are rejected`() {
        val bad = listOf(
            call("run_shell", "cmd" to "rm -rf /"),
            call(ToolRegistry.OPEN_APP),
            call(ToolRegistry.OPEN_APP, "name" to 5),
            call(ToolRegistry.OPEN_APP, "name" to "   "),
            call(ToolRegistry.OPEN_APP, "name" to "X", "package" to "com.evil"),
            call(ToolRegistry.OPEN_APP, "name" to "a".repeat(81)),
        )
        bad.forEach { assertTrue("powinno odrzucić: $it", ToolRegistry.validate(it) is ToolValidation.Error) }
    }

    @Test
    fun `unknown tools always require confirmation and only known tools are offered`() {
        assertTrue(ToolRegistry.needsConfirmation(call("send_sms")))
        assertEquals(listOf("open_app"), ToolRegistry.names)
        assertEquals("Otwórz aplikację: Spotify", ToolRegistry.describe(call(ToolRegistry.OPEN_APP, "name" to "Spotify")))
    }

    @Test
    fun `request carries tools and pending results`() {
        val pending = ToolPending(
            listOf(ToolCall("toolu_1", "open_app", mapOf("name" to "Spotify"))),
            listOf(ToolResult("toolu_1", true, "Otwarto aplikację: Spotify")),
        )
        val json = JSONObject(
            ChatProtocol.buildRequest(listOf(ChatMessage(Kind.USER, "Otwórz Spotify")), "1", ToolRegistry.names, pending),
        )
        assertEquals("open_app", json.getJSONArray("tools").getString(0))
        val p = json.getJSONObject("pending")
        assertEquals("Spotify", p.getJSONArray("actions").getJSONObject(0).getJSONObject("args").getString("name"))
        assertEquals(true, p.getJSONArray("results").getJSONObject(0).getBoolean("ok"))
        assertFalse(JSONObject(ChatProtocol.buildRequest(listOf(ChatMessage(Kind.USER, "x")))).has("tools"))
    }

    @Test
    fun `response with actions and empty text is accepted, malformed actions are skipped`() {
        val r = ChatProtocol.parseResponse(
            """{"reply":"","actions":[{"id":"toolu_1","tool":"open_app","args":{"name":"Spotify"}},
               {"id":"","tool":"open_app","args":{}},{"id":"x","tool":"open_app"}]}""",
        )
        assertEquals("", r.text)
        assertEquals(listOf(ToolCall("toolu_1", "open_app", mapOf("name" to "Spotify"))), r.actions)
        assertEquals("Cześć", ChatProtocol.parseResponse("""{"reply":"Cześć"}""").text)
    }

    @Test(expected = ProtocolException::class)
    fun `response without text and without valid actions is an error`() {
        ChatProtocol.parseResponse("""{"reply":"","actions":[{"id":"","tool":"x","args":{}}]}""")
    }
}
