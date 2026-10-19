package pl.zamirski.jarvis.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherAndLocalTest {

    // Odpowiedź w formacie Open-Meteo (dane testowe, nie prawdziwy pomiar).
    private val sample = """
        {
          "current": {"time": "2026-10-19T10:45", "temperature_2m": 12.6, "weather_code": 2, "is_day": 1},
          "hourly": {
            "time": [${(0..23).joinToString(",") { "\"2026-10-19T%02d:00\"".format(it) }}],
            "temperature_2m": [${(0..23).joinToString(",") { "${it}.4" }}],
            "weather_code": [${(0..23).joinToString(",") { "3" }}]
          },
          "daily": {
            "time": ["2026-10-19","2026-10-20","2026-10-21","2026-10-22","2026-10-23","2026-10-24"],
            "temperature_2m_max": [14.5, 15.1, 13.0, 11.2, 10.0, 9.6],
            "temperature_2m_min": [6.4, 7.0, 5.5, 4.9, 3.2, 2.1],
            "weather_code": [2, 61, 0, 95, 71, 45]
          }
        }
    """.trimIndent()

    @Test
    fun `forecast is parsed into current, hours and days`() {
        val r = WeatherParser.parseForecast(sample, "Warszawa")
        assertEquals("Warszawa", r.place)
        assertEquals(13, r.temp)
        assertEquals("Częściowo pochmurno", r.description)
        assertEquals(WeatherIcon.PARTLY, r.icon)
        assertEquals(15, r.todayMax) // 14.5 → 15 (zaokrąglenie)
        assertEquals(6, r.todayMin)
        assertEquals("10:45", r.observedAt)
        assertEquals(5, r.hours.size)
        assertEquals("Teraz", r.hours[0].label)
        assertEquals(10, r.hours[0].temp)
        assertEquals("13:00", r.hours[1].label)
        assertEquals(5, r.days.size)
        assertEquals("Wtorek", r.days[0].dayLabel)
        assertEquals(WeatherIcon.RAIN, r.days[0].icon)
        assertEquals(WeatherIcon.STORM, r.days[2].icon)
    }

    @Test
    fun `geocode picks first result or null`() {
        val place = WeatherParser.parseGeocode("""{"results":[{"name":"Kraków","latitude":50.06,"longitude":19.94}]}""")
        assertEquals("Kraków", place?.name)
        assertNull(WeatherParser.parseGeocode("""{"generationtime_ms":0.5}"""))
        assertTrue(WeatherParser.geocodeUrl("Bielsko-Biała").contains("Bielsko-Bia%C5%82a"))
    }

    @Test
    fun `unknown weather code is reported as missing data`() {
        assertEquals("Brak danych", WeatherCodes.describe(42))
        assertEquals(WeatherIcon.UNKNOWN, WeatherCodes.icon(42))
    }

    @Test
    fun `local items round trip`() {
        val notes = listOf(Note(1, "Kupić mleko — ąę", 100))
        assertEquals(notes, LocalCodec.decodeNotes(LocalCodec.encodeNotes(notes)))
        val tasks = listOf(TaskItem(2, "Strona GarmiLabs", true, 200))
        assertEquals(tasks, LocalCodec.decodeTasks(LocalCodec.encodeTasks(tasks)))
        val reminders = listOf(Reminder(3, "Trening", 300))
        assertEquals(reminders, LocalCodec.decodeReminders(LocalCodec.encodeReminders(reminders)))
    }

    @Test
    fun `corrupted storage gives empty list`() {
        assertTrue(LocalCodec.decodeNotes("nie-json").isEmpty())
        assertTrue(LocalCodec.decodeTasks(null).isEmpty())
        assertEquals(1, LocalCodec.decodeTasks("""[{"id":1,"title":"ok"},{"bad":true}]""").size)
    }

    @Test
    fun `greeting depends on hour`() {
        assertEquals("Dzień dobry!", Greeting.forHour(8))
        assertEquals("Cześć!", Greeting.forHour(14))
        assertEquals("Dobry wieczór!", Greeting.forHour(20))
        assertFalse(Greeting.forHour(2).isBlank())
    }

    @Test
    fun `image is attached only to the last user message`() {
        val history = listOf(
            ChatMessage(Kind.USER, "stare", imageBase64 = "QUJD"),
            ChatMessage(Kind.ASSISTANT, "odp"),
            ChatMessage(Kind.USER, "Co to?", imageBase64 = "REVG", attachmentLabel = "Zdjęcie z kamery"),
        )
        val msgs = JSONObject(ChatProtocol.buildRequest(history)).getJSONArray("messages")
        assertFalse(msgs.getJSONObject(0).has("image"))
        assertEquals("REVG", msgs.getJSONObject(2).getJSONObject("image").getString("data"))
        assertEquals("image/jpeg", msgs.getJSONObject(2).getJSONObject("image").getString("media_type"))
    }
}
