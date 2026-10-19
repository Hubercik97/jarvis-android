package pl.zamirski.jarvis.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import pl.zamirski.jarvis.core.AiClient
import pl.zamirski.jarvis.core.AiResult
import pl.zamirski.jarvis.core.ChatMessage
import pl.zamirski.jarvis.core.FailureReason
import pl.zamirski.jarvis.core.HealthResult
import pl.zamirski.jarvis.core.Kind
import pl.zamirski.jarvis.core.Messages
import pl.zamirski.jarvis.data.CalendarRepository
import pl.zamirski.jarvis.data.LocalStore
import pl.zamirski.jarvis.data.WeatherRepository
import pl.zamirski.jarvis.data.WeatherState
import pl.zamirski.jarvis.net.BackendAiClient
import pl.zamirski.jarvis.net.NetworkMonitor
import pl.zamirski.jarvis.settings.AppSettings
import pl.zamirski.jarvis.voice.SpeechInput
import pl.zamirski.jarvis.voice.VoiceOutput

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
    private val ai: AiClient = BackendAiClient(app, settings)
    private val weatherRepo = WeatherRepository(app, settings)
    private val speech = SpeechInput(app, this)
    private val voice = VoiceOutput(app, this)

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

    /** Krótki komunikat do pokazania jako baner. */
    var notice by mutableStateOf<String?>(null)

    init {
        refreshConnection()
        refreshWeather()
    }

    val lastReply: String?
        get() = messages.lastOrNull { it.kind == Kind.ASSISTANT }?.text

    fun isSpeechAvailable(): Boolean = speech.isAvailable()

    // ---------- Mikrofon ----------

    fun startListening() {
        if (!NetworkMonitor.isOnline(getApplication())) {
            notice = Messages.forFailure(FailureReason.OFFLINE)
            return
        }
        if (!speech.isAvailable()) {
            notice = "Na tym telefonie brak usługi rozpoznawania mowy. Włącz aplikację Google i spróbuj ponownie."
            return
        }
        voice.stop()
        mode = VoiceMode.LISTENING
        liveText = "Uruchamiam mikrofon…"
        speech.start()
    }

    fun stopListening() = speech.stop()

    fun cancelVoice() {
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
        if (mode == VoiceMode.LISTENING) liveText = "Słucham Cię…"
    }

    override fun onPartialText(text: String) {
        if (mode == VoiceMode.LISTENING) liveText = "„$text”"
    }

    override fun onFinalText(text: String) {
        if (mode != VoiceMode.LISTENING) return
        setIdle()
        if (text.isBlank()) {
            liveText = Messages.forSpeechError(7)
            return
        }
        send(text)
    }

    override fun onSpeechError(code: Int) {
        if (mode != VoiceMode.LISTENING) return
        setIdle()
        liveText = Messages.forSpeechError(code)
        if (!Messages.isSilence(code)) notice = Messages.forSpeechError(code)
    }

    override fun onSoundLevel(rmsDb: Float) {
        if (mode == VoiceMode.LISTENING) soundLevel = (rmsDb.coerceIn(0f, 10f) / 10f)
    }

    // ---------- AI ----------

    fun send(text: String, imageBase64: String? = null, attachmentLabel: String? = null) {
        if (mode == VoiceMode.THINKING) {
            liveText = "Poczekaj na odpowiedź…"
            return
        }
        if (mode == VoiceMode.LISTENING) speech.cancel()
        voice.stop()
        messages += ChatMessage(Kind.USER, text, imageBase64, attachmentLabel)
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
                    connection = ConnectionState.Ok((connection as? ConnectionState.Ok)?.model ?: "")
                    if (settings.voiceReplies && voiceStatus == VoiceOutput.Status.READY && voice.speak(result.text)) {
                        mode = VoiceMode.SPEAKING
                        liveText = "Mówię… (dotknij mikrofonu, aby przerwać)"
                    } else {
                        setIdle()
                        liveText = ""
                    }
                }
                is AiResult.Failure -> {
                    val msg = Messages.forFailure(result.reason, result.detail)
                    messages += ChatMessage(Kind.NOTICE, msg)
                    setIdle()
                    liveText = msg
                    refreshConnection()
                }
            }
        }
    }

    fun clearConversation() {
        messages.clear()
        liveText = ""
    }

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
        if (mode == VoiceMode.SPEAKING) {
            setIdle()
            liveText = ""
        }
    }

    private fun setIdle() {
        mode = VoiceMode.IDLE
        soundLevel = 0f
    }

    override fun onCleared() {
        speech.destroy()
        voice.shutdown()
        super.onCleared()
    }
}
