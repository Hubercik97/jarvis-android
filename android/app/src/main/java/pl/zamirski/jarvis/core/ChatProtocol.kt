package pl.zamirski.jarvis.core

import org.json.JSONArray
import org.json.JSONObject

/** Wynik sprawdzenia /health backendu. */
data class BackendHealth(
    val ok: Boolean,
    val configured: Boolean,
    val model: String,
    /** "ok", "invalid" albo "none" (backend bez tokenu aplikacji). */
    val auth: String,
)

class ProtocolException(message: String) : Exception(message)

/**
 * Format wymiany danych z backendem Jarvisa.
 * Żądanie:  POST /chat  {"messages":[{"role":"user","content":"..."}], "client":{...}}
 * Odpowiedź: {"reply":"..."}  albo  {"error":"..."}
 */
object ChatProtocol {
    const val MAX_HISTORY = 20

    fun buildRequest(history: List<ChatMessage>, appVersion: String = ""): String {
        val messages = history
            .filter { it.kind != Kind.NOTICE && it.text.isNotBlank() }
            .takeLast(MAX_HISTORY)
            .toMutableList()
        // Rozmowa wysyłana do modelu musi zaczynać się od wypowiedzi użytkownika.
        while (messages.isNotEmpty() && messages.first().kind != Kind.USER) {
            messages.removeAt(0)
        }
        val array = JSONArray()
        messages.forEach { array.put(JSONObject().put("role", it.kind.wire).put("content", it.text.trim())) }
        return JSONObject()
            .put("messages", array)
            .put("client", JSONObject().put("app", "jarvis-android").put("version", appVersion))
            .toString()
    }

    fun parseReply(body: String): String {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ProtocolException("Nieprawidłowa odpowiedź serwera")
        }
        val reply = json.optString("reply", "")
        if (reply.isBlank()) {
            throw ProtocolException(json.optString("error", "Pusta odpowiedź serwera"))
        }
        return reply.trim()
    }

    fun parseError(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            JSONObject(body).optString("error", "").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    fun parseHealth(body: String): BackendHealth {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ProtocolException("Nieprawidłowa odpowiedź serwera")
        }
        return BackendHealth(
            ok = json.optBoolean("ok", false),
            configured = json.optBoolean("configured", false),
            model = json.optString("model", ""),
            auth = json.optString("auth", "none"),
        )
    }
}
