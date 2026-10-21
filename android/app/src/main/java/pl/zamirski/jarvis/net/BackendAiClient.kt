package pl.zamirski.jarvis.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.zamirski.jarvis.BuildConfig
import pl.zamirski.jarvis.core.AiClient
import pl.zamirski.jarvis.core.AiResult
import pl.zamirski.jarvis.core.ChatMessage
import pl.zamirski.jarvis.core.ChatProtocol
import pl.zamirski.jarvis.core.FailureReason
import pl.zamirski.jarvis.core.HealthResult
import pl.zamirski.jarvis.core.ProtocolException
import pl.zamirski.jarvis.core.ToolPending
import pl.zamirski.jarvis.core.ToolTime
import pl.zamirski.jarvis.core.Urls
import pl.zamirski.jarvis.settings.AppSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.NoRouteToHostException
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime

/** Łączy się z backendem Jarvisa (HTTPS). Klucz modelu AI jest wyłącznie na serwerze. */
class BackendAiClient(
    private val context: Context,
    private val settings: AppSettings,
) : AiClient {

    private data class HttpResponse(val code: Int, val body: String)

    override suspend fun chat(
        history: List<ChatMessage>,
        tools: List<String>,
        pending: ToolPending?,
    ): AiResult = withContext(Dispatchers.IO) {
        val base = Urls.normalizeBackend(settings.backendUrl)
            ?: return@withContext AiResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!NetworkMonitor.isOnline(context)) return@withContext AiResult.Failure(FailureReason.OFFLINE)
        try {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val body = ChatProtocol.buildRequest(
                history, BuildConfig.VERSION_NAME, tools, pending,
                localTime = ToolTime.localTimeLabel(now), timeZone = now.zone.id,
            )
            // Wyszukiwanie w internecie może trwać dłużej (kilka zapytań i stron) — backend ma limit 70 s na zadanie.
            val response = withRetry { request("POST", "$base/chat", body, 120_000) }
            when (response.code) {
                in 200..299 -> ChatProtocol.parseResponse(response.body).let { AiResult.Success(it.text, it.actions, it.sources) }
                401, 403 -> AiResult.Failure(FailureReason.UNAUTHORIZED)
                else -> AiResult.Failure(FailureReason.SERVER, ChatProtocol.parseError(response.body) ?: "HTTP ${response.code}")
            }
        } catch (e: SocketTimeoutException) {
            AiResult.Failure(FailureReason.TIMEOUT)
        } catch (e: UnknownHostException) {
            AiResult.Failure(FailureReason.OFFLINE)
        } catch (e: ProtocolException) {
            AiResult.Failure(FailureReason.BAD_RESPONSE, e.message)
        } catch (e: IOException) {
            AiResult.Failure(FailureReason.SERVER, e.message)
        }
    }

    override suspend fun health(): HealthResult = withContext(Dispatchers.IO) {
        val base = Urls.normalizeBackend(settings.backendUrl)
            ?: return@withContext HealthResult.Failure(FailureReason.NOT_CONFIGURED)
        if (!NetworkMonitor.isOnline(context)) return@withContext HealthResult.Failure(FailureReason.OFFLINE)
        try {
            val response = withRetry { request("GET", "$base/health", null, 15_000) }
            if (response.code in 200..299) {
                HealthResult.Success(ChatProtocol.parseHealth(response.body))
            } else {
                HealthResult.Failure(FailureReason.SERVER, "HTTP ${response.code}")
            }
        } catch (e: SocketTimeoutException) {
            HealthResult.Failure(FailureReason.TIMEOUT)
        } catch (e: UnknownHostException) {
            HealthResult.Failure(FailureReason.SERVER, "Nie znaleziono serwera — sprawdź adres")
        } catch (e: ProtocolException) {
            HealthResult.Failure(FailureReason.BAD_RESPONSE, e.message)
        } catch (e: IOException) {
            HealthResult.Failure(FailureReason.SERVER, e.message)
        }
    }

    /**
     * Jedno ponowienie, gdy połączenie w ogóle nie powstało (słaby zasięg, zmiana Wi-Fi ↔ LTE).
     * NIE ponawiamy po przekroczeniu czasu odpowiedzi — żądanie mogło już dotrzeć i zostać wykonane.
     */
    private suspend fun <T> withRetry(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (e is ConnectException || e is UnknownHostException || e is NoRouteToHostException) {
            delay(RETRY_DELAY_MS)
            block()
        } else {
            throw e
        }
    }

    private fun request(method: String, url: String, body: String?, readTimeoutMs: Int): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeoutMs
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
            return HttpResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_000L
    }
}
