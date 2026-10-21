package pl.zamirski.jarvis.settings

import android.content.Context
import pl.zamirski.jarvis.BuildConfig

/**
 * Ustawienia lokalne. Przechowujemy tylko adres backendu i token aplikacji
 * (token chroni Twój backend przed obcymi — to NIE jest klucz API modelu AI).
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    var backendUrl: String
        // Puste pole = adres wbudowany w aplikację (nie da się „zgubić” adresu, czyszcząc pole).
        get() = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_BACKEND_URL
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    var appToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    var voiceReplies: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    /** Tryb rozmowy: po odpowiedzi głosowej Jarvis sam znowu słucha (przy otwartej aplikacji). */
    var handsFree: Boolean
        get() = prefs.getBoolean(KEY_HANDS_FREE, false)
        set(value) = prefs.edit().putBoolean(KEY_HANDS_FREE, value).apply()

    /** Mikrofon słuchawek Bluetooth podczas słuchania polecenia (gdy słuchawki są podłączone). */
    var bluetoothMic: Boolean
        get() = prefs.getBoolean(KEY_BT_MIC, true)
        set(value) = prefs.edit().putBoolean(KEY_BT_MIC, value).apply()

    /** Nasłuch słowa „Jarvis” w tle (usługa z powiadomieniem). Zapamiętane, żeby przełącznik pokazywał stan. */
    var backgroundListening: Boolean
        get() = prefs.getBoolean(KEY_BG, false)
        set(value) = prefs.edit().putBoolean(KEY_BG, value).apply()

    /** Wybrany głos syntezatora (nazwa z TextToSpeech.voices); pusty = domyślny polski. */
    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_VOICE, value).apply()

    /** Tempo mowy 0.7–1.4 (1.0 = normalne). */
    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_RATE, value.coerceIn(0.7f, 1.4f)).apply()

    /** Ton 0.6–1.3 (niżej = głębszy, „męski” głos). */
    var ttsPitch: Float
        get() = prefs.getFloat(KEY_TTS_PITCH, 0.95f)
        set(value) = prefs.edit().putFloat(KEY_TTS_PITCH, value.coerceIn(0.6f, 1.3f)).apply()

    /** Miasto dla pogody; puste = przybliżona lokalizacja telefonu. */
    var weatherCity: String
        get() = prefs.getString(KEY_CITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CITY, value.trim()).apply()

    private companion object {
        const val KEY_CITY = "weather_city"
        const val KEY_URL = "backend_url"
        const val KEY_TOKEN = "app_token"
        const val KEY_VOICE = "voice_replies"
        const val KEY_HANDS_FREE = "hands_free"
        const val KEY_BT_MIC = "bluetooth_mic"
        const val KEY_BG = "background_listening"
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_TTS_RATE = "tts_rate"
        const val KEY_TTS_PITCH = "tts_pitch"
    }
}
