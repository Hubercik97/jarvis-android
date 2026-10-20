package pl.zamirski.jarvis.core

/**
 * Narzędzia LOKALNE Jarvisa — działania na telefonie, które AI może tylko ZAPROPONOWAĆ.
 * Aplikacja sprawdza każde wywołanie w tym rejestrze (nazwa, parametry, poziom ryzyka)
 * i dopiero wtedy je wykonuje. Nie ma tu żadnych dowolnych poleceń systemowych.
 *
 * Nazwy muszą być zgodne z TOOL_DEFS w backend/worker.js (sprawdza to test backendu).
 */

/** Wywołanie narzędzia zaproponowane przez AI (id nadaje model, odsyłamy je z wynikiem). */
data class ToolCall(val id: String, val tool: String, val args: Map<String, Any?>)

/** Wynik wykonania narzędzia na telefonie — wraca do AI, żeby powiedziało, co się stało. */
data class ToolResult(val id: String, val ok: Boolean, val output: String)

/** Wywołania z poprzedniej odpowiedzi AI razem z ich wynikami (do kolejnego zapytania). */
data class ToolPending(val actions: List<ToolCall>, val results: List<ToolResult>)

enum class ToolRisk {
    /** Bez skutków dla innych osób i bez usuwania danych (otwarcie aplikacji, alarm, latarka…). */
    SAFE,

    /** Wymaga potwierdzenia użytkownika (wiadomość, połączenie, usunięcie danych…). */
    CONFIRM,
}

enum class ParamType { STRING, INT, BOOLEAN, ENUM }

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean = true,
    val maxLength: Int = 200,
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE,
    val allowed: Set<String> = emptySet(),
    /** Wymagany format tekstu (np. data RRRR-MM-DD). */
    val pattern: Regex? = null,
)

data class ToolSpec(
    val name: String,
    /** Krótka polska nazwa działania do historii i potwierdzeń. */
    val title: String,
    val risk: ToolRisk,
    val params: List<ParamSpec>,
)

sealed class ToolValidation {
    data class Ok(val args: Map<String, Any?>) : ToolValidation()
    data class Error(val message: String) : ToolValidation()
}

object ToolRegistry {

    const val OPEN_APP = "open_app"
    const val SET_ALARM = "set_alarm"
    const val SET_TIMER = "set_timer"
    const val CREATE_REMINDER = "create_reminder"
    const val ADD_CALENDAR_EVENT = "add_calendar_event"
    const val GET_CALENDAR_EVENTS = "get_calendar_events"
    const val FLASHLIGHT = "flashlight"
    const val OPEN_SETTINGS = "open_settings"

    /** Maks. liczba rund „AI proponuje → telefon wykonuje → AI odpowiada” na jedno polecenie. */
    const val MAX_ROUNDS = 3

    val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    val TIME = Regex("^\\d{1,2}:\\d{2}$")

    val SETTINGS_SCREENS = setOf("main", "bluetooth", "wifi", "battery", "sound", "display", "apps", "location", "notifications")

    private val label = ParamSpec("label", ParamType.STRING, required = false, maxLength = 60)

    val specs: List<ToolSpec> = listOf(
        ToolSpec(OPEN_APP, "Otwórz aplikację", ToolRisk.SAFE, listOf(ParamSpec("name", ParamType.STRING, maxLength = 80))),
        ToolSpec(
            SET_ALARM, "Ustaw alarm", ToolRisk.SAFE,
            listOf(
                ParamSpec("hour", ParamType.INT, min = 0, max = 23),
                ParamSpec("minute", ParamType.INT, required = false, min = 0, max = 59),
                label,
            ),
        ),
        ToolSpec(
            SET_TIMER, "Ustaw minutnik", ToolRisk.SAFE,
            listOf(ParamSpec("seconds", ParamType.INT, min = 1, max = 86_400), label),
        ),
        ToolSpec(
            CREATE_REMINDER, "Przypomnienie", ToolRisk.SAFE,
            listOf(
                ParamSpec("text", ParamType.STRING, maxLength = 200),
                ParamSpec("in_minutes", ParamType.INT, required = false, min = 1, max = 10_080),
                ParamSpec("hour", ParamType.INT, required = false, min = 0, max = 23),
                ParamSpec("minute", ParamType.INT, required = false, min = 0, max = 59),
                ParamSpec("day_offset", ParamType.INT, required = false, min = 0, max = 30),
            ),
        ),
        ToolSpec(
            ADD_CALENDAR_EVENT, "Nowe wydarzenie w kalendarzu", ToolRisk.SAFE,
            listOf(
                ParamSpec("title", ParamType.STRING, maxLength = 120),
                ParamSpec("date", ParamType.STRING, maxLength = 10, pattern = DATE),
                ParamSpec("start_time", ParamType.STRING, required = false, maxLength = 5, pattern = TIME),
                ParamSpec("duration_minutes", ParamType.INT, required = false, min = 5, max = 1440),
            ),
        ),
        ToolSpec(
            GET_CALENDAR_EVENTS, "Sprawdź kalendarz", ToolRisk.SAFE,
            listOf(
                ParamSpec("date", ParamType.STRING, required = false, maxLength = 10, pattern = DATE),
                ParamSpec("days", ParamType.INT, required = false, min = 1, max = 7),
            ),
        ),
        ToolSpec(FLASHLIGHT, "Latarka", ToolRisk.SAFE, listOf(ParamSpec("on", ParamType.BOOLEAN))),
        ToolSpec(
            OPEN_SETTINGS, "Otwórz ustawienia", ToolRisk.SAFE,
            listOf(ParamSpec("screen", ParamType.ENUM, allowed = SETTINGS_SCREENS)),
        ),
    )

    /**
     * Narzędzia otwierające ekran innej aplikacji. Z tła Android pozwala na to tylko aplikacjom
     * ze zgodą „Wyświetlanie nad innymi aplikacjami” (SYSTEM_ALERT_WINDOW).
     */
    val LAUNCHES_UI = setOf(OPEN_APP, SET_ALARM, SET_TIMER, ADD_CALENDAR_EVENT, OPEN_SETTINGS)

    /** Nazwy narzędzi zgłaszane backendowi (AI może użyć tylko tych). */
    val names: List<String> get() = specs.map { it.name }

    fun spec(name: String): ToolSpec? = specs.firstOrNull { it.name == name }

    /** Sprawdza nazwę i parametry; zwraca oczyszczone parametry albo polski opis błędu. */
    fun validate(call: ToolCall): ToolValidation {
        val spec = spec(call.tool) ?: return ToolValidation.Error("Nieznane narzędzie: ${call.tool}")
        val unknown = call.args.keys - spec.params.map { it.name }.toSet()
        if (unknown.isNotEmpty()) return ToolValidation.Error("Nieznane parametry: ${unknown.sorted().joinToString()}")
        val clean = mutableMapOf<String, Any?>()
        for (p in spec.params) {
            val raw = call.args[p.name]
            if (raw == null) {
                if (p.required) return ToolValidation.Error("Brak parametru: ${p.name}")
                continue
            }
            when (p.type) {
                ParamType.STRING -> {
                    val v = (raw as? String)?.trim() ?: return ToolValidation.Error("Parametr ${p.name} musi być tekstem")
                    if (v.isEmpty()) {
                        if (p.required) return ToolValidation.Error("Pusty parametr: ${p.name}")
                        continue
                    }
                    if (v.length > p.maxLength) return ToolValidation.Error("Za długi parametr: ${p.name}")
                    if (p.pattern != null && !p.pattern.matches(v)) return ToolValidation.Error("Zły format parametru: ${p.name}")
                    clean[p.name] = v
                }
                ParamType.INT -> {
                    val v = when (raw) {
                        is Int -> raw
                        is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                        is Double -> raw.takeIf { it % 1.0 == 0.0 && it in -1e9..1e9 }?.toInt()
                        else -> null
                    } ?: return ToolValidation.Error("Parametr ${p.name} musi być liczbą całkowitą")
                    if (v < p.min || v > p.max) return ToolValidation.Error("Parametr ${p.name} poza zakresem")
                    clean[p.name] = v
                }
                ParamType.BOOLEAN -> {
                    clean[p.name] = raw as? Boolean ?: return ToolValidation.Error("Parametr ${p.name} musi być tak/nie")
                }
                ParamType.ENUM -> {
                    val v = (raw as? String)?.trim()?.lowercase()
                    if (v == null || v !in p.allowed) return ToolValidation.Error("Niedozwolona wartość parametru ${p.name}")
                    clean[p.name] = v
                }
            }
        }
        return ToolValidation.Ok(clean)
    }

    fun needsConfirmation(call: ToolCall): Boolean = spec(call.tool)?.risk != ToolRisk.SAFE

    /** Opis działania dla użytkownika (historia, potwierdzenie). */
    fun describe(call: ToolCall): String {
        val spec = spec(call.tool) ?: return call.tool
        val a = call.args
        fun two(v: Any?) = (v as? Number)?.toInt()?.toString()?.padStart(2, '0') ?: "00"
        val detail = when (call.tool) {
            OPEN_APP -> a["name"]
            SET_ALARM -> "${(a["hour"] as? Number)?.toInt() ?: "?"}:${two(a["minute"])}" + (a["label"]?.let { " — $it" } ?: "")
            SET_TIMER -> ToolTime.durationLabel((a["seconds"] as? Number)?.toInt() ?: 0)
            CREATE_REMINDER -> a["text"]
            ADD_CALENDAR_EVENT -> "${a["title"]}, ${a["date"]}" + (a["start_time"]?.let { " $it" } ?: "")
            GET_CALENDAR_EVENTS -> a["date"] ?: "dziś"
            FLASHLIGHT -> if (a["on"] == true) "włącz" else "wyłącz"
            OPEN_SETTINGS -> a["screen"]
            else -> null
        }
        return if (detail == null) spec.title else "${spec.title}: $detail"
    }
}
