package pl.zamirski.jarvis.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import pl.zamirski.jarvis.ui.Routes
import pl.zamirski.jarvis.ui.components.FeatureRow
import pl.zamirski.jarvis.ui.components.ListGap
import pl.zamirski.jarvis.ui.components.ScreenFrame
import pl.zamirski.jarvis.ui.theme.JColors

private data class FeatureEntry(
    val route: String,
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val subtitle: String,
    val badge: String? = null,
)

private val entries = listOf(
    FeatureEntry(Routes.CHAT, Icons.Filled.Forum, JColors.Accent, "Rozmowa", "Głos i tekst z AI"),
    FeatureEntry(Routes.WEATHER, Icons.Filled.WbSunny, JColors.Orange, "Pogoda", "Aktualne informacje i prognoza"),
    FeatureEntry(Routes.CALENDAR, Icons.Filled.DateRange, Color(0xFF8C7BFF), "Kalendarz", "Wydarzenia z kalendarza telefonu"),
    FeatureEntry(Routes.REMINDERS, Icons.Filled.Notifications, Color(0xFFFF7AA8), "Przypomnienia", "Ustaw przypomnienia"),
    FeatureEntry(Routes.NOTES, Icons.Filled.Description, JColors.Warning, "Notatki", "Szybkie notatki głosowe i tekstowe"),
    FeatureEntry(Routes.TASKS, Icons.Filled.CheckBox, JColors.Success, "Zadania", "Lista zadań"),
    FeatureEntry(Routes.SMART_HOME, Icons.Filled.Home, Color(0xFF4FD1C5), "Inteligentny dom", "Sterowanie urządzeniami", "niepodłączone"),
    FeatureEntry(Routes.COMPUTER, Icons.Filled.Computer, JColors.AccentBright, "Komputer", "Panel Windows i synchronizacja", "planowane"),
    FeatureEntry(Routes.SETTINGS, Icons.Filled.Settings, JColors.TextDim, "Ustawienia", "Backend AI, głos, pogoda, uprawnienia"),
)

@Composable
fun FeaturesScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    ScreenFrame(title = "Funkcje", onBack = onBack) {
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = ListGap) {
            items(entries) { e ->
                FeatureRow(e.icon, e.tint, e.title, e.subtitle, e.badge) { navigate(e.route) }
            }
        }
    }
}
