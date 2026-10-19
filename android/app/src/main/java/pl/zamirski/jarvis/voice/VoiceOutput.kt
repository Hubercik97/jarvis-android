package pl.zamirski.jarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Odpowiedzi głosowe po polsku przez systemowy syntezator mowy (TTS). */
class VoiceOutput(context: Context, private val listener: Listener) : TextToSpeech.OnInitListener {

    enum class Status { INITIALIZING, READY, NO_POLISH, FAILED }

    interface Listener {
        fun onVoiceStatus(status: Status)
        fun onSpeakingStarted()
        fun onSpeakingFinished()
    }

    var status: Status = Status.INITIALIZING
        private set

    private var counter = 0
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(result: Int) {
        status = if (result == TextToSpeech.SUCCESS) {
            when (tts.setLanguage(Locale("pl", "PL"))) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> Status.NO_POLISH
                else -> Status.READY
            }
        } else {
            Status.FAILED
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { listener.onSpeakingStarted() }
            }

            override fun onDone(utteranceId: String?) {
                main.post { listener.onSpeakingFinished() }
            }

            @Deprecated("Wymagane przez starsze API")
            override fun onError(utteranceId: String?) {
                main.post { listener.onSpeakingFinished() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { listener.onSpeakingFinished() }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                main.post { listener.onSpeakingFinished() }
            }
        })
        main.post { listener.onVoiceStatus(status) }
    }

    /** Zwraca false, gdy mowa nie jest możliwa (brak polskiego głosu lub błąd TTS). */
    fun speak(text: String): Boolean {
        if (status != Status.READY || text.isBlank()) return false
        counter += 1
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-$counter") == TextToSpeech.SUCCESS
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
