package pl.zamirski.jarvis.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import pl.zamirski.jarvis.BuildConfig
import pl.zamirski.jarvis.core.BluetoothRoutePolicy
import pl.zamirski.jarvis.core.Urls
import pl.zamirski.jarvis.features.FeatureRegistry
import pl.zamirski.jarvis.net.NetworkMonitor
import pl.zamirski.jarvis.ui.AppActions
import pl.zamirski.jarvis.ui.ConnectionState
import pl.zamirski.jarvis.ui.JarvisViewModel
import pl.zamirski.jarvis.ui.components.GlassCard
import pl.zamirski.jarvis.ui.components.InfoText
import pl.zamirski.jarvis.ui.components.ScreenFrame
import pl.zamirski.jarvis.ui.components.SectionLabel
import pl.zamirski.jarvis.ui.theme.JColors
import pl.zamirski.jarvis.voice.VoiceOutput
import pl.zamirski.jarvis.service.WakeWordService
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun SettingsScreen(vm: JarvisViewModel, actions: AppActions, onBack: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(vm.settings.backendUrl) }
    var token by remember { mutableStateOf(vm.settings.appToken) }
    var city by remember { mutableStateOf(vm.settings.weatherCity) }
    var voiceReplies by remember { mutableStateOf(vm.settings.voiceReplies) }
    var handsFree by remember { mutableStateOf(vm.settings.handsFree) }
    var bluetoothMic by remember { mutableStateOf(vm.settings.bluetoothMic) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    ScreenFrame(title = "Ustawienia", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Status")
            GlassCard(Modifier.fillMaxWidth()) {
                val tick = refreshTick // odczyt wymusza odświeżenie statusu po „Sprawdź ponownie”
                if (tick < 0) return@GlassCard
                StatusLine(NetworkMonitor.isOnline(context), "Internet", "połączono", "brak połączenia")
                StatusLine(granted(Manifest.permission.RECORD_AUDIO), "Mikrofon", "zgoda przyznana", "zapytam po naciśnięciu mikrofonu")
                StatusLine(SpeechRecognizer.isRecognitionAvailable(context), "Rozpoznawanie mowy", "dostępne (pl-PL)", "niedostępne — włącz aplikację Google")
                when (vm.voiceStatus) {
                    VoiceOutput.Status.READY -> StatusLine(true, "Głos polski", "gotowy", "")
                    VoiceOutput.Status.NO_POLISH -> StatusLine(false, "Głos polski", "", "brak pakietu — Ustawienia → Zamiana tekstu na mowę")
                    VoiceOutput.Status.FAILED -> StatusLine(false, "Głos polski", "", "błąd syntezatora")
                    VoiceOutput.Status.INITIALIZING -> Text("⏳ Głos polski: sprawdzanie…", color = JColors.TextDim, fontSize = 14.sp)
                }
                when (val c = vm.connection) {
                    ConnectionState.Checking -> Text("⏳ Połączenie z AI: sprawdzanie…", color = JColors.TextDim, fontSize = 14.sp)
                    is ConnectionState.Ok -> StatusLine(true, "Połączenie z AI", "działa" + if (c.model.isNotBlank()) " (${c.model})" else "", "")
                    is ConnectionState.Problem -> StatusLine(false, "Połączenie z AI", "", c.text)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    refreshTick++
                    vm.refreshConnection()
                }) { Text("Sprawdź ponownie") }
            }

            SectionLabel("Backend AI")
            GlassCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        urlError = null
                    },
                    label = { Text("Adres backendu (https://…workers.dev)") },
                    isError = urlError != null,
                    supportingText = { urlError?.let { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token aplikacji") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    val normalized = Urls.normalizeBackend(url)
                    if (url.isNotBlank() && normalized == null) {
                        urlError = "Adres musi zaczynać się od https://"
                    } else {
                        vm.settings.backendUrl = normalized ?: ""
                        vm.settings.appToken = token
                        url = vm.settings.backendUrl
                        vm.refreshConnection()
                        vm.notice = "Zapisano. Sprawdzam połączenie z AI…"
                    }
                }) { Text("Zapisz i sprawdź") }
                Spacer(Modifier.height(6.dp))
                InfoText("Klucz API modelu jest tylko na serwerze. Token chroni Twój backend przed obcymi.")
            }

            SectionLabel("Głos")
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Odpowiadaj głosem", color = JColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = voiceReplies, onCheckedChange = {
                        voiceReplies = it
                        vm.settings.voiceReplies = it
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tryb rozmowy", color = JColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = handsFree, onCheckedChange = {
                        handsFree = it
                        vm.settings.handsFree = it
                    })
                }
                InfoText(
                    "Po odpowiedzi głosowej Jarvis sam znowu słucha — możesz rozmawiać bez dotykania ekranu. " +
                        "Zakończ, mówiąc „to wszystko” albo „dziękuję”. Działa tylko przy otwartej aplikacji: " +
                        "ekran nie gaśnie w trakcie rozmowy, a po przejściu do innej aplikacji nasłuch się zatrzymuje.",
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mikrofon słuchawek Bluetooth", color = JColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = bluetoothMic, onCheckedChange = {
                        bluetoothMic = it
                        vm.settings.bluetoothMic = it
                        refreshTick++
                    })
                }
                // Odczyt refreshTick odświeża stan słuchawek po przełączeniu lub „Sprawdź ponownie”.
                if (refreshTick >= 0) InfoText(
                    BluetoothRoutePolicy.describe(bluetoothMic, vm.bluetooth.headset() != null, vm.bluetooth.headsetName()) +
                        ". Głos Jarvisa gra tam, gdzie multimedia — w podłączonych słuchawkach. " +
                        "Czy usługa rozpoznawania mowy Google używa mikrofonu słuchawek, zależy od telefonu — sprawdź na S21.",
                )
            }

            SectionLabel("Słuchanie w tle")
            BackgroundListeningCard(vm, actions)

            SectionLabel("Pogoda")
            GlassCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text("Miasto (puste = moja lokalizacja)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(onClick = {
                        vm.settings.weatherCity = city
                        vm.refreshWeather()
                        vm.notice = "Zapisano. Pobieram pogodę…"
                    }) { Text("Zapisz") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        actions.requestPermission(Manifest.permission.ACCESS_COARSE_LOCATION) { ok ->
                            if (ok) {
                                city = ""
                                vm.settings.weatherCity = ""
                                vm.refreshWeather()
                            }
                        }
                    }) { Text("Użyj lokalizacji") }
                }
            }

            SectionLabel("Moduły")
            GlassCard(Modifier.fillMaxWidth()) {
                FeatureRegistry.all.forEach {
                    Text("• ${it.name} — ${it.state.label}", color = JColors.Text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }

            SectionLabel("Prywatność")
            GlassCard(Modifier.fillMaxWidth()) {
                InfoText(
                    "Aplikacja nie zapisuje nagrań głosu. Rozpoznawaniem mowy zajmuje się systemowa usługa Google na telefonie. " +
                        "Do serwera AI trafia tylko tekst bieżącej rozmowy oraz zdjęcia/pliki, które sam wyślesz. " +
                        "Historia rozmów (bez zdjęć), notatki, zadania i przypomnienia są zapisane tylko na tym telefonie.",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = actions.openAppSettings) { Text("Uprawnienia aplikacji") }
            }
            Text("Wersja ${BuildConfig.VERSION_NAME}", color = JColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, name: String, good: String, bad: String) {
    Text(
        if (ok) "✅ $name: $good" else "❌ $name: $bad",
        color = JColors.Text,
        fontSize = 14.sp,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}

/**
 * Nasłuch słowa „Jarvis” w tle. Uczciwie pokazuje, czego brakuje (zgody, bateria) i jak działa rozpoznawanie.
 */
@Composable
private fun BackgroundListeningCard(vm: JarvisViewModel, actions: AppActions) {
    val context = LocalContext.current
    val running by WakeWordService.running.collectAsState()
    val status by WakeWordService.status.collectAsState()
    var overlayOk by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var micOk by remember { mutableStateOf(false) }

    // Po powrocie z ustawień systemu odświeżamy stan zgód.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            overlayOk = android.provider.Settings.canDrawOverlays(context)
            micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun startService() {
        actions.requestPermission(Manifest.permission.RECORD_AUDIO) { mic ->
            if (mic) {
                micOk = true
                vm.settings.backgroundListening = true
                WakeWordService.start(context)
                // Bez zgody na powiadomienia usługa i tak działa, ale nie widać jej stanu — prosimy o zgodę.
                if (Build.VERSION.SDK_INT >= 33) actions.requestPermission(Manifest.permission.POST_NOTIFICATIONS) { }
            } else {
                vm.notice = "Bez zgody na mikrofon Jarvis nie może słuchać."
            }
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Słuchaj „Jarvis” w tle", color = JColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = running, onCheckedChange = { on ->
                if (on) {
                    startService()
                } else {
                    vm.settings.backgroundListening = false
                    WakeWordService.stop(context)
                }
            })
        }
        InfoText(
            if (running) "Działa. Powiadomienie „Jarvis słucha w tle” pokazuje stan. Gdy Jarvis jest otwarty, nasłuch w tle czeka."
            else "Wyłączone.",
            if (running) JColors.Success else JColors.TextDim,
        )
        status?.let { InfoText(it, JColors.Warning) }
        Spacer(Modifier.height(8.dp))
        InfoText(
            "Powiedz „Jarvis” i polecenie, np. „Jarvis, przełącz na kalendarz”, będąc w dowolnej aplikacji. " +
                "Słowo jest wykrywane przez ciągłe rozpoznawanie mowy pl-PL: na telefonie, jeśli Android ma polski pakiet offline, " +
                "w przeciwnym razie przez usługę mowy Google (dźwięk trafia wtedy do Google). Zużywa baterię. " +
                "Część telefonów wydaje krótki dźwięk przy każdym wznowieniu słuchania.",
        )
        Spacer(Modifier.height(10.dp))
        StatusLine(micOk, "Mikrofon", "zgoda przyznana", "brak zgody")
        StatusLine(
            overlayOk, "Przełączanie aplikacji z tła", "zgoda przyznana",
            "potrzebna zgoda „Wyświetlanie nad innymi aplikacjami”",
        )
        if (!overlayOk) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Zezwól na przełączanie aplikacji") }
        }
        Spacer(Modifier.height(6.dp))
        InfoText("Samsung usypia aplikacje w tle. Ustaw: Bateria → Bez ograniczeń.")
        OutlinedButton(onClick = actions.openAppSettings) { Text("Otwórz ustawienia baterii Jarvisa") }
    }
}

