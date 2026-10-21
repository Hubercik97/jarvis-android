package pl.zamirski.jarvis.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.zamirski.jarvis.core.Urls
import pl.zamirski.jarvis.settings.AppSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Synteza mowy w chmurze przez backend Jarvisa (/tts → Google Chirp 3 HD).
 * Zwraca bajty MP3. Klucz Google jest wyłącznie na serwerze.
 */
class BackendTtsClient(private val settings: AppSettings) {

    suspend fun synthesize(text: String, voice: String, rate: Float): ByteArray = withContext(Dispatchers.IO) {
        val base = Urls.normalizeBackend(settings.backendUrl) ?: throw IOException("Brak adresu backendu")
        val connection = URL("$base/tts").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val token = settings.appToken
            if (token.isNotBlank()) connection.setRequestProperty("X-Jarvis-Token", token)
            val body = JSONObject().put("text", text).put("voice", voice).put("rate", rate.toDouble()).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("Głos w chmurze: HTTP $code")
            val audio = JSONObject(raw).optString("audio", "")
            if (audio.isBlank()) throw IOException("Głos w chmurze: pusta odpowiedź")
            Base64.decode(audio, Base64.DEFAULT)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Męskie polskie głosy Chirp 3 HD (zgodne z MALE_VOICES w backend/tts.js). */
        val MALE_VOICES = listOf("Charon", "Orus", "Fenrir", "Iapetus", "Algenib", "Alnilam", "Puck", "Schedar")
        const val DEFAULT_VOICE = "Charon"
    }
}
