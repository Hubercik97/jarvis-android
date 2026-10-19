package pl.zamirski.jarvis.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.zamirski.jarvis.core.WeatherParser
import pl.zamirski.jarvis.core.WeatherReport
import pl.zamirski.jarvis.net.Http
import pl.zamirski.jarvis.net.NetworkMonitor
import pl.zamirski.jarvis.settings.AppSettings
import java.util.Locale

sealed class WeatherState {
    object Idle : WeatherState()
    object Loading : WeatherState()
    data class Ready(val report: WeatherReport) : WeatherState()
    /** Brak danych — z konkretnym powodem. */
    data class Missing(val reason: String, val needsLocationPermission: Boolean = false) : WeatherState()
}

/**
 * Prawdziwa pogoda z Open-Meteo (bez klucza API).
 * Miejsce: miasto wpisane w Ustawieniach, a jeśli puste — przybliżona lokalizacja telefonu.
 */
class WeatherRepository(private val context: Context, private val settings: AppSettings) {

    suspend fun load(): WeatherState = withContext(Dispatchers.IO) {
        if (!NetworkMonitor.isOnline(context)) return@withContext WeatherState.Missing("Brak internetu — nie można pobrać pogody.")
        try {
            val city = settings.weatherCity.trim()
            if (city.isNotEmpty()) {
                val place = WeatherParser.parseGeocode(Http.get(WeatherParser.geocodeUrl(city)))
                    ?: return@withContext WeatherState.Missing("Nie znaleziono miasta „$city”. Popraw je w Ustawieniach.")
                return@withContext WeatherState.Ready(
                    WeatherParser.parseForecast(Http.get(WeatherParser.forecastUrl(place.latitude, place.longitude)), place.name),
                )
            }
            if (!hasLocationPermission()) {
                return@withContext WeatherState.Missing(
                    "Brak danych o pogodzie — zezwól na przybliżoną lokalizację albo wpisz miasto w Ustawieniach.",
                    needsLocationPermission = true,
                )
            }
            val location = lastKnownLocation()
                ?: return@withContext WeatherState.Missing("Telefon nie zna jeszcze lokalizacji. Włącz lokalizację albo wpisz miasto w Ustawieniach.")
            val name = placeName(location) ?: "Twoja okolica"
            WeatherState.Ready(WeatherParser.parseForecast(Http.get(WeatherParser.forecastUrl(location.latitude, location.longitude)), name))
        } catch (e: Exception) {
            WeatherState.Missing("Nie udało się pobrać pogody (${e.message ?: "błąd sieci"}).")
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    private fun placeName(location: Location): String? = runCatching {
        Geocoder(context, Locale("pl", "PL")).getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()?.locality
    }.getOrNull()
}
