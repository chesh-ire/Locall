package com.example.locall.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.locall.ui.theme.SignalRed

/**
 * The brand fill: a restrained, same-hue indigo with just enough vertical shift to read as a solid,
 * premium surface — not a rainbow gradient.
 */
fun brandBrush(): Brush = Brush.verticalGradient(listOf(Color(0xFF5A6FD6), Color(0xFF42539E)))

/**
 * The Perf Lab mark: an Erlenmeyer lab flask, drawn as a vector so it stays crisp at any size.
 * Defaults to a muted on-surface tint rather than a neon fill.
 */
@Composable
fun FlaskIcon(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(Modifier.size(22.dp).then(modifier)) {
        val w = size.width
        val h = size.height
        fun px(f: Float) = w * f
        fun py(f: Float) = h * f
        val stroke = minOf(w, h) * 0.085f

        // Flask outline: narrow neck flaring into a rounded conical body.
        val body = Path().apply {
            moveTo(px(0.38f), py(0.12f))
            lineTo(px(0.38f), py(0.40f))
            lineTo(px(0.16f), py(0.80f))
            cubicTo(px(0.12f), py(0.90f), px(0.17f), py(0.93f), px(0.26f), py(0.93f))
            lineTo(px(0.74f), py(0.93f))
            cubicTo(px(0.83f), py(0.93f), px(0.88f), py(0.90f), px(0.84f), py(0.80f))
            lineTo(px(0.62f), py(0.40f))
            lineTo(px(0.62f), py(0.12f))
        }
        // Liquid pooled in the lower body.
        val liquidTop = 0.58f
        val liquid = Path().apply {
            moveTo(px(0.275f), py(liquidTop))
            lineTo(px(0.16f), py(0.80f))
            cubicTo(px(0.12f), py(0.90f), px(0.17f), py(0.93f), px(0.26f), py(0.93f))
            lineTo(px(0.74f), py(0.93f))
            cubicTo(px(0.83f), py(0.93f), px(0.88f), py(0.90f), px(0.84f), py(0.80f))
            lineTo(px(0.725f), py(liquidTop))
            close()
        }
        drawPath(liquid, color = tint.copy(alpha = 0.22f))
        drawPath(
            body,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Flask lip.
        drawLine(
            color = tint,
            start = Offset(px(0.33f), py(0.12f)),
            end = Offset(px(0.67f), py(0.12f)),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun BrandHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        } else {
            Box(
                Modifier
                    .height(26.dp)
                    .width(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

enum class PillTone { Neutral, Active, Warn }

@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.Neutral, modifier: Modifier = Modifier) {
    val dot = when (tone) {
        PillTone.Active -> MaterialTheme.colorScheme.secondary
        PillTone.Warn -> SignalRed
        PillTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

enum class MicState { Disabled, Idle, Recording, Busy }

@Composable
fun GlowMic(
    state: MicState,
    label: String,
    modifier: Modifier = Modifier,
    gestureModifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "mic")
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == MicState.Recording) 1.04f else 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    val fill: Brush = when (state) {
        MicState.Disabled -> Brush.linearGradient(
            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant),
        )
        MicState.Recording -> Brush.linearGradient(listOf(SignalRed, SignalRed))
        else -> brandBrush()
    }
    val onColor = when (state) {
        MicState.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.White
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(200.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(132.dp)
                .scale(if (state == MicState.Recording) breathe else 1f)
                .clip(CircleShape)
                .background(fill)
                .then(gestureModifier),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (state == MicState.Recording) Icons.Outlined.Stop else Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = onColor,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    label,
                    color = onColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
fun BrandBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                // A single, very faint wash near the top — presence without the neon aurora.
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(tint.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.02f),
                        radius = size.width * 1.1f,
                    ),
                )
            },
    ) { content() }
}

val ScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
