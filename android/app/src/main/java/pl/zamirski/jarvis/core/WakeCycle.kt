package pl.zamirski.jarvis.core

/**
 * Maszyna stanów cyklu nasłuchu słowa „Jarvis” (dla usługi pierwszoplanowej).
 * Czysta logika bez Androida: zdarzenie → nowy stan + lista efektów do wykonania przez usługę.
 *
 * Gwarancje (sprawdzane testami):
 * - w danej chwili działa najwyżej jedno: detektor ALBO rozpoznawanie mowy (nigdy dwa naraz,
 *   bo Android oddaje mikrofon tylko jednemu nagrywaniu);
 * - detektor jest zawsze zatrzymywany PRZED startem rozpoznawania mowy;
 * - kolejne wybudzenie jest ignorowane, dopóki trwa obsługa poprzedniego;
 * - błędy detektora są ponawiane z rosnącą przerwą, a po wyczerpaniu prób nasłuch się wyłącza
 *   i użytkownik dostaje komunikat (bez cichych pętli zużywających baterię).
 *
 * Detektor słowa (openWakeWord / Vosk / inny) jest wymienny — ta logika od niego nie zależy.
 */
object WakeCycle {

    sealed class State {
        object Off : State()
        object Listening : State()             // detektor słucha słowa „Jarvis”
        object Command : State()               // SpeechRecognizer słucha polecenia (pl-PL)
        object Thinking : State()              // czekamy na odpowiedź AI
        object Speaking : State()              // TTS czyta odpowiedź
        data class Paused(val reason: PauseReason) : State()
        data class Failed(val attempt: Int, val message: String) : State()
    }

    enum class PauseReason { APP_IN_FOREGROUND, MIC_BUSY }

    sealed class Event {
        object Enable : Event()
        object Disable : Event()
        data class WakeDetected(val score: Float) : Event()

        /** „Jarvis, przełącz na kalendarz” — słowo i polecenie w jednej wypowiedzi. */
        data class WakeWithCommand(val text: String) : Event()
        data class CommandHeard(val text: String) : Event()
        data class CommandFailed(val silence: Boolean) : Event()
        data class ReplyReady(val text: String) : Event()
        data class ReplyFailed(val message: String) : Event()
        object SpeakingDone : Event()
        data class DetectorError(val message: String) : Event()
        object MicBusy : Event()
        object RetryTimer : Event()
        object AppForeground : Event()
        object AppBackground : Event()
    }

    sealed class Effect {
        object StartDetector : Effect()
        object StopDetector : Effect()
        object StartRecognition : Effect()
        object CancelRecognition : Effect()
        object StopSpeaking : Effect()
        data class AskAi(val text: String) : Effect()
        data class Speak(val text: String) : Effect()
        data class ScheduleRetry(val delayMs: Long) : Effect()
        object CancelRetry : Effect()
        data class Notify(val text: String) : Effect()
        object StopService : Effect()
    }

    data class Result(val state: State, val effects: List<Effect>)

    const val MAX_ATTEMPTS = 5
    private const val BASE_RETRY_MS = 2_000L
    private const val MAX_RETRY_MS = 60_000L
    const val NOT_HEARD = "Nie usłyszałem polecenia."

    /** Przerwa przed kolejną próbą: 2 s, 4 s, 8 s… maks. 60 s. */
    fun retryDelay(attempt: Int): Long =
        (BASE_RETRY_MS shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(MAX_RETRY_MS)

    fun reduce(state: State, event: Event): Result {
        // Wyłączenie działa zawsze i sprząta wszystko.
        if (event is Event.Disable) {
            return Result(
                State.Off,
                listOf(Effect.CancelRetry, Effect.StopDetector, Effect.CancelRecognition, Effect.StopSpeaking, Effect.StopService),
            )
        }
        return when (state) {
            State.Off -> when (event) {
                Event.Enable -> Result(State.Listening, listOf(Effect.StartDetector))
                else -> same(state)
            }

            State.Listening -> when (event) {
                is Event.WakeDetected -> Result(State.Command, listOf(Effect.StopDetector, Effect.StartRecognition))
                is Event.WakeWithCommand ->
                    if (event.text.isBlank()) Result(State.Command, listOf(Effect.StopDetector, Effect.StartRecognition))
                    else Result(State.Thinking, listOf(Effect.StopDetector, Effect.AskAi(event.text)))
                is Event.DetectorError -> fail(1, event.message)
                Event.MicBusy -> Result(State.Paused(PauseReason.MIC_BUSY), listOf(Effect.StopDetector, Effect.ScheduleRetry(BASE_RETRY_MS)))
                Event.AppForeground -> Result(State.Paused(PauseReason.APP_IN_FOREGROUND), listOf(Effect.StopDetector))
                else -> same(state)
            }

            State.Command -> when (event) {
                is Event.CommandHeard ->
                    if (event.text.isBlank()) backToListening(Effect.Speak(NOT_HEARD))
                    else Result(State.Thinking, listOf(Effect.AskAi(event.text)))
                is Event.CommandFailed ->
                    if (event.silence) backToListening() else backToListening(Effect.Speak(NOT_HEARD))
                Event.AppForeground -> Result(State.Paused(PauseReason.APP_IN_FOREGROUND), listOf(Effect.CancelRecognition))
                else -> same(state) // kolejne WakeDetected w trakcie polecenia jest ignorowane
            }

            State.Thinking -> when (event) {
                is Event.ReplyReady -> Result(State.Speaking, listOf(Effect.Speak(event.text)))
                is Event.ReplyFailed -> Result(State.Speaking, listOf(Effect.Speak(event.message)))
                else -> same(state)
            }

            State.Speaking -> when (event) {
                Event.SpeakingDone -> backToListening()
                Event.AppForeground -> Result(State.Paused(PauseReason.APP_IN_FOREGROUND), listOf(Effect.StopSpeaking))
                else -> same(state)
            }

            is State.Paused -> when (event) {
                Event.AppBackground ->
                    if (state.reason == PauseReason.APP_IN_FOREGROUND) Result(State.Listening, listOf(Effect.StartDetector))
                    else same(state)
                Event.RetryTimer ->
                    if (state.reason == PauseReason.MIC_BUSY) Result(State.Listening, listOf(Effect.StartDetector))
                    else same(state)
                Event.AppForeground -> Result(State.Paused(PauseReason.APP_IN_FOREGROUND), listOf(Effect.CancelRetry))
                else -> same(state)
            }

            is State.Failed -> when (event) {
                Event.RetryTimer -> Result(State.Listening, listOf(Effect.StartDetector))
                is Event.DetectorError -> fail(state.attempt + 1, event.message)
                Event.Enable -> Result(State.Listening, listOf(Effect.CancelRetry, Effect.StartDetector))
                else -> same(state)
            }
        }
        // Udany powrót do nasłuchu po błędzie zeruje licznik prób — stan Listening nie niesie licznika.
    }

    private fun same(state: State) = Result(state, emptyList())

    private fun backToListening(vararg before: Effect) =
        Result(State.Listening, before.toList() + Effect.StartDetector)

    private fun fail(attempt: Int, message: String): Result =
        if (attempt > MAX_ATTEMPTS) {
            Result(
                State.Off,
                listOf(
                    Effect.StopDetector,
                    Effect.Notify("Wybudzanie głosem wyłączone: detektor nie działa ($message). Włącz je ponownie w Ustawieniach."),
                    Effect.StopService,
                ),
            )
        } else {
            Result(State.Failed(attempt, message), listOf(Effect.StopDetector, Effect.ScheduleRetry(retryDelay(attempt))))
        }

    /** Tekst do powiadomienia usługi — zawsze mówi wprost, że mikrofon jest używany. */
    fun notificationText(state: State): String = when (state) {
        State.Off -> "Wybudzanie głosem wyłączone"
        State.Listening -> "Mikrofon aktywny — czekam na słowo „Jarvis”"
        State.Command -> "Mikrofon aktywny — słucham polecenia"
        State.Thinking -> "Przetwarzam polecenie…"
        State.Speaking -> "Odpowiadam…"
        is State.Paused -> when (state.reason) {
            PauseReason.APP_IN_FOREGROUND -> "Wstrzymane — aplikacja Jarvis jest otwarta"
            PauseReason.MIC_BUSY -> "Wstrzymane — mikrofon zajęty (np. rozmowa telefoniczna)"
        }
        is State.Failed -> "Błąd detektora — ponawiam (próba ${state.attempt}/$MAX_ATTEMPTS)"
    }

    /** Czy w tym stanie mikrofon jest (lub może być) używany przez Jarvisa. */
    fun micInUse(state: State): Boolean = state == State.Listening || state == State.Command
}
