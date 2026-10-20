package pl.zamirski.jarvis.features

/**
 * Rejestr modułów Jarvisa. Moduły PLANNED nie są zaimplementowane — ekran Status pokazuje to uczciwie.
 * Nowy moduł (pamięć, kamera, okulary, sterowanie komputerem) dostaje tu wpis i własny pakiet,
 * a z modelem AI komunikuje się przez AiClient / backend (np. jako narzędzia modelu).
 */
enum class FeatureState(val label: String) {
    ACTIVE("aktywne"),
    PLANNED("planowane"),
    TESTING("do sprawdzenia na telefonie"),
}

data class Feature(val id: String, val name: String, val state: FeatureState)

object FeatureRegistry {
    val all: List<Feature> = listOf(
        Feature("chat", "Rozmowa po polsku (mikrofon + tekst)", FeatureState.ACTIVE),
        Feature("open_app", "Otwieranie aplikacji i gier poleceniem („otwórz Spotify”)", FeatureState.TESTING),
        Feature("voice_reply", "Odpowiedzi głosowe po polsku", FeatureState.ACTIVE),
        Feature("history", "Historia rozmów (na telefonie)", FeatureState.ACTIVE),
        Feature("hands_free", "Tryb rozmowy bez dotykania ekranu (przy otwartej aplikacji)", FeatureState.ACTIVE),
        Feature("camera", "Kamera → opis zdjęcia przez AI", FeatureState.ACTIVE),
        Feature("files", "Pliki tekstowe i obrazy → analiza AI", FeatureState.ACTIVE),
        Feature("web", "Wyszukiwanie w przeglądarce", FeatureState.ACTIVE),
        Feature("weather", "Pogoda (Open-Meteo)", FeatureState.ACTIVE),
        Feature("calendar", "Kalendarz telefonu (odczyt)", FeatureState.ACTIVE),
        Feature("notes_tasks", "Notatki, zadania, przypomnienia (lokalnie)", FeatureState.ACTIVE),
        Feature("memory", "Pamięć AI między rozmowami (kontekst z historii)", FeatureState.PLANNED),
        Feature("smart_home", "Inteligentny dom", FeatureState.PLANNED),
        Feature("computer", "Panel Windows i sterowanie komputerem", FeatureState.PLANNED),
        Feature("glasses", "Okulary", FeatureState.PLANNED),
        Feature("wake_word", "Wywołanie słowem „Jarvis” / nasłuch w tle i przy zgaszonym ekranie", FeatureState.PLANNED),
    )
}
