package pl.zamirski.jarvis.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.zamirski.jarvis.data.WeatherState
import pl.zamirski.jarvis.ui.AppActions
import pl.zamirski.jarvis.ui.JarvisViewModel
import pl.zamirski.jarvis.ui.components.GlassCard
import pl.zamirski.jarvis.ui.components.InfoText
import pl.zamirski.jarvis.ui.components.ScreenFrame
import pl.zamirski.jarvis.ui.components.SectionLabel
import pl.zamirski.jarvis.ui.components.WeatherGlyph
import pl.zamirski.jarvis.ui.theme.JColors

@Composable
fun WeatherScreen(vm: JarvisViewModel, actions: AppActions, onBack: () -> Unit) {
    ScreenFrame(
        title = "Pogoda",
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.refreshWeather() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Odśwież", tint = JColors.Accent)
            }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (val w = vm.weather) {
                WeatherState.Idle, WeatherState.Loading -> Row(Modifier.padding(24.dp)) {
                    CircularProgressIndicator(color = JColors.Accent)
                }
                is WeatherState.Missing -> GlassCard(Modifier.fillMaxWidth()) {
                    Text("Brak danych", color = JColors.Warning, fontSize = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    InfoText(w.reason)
                    Spacer(Modifier.height(12.dp))
                    if (w.needsLocationPermission) {
                        Button(onClick = {
                            actions.requestPermission(Manifest.permission.ACCESS_COARSE_LOCATION) { granted ->
                                if (granted) vm.refreshWeather() else vm.notice = "Bez lokalizacji wpisz miasto w Ustawieniach."
                            }
                        }) { Text("Użyj mojej lokalizacji") }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(onClick = { vm.refreshWeather() }) { Text("Spróbuj ponownie") }
                }
                is WeatherState.Ready -> {
                    val r = w.report
                    GlassCard(Modifier.fillMaxWidth(), padding = 18.dp) {
                        Text(r.place, color = JColors.Text, fontSize = 20.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.temp}°C", color = JColors.Text, fontSize = 56.sp, fontWeight = FontWeight.Light)
                            Spacer(Modifier.weight(1f))
                            WeatherGlyph(r.icon, 72.dp)
                        }
                        Text(r.description, color = JColors.TextDim, fontSize = 15.sp)
                        Text("↑ ${r.todayMax}°   ↓ ${r.todayMin}°", color = JColors.TextDim, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Pomiar ${r.observedAt} · źródło: Open-Meteo", color = JColors.TextDim, fontSize = 11.sp)
                    }

                    SectionLabel("Najbliższe godziny")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            r.hours.forEach { h ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(h.label, color = JColors.TextDim, fontSize = 12.sp)
                                    Spacer(Modifier.height(4.dp))
                                    WeatherGlyph(h.icon, 24.dp)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${h.temp}°", color = JColors.Text, fontSize = 15.sp)
                                }
                            }
                        }
                    }

                    SectionLabel("Kolejne dni")
                    GlassCard(Modifier.fillMaxWidth()) {
                        r.days.forEach { d ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(d.dayLabel, color = JColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                WeatherGlyph(d.icon, 22.dp)
                                Spacer(Modifier.width(16.dp))
                                Text("${d.max}°", color = JColors.Text, fontSize = 15.sp, modifier = Modifier.width(40.dp))
                                Text("${d.min}°", color = JColors.TextDim, fontSize = 15.sp, modifier = Modifier.width(36.dp))
                            }
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                }
            }
        }
    }
}
