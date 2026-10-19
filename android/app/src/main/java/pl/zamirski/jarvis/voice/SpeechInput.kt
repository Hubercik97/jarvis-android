package pl.zamirski.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Rozpoznawanie polskiej mowy z mikrofonu telefonu (systemowa usługa Androida).
 * Słucha tylko po naciśnięciu przycisku. Aplikacja nie zapisuje nagrań —
 * fragmenty dźwięku (onBufferReceived) są ignorowane.
 */
class SpeechInput(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onListeningReady()
        fun onPartialText(text: String)
        fun onFinalText(text: String)
        fun onSpeechError(code: Int)
        fun onSoundLevel(rmsDb: Float)
    }

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    /** Kończy słuchanie i przetwarza to, co już powiedziano. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Przerywa bez wyniku. */
    fun cancel() {
        recognizer?.cancel()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListeningReady()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = listener.onSoundLevel(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) { /* celowo nic — nie zapisujemy dźwięku */ }
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) = listener.onSpeechError(error)

        override fun onResults(results: Bundle?) {
            listener.onFinalText(firstResult(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotBlank()) listener.onPartialText(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    companion object {
        const val LANGUAGE = "pl-PL"
    }
}
