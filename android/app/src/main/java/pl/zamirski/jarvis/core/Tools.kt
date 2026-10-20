package pl.zamirski.jarvis.core

/**
 * Narzędzia LOKALNE Jarvisa — działania na telefonie, które AI może tylko ZAPROPONOWAĆ.
 * Aplikacja sprawdza każde wywołanie w tym rejestrze (nazwa, parametry, poziom ryzyka)
 * i dopiero wtedy je wykonuje. Nie ma tu żadnych dowolnych poleceń systemowych.
 */

/** Wywołanie narzędzia zaproponowane przez AI (id nadaje model, odsyłamy je z wynikiem). */
data class ToolCall(val id: String, val tool: String, val args: Map<String, Any?>)

/** Wynik wykonania narzędzia na telefonie — wraca do AI, żeby powiedziało, co się stało. */
data class ToolResult(val id: String, val ok: Boolean, val output: String)

/** Wywołania z poprzedniej odpowiedzi AI razem z ich wynikami (do kolejnego zapytania). */
data class ToolPending(val actions: List<ToolCall>, val results: List<ToolResult>)

enum class ToolRisk {
    /** Bez skutków dla innych osób i danych (np. otwarcie aplikacji). */
    SAFE,

    /** Wymaga potwierdzenia użytkownika (wiadomość, połączenie, usunięcie danych…). */
    CONFIRM,
}

enum class ParamType { STRING, INT, BOOLEAN }

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean = true,
    val maxLength: Int = 200,
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE,
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

    /** Maks. liczba rund „AI proponuje → telefon wykonuje → AI odpowiada” na jedno polecenie. */
    const val MAX_ROUNDS = 3

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = OPEN_APP,
            title = "Otwórz aplikację",
            risk = ToolRisk.SAFE,
            params = listOf(ParamSpec("name", ParamType.STRING, maxLength = 80)),
        ),
    )

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
                    if (v.isEmpty() && p.required) return ToolValidation.Error("Pusty parametr: ${p.name}")
                    if (v.length > p.maxLength) return ToolValidation.Error("Za długi parametr: ${p.name}")
                    clean[p.name] = v
                }
                ParamType.INT -> {
                    val v = when (raw) {
                        is Int -> raw
                        is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                        is Double -> raw.takeIf { it % 1.0 == 0.0 }?.toInt()
                        else -> null
                    } ?: return ToolValidation.Error("Parametr ${p.name} musi być liczbą całkowitą")
                    if (v < p.min || v > p.max) return ToolValidation.Error("Parametr ${p.name} poza zakresem")
                    clean[p.name] = v
                }
                ParamType.BOOLEAN -> {
                    clean[p.name] = raw as? Boolean ?: return ToolValidation.Error("Parametr ${p.name} musi być tak/nie")
                }
            }
        }
        return ToolValidation.Ok(clean)
    }

    fun needsConfirmation(call: ToolCall): Boolean = spec(call.tool)?.risk != ToolRisk.SAFE

    /** Opis działania dla użytkownika (historia, potwierdzenie). */
    fun describe(call: ToolCall): String {
        val spec = spec(call.tool) ?: return call.tool
        return when (call.tool) {
            OPEN_APP -> "${spec.title}: ${call.args["name"] ?: "?"}"
            else -> spec.title
        }
    }
}
