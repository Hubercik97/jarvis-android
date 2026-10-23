package pl.zamirski.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.audiofx.Visualizer
import kotlin.math.sqrt
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.zamirski.jarvis.net.BackendTtsClient
import pl.zamirski.jarvis.settings.AppSettings
import java.io.File
import java.util.Locale

/** Polski głos dostępny w syntezatorze telefonu (Android nie podaje płci — wybór na słuch). */
data class VoiceOption(val name: String, val label: String, val offline: Boolean)

/**
 * Odpowiedzi głosowe po polsku.
 * - Domyślnie: systemowy syntezator telefonu (darmowy, działa offline z pobranym głosem).
 * - Opcjonalnie: głos w chmurze (Google Chirp 3 HD przez backend) — gdy włączony w Ustawieniach.
 *   Przy błędzie sieci lub serwera Jarvis automatycznie mówi głosem telefonu.
 */
class VoiceOutput(context: Context, private val listener: Listener) : TextToSpeech.OnInitListener {

    private val app = context.applicationContext
    private val settings = AppSettings(context)
    private val cloud = BackendTtsClient(settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cloudJob: Job? = null
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var generation = 0

    /** Ostatni powód przejścia z głosu w chmurze na głos telefonu (do pokazania w Ustawieniach). */
    var lastCloudError: String? = null
        private set

    enum class Status { INITIALIZING, READY, NO_POLISH, FAILED }

    interface Listener {
        fun onVoiceStatus(status: Status)
        fun onSpeakingStarted()
        fun onSpeakingFinished()
        fun onVoiceEnergy(level: Float, available: Boolean) {}
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
        if (text.isBlank()) return false
        stopPlayback(notify = false)
        if (settings.cloudVoice) {
            speakCloud(text)
            return true
        }
        return speakDevice(text)
    }

    private fun speakDevice(text: String): Boolean {
        if (status != Status.READY) return false
        listener.onVoiceEnergy(0f, false) // System TextToSpeech exposes no playback PCM/session.
        applySettings()
        counter += 1
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-$counter") == TextToSpeech.SUCCESS
    }

    private fun speakCloud(text: String) {
        val gen = ++generation
        cloudJob = scope.launch {
            val audio = try {
                cloud.synthesize(text, settings.cloudVoiceName, settings.ttsRate)
            } catch (e: Exception) {
                lastCloudError = e.message ?: "błąd sieci"
                null
            }
            if (gen != generation) return@launch // przerwane w międzyczasie
            if (audio == null || !play(audio)) {
                // Zapasowo: głos telefonu, żeby odpowiedź nie przepadła.
                if (!speakDevice(text)) listener.onSpeakingFinished()
            } else {
                lastCloudError = null
            }
        }
    }

    private suspend fun play(audio: ByteArray): Boolean {
        val file = File(app.cacheDir, "jarvis_voice.mp3")
        return try {
            withContext(Dispatchers.IO) { file.writeBytes(audio) }
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    releasePlayer()
                    listener.onSpeakingFinished()
                }
                setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    listener.onSpeakingFinished()
                    true
                }
                prepare()
            }
            player = mp
            mp.start()
            observePlayback(mp)
            listener.onSpeakingStarted()
            true
        } catch (e: Exception) {
            lastCloudError = "odtwarzanie: ${e.javaClass.simpleName}"
            releasePlayer()
            false
        }
    }

    /** Only this player's session is analysed, never global device output (session 0). */
    private fun observePlayback(mp: MediaPlayer) {
        if (mp.audioSessionId == 0) return
        try {
            visualizer = Visualizer(mp.audioSessionId)
            visualizer?.apply {
                scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {
                        if (waveform == null || waveform.isEmpty()) return
                        val rms = sqrt(waveform.sumOf { val x = ((it.toInt() and 255) - 128) / 128.0; x * x } / waveform.size).toFloat()
                        main.post { if (player === mp) listener.onVoiceEnergy(rms.coerceIn(0f, 1f), true) }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {}
                }, minOf(20_000, Visualizer.getMaxCaptureRate()), true, false)
                enabled = true
            }
        } catch (_: Exception) {
            try { visualizer?.release() } catch (_: Exception) {}
            visualizer = null
            listener.onVoiceEnergy(0f, false)
        }
    }

    private fun releasePlayer() {
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        listener.onVoiceEnergy(0f, false)
        player?.let {
            try {
                it.release()
            } catch (e: Exception) {
                // już zwolniony
            }
        }
        player = null
    }

    private fun stopPlayback(notify: Boolean) {
        generation++
        cloudJob?.cancel()
        cloudJob = null
        val wasPlaying = player != null
        player?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                // nie grał
            }
        }
        releasePlayer()
        if (notify && wasPlaying) main.post { listener.onSpeakingFinished() }
    }

    fun stop() {
        stopPlayback(notify = true)
        tts.stop()
    }

    fun shutdown() {
        stopPlayback(notify = false)
        scope.cancel()
        tts.stop()
        tts.shutdown()
    }
}
