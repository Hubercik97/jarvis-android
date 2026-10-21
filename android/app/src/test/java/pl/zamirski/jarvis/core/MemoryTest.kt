package pl.zamirski.jarvis.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {

    @Test
    fun `list is parsed and malformed facts are skipped`() {
        val s = MemoryProtocol.parseList(
            """{"enabled":true,"max":50,"facts":[{"id":"a1","text":"Lubię kawę","created":"2026-10-21T07:00:00Z"},
               {"id":"","text":"x"},{"id":"b2","text":""},"śmieć"]}""",
        )
        assertTrue(s.enabled)
        assertEquals(listOf(MemoryFact("a1", "Lubię kawę", "2026-10-21T07:00:00Z")), s.facts)
        assertFalse(MemoryProtocol.parseList("""{"facts":[]}""").enabled)
    }

    @Test
    fun `add request and results are readable in polish`() {
        assertEquals("Skoda", JSONObject(MemoryProtocol.addRequest("  Skoda ")).getString("text"))
        assertEquals("Zapamiętano: Skoda", MemoryProtocol.addResult("""{"ok":true,"fact":{"text":"Skoda"}}"""))
        assertEquals("Już to pamiętam: Skoda", MemoryProtocol.addResult("""{"ok":true,"duplicate":true,"fact":{"text":"Skoda"}}"""))
        assertTrue(MemoryProtocol.addResult("""{"ok":true,"dropped_oldest":true,"fact":{"text":"X"}}""").contains("pełna"))
    }

    @Test(expected = ProtocolException::class)
    fun `server error is surfaced`() {
        MemoryProtocol.addResult("""{"ok":false,"error":"Pusta informacja do zapamiętania"}""")
    }

    @Test
    fun `remember tool validates text`() {
        assertTrue(ToolRegistry.validate(ToolCall("t", ToolRegistry.REMEMBER_FACT, mapOf("text" to "Pracuję jako kurier"))) is ToolValidation.Ok)
        assertTrue(ToolRegistry.validate(ToolCall("t", ToolRegistry.REMEMBER_FACT, mapOf("text" to "x".repeat(201)))) is ToolValidation.Error)
        assertFalse(ToolRegistry.needsConfirmation(ToolCall("t", ToolRegistry.REMEMBER_FACT, mapOf("text" to "x"))))
    }
}
