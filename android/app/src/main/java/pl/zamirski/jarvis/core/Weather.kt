package pl.zamirski.jarvis.core

import org.json.JSONObject

/** Rodzaj ikony pogody (rysowany w UI). */
enum class WeatherIcon { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM, UNKNOWN }

data class HourForecast(val label: String, val temp: Int, val icon: WeatherIcon)
data class DayForecast(val dayLabel: String, val max: Int, val min: Int, val icon: WeatherIcon)

data class WeatherReport(
    val place: String,
    val temp: Int,
    val description: String,
    val icon: WeatherIcon,
    val isDay: Boolean,
    val todayMax: Int,
    val todayMin: Int,
    val hours: List<HourForecast>,
    val days: List<DayForecast>,
    /** Czas pomiaru z API (lokalny, „HH:mm”). */
    val observedAt: String,
)

data class GeoPlace(val name: String, val latitude: Double, val longitude: Double)

/** Kody pogody WMO używane przez Open-Meteo → polski opis i ikona. */
object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Bezchmurnie"
        1 -> "Przeważnie słonecznie"
        2 -> "Częściowo pochmurno"
        3 -> "Pochmurno"
        45, 48 -> "Mgła"
        51, 53, 55 -> "Mżawka"
        56, 57 -> "Marznąca mżawka"
        61, 63 -> "Deszcz"
        65 -> "Ulewny deszcz"
        66, 67 -> "Marznący deszcz"
        71, 73 -> "Śnieg"
        75 -> "Intensywny śnieg"
        77 -> "Ziarna śniegu"
        80, 81 -> "Przelotny deszcz"
        82 -> "Gwałtowne opady"
        85, 86 -> "Przelotny śnieg"
        95 -> "Burza"
        96, 99 -> "Burza z gradem"
        else -> "Brak danych"
    }

    fun icon(code: Int): WeatherIcon = when (code) {
        0 -> WeatherIcon.CLEAR
        1, 2 -> WeatherIcon.PARTLY
        3 -> WeatherIcon.CLOUDY
        45, 48 -> WeatherIcon.FOG
        51, 53, 55, 56, 57 -> WeatherIcon.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> WeatherIcon.RAIN
        71, 73, 75, 77, 85, 86 -> WeatherIcon.SNOW
        95, 96, 99 -> WeatherIcon.STORM
        else -> WeatherIcon.UNKNOWN
    }
}

object WeatherParser {
    private val dayNames = mapOf(
        1 to "Poniedziałek", 2 to "Wtorek", 3 to "Środa", 4 to "Czwartek",
        5 to "Piątek", 6 to "Sobota", 7 to "Niedziela",
    )

    fun forecastUrl(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=6"

    fun geocodeUrl(name: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&language=pl&format=json&name=" +
            java.net.URLEncoder.encode(name.trim(), "UTF-8")

    fun parseGeocode(body: String): GeoPlace? {
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        return GeoPlace(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    /**
     * Parsuje odpowiedź Open-Meteo. Zwraca 5 kolejnych godzin od bieżącej (co 3 h)
     * i do 5 kolejnych dni (bez dzisiejszego).
     */
    fun parseForecast(body: String, place: String): WeatherReport {
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val code = current.getInt("weather_code")
        val nowIso = current.getString("time") // np. 2026-10-19T10:45
        val hourly = json.getJSONObject("hourly")
        val hTimes = hourly.getJSONArray("time")
        val hTemps = hourly.getJSONArray("temperature_2m")
        val hCodes = hourly.getJSONArray("weather_code")

        val nowHourPrefix = nowIso.take(13) // 2026-10-19T10
        var start = 0
        for (i in 0 until hTimes.length()) {
            if (hTimes.getString(i).take(13) >= nowHourPrefix) {
                start = i
                break
            }
        }
        val hours = mutableListOf<HourForecast>()
        var i = start
        while (i < hTimes.length() && hours.size < 5) {
            val label = if (hours.isEmpty()) "Teraz" else hTimes.getString(i).substring(11, 16)
            hours += HourForecast(label, hTemps.getDouble(i).roundToIntSafe(), WeatherCodes.icon(hCodes.getInt(i)))
            i += 3
        }

        val daily = json.getJSONObject("daily")
        val dTimes = daily.getJSONArray("time")
        val dMax = daily.getJSONArray("temperature_2m_max")
        val dMin = daily.getJSONArray("temperature_2m_min")
        val dCodes = daily.getJSONArray("weather_code")
        val days = mutableListOf<DayForecast>()
        for (d in 1 until dTimes.length()) {
            val date = java.time.LocalDate.parse(dTimes.getString(d))
            days += DayForecast(
                dayNames[date.dayOfWeek.value] ?: dTimes.getString(d),
                dMax.getDouble(d).roundToIntSafe(),
                dMin.getDouble(d).roundToIntSafe(),
                WeatherCodes.icon(dCodes.getInt(d)),
            )
            if (days.size == 5) break
        }

        return WeatherReport(
            place = place,
            temp = current.getDouble("temperature_2m").roundToIntSafe(),
            description = WeatherCodes.describe(code),
            icon = WeatherCodes.icon(code),
            isDay = current.optInt("is_day", 1) == 1,
            todayMax = dMax.getDouble(0).roundToIntSafe(),
            todayMin = dMin.getDouble(0).roundToIntSafe(),
            hours = hours,
            days = days,
            observedAt = if (nowIso.length >= 16) nowIso.substring(11, 16) else nowIso,
        )
    }

    private fun Double.roundToIntSafe(): Int = Math.round(this).toInt()
}
