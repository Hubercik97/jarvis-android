package pl.zamirski.jarvis.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.zamirski.jarvis.core.AppEntry
import pl.zamirski.jarvis.core.AppMatcher
import pl.zamirski.jarvis.core.ToolCall
import pl.zamirski.jarvis.core.ToolRegistry
import pl.zamirski.jarvis.core.ToolResult
import pl.zamirski.jarvis.core.ToolTime
import pl.zamirski.jarvis.core.ToolValidation
import pl.zamirski.jarvis.data.CalendarRepository
import pl.zamirski.jarvis.data.LocalStore
import pl.zamirski.jarvis.data.ReminderScheduler
import pl.zamirski.jarvis.core.ProtocolException
import pl.zamirski.jarvis.net.BackendMemoryClient
import pl.zamirski.jarvis.settings.AppSettings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Wykonuje na telefonie wyłącznie narzędzia z ToolRegistry, po walidacji parametrów.
 * Potwierdzenia działań wrażliwych obsługuje ViewModel PRZED wywołaniem execute().
 */
class LocalToolExecutor(context: Context) {

    private val app = context.applicationContext
    private val store = LocalStore.get(app)
    private val calendar = CalendarRepository(app)
    private val memory = BackendMemoryClient(AppSettings(app))

    suspend fun execute(call: ToolCall): ToolResult {
        val a = when (val v = ToolRegistry.validate(call)) {
            is ToolValidation.Ok -> v.args
            is ToolValidation.Error -> return ToolResult(call.id, false, v.message)
        }
        val id = call.id
        return try {
            when (call.tool) {
                ToolRegistry.OPEN_APP -> openApp(id, a["name"] as String)
                ToolRegistry.SET_ALARM -> setAlarm(id, a["hour"] as Int, a["minute"] as Int? ?: 0, a["label"] as String?)
                ToolRegistry.SET_TIMER -> setTimer(id, a["seconds"] as Int, a["label"] as String?)
                ToolRegistry.CREATE_REMINDER -> createReminder(id, a)
                ToolRegistry.ADD_CALENDAR_EVENT -> addCalendarEvent(id, a)
                ToolRegistry.GET_CALENDAR_EVENTS -> calendarEvents(id, a["date"] as String?, a["days"] as Int? ?: 1)
                ToolRegistry.FLASHLIGHT -> flashlight(id, a["on"] as Boolean)
                ToolRegistry.OPEN_SETTINGS -> openSettings(id, a["screen"] as String)
                ToolRegistry.REMEMBER_FACT -> ToolResult(id, true, memory.add(a["text"] as String))
                else -> ToolResult(id, false, "Narzędzie niedostępne na tym telefonie")
            }
        } catch (e: ProtocolException) {
            ToolResult(id, false, e.message ?: "Błąd serwera")
        } catch (e: java.io.IOException) {
            ToolResult(id, false, e.message ?: "Brak połączenia z serwerem")
        } catch (e: SecurityException) {
            ToolResult(id, false, "Android nie pozwolił wykonać tego działania")
        } catch (e: ActivityNotFoundException) {
            ToolResult(id, false, "Na telefonie nie ma aplikacji, która obsługuje to działanie")
        } catch (e: Exception) {
            ToolResult(id, false, "Błąd wykonania: ${e.javaClass.simpleName}")
        }
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())

    private suspend fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) { app.startActivity(intent) }
    }

    // ---------- Aplikacje ----------

    /** Aplikacje z ikoną w menu (bez samego Jarvisa). Wymaga deklaracji <queries> dla LAUNCHER (Android 11+). */
    suspend fun launchableApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == app.packageName) return@mapNotNull null
                AppEntry(ri.loadLabel(pm).toString(), pkg)
            }
            .distinctBy { it.packageName }
    }

    private suspend fun openApp(id: String, name: String): ToolResult {
        val match = AppMatcher.match(name, launchableApps())
        if (match !is AppMatcher.Match.Found) return ToolResult(id, false, AppMatcher.resultText(name, match))
        val launch = app.packageManager.getLaunchIntentForPackage(match.app.packageName)
            ?: return ToolResult(id, false, "Aplikacji ${match.app.label} nie da się uruchomić z zewnątrz")
        launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        start(launch)
        return ToolResult(id, true, AppMatcher.resultText(name, match))
    }

    // ---------- Zegar ----------

    private suspend fun setAlarm(id: String, hour: Int, minute: Int, label: String?): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(intent)
        return ToolResult(id, true, "Przekazano alarm na %d:%02d do aplikacji Zegar".format(hour, minute))
    }

    private suspend fun setTimer(id: String, seconds: Int, label: String?): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(intent)
        return ToolResult(id, true, "Przekazano minutnik na ${ToolTime.durationLabel(seconds)} do aplikacji Zegar")
    }

    // ---------- Przypomnienia (istniejący mechanizm Jarvisa) ----------

    private fun createReminder(id: String, a: Map<String, Any?>): ToolResult {
        val now = now()
        val at = when (
            val r = ToolTime.reminderAt(now, a["in_minutes"] as Int?, a["hour"] as Int?, a["minute"] as Int?, a["day_offset"] as Int?)
        ) {
            is ToolTime.At.Ok -> r.time
            is ToolTime.At.Error -> return ToolResult(id, false, r.message)
        }
        val reminder = store.addReminder(a["text"] as String, at.toInstant().toEpochMilli())
        ReminderScheduler.schedule(app, reminder)
        val warn = if (ReminderScheduler.canNotify(app)) "" else " Uwaga: powiadomienia Jarvisa są wyłączone — włącz je, żeby zobaczyć przypomnienie."
        return ToolResult(id, true, "Zapisano przypomnienie na ${ToolTime.whenLabel(now, at)} (może przyjść z kilkuminutowym opóźnieniem).$warn")
    }

    // ---------- Kalendarz ----------

    private suspend fun addCalendarEvent(id: String, a: Map<String, Any?>): ToolResult {
        val range = when (val r = ToolTime.eventRange(now(), a["date"] as String, a["start_time"] as String?, a["duration_minutes"] as Int?)) {
            is ToolTime.Range.Ok -> r
            is ToolTime.Range.Error -> return ToolResult(id, false, r.message)
        }
        // Systemowy ekran kalendarza z wypełnionym wydarzeniem — zapisuje dopiero użytkownik (bez uprawnienia do zapisu).
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a["title"] as String)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, range.begin.toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, range.end.toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, range.allDay)
        start(intent)
        return ToolResult(id, true, "Otwarto kalendarz z gotowym wydarzeniem — użytkownik musi je jeszcze zapisać w kalendarzu.")
    }

    private suspend fun calendarEvents(id: String, date: String?, days: Int): ToolResult {
        if (!calendar.hasPermission()) {
            return ToolResult(id, false, "Brak zgody na odczyt kalendarza. Otwórz w Jarvisie ekran Kalendarz i zezwól na dostęp.")
        }
        val now = now()
        val from = ToolTime.parseDay(now, date) ?: return ToolResult(id, false, "Nieprawidłowa data: $date")
        val events = calendar.eventsBetween(from, from.plusDays(days.toLong()))
        if (events.isEmpty()) return ToolResult(id, true, "Brak wydarzeń w tym okresie.")
        val zone = now.zone
        val lines = events.take(MAX_EVENTS).map { e ->
            val b = Instant.ofEpochMilli(e.begin).atZone(zone)
            val day = if (days > 1) "${b.dayOfMonth}.${"%02d".format(b.monthValue)} " else ""
            val time = if (e.allDay) "cały dzień" else "${ToolTime.clock(b)}–${ToolTime.clock(Instant.ofEpochMilli(e.end).atZone(zone))}"
            "$day$time ${e.title.ifBlank { "(bez tytułu)" }}"
        }
        val more = if (events.size > MAX_EVENTS) " (i ${events.size - MAX_EVENTS} więcej)" else ""
        return ToolResult(id, true, lines.joinToString("; ") + more)
    }

    // ---------- Latarka i ustawienia ----------

    private fun flashlight(id: String, on: Boolean): ToolResult {
        val cm = app.getSystemService(CameraManager::class.java) ?: return ToolResult(id, false, "Brak dostępu do aparatu")
        val camera = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult(id, false, "Ten telefon nie ma latarki dostępnej dla aplikacji")
        cm.setTorchMode(camera, on)
        return ToolResult(id, true, if (on) "Latarka włączona" else "Latarka wyłączona")
    }

    private suspend fun openSettings(id: String, screen: String): ToolResult {
        val action = when (screen) {
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action)
        if (screen == "notifications") intent.putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
        start(intent)
        return ToolResult(id, true, "Otwarto ekran ustawień: $screen. Android nie pozwala aplikacjom samodzielnie przełączać tych opcji.")
    }

    private companion object {
        const val MAX_EVENTS = 10
    }
}
