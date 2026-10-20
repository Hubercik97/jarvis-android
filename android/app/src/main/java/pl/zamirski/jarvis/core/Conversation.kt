package pl.zamirski.jarvis.core

import org.json.JSONArray
import org.json.JSONObject

/** Jedna rozmowa z Jarvisem zapisana na telefonie. */
data class Conversation(
    val id: Long,
    val startedAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
) {
    /** Tytuł do listy historii: początek pierwszej wypowiedzi użytkownika. */
    val title: String
        get() {
            val first = messages.firstOrNull { it.kind == Kind.USER }?.text
                ?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            return when {
                first.isBlank() -> "Rozmowa bez treści"
                first.length > TITLE_MAX -> first.take(TITLE_MAX).trimEnd() + "…"
                else -> first
            }
        }

    /** Czy rozmowa zawiera coś, co warto zachować w historii. */
    val hasUserContent: Boolean
        get() = messages.any { it.kind == Kind.USER && it.text.isNotBlank() }

    companion object {
        const val TITLE_MAX = 60
    }
}

/**
 * Zapis rozmów do JSON. Zdjęcia (base64) nigdy nie są zapisywane na dysku —
 * zostaje tylko opis załącznika, np. „Zdjęcie z kamery”.
 */
object ConversationCodec {
    /** Ile rozmów trzymamy w historii (najstarsze są usuwane). */
    const val MAX_ARCHIVED = 50

    /** Ile ostatnich wiadomości jednej rozmowy zapisujemy. */
    const val MAX_MESSAGES = 200

    fun encode(c: Conversation): String = toJson(c).toString()

    fun decode(raw: String?): Conversation? {
        if (raw.isNullOrBlank()) return null
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    fun encodeList(items: List<Conversation>): String = JSONArray().apply {
        items.take(MAX_ARCHIVED).forEach { put(toJson(it)) }
    }.toString()

    fun decodeList(raw: String?): List<Conversation> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    fromJson(arr.getJSONObject(i))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Dodaje rozmowę na początek historii (zastępuje starszą wersję o tym samym id) i przycina listę. */
    fun prepend(archive: List<Conversation>, c: Conversation): List<Conversation> =
        (listOf(c) + archive.filterNot { it.id == c.id }).take(MAX_ARCHIVED)

    private fun toJson(c: Conversation): JSONObject {
        val messages = JSONArray()
        c.messages.takeLast(MAX_MESSAGES).forEach { m ->
            val o = JSONObject().put("kind", m.kind.wire).put("text", m.text)
            if (m.attachmentLabel != null) o.put("attachment", m.attachmentLabel)
            messages.put(o)
        }
        return JSONObject()
            .put("id", c.id)
            .put("startedAt", c.startedAt)
            .put("updatedAt", c.updatedAt)
            .put("messages", messages)
    }

    private fun fromJson(o: JSONObject): Conversation {
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = Kind.values().firstOrNull { it.wire == m.optString("kind") } ?: return@mapNotNull null
            val text = m.optString("text", "")
            if (text.isBlank()) return@mapNotNull null
            val attachment = if (m.has("attachment")) m.optString("attachment").ifBlank { null } else null
            ChatMessage(kind, text, imageBase64 = null, attachmentLabel = attachment)
        }
        return Conversation(
            id = o.getLong("id"),
            startedAt = o.optLong("startedAt", 0),
            updatedAt = o.optLong("updatedAt", 0),
            messages = messages,
        )
    }
}
