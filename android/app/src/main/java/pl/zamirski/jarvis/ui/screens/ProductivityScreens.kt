package pl.zamirski.jarvis.ui.screens

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.zamirski.jarvis.data.ReminderScheduler
import pl.zamirski.jarvis.ui.AppActions
import pl.zamirski.jarvis.ui.JarvisViewModel
import pl.zamirski.jarvis.ui.components.EmptyState
import pl.zamirski.jarvis.ui.components.GlassCard
import pl.zamirski.jarvis.ui.components.InfoText
import pl.zamirski.jarvis.ui.components.ScreenFrame
import pl.zamirski.jarvis.ui.theme.JColors
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PL = Locale("pl", "PL")
private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", PL)

private fun formatMillis(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTimeFmt)

/** Dyktowanie tekstu systemowym oknem rozpoznawania mowy Google (pl-PL). */
@Composable
private fun rememberDictation(onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
        }
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Mów…")
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "Brak usługi rozpoznawania mowy", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun AddRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDictate: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
        )
        IconButton(onClick = onDictate) { Icon(Icons.Filled.Mic, contentDescription = "Dyktuj", tint = JColors.Accent) }
        IconButton(onClick = onAdd, enabled = value.isNotBlank()) {
            Icon(Icons.Filled.Add, contentDescription = "Dodaj", tint = if (value.isNotBlank()) JColors.Accent else JColors.TextDim)
        }
    }
}

// ---------------------------------------------------------------- Notatki

@Composable
fun NotesScreen(vm: JarvisViewModel, actions: AppActions, onBack: () -> Unit) {
    val notes by vm.store.notes.collectAsState()
    var text by remember { mutableStateOf("") }
    val dictate = rememberDictation { spoken -> text = if (text.isBlank()) spoken else "$text $spoken" }

    ScreenFrame(title = "Notatki", onBack = onBack) {
        AddRow(text, { text = it }, "Nowa notatka…", dictate) {
            vm.store.addNote(text)
            text = ""
        }
        Spacer(Modifier.height(12.dp))
        if (notes.isEmpty()) EmptyState("Nie masz jeszcze notatek. Napisz albo podyktuj pierwszą.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(notes, key = { it.id }) { n ->
                GlassCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(n.text, color = JColors.Text, fontSize = 15.sp)
                            Text(formatMillis(n.createdAt), color = JColors.TextDim, fontSize = 12.sp)
                        }
                        IconButton(onClick = { vm.store.deleteNote(n.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Usuń", tint = JColors.TextDim)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Zadania

@Composable
fun TasksScreen(vm: JarvisViewModel, onBack: () -> Unit) {
    val tasks by vm.store.tasks.collectAsState()
    var text by remember { mutableStateOf("") }
    var showDone by remember { mutableStateOf(false) }
    val dictate = rememberDictation { spoken -> text = spoken }

    ScreenFrame(title = "Zadania", onBack = onBack) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip("Aktywne (${tasks.count { !it.done }})", !showDone, Modifier.weight(1f)) { showDone = false }
            TabChip("Zakończone (${tasks.count { it.done }})", showDone, Modifier.weight(1f)) { showDone = true }
        }
        Spacer(Modifier.height(12.dp))
        if (!showDone) {
            AddRow(text, { text = it }, "Nowe zadanie…", dictate) {
                vm.store.addTask(text)
                text = ""
            }
            Spacer(Modifier.height(12.dp))
        }
        val visible = tasks.filter { it.done == showDone }
        if (visible.isEmpty()) EmptyState(if (showDone) "Brak zakończonych zadań." else "Brak aktywnych zadań.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { t ->
                GlassCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.store.toggleTask(t.id) }) {
                            Icon(
                                if (t.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = if (t.done) "Przywróć" else "Oznacz jako zrobione",
                                tint = if (t.done) JColors.Success else JColors.Accent,
                            )
                        }
                        Text(
                            t.title,
                            color = if (t.done) JColors.TextDim else JColors.Text,
                            fontSize = 15.sp,
                            textDecoration = if (t.done) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.store.deleteTask(t.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Usuń", tint = JColors.TextDim)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        color = if (selected) JColors.Text else JColors.TextDim,
        fontSize = 14.sp,
        modifier = modifier
            .clip(shape)
            .background(if (selected) JColors.AccentDeep else JColors.Card)
            .border(1.dp, JColors.CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
    )
}

// ---------------------------------------------------------------- Przypomnienia

@Composable
fun RemindersScreen(vm: JarvisViewModel, actions: AppActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val reminders by vm.store.reminders.collectAsState()
    var text by remember { mutableStateOf("") }
    var at by remember { mutableStateOf<LocalDateTime?>(null) }
    val dictate = rememberDictation { spoken -> text = spoken }

    fun pickDateTime() {
        val base = at ?: LocalDateTime.now().plusMinutes(30)
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                at = LocalDateTime.of(y, m + 1, d, h, min)
            }, base.hour, base.minute, true).show()
        }, base.year, base.monthValue - 1, base.dayOfMonth).show()
    }

    fun save() {
        val time = at ?: return
        val millis = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (millis <= System.currentTimeMillis()) {
            vm.notice = "Wybierz godzinę w przyszłości."
            return
        }
        val doSave = {
            val r = vm.store.addReminder(text, millis)
            ReminderScheduler.schedule(context, r)
            text = ""
            at = null
        }
        if (Build.VERSION.SDK_INT >= 33) {
            actions.requestPermission(Manifest.permission.POST_NOTIFICATIONS) { granted ->
                if (!granted) vm.notice = "Bez zgody na powiadomienia przypomnienie nie wyświetli się."
                doSave()
            }
        } else {
            doSave()
        }
    }

    ScreenFrame(title = "Przypomnienia", onBack = onBack) {
        GlassCard(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("O czym przypomnieć?") },
                trailingIcon = {
                    IconButton(onClick = dictate) { Icon(Icons.Filled.Mic, contentDescription = "Dyktuj", tint = JColors.Accent) }
                },
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDateTime() }) {
                    Icon(Icons.Filled.Schedule, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(at?.format(dateTimeFmt) ?: "Wybierz termin")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { save() }, enabled = text.isNotBlank() && at != null) { Text("Ustaw") }
            }
            Spacer(Modifier.height(8.dp))
            InfoText("Powiadomienie może pojawić się z kilkuminutowym opóźnieniem (oszczędzanie baterii Androida).")
        }
        Spacer(Modifier.height(12.dp))
        if (reminders.isEmpty()) EmptyState("Brak zaplanowanych przypomnień.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(reminders, key = { it.id }) { r ->
                GlassCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.text, color = JColors.Text, fontSize = 15.sp)
                            Text(formatMillis(r.atMillis), color = JColors.Accent, fontSize = 13.sp)
                        }
                        IconButton(onClick = {
                            ReminderScheduler.cancel(context, r.id)
                            vm.store.deleteReminder(r.id)
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Usuń", tint = JColors.TextDim) }
                    }
                }
            }
        }
    }
}
