package pl.zamirski.jarvis.ui.components

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Perspective-projected 3D particles. No video, bitmap, WebView or local AI model. */
@Composable
fun JarvisCore(
    modifier: Modifier = Modifier,
    energy: Float = 0f,
    active: Boolean = false,
    mode: String = if (active) "THINKING" else "IDLE",
) {
    val context = LocalContext.current
    val lowRam = remember { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice }
    val count = if (lowRam) 220 else 480
    val vertices = remember(count) {
        List(count) { i ->
            val y = 1f - 2f * ((i * .754877666 + .5 / count) % 1.0).toFloat()
            val r = sqrt(1f - y * y)
            val angle = i * PI * (3.0 - sqrt(5.0))
            floatArrayOf((cos(angle) * r).toFloat(), y, (sin(angle) * r).toFloat())
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(lifecycle, lowRam) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (previous == 0L) previous = now
                    if (now - previous > if (lowRam) 50_000_000L else 33_000_000L) {
                        phase += ((now - previous) / 1_000_000_000f).coerceAtMost(.1f)
                        previous = now
                    }
                }
            }
        }
    }
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height * .43f)
        val radius = minOf(size.width, size.height) * .29f
        // energy is real microphone RMS or the Visualizer of Jarvis's own MediaPlayer.
        val level = if (mode == "LISTENING" || mode == "SPEAKING") energy.coerceIn(0f, 1f) else 0f
        val rotation = phase * if (mode == "THINKING") .7f else .12f
        drawCircle(Brush.radialGradient(listOf(Color(0x183397FF), Color.Transparent), center, radius * 1.8f), radius * 1.8f, center)
        val dots = vertices.map { p ->
            val x = p[0] * cos(rotation) + p[2] * sin(rotation)
            val z = -p[0] * sin(rotation) + p[2] * cos(rotation)
            floatArrayOf(x, p[1], z)
        }.sortedBy { it[2] }
        for (p in dots) {
            val perspective = 3.8f / (3.8f - p[2])
            val scale = 1f + level * .18f + sin(phase * .7f) * .008f
            val point = Offset(center.x + p[0] * radius * perspective * scale, center.y + p[1] * radius * perspective * scale)
            val depth = ((p[2] + 1f) / 2f).coerceIn(0f, 1f)
            drawCircle(Color(0xFF167FFF).copy(alpha = .18f + .2f * depth), radius = (2.6f + level * 2f) * perspective, center = point)
            drawCircle(Color(0xFFC0EEFF).copy(alpha = .35f + .6f * depth), radius = (1.0f + depth) * perspective, center = point)
        }
        for (ring in 0..3) {
            val r = radius * (1.24f + ring * .15f)
            drawCircle(Color(0xFF137FDF).copy(alpha = .23f), r, center, style = Stroke(1f))
            for (segment in 0..2) {
                drawArc(Color(0xFF45B6FF).copy(alpha = .8f), rotation * -20f + segment * 120f + ring * 23f, 25f + ring * 6f, false,
                    Offset(center.x-r, center.y-r), Size(r*2,r*2), style=Stroke(2f))
            }
        }
        for (tick in 0 until 96) {
            val a = tick * PI / 48
            val inner = radius * 1.77f
            val outer = inner + if (tick % 8 == 0) 5f else 2f
            drawLine(Color(0xFF2B8EDD).copy(alpha = .5f), Offset(center.x+cos(a).toFloat()*inner, center.y+sin(a).toFloat()*inner), Offset(center.x+cos(a).toFloat()*outer, center.y+sin(a).toFloat()*outer), 1f)
        }
        for (ring in 0..3) {
            val w = radius * (1.15f + ring*.16f)
            val h = radius * (.09f+ring*.015f)
            val y = center.y + radius * 1.65f
            drawOval(Color(0xFF1A9BFF).copy(alpha=.55f-ring*.1f), Offset(center.x-w,y-h), Size(w*2,h*2), style=Stroke(1.5f))
        }
    }
}
