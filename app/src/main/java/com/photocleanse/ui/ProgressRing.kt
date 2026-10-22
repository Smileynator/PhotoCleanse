package com.photocleanse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Share of photos already reviewed, 0..1. An empty library counts as 0, never NaN. */
fun progressFraction(done: Int, remaining: Int): Float {
    val total = done.toLong() + remaining
    return if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Whole-number percentage that only reads 100% when really nothing is left (no rounding up to 100). */
fun progressPercent(done: Int, remaining: Int): Int {
    val total = done.toLong() + remaining
    if (total <= 0) return 0
    if (remaining <= 0) return 100
    return ((done * 100L) / total).toInt().coerceAtMost(99)
}

private val RingColors = listOf(Color(0xFF14B8A6), Color(0xFF4F46E5), Color(0xFF14B8A6))

/** Filling circle in the app-icon colours, with the percentage in the middle. */
@Composable
fun ProgressRing(done: Int, remaining: Int, modifier: Modifier = Modifier, diameter: Dp = 112.dp) {
    val target = progressFraction(done, remaining)
    val sweep by animateFloatAsState(target, tween(700), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
            if (sweep > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(RingColors, center),
                    startAngle = -90f, sweepAngle = 360f * sweep, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize, style = stroke,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${progressPercent(done, remaining)}%", style = MaterialTheme.typography.headlineSmall)
            Text("cleaned", style = MaterialTheme.typography.labelSmall)
        }
    }
}
