package pl.zamirski.jarvis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.zamirski.jarvis.ui.JarvisViewModel
import pl.zamirski.jarvis.ui.components.Badge
import pl.zamirski.jarvis.ui.components.GlassCard
import pl.zamirski.jarvis.ui.components.InfoText
import pl.zamirski.jarvis.ui.components.ScreenFrame
import pl.zamirski.jarvis.ui.components.SectionLabel
import pl.zamirski.jarvis.ui.theme.JColors

/**
 * Brak integracji z urządzeniami — pokazujemy kategorie jako „niepodłączone”,
 * bez przełączników udających sterowanie.
 */
@Composable
fun SmartHomeScreen(onBack: () -> Unit) {
    ScreenFrame(title = "Inteligentny dom", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Brak podłączonych urządzeń", color = JColors.Warning, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                InfoText(
                    "Jarvis nie jest jeszcze połączony z żadnym systemem domowym. " +
                        "Planowane: Home Assistant lub SmartThings przez backend Jarvisa (klucze tylko na serwerze).",
                )
            }
            DeviceRow(Icons.Filled.Lightbulb, "Światła")
            DeviceRow(Icons.Filled.Thermostat, "Temperatura")
            DeviceRow(Icons.Filled.Shield, "Ochrona")
            DeviceRow(Icons.Filled.MusicNote, "Muzyka")
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String) {
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = JColors.TextDim)
            Spacer(Modifier.width(12.dp))
            Text(name, color = JColors.TextDim, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Badge("niepodłączone", JColors.TextDim)
        }
    }
}

@Composable
fun ComputerScreen(vm: JarvisViewModel, onBack: () -> Unit) {
    val notes by vm.store.notes.collectAsState()
    val tasks by vm.store.tasks.collectAsState()
    val reminders by vm.store.reminders.collectAsState()
    ScreenFrame(title = "Komputer", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Panel Windows", color = JColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Badge("planowane")
                }
                Spacer(Modifier.height(6.dp))
                InfoText(
                    "Sterowanie komputerem i synchronizacja z panelem Windows nie są jeszcze zaimplementowane. " +
                        "Telefon nie łączy się teraz z żadnym komputerem.",
                )
            }
            SectionLabel("Jak to będzie działać")
            GlassCard(Modifier.fillMaxWidth()) {
                InfoText(
                    "1. Telefon i panel Windows łączą się z tym samym backendem Jarvisa (ten sam token).\n" +
                        "2. Notatki, zadania i przypomnienia trafiają na serwer w jednym formacie.\n" +
                        "3. Polecenia dla komputera idą przez backend — telefon nigdy nie łączy się z PC bezpośrednio.",
                )
            }
            SectionLabel("Dane gotowe do synchronizacji (na telefonie)")
            GlassCard(Modifier.fillMaxWidth()) {
                InfoText("Notatki: ${notes.size}   ·   Zadania: ${tasks.size}   ·   Przypomnienia: ${reminders.size}", JColors.Text)
            }
        }
    }
}
