package pl.zamirski.jarvis.core

import org.json.JSONObject

/** Fakt z pamięci Jarvisa na serwerze (zapisany na prośbę użytkownika). */
data class MemoryFact(val id: String, val text: String, val created: String)

/** Stan pamięci: enabled=false, gdy backend nie ma przestrzeni KV (pamięć wyłączona). */
data class MemoryState(val enabled: Boolean, val facts: List<MemoryFact>, val max: Int)

object MemoryProtocol {
    fun parseList(body: String): MemoryState {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ProtocolException("Nieprawidłowa odpowiedź serwera")
        }
        val arr = json.optJSONArray("facts")
        val facts = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            val text = o.optString("text", "")
            if (id.isBlank() || text.isBlank()) null else MemoryFact(id, text, o.optString("created", ""))
        }
        return MemoryState(json.optBoolean("enabled", false), facts, json.optInt("max", 50))
    }

    fun addRequest(text: String): String = JSONObject().put("text", text.trim()).toString()

    /** Wynik dla AI po zapisaniu faktu. */
    fun addResult(body: String): String {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ProtocolException("Nieprawidłowa odpowiedź serwera")
        }
        if (!json.optBoolean("ok", false)) throw ProtocolException(json.optString("error", "Nie udało się zapamiętać"))
        val text = json.optJSONObject("fact")?.optString("text").orEmpty()
        return when {
            json.optBoolean("duplicate", false) -> "Już to pamiętam: $text"
            json.optBoolean("dropped_oldest", false) -> "Zapamiętano: $text (usunięto najstarszy fakt — pamięć jest pełna)"
            else -> "Zapamiętano: $text"
        }
    }
}
