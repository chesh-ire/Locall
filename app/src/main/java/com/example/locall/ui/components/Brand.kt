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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.locall.ui.theme.Cyan
import com.example.locall.ui.theme.CyanBright
import com.example.locall.ui.theme.Magenta
import com.example.locall.ui.theme.SignalGreen
import com.example.locall.ui.theme.SignalRed
import com.example.locall.ui.theme.Violet
import com.example.locall.ui.theme.VioletBright

fun brandBrush(): Brush = Brush.linearGradient(listOf(VioletBright, Cyan))

/**
 * The Perf Lab mark: a green Erlenmeyer lab flask, drawn as a vector so it stays crisp at any size.
 */
@Composable
fun FlaskIcon(modifier: Modifier = Modifier, tint: Color = SignalGreen) {
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
        drawPath(liquid, color = tint.copy(alpha = 0.35f))
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

private fun recordingBrush(): Brush = Brush.linearGradient(listOf(SignalRed, Magenta))

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
                    .height(34.dp)
                    .width(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(brandBrush()),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall.copy(brush = brandBrush()),
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
        PillTone.Active -> Cyan
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
    leading: String? = null,
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
                    Text(leading, fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
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
        targetValue = if (state == MicState.Idle) 1.04f else 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "ringScale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "ringAlpha",
    )

    val fill: Brush = when (state) {
        MicState.Disabled -> Brush.linearGradient(
            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant),
        )
        MicState.Recording -> recordingBrush()
        else -> brandBrush()
    }
    val onColor = when (state) {
        MicState.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.White
    }
    val haloColor = if (state == MicState.Recording) SignalRed else CyanBright

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(260.dp),
    ) {
        if (state == MicState.Recording) {
            Box(
                Modifier
                    .size(176.dp)
                    .scale(ringScale)
                    .graphicsLayer { alpha = ringAlpha }
                    .clip(CircleShape)
                    .background(haloColor),
            )
        }
        if (state == MicState.Idle || state == MicState.Busy) {
            Box(
                Modifier
                    .size(210.dp)
                    .graphicsLayer { alpha = 0.18f }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(Violet, Color.Transparent)),
                    ),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(176.dp)
                .scale(breathe)
                .clip(CircleShape)
                .background(fill)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                            center = Offset(size.width * 0.3f, size.height * 0.25f),
                            radius = size.minDimension * 0.7f,
                        ),
                    )
                }
                .then(gestureModifier),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (state == MicState.Recording) "●" else "🎙️", fontSize = 34.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    color = onColor,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                )
            }
        }
    }
}

@Composable
fun BrandBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                // Violet aurora up top…
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Violet.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.06f),
                        radius = size.width * 0.95f,
                    ),
                )
                // …with a cool cyan wash drifting up from the lower corner.
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Cyan.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.92f),
                        radius = size.width * 0.8f,
                    ),
                )
            },
    ) { content() }
}

val ScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
