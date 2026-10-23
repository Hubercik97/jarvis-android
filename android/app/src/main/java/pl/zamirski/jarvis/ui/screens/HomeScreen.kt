package pl.zamirski.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.zamirski.jarvis.core.Greeting
import pl.zamirski.jarvis.data.WeatherState
import pl.zamirski.jarvis.ui.AppActions
import pl.zamirski.jarvis.ui.ConnectionState
import pl.zamirski.jarvis.ui.JarvisViewModel
import pl.zamirski.jarvis.ui.Routes
import pl.zamirski.jarvis.ui.VoiceMode
import pl.zamirski.jarvis.ui.components.GlassCard
import pl.zamirski.jarvis.ui.components.JarvisBackground
import pl.zamirski.jarvis.ui.components.JarvisCore
import pl.zamirski.jarvis.ui.components.WeatherGlyph
import pl.zamirski.jarvis.ui.theme.JColors
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PL = Locale("pl", "PL")

@Composable
fun HomeScreen(vm: JarvisViewModel, actions: AppActions, navigate: (String) -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000)
        }
    }
    var searchOpen by remember { mutableStateOf(false) }

    JarvisBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Nagłówek
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigate(Routes.FEATURES) }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Funkcje", tint = JColors.Accent)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("JARVIS", color = JColors.AccentBright, fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = 6.sp)
                    Text("Twój osobisty asystent AI", color = JColors.TextDim, fontSize = 13.sp)
                }
                IconButton(onClick = { navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = JColors.Accent)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Zegar + pogoda
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard(Modifier.weight(1f).height(120.dp)) {
                    Text(
                        now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", PL)).replaceFirstChar { it.titlecase(PL) },
                        color = JColors.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        now.format(DateTimeFormatter.ofPattern("HH:mm")),
                        color = JColors.Text, fontSize = 46.sp, fontWeight = FontWeight.Light,
                    )
                    Text(now.format(DateTimeFormatter.ofPattern("yyyy", PL)), color = JColors.TextDim, fontSize = 12.sp)
                }
                WeatherCard(vm, Modifier.weight(1f).height(120.dp)) { navigate(Routes.WEATHER) }
            }

            // Rdzeń AI
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
                JarvisCore(
                    modifier = Modifier.fillMaxSize(),
                    energy = vm.soundLevel,
                    active = vm.mode != VoiceMode.IDLE,
                    mode = vm.mode.name,
                )
                if (vm.mode == VoiceMode.SPEAKING) {
                    Text(if (vm.voiceAudioAvailable) "TTS · rzeczywisty sygnał audio" else "Głos systemowy · brak analizy PCM", color = JColors.TextDim, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopCenter))
                }
                ConnectionLine(vm.connection) { navigate(Routes.SETTINGS) }
            }

            // Powitanie / odpowiedź
            GreetingCard(vm, now.hour) { navigate(Routes.CHAT) }

            Spacer(Modifier.height(12.dp))

            // Szybkie akcje
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(Icons.Filled.RecordVoiceOver, "Głos", Modifier.weight(1f)) { navigate(Routes.CHAT) }
                QuickAction(Icons.Filled.CameraAlt, "Kamera", Modifier.weight(1f)) { actions.takePhoto() }
                QuickAction(Icons.Filled.Folder, "Pliki", Modifier.weight(1f)) { actions.pickFile() }
                QuickAction(Icons.Filled.Public, "Internet", Modifier.weight(1f)) { searchOpen = true }
            }

            // Dolny pasek z mikrofonem
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Icons.Filled.Keyboard, "Pisz do Jarvisa") { navigate(Routes.CHAT) }
                MicButton(vm.mode) { actions.startVoice() }
                RoundIcon(Icons.Filled.Apps, "Funkcje") { navigate(Routes.FEATURES) }
            }
        }
    }

    if (searchOpen) WebSearchDialog(onDismiss = { searchOpen = false }) { q ->
        searchOpen = false
        actions.webSearch(q)
    }
}

@Composable
private fun WeatherCard(vm: JarvisViewModel, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier, onClick = onClick) {
        when (val w = vm.weather) {
            is WeatherState.Ready -> {
                val r = w.report
                Text(r.place, color = JColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherGlyph(r.icon, 34.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${r.temp}°C", color = JColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Light)
                }
                Text(r.description, color = JColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("↑ ${r.todayMax}°   ↓ ${r.todayMin}°", color = JColors.TextDim, fontSize = 12.sp)
            }
            WeatherState.Loading, WeatherState.Idle -> {
                Text("Pogoda", color = JColors.Text, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator(Modifier.size(24.dp), color = JColors.Accent, strokeWidth = 2.dp)
            }
            is WeatherState.Missing -> {
                Text("Pogoda", color = JColors.Text, fontSize = 14.sp)
                Text("Brak danych", color = JColors.Warning, fontSize = 18.sp)
                Text("Dotknij, aby ustawić", color = JColors.TextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ConnectionLine(state: ConnectionState, onClick: () -> Unit) {
    val (color, text) = when (state) {
        ConnectionState.Checking -> JColors.TextDim to "Łączę z AI…"
        is ConnectionState.Ok -> JColors.Success to "AI połączone"
        is ConnectionState.Problem -> JColors.Error to "AI niepołączone — dotknij"
    }
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = JColors.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun GreetingCard(vm: JarvisViewModel, hour: Int, onClick: () -> Unit) {
    val live = vm.liveText
    val reply = vm.lastReply
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 16.dp) {
        when {
            vm.mode != VoiceMode.IDLE && live.isNotBlank() -> {
                Text(live, color = JColors.AccentBright, fontSize = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            reply != null -> {
                Text(reply, color = JColors.Text, fontSize = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (live.isNotBlank()) Text(live, color = JColors.Warning, fontSize = 13.sp, maxLines = 2)
            }
            live.isNotBlank() -> {
                Text(Greeting.forHour(hour), color = JColors.Text, fontSize = 19.sp)
                Text(live, color = JColors.Warning, fontSize = 14.sp, maxLines = 3)
            }
            else -> {
                Text(Greeting.forHour(hour), color = JColors.Text, fontSize = 19.sp)
                Text("W czym mogę Ci dzisiaj pomóc?", color = JColors.Text, fontSize = 19.sp)
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.height(76.dp), onClick = onClick, padding = 8.dp) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = JColors.Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, color = JColors.Text, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(JColors.Card)
            .border(1.dp, JColors.CardBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = JColors.Accent) }
}

@Composable
fun MicButton(mode: VoiceMode, size: Int = 86, onClick: () -> Unit) {
    val ring = when (mode) {
        VoiceMode.LISTENING -> JColors.AccentBright
        VoiceMode.THINKING -> JColors.Orange
        VoiceMode.SPEAKING -> JColors.Success
        VoiceMode.IDLE -> JColors.Accent
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0xFF071C33))
            .border(3.dp, ring, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (mode == VoiceMode.LISTENING) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (mode == VoiceMode.LISTENING) "Zakończ słuchanie" else "Mów do Jarvisa",
            tint = JColors.Text,
            modifier = Modifier.size((size * 0.42f).dp),
        )
    }
}

@Composable
fun WebSearchDialog(onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Szukaj w internecie") },
        text = {
            Column {
                Text("Otworzy wyszukiwarkę w przeglądarce telefonu.", color = JColors.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Czego szukasz?") })
            }
        },
        confirmButton = { TextButton(onClick = { onSearch(query) }, enabled = query.isNotBlank()) { Text("Szukaj") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
