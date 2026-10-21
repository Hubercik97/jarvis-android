package pl.zamirski.jarvis.service

import android.app.KeyguardManager
import android.content.Context
import android.provider.Settings
import pl.zamirski.jarvis.core.AiClient
import pl.zamirski.jarvis.core.AiResult
import pl.zamirski.jarvis.core.ChatMessage
import pl.zamirski.jarvis.core.Conversation
import pl.zamirski.jarvis.core.Kind
import pl.zamirski.jarvis.core.Messages
import pl.zamirski.jarvis.core.Sources
import pl.zamirski.jarvis.core.ToolCall
import pl.zamirski.jarvis.core.ToolPending
import pl.zamirski.jarvis.core.ToolRegistry
import pl.zamirski.jarvis.core.ToolResult
import pl.zamirski.jarvis.data.HistoryStore
import pl.zamirski.jarvis.net.BackendAiClient
import pl.zamirski.jarvis.settings.AppSettings
import pl.zamirski.jarvis.tools.LocalToolExecutor

/**
 * Obsługa polecenia wydanego w tle (inna aplikacja na ekranie albo zgaszony ekran):
 * AI → walidacja → wykonanie narzędzi z rejestru → odpowiedź do przeczytania na głos.
 *
 * Zasady bezpieczeństwa w tle:
 * - działania wymagające potwierdzenia (ToolRisk.CONFIRM) NIE są wykonywane — Jarvis prosi o otwarcie aplikacji;
 * - otwieranie innych aplikacji wymaga zgody „Wyświetlanie nad innymi aplikacjami”, bez niej Jarvis mówi to wprost;
 * - przy zablokowanym ekranie nic nie jest odblokowywane — otwarta aplikacja czeka za blokadą.
 */
class BackgroundAssistant(context: Context) {

    private val app = context.applicationContext
    private val settings = AppSettings(app)
    private val ai: AiClient = BackendAiClient(app, settings)
    private val tools = LocalToolExecutor(app)
    private val history = HistoryStore.get(app)

    private var conversationId = 0L
    private var startedAt = 0L
    private var lastActivity = 0L
    private val messages = mutableListOf<ChatMessage>()

    /** Zwraca tekst do przeczytania na głos. */
    suspend fun handle(text: String): String {
        val now = System.currentTimeMillis()
        if (messages.isEmpty() || now - lastActivity > SESSION_GAP_MS) {
            messages.clear()
            conversationId = now
            startedAt = now
        }
        lastActivity = now
        messages += ChatMessage(Kind.USER, text)

        var pending: ToolPending? = null
        var reply: String? = null
        for (round in 0 until ToolRegistry.MAX_ROUNDS) {
            when (val r = ai.chat(messages.toList(), ToolRegistry.names, pending)) {
                is AiResult.Failure -> {
                    reply = Messages.forFailure(r.reason, r.detail)
                    messages += ChatMessage(Kind.NOTICE, reply)
                    break
                }
                is AiResult.Success -> {
                    if (r.actions.isEmpty()) {
                        reply = Sources.speakable(r.text)
                        messages += ChatMessage(Kind.ASSISTANT, Sources.withSources(r.text, r.sources))
                        break
                    }
                    if (r.text.isNotBlank()) messages += ChatMessage(Kind.ASSISTANT, r.text)
                    val results = r.actions.map { run(it) }
                    pending = ToolPending(r.actions, results)
                }
            }
        }
        val spoken = reply ?: "Przerwałem: to polecenie wymagało zbyt wielu kroków naraz."
        if (reply == null) messages += ChatMessage(Kind.ASSISTANT, spoken)
        history.upsertArchived(Conversation(conversationId, startedAt, System.currentTimeMillis(), messages.toList()))
        return spoken
    }

    private suspend fun run(call: ToolCall): ToolResult {
        val description = ToolRegistry.describe(call)
        val result = when {
            ToolRegistry.needsConfirmation(call) ->
                ToolResult(call.id, false, "To działanie wymaga potwierdzenia — otwórz aplikację Jarvis i wydaj polecenie tam.")
            call.tool in ToolRegistry.LAUNCHES_UI && !Settings.canDrawOverlays(app) ->
                ToolResult(
                    call.id, false,
                    "Android nie pozwala Jarvisowi otwierać aplikacji z tła bez zgody „Wyświetlanie nad innymi aplikacjami”. " +
                        "Włącz ją w Ustawieniach Jarvisa.",
                )
            else -> tools.execute(call).let { r ->
                if (r.ok && call.tool in ToolRegistry.LAUNCHES_UI && isLocked()) {
                    r.copy(output = r.output + " Telefon jest zablokowany — odblokuj go, żeby zobaczyć ekran.")
                } else {
                    r
                }
            }
        }
        messages += ChatMessage(
            Kind.NOTICE,
            (if (result.ok) "▶ $description — wykonano" else "▶ $description — nie udało się: ${result.output}") + " (w tle)",
        )
        return result
    }

    private fun isLocked(): Boolean = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private companion object {
        /** Po 5 minutach ciszy kolejne polecenie zaczyna nową rozmowę w historii. */
        const val SESSION_GAP_MS = 5 * 60_000L
    }
}
