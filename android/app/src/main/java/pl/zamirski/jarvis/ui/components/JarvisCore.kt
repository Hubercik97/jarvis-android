package pl.zamirski.jarvis.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import pl.zamirski.jarvis.ui.theme.JColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Centralny rdzeń AI: metaliczna kula z segmentami i pomarańczowymi akcentami,
 * obracające się pierścienie, pulsujące jądro i świetlna platforma pod spodem.
 * [energy] 0..1 — poziom głośności mowy / aktywność (rozjaśnia i powiększa jądro).
 */
@Composable
fun JarvisCore(modifier: Modifier = Modifier, energy: Float = 0f, active: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "core")
    val rotSlow by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "rotSlow",
    )
    val rotFast by transition.animateFloat(
        360f, 0f, infiniteRepeatable(tween(11_000, easing = LinearEasing)), label = "rotFast",
    )
    val pulse by transition.animateFloat(
        0.9f, 1.1f, infiniteRepeatable(tween(1_700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val wave by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "wave",
    )
    val e by animateFloatAsState(
        targetValue = (energy.coerceIn(0f, 1f) * 0.8f + if (active) 0.35f else 0f).coerceAtMost(1f),
        animationSpec = tween(180),
        label = "energy",
    )

    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height * 0.44f)
        val r = minOf(size.width, size.height) * 0.30f

        drawPlatform(c, r, wave, e)

        // Poświata wokół kuli
        drawCircle(
            brush = Brush.radialGradient(
                listOf(JColors.Accent.copy(alpha = 0.30f + 0.35f * e), JColors.AccentDeep.copy(alpha = 0.10f), Color.Transparent),
                center = c, radius = r * 2.0f,
            ),
            radius = r * 2.0f, center = c,
        )

        // Zewnętrzna skala z kreskami
        rotate(-rotSlow * 0.5f, c) {
            val ticks = 72
            for (i in 0 until ticks) {
                val a = Math.toRadians(i * 360.0 / ticks)
                val long = i % 6 == 0
                val r1 = r * 1.22f
                val r2 = r * (if (long) 1.32f else 1.27f)
                drawLine(
                    color = JColors.Accent.copy(alpha = if (long) 0.8f else 0.35f),
                    start = Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                    end = Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()),
                    strokeWidth = if (long) 2.2f else 1.2f,
                )
            }
        }
        drawCircle(JColors.Accent.copy(alpha = 0.55f), radius = r * 1.12f, center = c, style = Stroke(1.6f))

        // Ciało kuli — metal
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFF1C3A5E), Color(0xFF0B1A2E), Color(0xFF03080F)),
                center = Offset(c.x - r * 0.3f, c.y - r * 0.35f), radius = r * 1.4f,
            ),
            radius = r, center = c,
        )

        // Metaliczne płyty (segmenty) + pomarańczowe akcenty
        val plate = r * 0.16f
        val plateR = r * 0.88f
        rotate(rotSlow, c) {
            for (k in 0 until 14) {
                drawArc(
                    color = JColors.Steel,
                    startAngle = k * (360f / 14) + 2f,
                    sweepAngle = 360f / 14 - 5f,
                    useCenter = false,
                    topLeft = Offset(c.x - plateR, c.y - plateR),
                    size = Size(plateR * 2, plateR * 2),
                    style = Stroke(plate),
                )
            }
            listOf(1, 6, 10).forEach { k ->
                drawArc(
                    color = JColors.Orange.copy(alpha = 0.9f),
                    startAngle = k * (360f / 14) + 6f,
                    sweepAngle = 9f,
                    useCenter = false,
                    topLeft = Offset(c.x - plateR, c.y - plateR),
                    size = Size(plateR * 2, plateR * 2),
                    style = Stroke(plate * 0.45f, cap = StrokeCap.Round),
                )
            }
        }

        // Pierścień przerywany (obrót przeciwny)
        rotate(rotFast, c) {
            drawCircle(
                color = JColors.AccentBright.copy(alpha = 0.75f),
                radius = r * 0.66f, center = c,
                style = Stroke(2.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
            for (k in 0 until 3) {
                drawArc(
                    color = JColors.Accent,
                    startAngle = k * 120f, sweepAngle = 70f, useCenter = false,
                    topLeft = Offset(c.x - r * 0.55f, c.y - r * 0.55f),
                    size = Size(r * 1.1f, r * 1.1f),
                    style = Stroke(4f, cap = StrokeCap.Round),
                )
            }
        }

        // Celownik
        val ch = JColors.AccentBright.copy(alpha = 0.25f)
        drawLine(ch, Offset(c.x - r * 1.5f, c.y), Offset(c.x + r * 1.5f, c.y), 1f)
        drawLine(ch, Offset(c.x, c.y - r * 1.5f), Offset(c.x, c.y + r * 1.5f), 1f)

        // Jądro
        val coreR = r * 0.36f * pulse * (1f + 0.35f * e)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White, JColors.AccentBright, JColors.Accent.copy(alpha = 0.6f), Color.Transparent),
                center = c, radius = coreR * 1.6f,
            ),
            radius = coreR * 1.6f, center = c,
        )
        drawCircle(Color.White.copy(alpha = 0.9f), radius = coreR * 0.25f, center = c)
    }
}

/** Świetlna platforma z rozchodzącymi się elipsami pod kulą. */
private fun DrawScope.drawPlatform(c: Offset, r: Float, wave: Float, e: Float) {
    val baseY = c.y + r * 1.45f
    for (i in 0 until 4) {
        val grow = ((i + wave) / 4f)
        val w = r * (1.0f + grow * 1.9f)
        val h = w * 0.16f
        drawOval(
            color = JColors.Accent.copy(alpha = (0.55f * (1f - grow)).coerceAtLeast(0f)),
            topLeft = Offset(c.x - w, baseY - h),
            size = Size(w * 2, h * 2),
            style = Stroke(2f),
        )
    }
    drawOval(
        brush = Brush.radialGradient(
            listOf(JColors.AccentBright.copy(alpha = 0.55f + 0.3f * e), Color.Transparent),
            center = Offset(c.x, baseY), radius = r * 0.9f,
        ),
        topLeft = Offset(c.x - r * 0.9f, baseY - r * 0.12f),
        size = Size(r * 1.8f, r * 0.24f),
    )
}
