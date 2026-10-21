package pl.zamirski.jarvis.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.zamirski.jarvis.core.MemoryProtocol
import pl.zamirski.jarvis.core.MemoryState
import pl.zamirski.jarvis.core.ProtocolException
import pl.zamirski.jarvis.core.Urls
import pl.zamirski.jarvis.settings.AppSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pamięć Jarvisa na serwerze (/memory). Klucz modelu AI nie jest tu potrzebny — tylko token aplikacji.
 * Wszystkie metody rzucają IOException/ProtocolException z polskim opisem przy błędzie.
 */
class BackendMemoryClient(private val settings: AppSettings) {

    private fun base(): String = Urls.normalizeBackend(settings.backendUrl)
        ?: throw ProtocolException("Brak adresu backendu AI — wpisz go w Ustawieniach.")

    suspend fun list(): MemoryState = withContext(Dispatchers.IO) {
        val (code, body) = request("GET", "${base()}/memory", null)
        if (code == 503) return@withContext MemoryState(false, emptyList(), 0)
        check(code, body)
        MemoryProtocol.parseList(body)
    }

    /** Zwraca tekst wyniku dla AI/historii, np. „Zapamiętano: …”. */
    suspend fun add(text: String): String = withContext(Dispatchers.IO) {
        val (code, body) = request("POST", "${base()}/memory", MemoryProtocol.addRequest(text))
        check(code, body)
        MemoryProtocol.addResult(body)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val (code, body) = request("DELETE", "${base()}/memory/${URLEncoder.encode(id, "UTF-8")}", null)
        check(code, body)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        val (code, body) = request("DELETE", "${base()}/memory", null)
        check(code, body)
    }

    private fun check(code: Int, body: String) {
        if (code in 200..299) return
        val msg = pl.zamirski.jarvis.core.ChatProtocol.parseError(body)
        throw ProtocolException(
            when (code) {
                401, 403 -> "Serwer odrzucił token aplikacji"
                503 -> msg ?: "Pamięć jest niedostępna na serwerze"
                else -> msg ?: "Błąd serwera (HTTP $code)"
            },
        )
    }

    private fun request(method: String, url: String, body: String?): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            val token = settings.appToken
            if (token.isNotBlank()) connection.setRequestProperty("X-Jarvis-Token", token)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } catch (e: IOException) {
            throw IOException("Brak połączenia z serwerem", e)
        } finally {
            connection.disconnect()
        }
    }
}
