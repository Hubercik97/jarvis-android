package pl.zamirski.jarvis.core

import org.json.JSONArray
import org.json.JSONObject

data class Note(val id: Long, val text: String, val createdAt: Long)

data class TaskItem(val id: Long, val title: String, val done: Boolean, val createdAt: Long)

data class Reminder(val id: Long, val text: String, val atMillis: Long)

/**
 * Serializacja lokalnych danych do JSON. Ten sam format posłuży do przyszłej
 * synchronizacji z panelem Windows przez backend (zob. sync/SyncRepository).
 */
object LocalCodec {
    fun encodeNotes(items: List<Note>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt)) }
    }.toString()

    fun decodeNotes(raw: String?): List<Note> = parse(raw) { o ->
        Note(o.getLong("id"), o.getString("text"), o.optLong("createdAt", 0))
    }

    fun encodeTasks(items: List<TaskItem>): String = JSONArray().apply {
        items.forEach {
            put(JSONObject().put("id", it.id).put("title", it.title).put("done", it.done).put("createdAt", it.createdAt))
        }
    }.toString()

    fun decodeTasks(raw: String?): List<TaskItem> = parse(raw) { o ->
        TaskItem(o.getLong("id"), o.getString("title"), o.optBoolean("done", false), o.optLong("createdAt", 0))
    }

    fun encodeReminders(items: List<Reminder>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.atMillis)) }
    }.toString()

    fun decodeReminders(raw: String?): List<Reminder> = parse(raw) { o ->
        Reminder(o.getLong("id"), o.getString("text"), o.getLong("at"))
    }

    private fun <T> parse(raw: String?, map: (JSONObject) -> T): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    map(arr.getJSONObject(i))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Polskie powitanie zależne od pory dnia. */
object Greeting {
    fun forHour(hour: Int): String = when (hour) {
        in 5..10 -> "Dzień dobry!"
        in 11..17 -> "Cześć!"
        in 18..22 -> "Dobry wieczór!"
        else -> "Witaj ponownie."
    }
}
