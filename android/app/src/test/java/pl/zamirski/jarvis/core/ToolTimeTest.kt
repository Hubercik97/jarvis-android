package pl.zamirski.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ToolTimeTest {

    private val zone = ZoneId.of("Europe/Warsaw")

    // Wtorek, 20 października 2026, 20:59.
    private val now = ZonedDateTime.of(2026, 10, 20, 20, 59, 30, 0, zone)

    private fun at(r: ToolTime.At) = (r as ToolTime.At.Ok).time

    @Test
    fun `local time label is polish and explicit`() {
        assertEquals("wtorek, 20 października 2026, 20:59", ToolTime.localTimeLabel(now))
    }

    @Test
    fun `reminder in minutes`() {
        val t = at(ToolTime.reminderAt(now, 10, null, null, null))
        assertEquals(ZonedDateTime.of(2026, 10, 20, 21, 9, 0, 0, zone), t)
        assertEquals("dziś o 21:09", ToolTime.whenLabel(now, t))
    }

    @Test
    fun `reminder at an hour that already passed today means tomorrow`() {
        val t = at(ToolTime.reminderAt(now, null, 8, 30, null))
        assertEquals(ZonedDateTime.of(2026, 10, 21, 8, 30, 0, 0, zone), t)
        assertEquals("jutro o 08:30", ToolTime.whenLabel(now, t))
        assertEquals(ZonedDateTime.of(2026, 10, 20, 22, 0, 0, 0, zone), at(ToolTime.reminderAt(now, null, 22, null, null)))
    }

    @Test
    fun `explicit day offset in the past is an error, missing time is an error`() {
        assertTrue(ToolTime.reminderAt(now, null, 8, 0, 0) is ToolTime.At.Error)
        assertTrue(ToolTime.reminderAt(now, null, null, null, null) is ToolTime.At.Error)
        assertTrue(ToolTime.reminderAt(now, 10, 8, null, null) is ToolTime.At.Error)
        assertEquals(
            ZonedDateTime.of(2026, 10, 22, 9, 0, 0, 0, zone),
            at(ToolTime.reminderAt(now, null, 9, null, 2)),
        )
    }

    @Test
    fun `reminder across the DST change keeps the wall clock time`() {
        // 25.10.2026 o 3:00 w Polsce zmiana czasu na zimowy.
        val t = at(ToolTime.reminderAt(now, null, 9, 0, 5))
        assertEquals(9, t.hour)
        assertEquals(25, t.dayOfMonth)
    }

    @Test
    fun `calendar ranges`() {
        val timed = ToolTime.eventRange(now, "2026-10-22", "9:30", null) as ToolTime.Range.Ok
        assertEquals(ZonedDateTime.of(2026, 10, 22, 9, 30, 0, 0, zone), timed.begin)
        assertEquals(ZonedDateTime.of(2026, 10, 22, 10, 30, 0, 0, zone), timed.end)
        val allDay = ToolTime.eventRange(now, "2026-10-22", null, null) as ToolTime.Range.Ok
        assertTrue(allDay.allDay)
        assertTrue(ToolTime.eventRange(now, "2026-02-30", null, null) is ToolTime.Range.Error)
        assertTrue(ToolTime.eventRange(now, "2026-10-22", "25:00", null) is ToolTime.Range.Error)
        assertEquals(now.toLocalDate(), ToolTime.parseDay(now, null))
    }

    @Test
    fun `durations`() {
        assertEquals("10 min", ToolTime.durationLabel(600))
        assertEquals("1 godz. 5 min 30 s", ToolTime.durationLabel(3930))
        assertEquals("45 s", ToolTime.durationLabel(45))
    }
}
