package pl.zamirski.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Jedna instancja rozpoznawania mowy pl-PL dla nasłuchu w tle.
 * Najpierw próbuje rozpoznawania NA TELEFONIE (Android 12+, jeśli dostępny polski pakiet offline),
 * w przeciwnym razie używa systemowej usługi Google (dźwięk trafia wtedy do Google).
 * W danej chwili trwa najwyżej jedna sesja — kolejny start najpierw anuluje poprzednią.
 */
class ContinuousRecognizer(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onText(text: String)
        fun onRecognitionError(code: Int)
        fun onSessionReady() {}
    }

    private var recognizer: SpeechRecognizer? = null
    var onDevice: Boolean = false
        private set
    private var forceCloud = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context) || canUseOnDevice()

    private fun canUseOnDevice(): Boolean =
        !forceCloud && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Po błędzie „brak polskiego języka na urządzeniu” przechodzimy na usługę Google. */
    fun fallBackToCloud() {
        forceCloud = true
        destroy()
    }

    fun start() {
        val r = recognizer ?: create().also { recognizer = it }
        r.cancel()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, SpeechInput.LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, SpeechInput.LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    fun cancel() {
        recognizer?.cancel()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun create(): SpeechRecognizer {
        onDevice = canUseOnDevice()
        val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(callbacks)
        return r
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onSessionReady()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) { /* nie zapisujemy dźwięku */ }
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) = listener.onRecognitionError(error)
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            listener.onText(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
