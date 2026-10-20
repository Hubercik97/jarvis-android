package pl.zamirski.jarvis.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.zamirski.jarvis.core.AiClient
import pl.zamirski.jarvis.core.AiResult
import pl.zamirski.jarvis.core.ChatMessage
import pl.zamirski.jarvis.core.Conversation
import pl.zamirski.jarvis.core.ConversationFlow
import pl.zamirski.jarvis.core.FailureReason
import pl.zamirski.jarvis.core.HealthResult
import pl.zamirski.jarvis.core.Kind
import pl.zamirski.jarvis.core.Messages
import pl.zamirski.jarvis.data.CalendarRepository
import pl.zamirski.jarvis.data.HistoryStore
import pl.zamirski.jarvis.data.LocalStore
import pl.zamirski.jarvis.data.WeatherRepository
import pl.zamirski.jarvis.data.WeatherState
import pl.zamirski.jarvis.net.BackendAiClient
import pl.zamirski.jarvis.net.NetworkMonitor
import pl.zamirski.jarvis.settings.AppSettings
import pl.zamirski.jarvis.voice.SpeechInput
import pl.zamirski.jarvis.voice.VoiceOutput
import java.util.concurrent.Executors

enum class VoiceMode { IDLE, LISTENING, THINKING, SPEAKING }

sealed class ConnectionState {
    object Checking : ConnectionState()
    data class Ok(val model: String) : ConnectionState()
    data class Problem(val text: String) : ConnectionState()
}

class JarvisViewModel(app: Application) : AndroidViewModel(app), SpeechInput.Listener, VoiceOutput.Listener {

    val settings = AppSettings(app)
    val store = LocalStore.get(app)
    val calendar = CalendarRepository(app)
    val history = HistoryStore(app)
    private val ai: AiClient = BackendAiClient(app, settings)
    private val weatherRepo = WeatherRepository(app, settings)
    private val speech = SpeechInput(app, this)
    private val voice = VoiceOutput(app, this)

    /** Jeden wątek zapisu — zapisy historii wykonują się po kolei, bez nadpisywania nowszych starszymi. */
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    var mode by mutableStateOf(VoiceMode.IDLE)
        private set

    /** Bieżąca linia stanu / transkrypcja na żywo. */
    var liveText by mutableStateOf("")
        private set

    var soundLevel by mutableStateOf(0f)
        private set

    val messages = mutableStateListOf<ChatMessage>()

    var connection by mutableStateOf<ConnectionState>(ConnectionState.Checking)
        private set

    var weather by mutableStateOf<WeatherState>(WeatherState.Idle)
        private set

    var voiceStatus by mutableStateOf(VoiceOutput.Status.INITIALIZING)
        private set

    /** Trwa tryb rozmowy: po odpowiedzi głosowej Jarvis sam zacznie słuchać. */
    var handsFreeActive by mutableStateOf(false)
        private set

    /** Krótki komunikat do pokazania jako baner. */
    var notice by mutableStateOf<String?>(null)

    private var conversationId = System.currentTimeMillis()
    private var conversationStartedAt = conversationId
    private var appInForeground = true

    init {
        // Bieżąca rozmowa wraca po ponownym uruchomieniu (mały plik, odczyt przy starcie).
        history.loadCurrent()?.let { saved ->
            conversationId = saved.id
            conversationStartedAt = saved.startedAt
            messages.addAll(saved.messages)
        }
        viewModelScope.launch(io) { history.loadArchive() }
        refreshConnection()
        refreshWeather()
    }

    val lastReply: String?
        get() = messages.lastOrNull { it.kind == Kind.ASSISTANT }?.text

    fun isSpeechAvailable(): Boolean = speech.isAvailable()

    // ---------- Cykl życia aplikacji ----------

    fun onAppStarted() {
        appInForeground = true
    }

    /** Aplikacja przeszła w tło: Android nie pozwala tu słuchać, więc kończymy nasłuch i tryb rozmowy. */
    fun onAppStopped() {
        appInForeground = false
        if (mode == VoiceMode.LISTENING) {
            speech.cancel()
            setIdle()
        }
        if (handsFreeActive) {
            handsFreeActive = false
            liveText = "Tryb rozmowy zatrzymany — aplikacja przeszła w tło."
        }
    }

    // ---------- Mikrofon ----------

    /**
     * @param userInitiated true, gdy użytkownik nacisnął mikrofon; false, gdy Jarvis sam
     *        wznawia słuchanie w trybie rozmowy.
     */
    fun startListening(userInitiated: Boolean = true) {
        if (!NetworkMonitor.isOnline(getApplication())) {
            endHandsFree()
            notice = Messages.forFailure(FailureReason.OFFLINE)
            return
        }
        if (!speech.isAvailable()) {
            endHandsFree()
            notice = "Na tym telefonie brak usługi rozpoznawania mowy. Włącz aplikację Google i spróbuj ponownie."
            return
        }
        if (userInitiated && settings.handsFree) handsFreeActive = true
        voice.stop()
        mode = VoiceMode.LISTENING
        liveText = "Uruchamiam mikrofon…"
        speech.start()
    }

    fun stopListening() = speech.stop()

    fun cancelVoice() {
        handsFreeActive = false
        if (mode == VoiceMode.LISTENING) speech.cancel()
        if (mode == VoiceMode.SPEAKING) voice.stop()
        if (mode != VoiceMode.THINKING) setIdle()
    }

    fun onMicTapped(requestPermissionAndStart: () -> Unit) {
        when (mode) {
            VoiceMode.LISTENING -> stopListening()
            VoiceMode.THINKING -> liveText = "Poczekaj na odpowiedź…"
            VoiceMode.SPEAKING -> {
                voice.stop()
                requestPermissionAndStart()
            }
            VoiceMode.IDLE -> requestPermissionAndStart()
        }
    }

    override fun onListeningReady() {
        if (mode == VoiceMode.LISTENING) {
            liveText = if (handsFreeActive) "Słucham… (powiedz „to wszystko”, aby zakończyć)" else "Słucham Cię…"
        }
    }

    override fun onPartialText(text: String) {
        if (mode == VoiceMode.LISTENING) liveText = "„$text”"
    }

    override fun onFinalText(text: String) {
        if (mode != VoiceMode.LISTENING) return
        setIdle()
        if (text.isBlank()) {
            liveText = Messages.forSpeechError(7)
            if (handsFreeActive) endHandsFreeAloud("Nic nie usłyszałem. Kończę rozmowę.")
            return
        }
        send(text, fromVoice = true)
    }

    override fun onSpeechError(code: Int) {
        if (mode != VoiceMode.LISTENING) return
        setIdle()
        val message = Messages.forSpeechError(code)
        liveText = message
        if (handsFreeActive) {
            endHandsFreeAloud(if (Messages.isSilence(code)) "Nic nie usłyszałem. Kończę rozmowę." else message)
            return
        }
        if (!Messages.isSilence(code)) notice = message
    }

    override fun onSoundLevel(rmsDb: Float) {
        if (mode == VoiceMode.LISTENING) soundLevel = (rmsDb.coerceIn(0f, 10f) / 10f)
    }

    // ---------- AI ----------

    fun send(
        text: String,
        imageBase64: String? = null,
        attachmentLabel: String? = null,
        fromVoice: Boolean = false,
    ) {
        if (mode == VoiceMode.THINKING) {
            liveText = "Poczekaj na odpowiedź…"
            return
        }
        if (mode == VoiceMode.LISTENING) speech.cancel()
        voice.stop()
        // Pisanie oznacza, że użytkownik patrzy na ekran — tryb rozmowy się kończy.
        if (!fromVoice) handsFreeActive = false

        messages += ChatMessage(Kind.USER, text, imageBase64, attachmentLabel)

        if (handsFreeActive && ConversationFlow.isEndPhrase(text)) {
            handsFreeActive = false
            messages += ChatMessage(Kind.ASSISTANT, ConversationFlow.END_REPLY)
            persist()
            speakOrIdle(ConversationFlow.END_REPLY)
            return
        }

        persist()
        mode = VoiceMode.THINKING
        liveText = "Myślę…"
        val snapshot = messages.toList()
        viewModelScope.launch {
            when (val result = ai.chat(snapshot)) {
                is AiResult.Success -> {
                    // Zdjęcie nie jest już potrzebne w pamięci — zostawiamy tylko opis.
                    val idx = messages.indexOfLast { it.kind == Kind.USER }
                    if (idx >= 0 && messages[idx].imageBase64 != null) messages[idx] = messages[idx].copy(imageBase64 = null)
                    messages += ChatMessage(Kind.ASSISTANT, result.text)
                    persist()
                    connection = ConnectionState.Ok((connection as? ConnectionState.Ok)?.model ?: "")
                    if (!speakOrIdle(result.text) && handsFreeActive) {
                        handsFreeActive = false
                        liveText = "Tryb rozmowy zakończony — odpowiedzi głosowe są wyłączone lub brak polskiego głosu."
                    }
                }
                is AiResult.Failure -> {
                    val msg = Messages.forFailure(result.reason, result.detail)
                    messages += ChatMessage(Kind.NOTICE, msg)
                    persist()
                    setIdle()
                    liveText = msg
                    // W trybie rozmowy użytkownik może nie patrzeć na ekran — mówimy, co się stało.
                    if (handsFreeActive) endHandsFreeAloud(msg)
                    refreshConnection()
                }
            }
        }
    }

    /** Wypowiada tekst (jeśli głos jest włączony i dostępny). Zwraca true, gdy mowa ruszyła. */
    private fun speakOrIdle(text: String): Boolean {
        if (settings.voiceReplies && voiceStatus == VoiceOutput.Status.READY && voice.speak(text)) {
            mode = VoiceMode.SPEAKING
            liveText = if (handsFreeActive) "Mówię… potem znowu słucham." else "Mówię… (dotknij mikrofonu, aby przerwać)"
            return true
        }
        setIdle()
        liveText = ""
        return false
    }

    private fun endHandsFree() {
        handsFreeActive = false
    }

    /** Kończy tryb rozmowy i mówi dlaczego (dla użytkownika, który nie patrzy na ekran). */
    private fun endHandsFreeAloud(reason: String) {
        handsFreeActive = false
        if (!speakOrIdle(reason)) liveText = reason
    }

    // ---------- Historia ----------

    /** Zapisuje bieżącą rozmowę na telefonie (w tle, po kolei). */
    private fun persist() {
        val snapshot = Conversation(conversationId, conversationStartedAt, System.currentTimeMillis(), messages.toList())
        viewModelScope.launch(io) { history.saveCurrent(snapshot) }
    }

    /** Kończy bieżącą rozmowę: trafia do historii, a ekran rozmowy jest czysty. */
    fun newConversation() {
        cancelVoice()
        if (messages.isNotEmpty()) {
            val finished = Conversation(conversationId, conversationStartedAt, System.currentTimeMillis(), messages.toList())
            viewModelScope.launch(io) { history.archive(finished) }
        }
        messages.clear()
        liveText = ""
        conversationId = System.currentTimeMillis()
        conversationStartedAt = conversationId
    }

    fun clearConversation() = newConversation()

    fun deleteFromHistory(id: Long) {
        viewModelScope.launch(io) { history.delete(id) }
    }

    fun deleteAllHistory() {
        viewModelScope.launch(io) { history.deleteAll() }
    }

    // ---------- Połączenie i pogoda ----------

    fun refreshConnection() {
        connection = ConnectionState.Checking
        viewModelScope.launch {
            connection = when (val h = ai.health()) {
                is HealthResult.Success -> when {
                    !h.health.configured -> ConnectionState.Problem("Serwer AI działa, ale nie ma klucza API modelu")
                    h.health.auth == "invalid" -> ConnectionState.Problem("Błędny token aplikacji")
                    else -> ConnectionState.Ok(h.health.model)
                }
                is HealthResult.Failure -> ConnectionState.Problem(Messages.forFailure(h.reason, h.detail))
            }
        }
    }

    fun refreshWeather() {
        weather = WeatherState.Loading
        viewModelScope.launch { weather = weatherRepo.load() }
    }

    // ---------- Głos ----------

    override fun onVoiceStatus(status: VoiceOutput.Status) {
        voiceStatus = status
    }

    override fun onSpeakingStarted() {}

    override fun onSpeakingFinished() {
        if (mode != VoiceMode.SPEAKING) return
        setIdle()
        liveText = ""
        if (ConversationFlow.shouldListenAgain(handsFreeActive, replySpoken = true, appInForeground = appInForeground)) {
            viewModelScope.launch {
                // Krótka pauza, żeby mikrofon nie złapał końcówki własnej wypowiedzi Jarvisa.
                delay(LISTEN_AGAIN_DELAY_MS)
                if (handsFreeActive && mode == VoiceMode.IDLE && appInForeground && hasMicPermission()) {
                    startListening(userInitiated = false)
                } else {
                    handsFreeActive = false
                }
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun setIdle() {
        mode = VoiceMode.IDLE
        soundLevel = 0f
    }

    override fun onCleared() {
        speech.destroy()
        voice.shutdown()
        io.close()
        super.onCleared()
    }

    private companion object {
        const val LISTEN_AGAIN_DELAY_MS = 400L
    }
}
