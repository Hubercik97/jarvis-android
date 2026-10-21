package pl.zamirski.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import pl.zamirski.jarvis.settings.AppSettings
import java.util.Locale

/** Polski głos dostępny w syntezatorze telefonu (Android nie podaje płci — wybór na słuch). */
data class VoiceOption(val name: String, val label: String, val offline: Boolean)

/** Odpowiedzi głosowe po polsku przez systemowy syntezator mowy (TTS). */
class VoiceOutput(context: Context, private val listener: Listener) : TextToSpeech.OnInitListener {

    private val settings = AppSettings(context)

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
        // Głos asystenta: system kieruje go tam, gdzie multimedia — do podłączonych słuchawek Bluetooth (A2DP)
        // albo na głośnik telefonu.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
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

    /** Polskie głosy zainstalowane w telefonie (Google TTS / Samsung TTS). */
    fun polishVoices(): List<VoiceOption> {
        if (status != Status.READY) return emptyList()
        val voices: Set<Voice> = try {
            tts.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        return voices
            .filter { it.locale.language == "pl" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            .mapIndexed { i, v ->
                VoiceOption(v.name, "Głos ${i + 1} — ${v.name}" + if (v.isNetworkConnectionRequired) " (online)" else " (offline)", !v.isNetworkConnectionRequired)
            }
    }

    /** Ustawia wybrany głos, tempo i ton z ustawień (wywoływane przed każdą wypowiedzią). */
    private fun applySettings() {
        val name = settings.ttsVoice
        if (name.isNotBlank()) {
            try {
                tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it }
            } catch (e: Exception) {
                // Głos zniknął (np. odinstalowany pakiet) — zostaje domyślny polski.
            }
        }
        tts.setSpeechRate(settings.ttsRate)
        tts.setPitch(settings.ttsPitch)
    }

    /** Zwraca false, gdy mowa nie jest możliwa (brak polskiego głosu lub błąd TTS). */
    fun speak(text: String): Boolean {
        if (status != Status.READY || text.isBlank()) return false
        applySettings()
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
