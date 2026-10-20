package pl.zamirski.jarvis.core

import org.json.JSONArray
import org.json.JSONObject

/** Wynik sprawdzenia /health backendu. */
data class BackendHealth(
    val ok: Boolean,
    val configured: Boolean,
    val model: String,
    /** "ok", "invalid" (brak/błędny token) albo "missing" (serwer bez APP_TOKEN — odrzuca rozmowy). */
    val auth: String,
)

class ProtocolException(message: String) : Exception(message)

/** Odpowiedź backendu: tekst (może być pusty) i proponowane działania na telefonie. */
data class AiReply(val text: String, val actions: List<ToolCall>)

/**
 * Format wymiany danych z backendem Jarvisa.
 * Żądanie:  POST /chat  {"messages":[{"role":"user","content":"..."}], "client":{...}}
 * Odpowiedź: {"reply":"...", "actions":[{"id":"...","tool":"open_app","args":{...}}]}  albo  {"error":"..."}
 * Narzędzia: "tools":["open_app"] — lista dostępnych na telefonie; "pending" — wyniki wykonanych działań.
 */
object ChatProtocol {
    const val MAX_HISTORY = 20

    fun buildRequest(
        history: List<ChatMessage>,
        appVersion: String = "",
        tools: List<String> = emptyList(),
        pending: ToolPending? = null,
        /** Czas lokalny i strefa telefonu — AI nie zna aktualnej daty, a potrzebuje jej do alarmów i kalendarza. */
        localTime: String? = null,
        timeZone: String? = null,
    ): String {
        val messages = history
            .filter { it.kind != Kind.NOTICE && it.text.isNotBlank() }
            .takeLast(MAX_HISTORY)
            .toMutableList()
        // Rozmowa wysyłana do modelu musi zaczynać się od wypowiedzi użytkownika.
        while (messages.isNotEmpty() && messages.first().kind != Kind.USER) {
            messages.removeAt(0)
        }
        val array = JSONArray()
        messages.forEachIndexed { index, m ->
            val item = JSONObject().put("role", m.kind.wire).put("content", m.text.trim())
            // Zdjęcie wysyłamy tylko z ostatnią wiadomością — starsze nie są potrzebne i obciążałyby łącze.
            if (index == messages.lastIndex && m.kind == Kind.USER && !m.imageBase64.isNullOrBlank()) {
                item.put("image", JSONObject().put("media_type", "image/jpeg").put("data", m.imageBase64))
            }
            array.put(item)
        }
        val root = JSONObject()
            .put("messages", array)
            .put(
                "client",
                JSONObject().put("app", "jarvis-android").put("version", appVersion).apply {
                    if (!localTime.isNullOrBlank()) put("local_time", localTime)
                    if (!timeZone.isNullOrBlank()) put("tz", timeZone)
                },
            )
        if (tools.isNotEmpty()) root.put("tools", JSONArray(tools))
        if (pending != null) {
            val actions = JSONArray()
            pending.actions.forEach {
                actions.put(JSONObject().put("id", it.id).put("tool", it.tool).put("args", JSONObject(it.args)))
            }
            val results = JSONArray()
            pending.results.forEach { results.put(JSONObject().put("id", it.id).put("ok", it.ok).put("output", it.output)) }
            root.put("pending", JSONObject().put("actions", actions).put("results", results))
        }
        return root.toString()
    }

    /** Odpowiedź z tekstem i/lub działaniami. Rzuca ProtocolException, gdy nie ma ani jednego, ani drugiego. */
    fun parseResponse(body: String): AiReply {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ProtocolException("Nieprawidłowa odpowiedź serwera")
        }
        val text = json.optString("reply", "").trim()
        val actions = mutableListOf<ToolCall>()
        val arr = json.optJSONArray("actions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val tool = o.optString("tool", "")
                val args = o.optJSONObject("args") ?: continue
                if (id.isBlank() || tool.isBlank()) continue
                actions += ToolCall(id, tool, args.keys().asSequence().associateWith { k -> args.opt(k).takeUnless { it == JSONObject.NULL } })
            }
        }
        if (text.isBlank() && actions.isEmpty()) {
            throw ProtocolException(json.optString("error", "Pusta odpowiedź serwera"))
        }
        return AiReply(text, actions)
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
            auth = json.optString("auth", "invalid"),
        )
    }
}
