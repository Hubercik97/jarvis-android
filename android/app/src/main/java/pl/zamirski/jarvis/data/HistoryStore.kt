package pl.zamirski.jarvis.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pl.zamirski.jarvis.core.Conversation
import pl.zamirski.jarvis.core.ConversationCodec
import java.io.File

/**
 * Historia rozmów zapisana tylko na tym telefonie (katalog prywatny aplikacji).
 * - current.json — bieżąca rozmowa, przywracana po ponownym uruchomieniu,
 * - archive.json — zakończone rozmowy (najnowsze pierwsze, maks. ConversationCodec.MAX_ARCHIVED).
 *
 * Zapisy są atomowe (AtomicFile), więc przerwanie w połowie nie psuje pliku.
 * Wywoływać z wątku tła; metody są synchronizowane.
 */
class HistoryStore private constructor(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "history").apply { mkdirs() }
    private val currentFile = AtomicFile(File(dir, "current.json"))
    private val archiveFile = AtomicFile(File(dir, "archive.json"))

    private val _archive = MutableStateFlow<List<Conversation>>(emptyList())
    val archive: StateFlow<List<Conversation>> = _archive

    @Synchronized
    fun loadCurrent(): Conversation? = ConversationCodec.decode(read(currentFile))

    @Synchronized
    fun loadArchive() {
        _archive.value = ConversationCodec.decodeList(read(archiveFile))
    }

    @Synchronized
    fun saveCurrent(c: Conversation) {
        if (c.messages.isEmpty()) currentFile.delete() else write(currentFile, ConversationCodec.encode(c))
    }

    /** Przenosi rozmowę do historii (jeśli jest w niej coś od użytkownika) i czyści bieżącą. */
    @Synchronized
    fun archive(c: Conversation) {
        if (c.hasUserContent) {
            val updated = ConversationCodec.prepend(_archive.value, c)
            write(archiveFile, ConversationCodec.encodeList(updated))
            _archive.value = updated
        }
        currentFile.delete()
    }

    /** Zapisuje/aktualizuje rozmowę w historii BEZ ruszania bieżącej rozmowy z ekranu (np. rozmowy z tła). */
    @Synchronized
    fun upsertArchived(c: Conversation) {
        if (!c.hasUserContent) return
        if (_archive.value.isEmpty()) _archive.value = ConversationCodec.decodeList(read(archiveFile))
        val updated = ConversationCodec.prepend(_archive.value, c)
        write(archiveFile, ConversationCodec.encodeList(updated))
        _archive.value = updated
    }

    @Synchronized
    fun delete(id: Long) {
        val updated = _archive.value.filterNot { it.id == id }
        write(archiveFile, ConversationCodec.encodeList(updated))
        _archive.value = updated
    }

    @Synchronized
    fun deleteAll() {
        archiveFile.delete()
        _archive.value = emptyList()
    }

    companion object {
        @Volatile private var instance: HistoryStore? = null

        /** Jeden egzemplarz na proces — ekran i (w przyszłości) usługa w tle piszą przez ten sam obiekt. */
        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) { instance ?: HistoryStore(context).also { instance = it } }
    }

    private fun read(file: AtomicFile): String? = try {
        if (file.baseFile.exists()) String(file.readFully(), Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    private fun write(file: AtomicFile, text: String) {
        val out = try {
            file.startWrite()
        } catch (e: Exception) {
            return
        }
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }
}
