package com.hertz.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object HertzColors {
    val Bg = Color(0xFF070B12)
    val Surface = Color(0xFF0F1622)
    val SurfaceHigh = Color(0xFF161F2E)
    val Line = Color(0xFF1E2A3B)
    val Cyan = Color(0xFF22D3EE)
    val Blue = Color(0xFF3B82F6)
    val Ink = Color(0xFFE6EDF5)
    val Muted = Color(0xFF8A97A8)
    val Good = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)
}

private val scheme = darkColorScheme(
    primary = HertzColors.Cyan,
    onPrimary = Color(0xFF04131A),
    secondary = HertzColors.Blue,
    background = HertzColors.Bg,
    onBackground = HertzColors.Ink,
    surface = HertzColors.Surface,
    onSurface = HertzColors.Ink,
    surfaceVariant = HertzColors.SurfaceHigh,
    onSurfaceVariant = HertzColors.Muted,
    surfaceContainer = HertzColors.Surface,
    surfaceContainerHigh = HertzColors.SurfaceHigh,
    outline = HertzColors.Line,
    error = HertzColors.Bad,
)

val Mono = FontFamily.Monospace

@Composable
fun HertzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
            labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.8.sp),
        ),
        content = content,
    )
}

/** The Hertz mark drawn in code: a split ring around an H whose crossbar is a sine wave. */
@Composable
fun HertzLogo(size: Dp, modifier: Modifier = Modifier, glow: Boolean = true) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 66f // design grid is 66 units
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        val c = p(33f, 33f)
        if (glow) drawCircle(HertzColors.Cyan.copy(alpha = 0.10f), radius = 33f * s, center = c)
        drawCircle(Color(0xFF1E3A5F), radius = 30f * s, center = c, style = Stroke(1.5f * s))
        drawArc(HertzColors.Cyan, -90f, 90f, false, topLeft = p(7f, 7f),
            size = androidx.compose.ui.geometry.Size(52f * s, 52f * s), style = Stroke(3.5f * s, cap = StrokeCap.Round))
        drawArc(HertzColors.Blue, 90f, 90f, false, topLeft = p(7f, 7f),
            size = androidx.compose.ui.geometry.Size(52f * s, 52f * s), style = Stroke(3.5f * s, cap = StrokeCap.Round))
        val bar = Color(0xFFE6F7FB)
        drawLine(bar, p(21f, 19f), p(21f, 47f), 5f * s, StrokeCap.Round)
        drawLine(bar, p(45f, 19f), p(45f, 47f), 5f * s, StrokeCap.Round)
        val wave = Path().apply {
            moveTo(21f * s, 33f * s)
            cubicTo(24f * s, 23f * s, 28f * s, 23f * s, 33f * s, 33f * s)
            cubicTo(38f * s, 43f * s, 42f * s, 43f * s, 45f * s, 33f * s)
        }
        drawPath(wave, HertzColors.Cyan, style = Stroke(4f * s, cap = StrokeCap.Round))
    }
}
