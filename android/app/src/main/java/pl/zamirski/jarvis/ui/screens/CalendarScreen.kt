package pl.zamirski.jarvis.ui.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.zamirski.jarvis.data.CalendarEvent
import pl.zamirski.jarvis.ui.AppActions
import pl.zamirski.jarvis.ui.JarvisViewModel
import pl.zamirski.jarvis.ui.components.EmptyState
import pl.zamirski.jarvis.ui.components.GlassCard
import pl.zamirski.jarvis.ui.components.InfoText
import pl.zamirski.jarvis.ui.components.ScreenFrame
import pl.zamirski.jarvis.ui.components.SectionLabel
import pl.zamirski.jarvis.ui.theme.JColors
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PL = Locale("pl", "PL")

@Composable
fun CalendarScreen(vm: JarvisViewModel, actions: AppActions, onBack: () -> Unit) {
    var hasPermission by remember { mutableStateOf(vm.calendar.hasPermission()) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }

    LaunchedEffect(hasPermission, month) {
        if (hasPermission) events = vm.calendar.eventsBetween(month.atDay(1), month.plusMonths(1).atDay(1))
    }

    ScreenFrame(title = "Kalendarz", onBack = onBack) {
        if (!hasPermission) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Brak dostępu do kalendarza", color = JColors.Warning, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                InfoText("Jarvis pokaże wydarzenia z kalendarza telefonu (Google/Samsung). Dostęp jest tylko do odczytu.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    actions.requestPermission(Manifest.permission.READ_CALENDAR) { granted ->
                        hasPermission = granted
                        if (!granted) vm.notice = "Bez zgody kalendarz pozostanie pusty."
                    }
                }) { Text("Zezwól na odczyt kalendarza") }
            }
            return@ScreenFrame
        }

        val zone = ZoneId.systemDefault()
        fun dayOf(e: CalendarEvent): LocalDate =
            if (e.allDay) Instant.ofEpochMilli(e.begin).atZone(ZoneId.of("UTC")).toLocalDate()
            else Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate()
        val daysWithEvents = events.map { dayOf(it) }.toSet()

        Column(Modifier.verticalScroll(rememberScrollState())) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Poprzedni miesiąc", tint = JColors.Accent)
                    }
                    Text(
                        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", PL)).replaceFirstChar { it.titlecase(PL) },
                        color = JColors.Text, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Następny miesiąc", tint = JColors.Accent)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd").forEach {
                        Text(it, color = JColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
                val offset = month.atDay(1).dayOfWeek.value - 1
                val cells = offset + month.lengthOfMonth()
                val rows = (cells + 6) / 7
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val dayNum = row * 7 + col - offset + 1
                            Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (dayNum in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(dayNum)
                                    val isSel = date == selected
                                    val isToday = date == LocalDate.now()
                                    Column(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(if (isSel) JColors.AccentDeep else androidx.compose.ui.graphics.Color.Transparent)
                                            .clickable { selected = date }
                                            .padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            "$dayNum",
                                            color = if (isToday && !isSel) JColors.AccentBright else JColors.Text,
                                            fontSize = 14.sp,
                                        )
                                        Box(
                                            Modifier
                                                .padding(top = 2.dp)
                                                .width(4.dp).height(4.dp)
                                                .clip(CircleShape)
                                                .background(if (date in daysWithEvents) JColors.Orange else androidx.compose.ui.graphics.Color.Transparent),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            SectionLabel(selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", PL)))
            val dayEvents = events.filter { dayOf(it) == selected }
            if (dayEvents.isEmpty()) {
                EmptyState("Brak wydarzeń tego dnia.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dayEvents.forEach { e ->
                        GlassCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (e.allDay) "cały dzień"
                                    else Instant.ofEpochMilli(e.begin).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),
                                    color = JColors.Accent, fontSize = 14.sp, modifier = Modifier.width(76.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, color = JColors.Text, fontSize = 15.sp)
                                    if (e.location.isNotBlank()) Text(e.location, color = JColors.TextDim, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
