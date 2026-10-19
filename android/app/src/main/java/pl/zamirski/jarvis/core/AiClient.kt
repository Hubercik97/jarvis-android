package pl.zamirski.jarvis.core

enum class FailureReason {
    NOT_CONFIGURED,
    OFFLINE,
    UNAUTHORIZED,
    TIMEOUT,
    SERVER,
    BAD_RESPONSE,
}

sealed class AiResult {
    data class Success(val text: String) : AiResult()
    data class Failure(val reason: FailureReason, val detail: String? = null) : AiResult()
}

sealed class HealthResult {
    data class Success(val health: BackendHealth) : HealthResult()
    data class Failure(val reason: FailureReason, val detail: String? = null) : HealthResult()
}

/**
 * Punkt wymiany „mózgu” Jarvisa. Dziś: backend w chmurze z modelem AI.
 * Później można tu dołożyć pamięć, narzędzia (kamera, okulary, komputer) bez zmiany interfejsu użytkownika.
 */
interface AiClient {
    suspend fun chat(history: List<ChatMessage>): AiResult
    suspend fun health(): HealthResult
}
