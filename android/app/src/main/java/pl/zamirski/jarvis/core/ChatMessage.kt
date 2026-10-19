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
)
