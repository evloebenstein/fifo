package com.fifo.voicepipeline.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fifo.voicepipeline.pipeline.PipelineState

/**
 * Ilustración vectorial nativa de la cara amigable de "Fifo".
 * Reacciona dinámicamente al estado del pipeline de voz:
 * - LISTENING: Ondas sonoras activas a los lados y ojos atentos.
 * - SPEAKING: Boca animada abriendo y cerrando en tiempo real y ojos felices (^ ^).
 * - PROCESSING: Ojos pensativos y animación suave.
 * - IDLE: Parpadeo periódico y sonrisa amigable.
 */
@Composable
fun FifoFace(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    state: PipelineState = PipelineState.IDLE,
    isDarkTheme: Boolean = true,
    rmsLevel: Float = 0f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fifo_anim")

    // Pulso suave de respiración adaptado al estado (1.0f estático si no habla/escucha)
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.00f,
        targetValue = when (state) {
            PipelineState.SPEAKING -> 1.03f
            PipelineState.LISTENING -> 1.02f
            else -> 1.00f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 900,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Parpadeo orgánico natural (ocurre cada ~4 segundos suavemente)
    val blinkFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4200
                0.0f at 0
                0.0f at 3700
                1.0f at 3830 // Ojos cerrados por ~130ms
                0.0f at 3960 // Ojos reabiertos
                0.0f at 4200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "blink"
    )

    // Aleteo rítmico de boca al hablar (modulado por la voz)
    val speakingMouthFlap by infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(130, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speak_flap"
    )

    // Ondas expansivas de escucha
    val listeningWaveRadius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "listen_waves"
    )

    // Movimiento pensativo de ojos cuando procesa
    val eyeOffsetX by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = if (state == PipelineState.PROCESSING) 5f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eye_look_x"
    )

    val eyeOffsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (state == PipelineState.PROCESSING) -3f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eye_look_y"
    )

    // Brillo cálido de mejillas
    val blushAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = if (state == PipelineState.SPEAKING || state == PipelineState.LISTENING) 0.85f else 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blush_alpha"
    )

    // Apertura reactiva de boca modulada por RMS
    val clampedRms = rmsLevel.coerceIn(0f, 1f)
    val mouthOpenAmount = when (state) {
        PipelineState.SPEAKING -> {
            if (clampedRms > 0.04f) {
                (speakingMouthFlap * 0.45f + clampedRms * 0.55f).coerceIn(0.20f, 1.0f)
            } else {
                speakingMouthFlap
            }
        }
        PipelineState.LISTENING -> (0.15f + (clampedRms * 0.80f)).coerceIn(0.12f, 0.75f)
        PipelineState.PROCESSING -> 0.12f
        PipelineState.SLEEPING -> 0f
        else -> 0.18f
    }

    // Colores según el tema de alta visibilidad
    val strokeColor = if (isDarkTheme) Color(0xFFC7E4F9) else Color(0xFF141A2D)
    val bodyFillColor = if (isDarkTheme) Color(0xFFC7E4F9).copy(alpha = 0.15f) else Color(0xFFDCEBFA)
    val eyeColor = strokeColor

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
        ) {
            val w = size.toPx()
            val h = size.toPx()
            val strokeWidth = w * 0.05f

            // ── ONDAS DE SONIDO EXTERIOR CUANDO ESCUCHA ───
            if (state == PipelineState.LISTENING) {
                val waveAlpha = (1f - listeningWaveRadius).coerceIn(0.1f, 0.9f)
                val waveColor = (if (isDarkTheme) Color(0xFF38BDF8) else Color(0xFF2563EB)).copy(alpha = waveAlpha)
                val waveOffset = listeningWaveRadius * w * 0.12f

                // Arco izquierdo
                drawArc(
                    color = waveColor,
                    startAngle = 135f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(-waveOffset, h * 0.2f),
                    size = Size(w * 0.35f, h * 0.5f),
                    style = Stroke(width = strokeWidth * 0.7f, cap = StrokeCap.Round)
                )

                // Arco derecho
                drawArc(
                    color = waveColor,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(w * 0.65f + waveOffset, h * 0.2f),
                    size = Size(w * 0.35f, h * 0.5f),
                    style = Stroke(width = strokeWidth * 0.7f, cap = StrokeCap.Round)
                )
            }

            // 1. Antena / Asa superior pequeña
            val handleWidth = w * 0.22f
            val handleHeight = h * 0.08f
            drawRoundRect(
                color = strokeColor,
                topLeft = Offset((w - handleWidth) / 2f, h * 0.04f),
                size = Size(handleWidth, handleHeight),
                cornerRadius = CornerRadius(handleHeight / 2f, handleHeight / 2f),
                style = Stroke(width = strokeWidth * 0.8f)
            )

            // 2. Cabeza / Monitor de Fifo
            val headTop = h * 0.12f
            val headHeight = h * 0.76f
            val headWidth = w * 0.88f
            val headLeft = (w - headWidth) / 2f
            val corner = w * 0.18f

            // Relleno
            drawRoundRect(
                color = bodyFillColor,
                topLeft = Offset(headLeft, headTop),
                size = Size(headWidth, headHeight),
                cornerRadius = CornerRadius(corner, corner)
            )

            // Borde exterior nítido
            drawRoundRect(
                color = strokeColor,
                topLeft = Offset(headLeft, headTop),
                size = Size(headWidth, headHeight),
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = strokeWidth)
            )

            // 3. Cejas curiosas
            val eyebrowWidth = w * 0.12f
            val leftEyebrowStartX = w * 0.24f
            val rightEyebrowStartX = w * 0.60f
            val eyebrowY = headTop + headHeight * 0.26f

            if (state == PipelineState.LISTENING) {
                // Cejas levantadas atentas y receptivas
                drawLine(
                    color = strokeColor,
                    start = Offset(leftEyebrowStartX, eyebrowY - h * 0.03f),
                    end = Offset(leftEyebrowStartX + eyebrowWidth, eyebrowY - h * 0.04f),
                    strokeWidth = strokeWidth * 0.85f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(rightEyebrowStartX, eyebrowY - h * 0.04f),
                    end = Offset(rightEyebrowStartX + eyebrowWidth, eyebrowY - h * 0.03f),
                    strokeWidth = strokeWidth * 0.85f,
                    cap = StrokeCap.Round
                )
            } else if (state == PipelineState.PROCESSING) {
                // Ceja izquierda ligeramente alzada con curiosidad
                drawLine(
                    color = strokeColor,
                    start = Offset(leftEyebrowStartX, eyebrowY - h * 0.02f),
                    end = Offset(leftEyebrowStartX + eyebrowWidth, eyebrowY - h * 0.04f),
                    strokeWidth = strokeWidth * 0.85f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(rightEyebrowStartX, eyebrowY),
                    end = Offset(rightEyebrowStartX + eyebrowWidth, eyebrowY + h * 0.01f),
                    strokeWidth = strokeWidth * 0.75f,
                    cap = StrokeCap.Round
                )
            } else {
                drawLine(
                    color = strokeColor,
                    start = Offset(leftEyebrowStartX, eyebrowY + h * 0.015f),
                    end = Offset(leftEyebrowStartX + eyebrowWidth, eyebrowY),
                    strokeWidth = strokeWidth * 0.75f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(rightEyebrowStartX, eyebrowY),
                    end = Offset(rightEyebrowStartX + eyebrowWidth, eyebrowY + h * 0.015f),
                    strokeWidth = strokeWidth * 0.75f,
                    cap = StrokeCap.Round
                )
            }

            // 4. Ojos expresivos
            val eyeRadius = w * 0.09f
            val leftEyeCenter = Offset(w * 0.32f + eyeOffsetX, headTop + headHeight * 0.44f + eyeOffsetY)
            val rightEyeCenter = Offset(w * 0.66f + eyeOffsetX, headTop + headHeight * 0.44f + eyeOffsetY)

            // Factor de parpadeo (solo activo en IDLE)
            val isBlinking = state == PipelineState.IDLE && blinkFraction > 0.05f
            val eyeHeightScale = if (isBlinking) (1f - blinkFraction * 0.95f).coerceIn(0.08f, 1f) else 1f

            if (state == PipelineState.SLEEPING) {
                // Ojos cerrados durmiendo plácidamente: arcos serenos ‿  ‿
                val eyeArcW = eyeRadius * 1.8f
                val eyeArcH = eyeRadius * 0.9f
                drawArc(
                    color = eyeColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(leftEyeCenter.x - eyeArcW / 2f, leftEyeCenter.y - eyeArcH / 2f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = strokeWidth * 0.95f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = eyeColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(rightEyeCenter.x - eyeArcW / 2f, rightEyeCenter.y - eyeArcH / 2f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = strokeWidth * 0.95f, cap = StrokeCap.Round)
                )
            } else if (state == PipelineState.SPEAKING) {
                // Ojos felices al hablar: arcos radiantes ^ ^
                val eyeArcW = eyeRadius * 2.2f
                val eyeArcH = eyeRadius * 1.6f
                drawArc(
                    color = eyeColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(leftEyeCenter.x - eyeArcW / 2f, leftEyeCenter.y - eyeArcH / 2f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = strokeWidth * 1.1f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = eyeColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(rightEyeCenter.x - eyeArcW / 2f, rightEyeCenter.y - eyeArcH / 2f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = strokeWidth * 1.1f, cap = StrokeCap.Round)
                )
            } else if (isBlinking && eyeHeightScale <= 0.18f) {
                // Ojos parpadeando: línea suave cerrada momentánea
                val eyeLineW = eyeRadius * 1.8f
                drawLine(
                    color = eyeColor,
                    start = Offset(leftEyeCenter.x - eyeLineW / 2f, leftEyeCenter.y),
                    end = Offset(leftEyeCenter.x + eyeLineW / 2f, leftEyeCenter.y),
                    strokeWidth = strokeWidth * 0.9f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = eyeColor,
                    start = Offset(rightEyeCenter.x - eyeLineW / 2f, rightEyeCenter.y),
                    end = Offset(rightEyeCenter.x + eyeLineW / 2f, rightEyeCenter.y),
                    strokeWidth = strokeWidth * 0.9f,
                    cap = StrokeCap.Round
                )
            } else {
                // Ojos redondos con pupila y reflejo
                val r = if (state == PipelineState.LISTENING) eyeRadius * 1.08f else eyeRadius

                // Ojo izquierdo
                drawOval(
                    color = eyeColor,
                    topLeft = Offset(leftEyeCenter.x - r, leftEyeCenter.y - r * eyeHeightScale),
                    size = Size(2 * r, 2 * r * eyeHeightScale),
                    style = Stroke(width = strokeWidth * 0.85f)
                )
                drawOval(
                    color = eyeColor,
                    topLeft = Offset(leftEyeCenter.x - r * 0.3f, leftEyeCenter.y - r * 0.75f * eyeHeightScale),
                    size = Size(r * 1.1f, r * 1.1f * eyeHeightScale)
                )
                // Reflejo blanco de vida
                drawOval(
                    color = if (isDarkTheme) Color(0xFF141A2D) else Color.White,
                    topLeft = Offset(leftEyeCenter.x + r * 0.15f, leftEyeCenter.y - r * 0.5f * eyeHeightScale),
                    size = Size(r * 0.4f, r * 0.4f * eyeHeightScale)
                )

                // Ojo derecho
                drawOval(
                    color = eyeColor,
                    topLeft = Offset(rightEyeCenter.x - r, rightEyeCenter.y - r * eyeHeightScale),
                    size = Size(2 * r, 2 * r * eyeHeightScale),
                    style = Stroke(width = strokeWidth * 0.85f)
                )
                drawOval(
                    color = eyeColor,
                    topLeft = Offset(rightEyeCenter.x - r * 0.3f, rightEyeCenter.y - r * 0.75f * eyeHeightScale),
                    size = Size(r * 1.1f, r * 1.1f * eyeHeightScale)
                )
                // Reflejo blanco de vida
                drawOval(
                    color = if (isDarkTheme) Color(0xFF141A2D) else Color.White,
                    topLeft = Offset(rightEyeCenter.x + r * 0.15f, rightEyeCenter.y - r * 0.5f * eyeHeightScale),
                    size = Size(r * 0.4f, r * 0.4f * eyeHeightScale)
                )
            }

            // 5. Mejillas sonrosadas cálidas
            val blushW = w * 0.09f
            val blushColor = (if (isDarkTheme) Color(0xFFF472B6) else Color(0xFFFB7185)).copy(alpha = blushAlpha)
            drawRoundRect(
                color = blushColor,
                topLeft = Offset(w * 0.17f, headTop + headHeight * 0.58f),
                size = Size(blushW, h * 0.035f),
                cornerRadius = CornerRadius(h * 0.018f, h * 0.018f)
            )
            drawRoundRect(
                color = blushColor,
                topLeft = Offset(w * 0.74f, headTop + headHeight * 0.58f),
                size = Size(blushW, h * 0.035f),
                cornerRadius = CornerRadius(h * 0.018f, h * 0.018f)
            )

            // 6. Boca animada al hablar, escuchar o sonreír
            val mouthWidth = w * (if (state == PipelineState.SPEAKING) 0.38f else 0.32f)
            val mouthLeft = (w - mouthWidth) / 2f
            val mouthTop = headTop + headHeight * 0.65f
            val mouthDepth = h * (0.04f + mouthOpenAmount * 0.12f)

            if (state == PipelineState.SLEEPING) {
                // Boca relajada durmiendo
                drawLine(
                    color = strokeColor,
                    start = Offset(w * 0.43f, mouthTop + h * 0.02f),
                    end = Offset(w * 0.57f, mouthTop + h * 0.02f),
                    strokeWidth = strokeWidth * 0.8f,
                    cap = StrokeCap.Round
                )
            } else if (state == PipelineState.SPEAKING) {
                // Boca abierta modulada con forma orgánica y lengua cálida
                val mouthPath = Path().apply {
                    moveTo(mouthLeft, mouthTop)
                    quadraticBezierTo(w / 2f, mouthTop + mouthDepth * 2.2f, mouthLeft + mouthWidth, mouthTop)
                    quadraticBezierTo(w / 2f, mouthTop - mouthDepth * 0.3f, mouthLeft, mouthTop)
                    close()
                }
                drawPath(path = mouthPath, color = strokeColor)

                // Lengua interior visible cuando abre la boca
                val tonguePath = Path().apply {
                    moveTo(w * 0.43f, mouthTop + mouthDepth * 1.5f)
                    quadraticBezierTo(w / 2f, mouthTop + mouthDepth * 2.05f, w * 0.57f, mouthTop + mouthDepth * 1.5f)
                    close()
                }
                drawPath(path = tonguePath, color = if (isDarkTheme) Color(0xFFF43F5E) else Color(0xFFE11D48))

            } else if (state == PipelineState.LISTENING) {
                // Pequeña 'o' curiosa mientras escucha atentamente
                drawCircle(
                    color = strokeColor,
                    radius = w * (0.04f + mouthOpenAmount * 0.035f),
                    center = Offset(w / 2f, mouthTop + h * 0.04f),
                    style = Stroke(width = strokeWidth * 0.85f)
                )
            } else {
                // Sonrisa alegre en reposo (amigable y acogedora)
                val mouthPath = Path().apply {
                    moveTo(mouthLeft, mouthTop)
                    quadraticBezierTo(w / 2f, mouthTop + mouthDepth * 2.1f, mouthLeft + mouthWidth, mouthTop)
                    quadraticBezierTo(w / 2f, mouthTop + mouthDepth * 0.4f, mouthLeft, mouthTop)
                    close()
                }
                drawPath(path = mouthPath, color = strokeColor)
            }
        }
    }
}
