package pl.zamirski.jarvis.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import pl.zamirski.jarvis.R
import pl.zamirski.jarvis.core.AiClient
import pl.zamirski.jarvis.core.AiResult
import pl.zamirski.jarvis.core.ChatMessage
import pl.zamirski.jarvis.core.HealthResult
import pl.zamirski.jarvis.core.Kind
import pl.zamirski.jarvis.core.Messages
import pl.zamirski.jarvis.net.BackendAiClient
import pl.zamirski.jarvis.net.NetworkMonitor
import pl.zamirski.jarvis.settings.AppSettings
import pl.zamirski.jarvis.voice.SpeechInput
import pl.zamirski.jarvis.voice.VoiceOutput

class MainActivity : AppCompatActivity(), SpeechInput.Listener, VoiceOutput.Listener {

    private enum class UiState { IDLE, LISTENING, THINKING, SPEAKING }

    private lateinit var settings: AppSettings
    private lateinit var ai: AiClient
    private lateinit var speech: SpeechInput
    private lateinit var voice: VoiceOutput

    private lateinit var micButton: ImageButton
    private lateinit var micRing: View
    private lateinit var stateText: TextView
    private lateinit var connectionDot: View
    private lateinit var connectionText: TextView
    private lateinit var input: EditText
    private lateinit var list: RecyclerView

    private val history = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var state = UiState.IDLE
    private var ringAnimator: ObjectAnimator? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else onMicDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = AppSettings(this)
        ai = BackendAiClient(applicationContext, settings)
        speech = SpeechInput(this, this)
        voice = VoiceOutput(this, this)

        micButton = findViewById(R.id.mic_button)
        micRing = findViewById(R.id.mic_ring)
        stateText = findViewById(R.id.state_text)
        connectionDot = findViewById(R.id.connection_dot)
        connectionText = findViewById(R.id.connection_text)
        input = findViewById(R.id.text_input)
        list = findViewById(R.id.chat_list)

        adapter = ChatAdapter(history)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        micButton.setOnClickListener { onMicPressed() }
        findViewById<View>(R.id.send_button).setOnClickListener { submitTypedText() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedText()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.status_button).setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }
        findViewById<View>(R.id.connection_bar).setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }

        addMessage(ChatMessage(Kind.NOTICE, getString(R.string.welcome)))
        setState(UiState.IDLE)
    }

    override fun onResume() {
        super.onResume()
        refreshConnection()
    }

    override fun onStop() {
        super.onStop()
        if (state == UiState.LISTENING) {
            speech.cancel()
            setState(UiState.IDLE)
        }
        if (state == UiState.SPEAKING) {
            voice.stop()
            setState(UiState.IDLE)
        }
    }

    override fun onDestroy() {
        speech.destroy()
        voice.shutdown()
        super.onDestroy()
    }

    // ---------- Mikrofon ----------

    private fun onMicPressed() {
        when (state) {
            UiState.LISTENING -> {
                speech.stop()
                return
            }
            UiState.SPEAKING -> voice.stop()
            UiState.THINKING -> {
                stateText.text = getString(R.string.state_wait)
                return
            }
            UiState.IDLE -> Unit
        }
        if (!speech.isAvailable()) {
            addMessage(ChatMessage(Kind.NOTICE, getString(R.string.err_no_recognizer)))
            return
        }
        when {
            hasMicPermission() -> startListening()
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.mic_rationale_title)
                    .setMessage(R.string.mic_rationale)
                    .setPositiveButton(R.string.allow) { _, _ -> micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun onMicDenied() {
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Odmowa trwała („Nie pytaj ponownie”) — jedyna droga to ustawienia systemu.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mic_denied_title)
                .setMessage(R.string.mic_denied_permanent)
                .setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            addMessage(ChatMessage(Kind.NOTICE, getString(R.string.mic_denied)))
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun startListening() {
        if (!NetworkMonitor.isOnline(this)) {
            addMessage(ChatMessage(Kind.NOTICE, Messages.forFailure(pl.zamirski.jarvis.core.FailureReason.OFFLINE)))
            refreshConnection()
            return
        }
        voice.stop()
        setState(UiState.LISTENING)
        stateText.text = getString(R.string.state_preparing)
        speech.start()
    }

    override fun onListeningReady() {
        if (state == UiState.LISTENING) stateText.text = getString(R.string.state_listening)
    }

    override fun onPartialText(text: String) {
        if (state == UiState.LISTENING) stateText.text = "„$text”"
    }

    override fun onFinalText(text: String) {
        if (state != UiState.LISTENING) return
        setState(UiState.IDLE)
        if (text.isBlank()) {
            stateText.text = Messages.forSpeechError(7)
            return
        }
        sendToAi(text)
    }

    override fun onSpeechError(code: Int) {
        if (state != UiState.LISTENING) return
        setState(UiState.IDLE)
        if (Messages.isSilence(code)) {
            stateText.text = Messages.forSpeechError(code)
        } else {
            addMessage(ChatMessage(Kind.NOTICE, Messages.forSpeechError(code)))
        }
    }

    override fun onSoundLevel(rmsDb: Float) {
        if (state != UiState.LISTENING) return
        val scale = 1f + (rmsDb.coerceIn(0f, 10f) / 10f) * 0.35f
        micRing.scaleX = scale
        micRing.scaleY = scale
    }

    // ---------- Tekst ----------

    private fun submitTypedText() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (state == UiState.THINKING) {
            stateText.text = getString(R.string.state_wait)
            return
        }
        if (state == UiState.LISTENING) speech.cancel()
        if (state == UiState.SPEAKING) voice.stop()
        input.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        setState(UiState.IDLE)
        sendToAi(text)
    }

    // ---------- AI ----------

    private fun sendToAi(text: String) {
        addMessage(ChatMessage(Kind.USER, text))
        setState(UiState.THINKING)
        val snapshot = history.toList()
        lifecycleScope.launch {
            when (val result = ai.chat(snapshot)) {
                is AiResult.Success -> {
                    addMessage(ChatMessage(Kind.ASSISTANT, result.text))
                    setConnection(true, getString(R.string.conn_ok))
                    if (settings.voiceReplies && voice.status == VoiceOutput.Status.READY && voice.speak(result.text)) {
                        setState(UiState.SPEAKING)
                    } else {
                        setState(UiState.IDLE)
                    }
                }
                is AiResult.Failure -> {
                    addMessage(ChatMessage(Kind.NOTICE, Messages.forFailure(result.reason, result.detail)))
                    setState(UiState.IDLE)
                    refreshConnection()
                }
            }
        }
    }

    private fun refreshConnection() {
        setConnection(null, getString(R.string.conn_checking))
        lifecycleScope.launch {
            when (val h = ai.health()) {
                is HealthResult.Success -> when {
                    !h.health.configured -> setConnection(false, getString(R.string.conn_no_key))
                    h.health.auth == "invalid" -> setConnection(false, getString(R.string.conn_bad_token))
                    else -> setConnection(true, getString(R.string.conn_ok))
                }
                is HealthResult.Failure -> setConnection(false, Messages.forFailure(h.reason, h.detail))
            }
        }
    }

    private fun setConnection(ok: Boolean?, label: String) {
        val color = when (ok) {
            true -> R.color.success
            false -> R.color.error
            null -> R.color.text_secondary
        }
        connectionDot.background.mutate().setTint(ContextCompat.getColor(this, color))
        connectionText.text = label
    }

    // ---------- Głos ----------

    override fun onVoiceStatus(status: VoiceOutput.Status) {
        when (status) {
            VoiceOutput.Status.NO_POLISH -> addMessage(ChatMessage(Kind.NOTICE, getString(R.string.err_no_polish_tts)))
            VoiceOutput.Status.FAILED -> addMessage(ChatMessage(Kind.NOTICE, getString(R.string.err_tts_failed)))
            else -> Unit
        }
    }

    override fun onSpeakingStarted() {
        if (state == UiState.SPEAKING) stateText.text = getString(R.string.state_speaking)
    }

    override fun onSpeakingFinished() {
        if (state == UiState.SPEAKING) setState(UiState.IDLE)
    }

    // ---------- Pomocnicze ----------

    private fun addMessage(message: ChatMessage) {
        history.add(message)
        adapter.notifyItemInserted(history.size - 1)
        list.scrollToPosition(history.size - 1)
    }

    private fun setState(newState: UiState) {
        state = newState
        stateText.text = when (newState) {
            UiState.IDLE -> getString(R.string.state_idle)
            UiState.LISTENING -> getString(R.string.state_listening)
            UiState.THINKING -> getString(R.string.state_thinking)
            UiState.SPEAKING -> getString(R.string.state_speaking)
        }
        micButton.setImageResource(if (newState == UiState.LISTENING) R.drawable.ic_stop else R.drawable.ic_mic)
        micButton.contentDescription = getString(
            if (newState == UiState.LISTENING) R.string.mic_stop else R.string.mic_start,
        )
        animateRing(newState != UiState.IDLE)
        if (newState != UiState.LISTENING) {
            micRing.scaleX = 1f
            micRing.scaleY = 1f
        }
    }

    private fun animateRing(active: Boolean) {
        if (active) {
            if (ringAnimator == null) {
                ringAnimator = ObjectAnimator.ofPropertyValuesHolder(
                    micRing,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.35f, 1f),
                ).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            ringAnimator?.cancel()
            ringAnimator = null
            micRing.alpha = 0.6f
        }
    }
}
