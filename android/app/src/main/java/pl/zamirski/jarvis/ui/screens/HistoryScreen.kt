package pl.zamirski.jarvis.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.zamirski.jarvis.core.Conversation
import pl.zamirski.jarvis.core.Kind
import pl.zamirski.jarvis.ui.JarvisViewModel
import pl.zamirski.jarvis.ui.components.EmptyState
import pl.zamirski.jarvis.ui.components.GlassCard
import pl.zamirski.jarvis.ui.components.InfoText
import pl.zamirski.jarvis.ui.components.ScreenFrame
import pl.zamirski.jarvis.ui.theme.JColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PL = Locale("pl", "PL")

@Composable
fun HistoryScreen(vm: JarvisViewModel, onBack: () -> Unit) {
    val archive by vm.history.archive.collectAsState()
    HistoryContent(
        conversations = archive,
        onDelete = vm::deleteFromHistory,
        onDeleteAll = vm::deleteAllHistory,
        onBack = onBack,
    )
}

/** Ekran historii bez zależności od ViewModelu (łatwy do podglądu i testów zrzutów ekranu). */
@Composable
fun HistoryContent(
    conversations: List<Conversation>,
    onDelete: (Long) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
    initiallyOpenId: Long? = null,
) {
    var openId by remember { mutableStateOf(initiallyOpenId) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }
    val open = conversations.firstOrNull { it.id == openId }

    if (open != null) {
        BackHandler { openId = null }
        ConversationDetail(open, onBack = { openId = null }, onDelete = { confirmDeleteId = open.id })
    } else {
        ScreenFrame(
            title = "Historia rozmów",
            onBack = onBack,
            actions = {
                if (conversations.isNotEmpty()) {
                    IconButton(onClick = { confirmDeleteAll = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Usuń całą historię", tint = JColors.TextDim)
                    }
                }
            },
        ) {
            if (conversations.isEmpty()) {
                EmptyState("Brak zapisanych rozmów.")
                InfoText(
                    "Rozmowa trafia tutaj, gdy w ekranie Rozmowa naciśniesz „Nowa rozmowa”. " +
                        "Historia jest zapisana tylko na tym telefonie (maks. 50 rozmów, bez zdjęć).",
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(conversations, key = { it.id }) { c ->
                        ConversationRow(c) { openId = c.id }
                    }
                }
            }
        }
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "Usunąć całą historię?",
            text = "Wszystkie zapisane rozmowy zostaną trwale usunięte z telefonu.",
            onConfirm = {
                confirmDeleteAll = false
                onDeleteAll()
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
    confirmDeleteId?.let { id ->
        ConfirmDialog(
            title = "Usunąć tę rozmowę?",
            text = "Rozmowa zostanie trwale usunięta z telefonu.",
            onConfirm = {
                confirmDeleteId = null
                openId = null
                onDelete(id)
            },
            onDismiss = { confirmDeleteId = null },
        )
    }
}

@Composable
private fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    val replies = c.messages.count { it.kind == Kind.ASSISTANT }
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Text(c.title, color = JColors.Text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${formatDate(c.updatedAt)} · ${c.messages.size} wiad. · odpowiedzi Jarvisa: $replies",
            color = JColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ConversationDetail(c: Conversation, onBack: () -> Unit, onDelete: () -> Unit) {
    ScreenFrame(
        title = formatDate(c.startedAt),
        onBack = onBack,
        actions = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Usuń rozmowę", tint = JColors.TextDim)
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(c.messages) { MessageBubble(it) }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        InfoText("Zapis tylko do odczytu. Zdjęcia nie są przechowywane.")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Usuń") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private fun formatDate(millis: Long): String =
    if (millis <= 0) "Brak danych" else SimpleDateFormat("d MMM yyyy, HH:mm", PL).format(Date(millis))
