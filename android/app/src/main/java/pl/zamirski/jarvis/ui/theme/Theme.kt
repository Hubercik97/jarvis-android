package pl.zamirski.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object JColors {
    val Bg = Color(0xFF02060D)
    val BgTop = Color(0xFF071427)
    val Card = Color(0xCC0A1628)
    val CardSolid = Color(0xFF0A1628)
    val CardBorder = Color(0x5533A8FF)
    val Accent = Color(0xFF3DB4FF)
    val AccentBright = Color(0xFF7FD6FF)
    val AccentDeep = Color(0xFF0B5EC9)
    val Orange = Color(0xFFFF9A3D)
    val Text = Color(0xFFE7F3FF)
    val TextDim = Color(0xFF8AA4C2)
    val Success = Color(0xFF2EE6A0)
    val Warning = Color(0xFFFFC24D)
    val Error = Color(0xFFFF5D73)
    val Steel = Color(0xFF3B4A5F)
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JColors.Accent,
            onPrimary = Color(0xFF00121F),
            secondary = JColors.Orange,
            background = JColors.Bg,
            onBackground = JColors.Text,
            surface = JColors.CardSolid,
            onSurface = JColors.Text,
            surfaceVariant = JColors.CardSolid,
            onSurfaceVariant = JColors.TextDim,
            outline = JColors.CardBorder,
            error = JColors.Error,
        ),
        content = content,
    )
}
