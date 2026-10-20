package pl.zamirski.jarvis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pl.zamirski.jarvis.R
import pl.zamirski.jarvis.core.RestartGuard
import pl.zamirski.jarvis.core.WakeCycle
import pl.zamirski.jarvis.core.WakeCycle.Effect
import pl.zamirski.jarvis.core.WakeCycle.Event
import pl.zamirski.jarvis.core.WakeCycle.State
import pl.zamirski.jarvis.core.WakeWord
import pl.zamirski.jarvis.settings.AppSettings
import pl.zamirski.jarvis.ui.MainActivity
import pl.zamirski.jarvis.voice.ContinuousRecognizer
import pl.zamirski.jarvis.voice.VoiceOutput

/** Czy ekran Jarvisa jest widoczny (wtedy mikrofonem zarządza aplikacja, a nasłuch w tle czeka). */
object AppVisibility {
    val foreground = MutableStateFlow(false)
}

/**
 * Nasłuch słowa „Jarvis” w tle — usługa pierwszoplanowa typu „microphone” ze stałym powiadomieniem.
 *
 * Słowo jest wykrywane w tekście z ciągłego rozpoznawania mowy pl-PL (ContinuousRecognizer).
 * Stany i kolejność działań (jedno nagrywanie naraz, pauzy, ponawianie) wyznacza WakeCycle.
 * Uruchamiać WYŁĄCZNIE z otwartej aplikacji — Android nie pozwala włączyć mikrofonu w tle inaczej.
 */
class WakeWordService : Service(), ContinuousRecognizer.Listener, VoiceOutput.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var recognizer: ContinuousRecognizer
    private lateinit var voice: VoiceOutput
    private lateinit var assistant: BackgroundAssistant
    private val guard = RestartGuard()

    private var state: State = State.Off
    /** true, gdy trwająca sesja rozpoznawania słucha polecenia (po samym „Jarvis”), a nie słowa. */
    private var commandSession = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        recognizer = ContinuousRecognizer(this, this)
        voice = VoiceOutput(this, this)
        assistant = BackgroundAssistant(this)
        ensureChannel(this)
        scope.launch {
            AppVisibility.foreground.collect { visible ->
                if (state != State.Off) dispatch(if (visible) Event.AppForeground else Event.AppBackground)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppSettings(this).backgroundListening = false
            dispatch(Event.Disable)
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(State.Listening), type)
        } catch (e: Exception) {
            // Np. start z tła — Android odmawia. Nie udajemy, że nasłuch działa.
            _status.value = "Nie udało się włączyć nasłuchu w tle: otwórz Jarvisa i włącz go ponownie."
            stopSelf()
            return START_NOT_STICKY
        }
        if (!recognizer.isAvailable()) {
            _status.value = "Brak usługi rozpoznawania mowy na telefonie."
            stopSelf()
            return START_NOT_STICKY
        }
        if (state == State.Off) {
            guard.reset()
            dispatch(Event.Enable)
            if (AppVisibility.foreground.value) dispatch(Event.AppForeground)
        }
        // Po zabiciu przez system nie wracamy sami z tła — i tak nie dostalibyśmy mikrofonu.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recognizer.destroy()
        voice.shutdown()
        tone?.release()
        scope.cancel()
        state = State.Off
        _running.value = false
        super.onDestroy()
    }

    // ---------- Maszyna stanów ----------

    private fun dispatch(event: Event) {
        val r = WakeCycle.reduce(state, event)
        state = r.state
        _running.value = state != State.Off
        r.effects.forEach(::perform)
        if (state != State.Off) updateNotification()
    }

    private fun perform(effect: Effect) {
        when (effect) {
            Effect.StartDetector -> startSession(command = false)
            Effect.StopDetector -> if (!commandSession) recognizer.cancel()
            Effect.StartRecognition -> {
                // „Jarvis” bez polecenia: krótki sygnał (zamiast mówionego „Słucham”, które zagłuszało początek polecenia)
                // i od razu słuchamy polecenia.
                beep()
                startSession(command = true)
            }
            Effect.CancelRecognition -> {
                commandSession = false
                recognizer.cancel()
            }
            Effect.StopSpeaking -> voice.stop()
            is Effect.AskAi -> scope.launch {
                _status.value = "Wykonuję: ${effect.text}"
                val reply = assistant.handle(effect.text)
                if (state == State.Thinking) dispatch(Event.ReplyReady(reply))
            }
            is Effect.Speak -> if (!voice.speak(effect.text)) main.post { dispatch(Event.SpeakingDone) }
            is Effect.ScheduleRetry -> main.postDelayed({ dispatch(Event.RetryTimer) }, effect.delayMs)
            Effect.CancelRetry -> main.removeCallbacksAndMessages(null)
            is Effect.Notify -> _status.value = effect.text
            Effect.StopService -> {
                recognizer.cancel()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private var pendingCommandAfterSpeech = false
    private var tone: ToneGenerator? = null

    private fun beep() {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: RuntimeException) {
            // Brak generatora dźwięku na urządzeniu — działamy dalej bez sygnału.
        }
    }

    private fun startSession(command: Boolean) {
        if (!guard.allow(SystemClock.elapsedRealtime())) {
            dispatch(Event.DetectorError("rozpoznawanie mowy kończy się natychmiast"))
            return
        }
        commandSession = command
        recognizer.start()
    }

    // ---------- Rozpoznawanie mowy ----------

    /** „Jarvis” usłyszane już w wyniku cząstkowym — nawet jeśli wynik końcowy zapisze je inaczej. */
    private var wakeSeenInPartial = false

    override fun onPartial(text: String) {
        if (state == State.Listening && !commandSession && WakeWord.match(text) != null) wakeSeenInPartial = true
    }

    override fun onText(alternatives: List<String>) {
        val wasCommand = commandSession
        commandSession = false
        val seen = wakeSeenInPartial
        wakeSeenInPartial = false
        _lastHeard.value = alternatives.firstOrNull()
        when {
            state == State.Command && wasCommand -> dispatch(Event.CommandHeard(alternatives.firstOrNull().orEmpty()))
            state == State.Listening -> {
                // Sprawdzamy wszystkie warianty rozpoznania, nie tylko najlepszy.
                val hit = alternatives.firstNotNullOfOrNull { alt -> WakeWord.match(alt)?.let { alt to it } }
                when {
                    hit != null -> {
                        guard.reset()
                        _lastHeard.value = hit.first
                        dispatch(Event.WakeWithCommand(hit.second))
                    }
                    seen -> {
                        guard.reset()
                        dispatch(Event.WakeWithCommand(""))
                    }
                    else -> restartListeningSoon()
                }
            }
        }
    }

    override fun onRecognitionError(code: Int) {
        val wasCommand = commandSession
        commandSession = false
        if (state == State.Listening && wakeSeenInPartial) {
            // „Jarvis” padło, ale sesja skończyła się bez wyniku końcowego — i tak słuchamy polecenia.
            wakeSeenInPartial = false
            dispatch(Event.WakeWithCommand(""))
            return
        }
        wakeSeenInPartial = false
        val kind = WakeWord.classify(code)
        if (state == State.Command && wasCommand) {
            dispatch(Event.CommandFailed(silence = kind == WakeWord.ErrorKind.RESTART))
            return
        }
        if (state != State.Listening) return
        when (kind) {
            WakeWord.ErrorKind.RESTART -> restartListeningSoon()
            WakeWord.ErrorKind.BUSY -> dispatch(Event.MicBusy)
            WakeWord.ErrorKind.LANGUAGE -> {
                recognizer.fallBackToCloud()
                _status.value = "Brak polskiego rozpoznawania na telefonie — używam usługi mowy Google."
                restartListeningSoon()
            }
            WakeWord.ErrorKind.RETRY -> dispatch(Event.DetectorError("błąd rozpoznawania mowy ($code)"))
            WakeWord.ErrorKind.FATAL -> {
                _status.value = "Brak zgody na mikrofon — nasłuch w tle wyłączony."
                AppSettings(this).backgroundListening = false
                dispatch(Event.Disable)
            }
        }
    }

    private fun restartListeningSoon() {
        main.postDelayed({ if (state == State.Listening && !commandSession) startSession(command = false) }, RESTART_DELAY_MS)
    }

    // ---------- Głos ----------

    override fun onVoiceStatus(status: VoiceOutput.Status) {}

    override fun onSpeakingStarted() {}

    override fun onSpeakingFinished() {
        if (pendingCommandAfterSpeech) {
            pendingCommandAfterSpeech = false
            if (state == State.Command) startSession(command = true)
            return
        }
        if (state == State.Speaking) {
            dispatch(Event.SpeakingDone)
            // Ekran Jarvisa otworzył się w trakcie odpowiedzi — oddajemy mu mikrofon.
            if (AppVisibility.foreground.value) dispatch(Event.AppForeground)
        }
    }

    // ---------- Powiadomienie ----------

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, notification(state))
    }

    private fun notification(s: State): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val engine = if (recognizer.onDevice) "rozpoznawanie na telefonie" else "rozpoznawanie: usługa mowy Google"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis słucha w tle")
            .setContentText(WakeCycle.notificationText(s))
            .setSubText(engine)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Wyłącz", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "pl.zamirski.jarvis.STOP_LISTENING"
        private const val RESTART_DELAY_MS = 80L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _lastHeard = MutableStateFlow<String?>(null)
        /** Diagnostyka: ostatnio rozpoznany tekst (tylko w pamięci, widoczny w Ustawieniach, nigdzie nie wysyłany). */
        val lastHeard: StateFlow<String?> = _lastHeard

        private val _status = MutableStateFlow<String?>(null)
        /** Ostatni komunikat usługi do pokazania w Ustawieniach (np. dlaczego się wyłączyła). */
        val status: StateFlow<String?> = _status

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Nasłuch w tle", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Stałe powiadomienie, gdy Jarvis słucha słowa „Jarvis” w tle"
                },
            )
        }

        /** Wywoływać tylko z widocznego ekranu Jarvisa. */
        fun start(context: Context) {
            _status.value = null
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_STOP))
        }
    }
}
