package pl.zamirski.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import pl.zamirski.jarvis.BuildConfig
import pl.zamirski.jarvis.R
import pl.zamirski.jarvis.core.HealthResult
import pl.zamirski.jarvis.core.Messages
import pl.zamirski.jarvis.core.Urls
import pl.zamirski.jarvis.features.FeatureRegistry
import pl.zamirski.jarvis.net.BackendAiClient
import pl.zamirski.jarvis.net.NetworkMonitor
import pl.zamirski.jarvis.settings.AppSettings
import pl.zamirski.jarvis.voice.VoiceOutput

/** Ekran statusu połączenia z AI + ustawienia backendu. */
class StatusActivity : AppCompatActivity(), VoiceOutput.Listener {

    private lateinit var settings: AppSettings
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var report: TextView
    private var voice: VoiceOutput? = null
    private var voiceStatus: VoiceOutput.Status = VoiceOutput.Status.INITIALIZING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settings = AppSettings(this)
        urlField = findViewById(R.id.backend_url)
        tokenField = findViewById(R.id.app_token)
        report = findViewById(R.id.status_report)
        val voiceSwitch = findViewById<MaterialSwitch>(R.id.voice_switch)

        urlField.setText(settings.backendUrl)
        tokenField.setText(settings.appToken)
        voiceSwitch.isChecked = settings.voiceReplies
        voiceSwitch.setOnCheckedChangeListener { _, checked -> settings.voiceReplies = checked }

        findViewById<Button>(R.id.save_button).setOnClickListener { saveAndTest() }
        findViewById<Button>(R.id.test_button).setOnClickListener { runChecks() }

        findViewById<TextView>(R.id.features_list).text = FeatureRegistry.all.joinToString("\n") {
            "• ${it.name} — ${it.state.label}"
        }
        findViewById<TextView>(R.id.version_text).text = getString(R.string.version_format, BuildConfig.VERSION_NAME)

        voice = VoiceOutput(this, this)
        runChecks()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        voice?.shutdown()
        super.onDestroy()
    }

    private fun saveAndTest() {
        val rawUrl = urlField.text.toString()
        if (rawUrl.isNotBlank() && Urls.normalizeBackend(rawUrl) == null) {
            urlField.error = getString(R.string.err_url)
            return
        }
        settings.backendUrl = Urls.normalizeBackend(rawUrl) ?: ""
        settings.appToken = tokenField.text.toString()
        urlField.setText(settings.backendUrl)
        runChecks()
    }

    private fun runChecks() {
        val lines = mutableListOf<String>()
        lines += line(NetworkMonitor.isOnline(this), "Internet", "połączono", "brak połączenia")
        lines += line(
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            "Mikrofon",
            "zgoda przyznana",
            "brak zgody (zostaniesz zapytany po naciśnięciu mikrofonu)",
        )
        lines += line(
            SpeechRecognizer.isRecognitionAvailable(this),
            "Rozpoznawanie mowy",
            "dostępne (pl-PL)",
            "niedostępne — zainstaluj/aktywuj aplikację Google",
        )
        lines += when (voiceStatus) {
            VoiceOutput.Status.READY -> "✅ Głos polski (TTS): gotowy"
            VoiceOutput.Status.NO_POLISH -> "❌ Głos polski (TTS): brak pakietu języka polskiego"
            VoiceOutput.Status.FAILED -> "❌ Głos polski (TTS): błąd syntezatora"
            VoiceOutput.Status.INITIALIZING -> "⏳ Głos polski (TTS): sprawdzanie…"
        }
        val url = Urls.normalizeBackend(settings.backendUrl)
        lines += if (url == null) "❌ Backend: Brak danych — wpisz adres https://" else "ℹ️ Backend: $url"
        lines += "⏳ Połączenie z AI: sprawdzanie…"
        report.text = lines.joinToString("\n")

        lifecycleScope.launch {
            val ai = BackendAiClient(applicationContext, settings)
            val aiLine = when (val h = ai.health()) {
                is HealthResult.Success -> when {
                    !h.health.ok -> "❌ Połączenie z AI: serwer zgłasza problem"
                    !h.health.configured -> "❌ Połączenie z AI: serwer działa, ale nie ma klucza API modelu"
                    h.health.auth == "invalid" -> "❌ Połączenie z AI: błędny token aplikacji"
                    else -> "✅ Połączenie z AI: działa" +
                        (if (h.health.model.isNotBlank()) " (model: ${h.health.model})" else "")
                }
                is HealthResult.Failure -> "❌ Połączenie z AI: " + Messages.forFailure(h.reason, h.detail)
            }
            lines[lines.lastIndex] = aiLine
            report.text = lines.joinToString("\n")
        }
    }

    private fun line(ok: Boolean, name: String, good: String, bad: String) =
        if (ok) "✅ $name: $good" else "❌ $name: $bad"

    override fun onVoiceStatus(status: VoiceOutput.Status) {
        voiceStatus = status
        runChecks()
    }

    override fun onSpeakingStarted() {}
    override fun onSpeakingFinished() {}
}
