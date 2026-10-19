package pl.zamirski.jarvis.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pl.zamirski.jarvis.core.LocalCodec
import pl.zamirski.jarvis.core.Note
import pl.zamirski.jarvis.core.Reminder
import pl.zamirski.jarvis.core.TaskItem

/** Notatki, zadania i przypomnienia zapisane lokalnie na telefonie. */
class LocalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_local", Context.MODE_PRIVATE)

    private val _notes = MutableStateFlow(LocalCodec.decodeNotes(prefs.getString(KEY_NOTES, null)))
    val notes: StateFlow<List<Note>> = _notes

    private val _tasks = MutableStateFlow(LocalCodec.decodeTasks(prefs.getString(KEY_TASKS, null)))
    val tasks: StateFlow<List<TaskItem>> = _tasks

    private val _reminders = MutableStateFlow(LocalCodec.decodeReminders(prefs.getString(KEY_REMINDERS, null)))
    val reminders: StateFlow<List<Reminder>> = _reminders

    fun addNote(text: String) = saveNotes(listOf(Note(newId(), text.trim(), now())) + _notes.value)
    fun deleteNote(id: Long) = saveNotes(_notes.value.filterNot { it.id == id })

    fun addTask(title: String) = saveTasks(listOf(TaskItem(newId(), title.trim(), false, now())) + _tasks.value)
    fun toggleTask(id: Long) = saveTasks(_tasks.value.map { if (it.id == id) it.copy(done = !it.done) else it })
    fun deleteTask(id: Long) = saveTasks(_tasks.value.filterNot { it.id == id })

    fun addReminder(text: String, atMillis: Long): Reminder {
        val r = Reminder(newId(), text.trim(), atMillis)
        saveReminders((_reminders.value + r).sortedBy { it.atMillis })
        return r
    }
    fun deleteReminder(id: Long) = saveReminders(_reminders.value.filterNot { it.id == id })
    fun reminder(id: Long): Reminder? = _reminders.value.firstOrNull { it.id == id }

    private fun saveNotes(items: List<Note>) {
        _notes.value = items
        prefs.edit().putString(KEY_NOTES, LocalCodec.encodeNotes(items)).apply()
    }

    private fun saveTasks(items: List<TaskItem>) {
        _tasks.value = items
        prefs.edit().putString(KEY_TASKS, LocalCodec.encodeTasks(items)).apply()
    }

    private fun saveReminders(items: List<Reminder>) {
        _reminders.value = items
        prefs.edit().putString(KEY_REMINDERS, LocalCodec.encodeReminders(items)).apply()
    }

    private fun now() = System.currentTimeMillis()
    private fun newId() = System.nanoTime()

    companion object {
        private const val KEY_NOTES = "notes"
        private const val KEY_TASKS = "tasks"
        private const val KEY_REMINDERS = "reminders"

        @Volatile private var instance: LocalStore? = null
        fun get(context: Context): LocalStore =
            instance ?: synchronized(this) { instance ?: LocalStore(context).also { instance = it } }
    }
}
