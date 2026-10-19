package pl.zamirski.jarvis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.zamirski.jarvis.core.WeatherIcon
import pl.zamirski.jarvis.ui.theme.JColors

val CardShape = RoundedCornerShape(18.dp)

/** Tło aplikacji: głęboka czerń z delikatną niebieską poświatą u góry. */
@Composable
fun JarvisBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(JColors.BgTop, JColors.Bg, JColors.Bg))),
    ) { content() }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    var m = modifier
        .clip(CardShape)
        .background(JColors.Card)
        .border(1.dp, JColors.CardBorder, CardShape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

/** Ekran z nagłówkiem (strzałka wstecz + tytuł). */
@Composable
fun ScreenFrame(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    JarvisBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Wstecz", tint = JColors.Accent)
                }
                Text(
                    title,
                    color = JColors.Text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                actions()
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                content = content,
            )
        }
    }
}

@Composable
fun FeatureRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = tint) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = JColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Badge(badge)
                    }
                }
                Text(subtitle, color = JColors.TextDim, fontSize = 13.sp)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = JColors.TextDim)
        }
    }
}

@Composable
fun Badge(text: String, color: Color = JColors.Warning) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = JColors.TextDim,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun InfoText(text: String, color: Color = JColors.TextDim) {
    Text(text, color = color, fontSize = 14.sp, lineHeight = 20.sp)
}

fun weatherIconVector(icon: WeatherIcon): Pair<ImageVector, Color> = when (icon) {
    WeatherIcon.CLEAR -> Icons.Filled.WbSunny to JColors.Orange
    WeatherIcon.PARTLY -> Icons.Filled.WbCloudy to Color(0xFFFFD27A)
    WeatherIcon.CLOUDY -> Icons.Filled.Cloud to Color(0xFFB9C8DA)
    WeatherIcon.FOG -> Icons.Filled.Dehaze to Color(0xFFB9C8DA)
    WeatherIcon.DRIZZLE -> Icons.Filled.Grain to JColors.AccentBright
    WeatherIcon.RAIN -> Icons.Filled.Umbrella to JColors.Accent
    WeatherIcon.SNOW -> Icons.Filled.AcUnit to Color.White
    WeatherIcon.STORM -> Icons.Filled.FlashOn to JColors.Warning
    WeatherIcon.UNKNOWN -> Icons.Filled.HelpOutline to JColors.TextDim
}

@Composable
fun WeatherGlyph(icon: WeatherIcon, size: Dp) {
    val (vector, tint) = weatherIconVector(icon)
    Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
fun EmptyState(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = JColors.TextDim, fontSize = 14.sp)
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.padding(top = h))

@Composable
fun RowGap() = Spacer(Modifier.width(8.dp))

val ListGap = Arrangement.spacedBy(10.dp)
