package pl.zamirski.jarvis.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import pl.zamirski.jarvis.core.ChatMessage
import pl.zamirski.jarvis.core.Conversation
import pl.zamirski.jarvis.core.Kind
import pl.zamirski.jarvis.ui.components.JarvisCore
import pl.zamirski.jarvis.ui.screens.HistoryContent
import pl.zamirski.jarvis.ui.theme.JColors
import pl.zamirski.jarvis.ui.theme.JarvisTheme

/**
 * Zrzuty ekranów do podglądu w CI (bez emulatora i bez telefonu).
 * Dane poniżej to wyłącznie przykładowe dane testowe do pokazania wyglądu ekranu.
 * Uruchamianie: gradle -Ppreview :app:recordPaparazziDebug  → app/src/test/snapshots/images/
 */
class ScreensPreviewTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.NoActionBar",
    )

    // 20 października 2026, 07:14 UTC — stała data, żeby zrzut był powtarzalny.
    private val base = 1_792_480_440_000L

    private val sample = listOf(
        Conversation(
            id = 3, startedAt = base, updatedAt = base + 600_000,
            messages = listOf(
                ChatMessage(Kind.USER, "[PRZYKŁAD] Jaka będzie jutro pogoda w Krakowie?"),
                ChatMessage(Kind.ASSISTANT, "[PRZYKŁAD] Tekst odpowiedzi Jarvisa pojawi się tutaj."),
            ),
        ),
        Conversation(
            id = 2, startedAt = base - 86_400_000, updatedAt = base - 86_000_000,
            messages = listOf(
                ChatMessage(Kind.USER, "[PRZYKŁAD] Opisz to zdjęcie", attachmentLabel = "Zdjęcie z kamery"),
                ChatMessage(Kind.ASSISTANT, "[PRZYKŁAD] Odpowiedź do zdjęcia."),
                ChatMessage(Kind.USER, "[PRZYKŁAD] Dziękuję, to wszystko"),
                ChatMessage(Kind.ASSISTANT, "W porządku, kończę rozmowę. Naciśnij mikrofon, gdy będziesz mnie potrzebować."),
            ),
        ),
        Conversation(
            id = 1, startedAt = base - 172_800_000, updatedAt = base - 172_700_000,
            messages = listOf(
                ChatMessage(Kind.USER, "[PRZYKŁAD] Która godzina?"),
                ChatMessage(Kind.NOTICE, "Brak adresu backendu AI. Otwórz Status i wpisz adres serwera (https://…)."),
            ),
        ),
    )

    @Test
    fun history_list() = paparazzi.snapshot {
        JarvisTheme { HistoryContent(sample, onDelete = {}, onDeleteAll = {}, onBack = {}) }
    }

    @Test
    fun history_empty() = paparazzi.snapshot {
        JarvisTheme { HistoryContent(emptyList(), onDelete = {}, onDeleteAll = {}, onBack = {}) }
    }

    @Test
    fun history_conversation() = paparazzi.snapshot {
        JarvisTheme { HistoryContent(sample, onDelete = {}, onDeleteAll = {}, onBack = {}, initiallyOpenId = 2) }
    }

    @Test
    fun ai_core() = paparazzi.snapshot {
        JarvisTheme {
            Box(Modifier.fillMaxSize().background(JColors.Bg)) {
                JarvisCore(Modifier.fillMaxWidth().height(420.dp), energy = 0.6f, active = true)
            }
        }
    }
}
