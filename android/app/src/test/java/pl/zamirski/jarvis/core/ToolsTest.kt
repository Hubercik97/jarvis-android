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
        assertEquals(
            listOf(
                "open_app", "set_alarm", "set_timer", "create_reminder", "add_calendar_event", "get_calendar_events",
                "flashlight", "open_settings", "remember_fact",
            ),
            ToolRegistry.names,
        )
        assertTrue(ToolRegistry.specs.all { it.risk == ToolRisk.SAFE }) // etapy A i D: nic nie wysyła ani nie usuwa
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

    @Test
    fun `stage D tools validate ranges, formats and enums`() {
        fun ok(c: ToolCall) = assertTrue("powinno przejść: $c", ToolRegistry.validate(c) is ToolValidation.Ok)
        fun bad(c: ToolCall) = assertTrue("powinno odrzucić: $c", ToolRegistry.validate(c) is ToolValidation.Error)
        ok(call(ToolRegistry.SET_ALARM, "hour" to 7, "minute" to 30))
        ok(call(ToolRegistry.SET_ALARM, "hour" to 7.0)) // JSON bywa liczbą zmiennoprzecinkową
        bad(call(ToolRegistry.SET_ALARM, "hour" to 24))
        bad(call(ToolRegistry.SET_ALARM, "hour" to 7.5))
        ok(call(ToolRegistry.SET_TIMER, "seconds" to 600))
        bad(call(ToolRegistry.SET_TIMER, "seconds" to 0))
        ok(call(ToolRegistry.CREATE_REMINDER, "text" to "Wyjmij pranie", "in_minutes" to 40))
        bad(call(ToolRegistry.CREATE_REMINDER, "in_minutes" to 40))
        ok(call(ToolRegistry.ADD_CALENDAR_EVENT, "title" to "Dentysta", "date" to "2026-10-22", "start_time" to "9:30"))
        bad(call(ToolRegistry.ADD_CALENDAR_EVENT, "title" to "Dentysta", "date" to "22.10.2026"))
        bad(call(ToolRegistry.ADD_CALENDAR_EVENT, "title" to "Dentysta", "date" to "2026-10-22", "start_time" to "9.30"))
        ok(call(ToolRegistry.GET_CALENDAR_EVENTS))
        ok(call(ToolRegistry.FLASHLIGHT, "on" to true))
        bad(call(ToolRegistry.FLASHLIGHT, "on" to "tak"))
        ok(call(ToolRegistry.OPEN_SETTINGS, "screen" to "Bluetooth"))
        bad(call(ToolRegistry.OPEN_SETTINGS, "screen" to "developer"))
        assertEquals(
            ToolValidation.Ok(mapOf("screen" to "bluetooth")),
            ToolRegistry.validate(call(ToolRegistry.OPEN_SETTINGS, "screen" to " Bluetooth ")),
        )
    }

    @Test
    fun `descriptions are readable in polish`() {
        assertEquals("Ustaw alarm: 7:05 — praca", ToolRegistry.describe(call(ToolRegistry.SET_ALARM, "hour" to 7, "minute" to 5, "label" to "praca")))
        assertEquals("Ustaw minutnik: 1 godz. 30 min", ToolRegistry.describe(call(ToolRegistry.SET_TIMER, "seconds" to 5400)))
        assertEquals("Latarka: włącz", ToolRegistry.describe(call(ToolRegistry.FLASHLIGHT, "on" to true)))
        assertEquals("Sprawdź kalendarz: dziś", ToolRegistry.describe(call(ToolRegistry.GET_CALENDAR_EVENTS)))
        assertEquals("Zapamiętaj: Mój samochód to Skoda", ToolRegistry.describe(call(ToolRegistry.REMEMBER_FACT, "text" to "Mój samochód to Skoda")))
    }

    @Test
    fun `request carries phone local time and zone`() {
        val json = JSONObject(
            ChatProtocol.buildRequest(listOf(ChatMessage(Kind.USER, "x")), "1", localTime = "wtorek, 20 października 2026, 20:59", timeZone = "Europe/Amsterdam"),
        ).getJSONObject("client")
        assertEquals("wtorek, 20 października 2026, 20:59", json.getString("local_time"))
        assertEquals("Europe/Amsterdam", json.getString("tz"))
    }
}
