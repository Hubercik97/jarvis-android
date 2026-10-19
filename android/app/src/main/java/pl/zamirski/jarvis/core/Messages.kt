package pl.zamirski.jarvis.core

/** Polskie komunikaty błędów pokazywane użytkownikowi. */
object Messages {

    fun forFailure(reason: FailureReason, detail: String? = null): String = when (reason) {
        FailureReason.NOT_CONFIGURED ->
            "Brak adresu backendu AI. Otwórz Status i wpisz adres serwera (https://…)."
        FailureReason.OFFLINE ->
            "Brak internetu. Sprawdź Wi‑Fi lub dane komórkowe i spróbuj ponownie."
        FailureReason.UNAUTHORIZED ->
            "Serwer odrzucił token aplikacji. Sprawdź token w ekranie Status."
        FailureReason.TIMEOUT ->
            "Serwer AI nie odpowiedział na czas. Spróbuj ponownie."
        FailureReason.SERVER ->
            "Błąd serwera AI" + (detail?.let { ": $it" } ?: ".")
        FailureReason.BAD_RESPONSE ->
            "Serwer zwrócił nieprawidłową odpowiedź" + (detail?.let { ": $it" } ?: ".")
    }

    /** Kody błędów z android.speech.SpeechRecognizer (wartości stałych ERROR_*). */
    fun forSpeechError(code: Int): String = when (code) {
        1 -> "Przekroczono czas połączenia rozpoznawania mowy. Sprawdź internet."
        2 -> "Rozpoznawanie mowy wymaga internetu albo pobranego pakietu języka polskiego."
        3 -> "Problem z nagrywaniem dźwięku. Inna aplikacja może używać mikrofonu."
        4 -> "Usługa rozpoznawania mowy zwróciła błąd. Spróbuj ponownie."
        5 -> "Rozpoznawanie przerwane."
        6 -> "Nic nie usłyszałem. Naciśnij mikrofon i mów."
        7 -> "Nie zrozumiałem. Spróbuj powiedzieć to jeszcze raz."
        8 -> "Rozpoznawanie mowy jest zajęte. Poczekaj chwilę."
        9 -> "Brak zgody na mikrofon. Zezwól na mikrofon w ustawieniach aplikacji."
        10 -> "Zbyt wiele prób rozpoznawania. Poczekaj chwilę."
        11 -> "Rozłączono z usługą rozpoznawania mowy."
        12, 13 -> "Język polski nie jest dostępny w rozpoznawaniu mowy. Zainstaluj go w ustawieniach Google → Mowa."
        else -> "Nieznany błąd rozpoznawania mowy ($code)."
    }

    /** Czy błąd rozpoznawania to po prostu cisza / brak dopasowania (łagodniejszy komunikat). */
    fun isSilence(code: Int): Boolean = code == 6 || code == 7
}
