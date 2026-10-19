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
        get() = prefs.getString(KEY_URL, null) ?: BuildConfig.DEFAULT_BACKEND_URL
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    var appToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    var voiceReplies: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    /** Miasto dla pogody; puste = przybliżona lokalizacja telefonu. */
    var weatherCity: String
        get() = prefs.getString(KEY_CITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CITY, value.trim()).apply()

    private companion object {
        const val KEY_CITY = "weather_city"
        const val KEY_URL = "backend_url"
        const val KEY_TOKEN = "app_token"
        const val KEY_VOICE = "voice_replies"
    }
}
