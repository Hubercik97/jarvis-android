package pl.zamirski.jarvis.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Obliczenia czasu dla narzędzi (czysta logika — testowana jednostkowo). */
object ToolTime {

    private val PL = Locale("pl", "PL")
    private val LABEL = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", PL)
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** Czas lokalny dla AI, np. „wtorek, 20 października 2026, 20:59” — model nie zna aktualnej daty. */
    fun localTimeLabel(now: ZonedDateTime): String = LABEL.format(now)

    sealed class At {
        data class Ok(val time: ZonedDateTime) : At()
        data class Error(val message: String) : At()
    }

    /**
     * Termin przypomnienia: „za N minut” (in_minutes) albo godzina (hour, minute, day_offset).
     * Bez day_offset godzina, która już minęła dziś, oznacza jutro.
     */
    fun reminderAt(now: ZonedDateTime, inMinutes: Int?, hour: Int?, minute: Int?, dayOffset: Int?): At {
        if (inMinutes != null) {
            if (hour != null || dayOffset != null) return At.Error("Podaj albo „za ile minut”, albo godzinę — nie oba naraz")
            return At.Ok(now.plusMinutes(inMinutes.toLong()).withSecond(0).withNano(0))
        }
        if (hour == null) return At.Error("Brak terminu przypomnienia (za ile minut albo o której)")
        var at = now.toLocalDate().plusDays((dayOffset ?: 0).toLong())
            .atTime(hour, minute ?: 0).atZone(now.zone)
        if (!at.isAfter(now)) {
            if (dayOffset == null) at = at.plusDays(1) else return At.Error("Ten termin już minął")
        }
        return At.Ok(at)
    }

    sealed class Range {
        data class Ok(val begin: ZonedDateTime, val end: ZonedDateTime, val allDay: Boolean) : Range()
        data class Error(val message: String) : Range()
    }

    /** Zakres wydarzenia: data RRRR-MM-DD, opcjonalnie godzina HH:MM i czas trwania (domyślnie 60 min). */
    fun eventRange(now: ZonedDateTime, date: String, startTime: String?, durationMinutes: Int?): Range {
        val day = try {
            LocalDate.parse(date)
        } catch (e: DateTimeParseException) {
            return Range.Error("Nieprawidłowa data: $date")
        }
        if (startTime == null) {
            val begin = day.atStartOfDay(now.zone)
            return Range.Ok(begin, begin.plusDays(1), allDay = true)
        }
        val time = try {
            LocalTime.parse(startTime.padStart(5, '0'))
        } catch (e: DateTimeParseException) {
            return Range.Error("Nieprawidłowa godzina: $startTime")
        }
        val begin = day.atTime(time).atZone(now.zone)
        return Range.Ok(begin, begin.plusMinutes((durationMinutes ?: 60).toLong()), allDay = false)
    }

    /** Pierwszy dzień do sprawdzenia w kalendarzu (domyślnie dziś). */
    fun parseDay(now: ZonedDateTime, date: String?): LocalDate? =
        if (date == null) now.toLocalDate() else try {
            LocalDate.parse(date)
        } catch (e: DateTimeParseException) {
            null
        }

    fun clock(t: ZonedDateTime): String = HM.format(t)

    /** „1 godz. 5 min 30 s” — do opisu minutnika. */
    fun durationLabel(seconds: Int): String {
        // Bez Duration.toMinutesPart() — to API dopiero od Androida 12, a minSdk to 26.
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h godz."
        if (m > 0) parts += "$m min"
        if (s > 0) parts += "$s s"
        return if (parts.isEmpty()) "0 s" else parts.joinToString(" ")
    }

    /** Opis terminu dla AI i historii, np. „jutro o 08:30” albo „20.10 o 21:15”. */
    fun whenLabel(now: ZonedDateTime, at: ZonedDateTime): String {
        val days = Duration.between(now.toLocalDate().atStartOfDay(now.zone), at.toLocalDate().atStartOfDay(now.zone)).toDays()
        val day = when (days) {
            0L -> "dziś"
            1L -> "jutro"
            2L -> "pojutrze"
            else -> DateTimeFormatter.ofPattern("d.MM", PL).format(at)
        }
        return "$day o ${clock(at)}"
    }
}
