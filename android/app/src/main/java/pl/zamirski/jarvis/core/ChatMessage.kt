package pl.zamirski.jarvis.core

/** Rodzaj wpisu w rozmowie. NOTICE to komunikat aplikacji (błąd, informacja) — nigdy nie trafia do modelu AI. */
enum class Kind(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    NOTICE("notice"),
}

data class ChatMessage(
    val kind: Kind,
    val text: String,
    /** Zdjęcie JPEG w base64 (tylko wiadomości użytkownika, np. z przycisku Kamera). */
    val imageBase64: String? = null,
    /** Krótki opis załącznika do pokazania w rozmowie, np. „📷 zdjęcie” lub nazwa pliku. */
    val attachmentLabel: String? = null,
)
