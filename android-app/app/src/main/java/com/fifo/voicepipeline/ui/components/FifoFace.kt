package com.fifo.voicepipeline.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

    // Pulso suave general
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = if (state == PipelineState.LISTENING || state == PipelineState.SPEAKING) 1.04f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Aleteo rápido de boca al hablar (5-7 Hz para animación natural de voz de caricatura/robot)
    val speakingMouthFlap by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(130, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speak_flap"
    )

    // Ondas de escucha animadas
    val listeningWaveRadius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "listen_waves"
    )

    // Movimiento sutil de ojos cuando piensa
    val eyeOffsetX by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = if (state == PipelineState.PROCESSING) 4f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eye_look"
    )

    // Apertura de boca
    val mouthOpenAmount = when (state) {
        PipelineState.SPEAKING -> speakingMouthFlap
        PipelineState.LISTENING -> (0.15f + (rmsLevel * 0.8f)).coerceIn(0.12f, 0.65f)
        else -> 0.18f
    }

    // Colores según el tema
    val strokeColor = if (isDarkTheme) Color(0xFFC7E4F9) else Color(0xFF141A2D)
    val bodyFillColor = if (isDarkTheme) Color(0xFFC7E4F9).copy(alpha = 0.15f) else Color(0xFFDCEBFA)
    val eyeColor = strokeColor

    Box(modifier = modifier.size(size * pulseScale)) {
        Canvas(modifier = Modifier.matchParentSize()) {
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

            // Borde exterior
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
                // Cejas levantadas atentas
                drawLine(
                    color = strokeColor,
                    start = Offset(leftEyebrowStartX, eyebrowY - h * 0.02f),
                    end = Offset(leftEyebrowStartX + eyebrowWidth, eyebrowY - h * 0.03f),
                    strokeWidth = strokeWidth * 0.85f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(rightEyebrowStartX, eyebrowY - h * 0.03f),
                    end = Offset(rightEyebrowStartX + eyebrowWidth, eyebrowY - h * 0.02f),
                    strokeWidth = strokeWidth * 0.85f,
                    cap = StrokeCap.Round
                )
            } else {
                drawLine(
                    color = strokeColor,
                    start = Offset(leftEyebrowStartX, eyebrowY + h * 0.02f),
                    end = Offset(leftEyebrowStartX + eyebrowWidth, eyebrowY),
                    strokeWidth = strokeWidth * 0.75f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = strokeColor,
                    start = Offset(rightEyebrowStartX, eyebrowY),
                    end = Offset(rightEyebrowStartX + eyebrowWidth, eyebrowY + h * 0.02f),
                    strokeWidth = strokeWidth * 0.75f,
                    cap = StrokeCap.Round
                )
            }

            // 4. Ojos
            val eyeRadius = w * 0.09f
            val leftEyeCenter = Offset(w * 0.32f + eyeOffsetX, headTop + headHeight * 0.44f)
            val rightEyeCenter = Offset(w * 0.66f + eyeOffsetX, headTop + headHeight * 0.44f)

            if (state == PipelineState.SLEEPING) {
                // Ojos cerrados durmiendo pacíficamente: - -
                val eyeLineW = eyeRadius * 1.8f
                drawLine(
                    color = eyeColor,
                    start = Offset(leftEyeCenter.x - eyeLineW / 2f, leftEyeCenter.y),
                    end = Offset(leftEyeCenter.x + eyeLineW / 2f, leftEyeCenter.y),
                    strokeWidth = strokeWidth * 1.0f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = eyeColor,
                    start = Offset(rightEyeCenter.x - eyeLineW / 2f, rightEyeCenter.y),
                    end = Offset(rightEyeCenter.x + eyeLineW / 2f, rightEyeCenter.y),
                    strokeWidth = strokeWidth * 1.0f,
                    cap = StrokeCap.Round
                )
            } else if (state == PipelineState.SPEAKING) {
                // Ojos felices al hablar: arcos animados ^ ^
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
            } else {
                // Ojos redondos con pupilas y reflejos
                val r = if (state == PipelineState.LISTENING) eyeRadius * 1.08f else eyeRadius

                drawCircle(color = eyeColor, radius = r, center = leftEyeCenter, style = Stroke(width = strokeWidth * 0.85f))
                drawCircle(color = eyeColor, radius = r * 0.55f, center = Offset(leftEyeCenter.x + r * 0.25f, leftEyeCenter.y - r * 0.2f))
                drawCircle(color = if (isDarkTheme) Color(0xFF141A2D) else Color.White, radius = r * 0.2f, center = Offset(leftEyeCenter.x + r * 0.35f, leftEyeCenter.y - r * 0.3f))

                drawCircle(color = eyeColor, radius = r, center = rightEyeCenter, style = Stroke(width = strokeWidth * 0.85f))
                drawCircle(color = eyeColor, radius = r * 0.55f, center = Offset(rightEyeCenter.x + r * 0.25f, rightEyeCenter.y - r * 0.2f))
                drawCircle(color = if (isDarkTheme) Color(0xFF141A2D) else Color.White, radius = r * 0.2f, center = Offset(rightEyeCenter.x + r * 0.35f, rightEyeCenter.y - r * 0.3f))
            }

            // 5. Mejillas sonrosadas
            val blushW = w * 0.08f
            drawRoundRect(
                color = (if (isDarkTheme) Color(0xFFF472B6) else Color(0xFFFB7185)).copy(alpha = 0.5f),
                topLeft = Offset(w * 0.18f, headTop + headHeight * 0.58f),
                size = Size(blushW, h * 0.03f),
                cornerRadius = CornerRadius(h * 0.015f, h * 0.015f)
            )
            drawRoundRect(
                color = (if (isDarkTheme) Color(0xFFF472B6) else Color(0xFFFB7185)).copy(alpha = 0.5f),
                topLeft = Offset(w * 0.74f, headTop + headHeight * 0.58f),
                size = Size(blushW, h * 0.03f),
                cornerRadius = CornerRadius(h * 0.015f, h * 0.015f)
            )

            // 6. Boca animada al hablar o escuchar
            val mouthWidth = w * (if (state == PipelineState.SPEAKING) 0.38f else 0.32f)
            val mouthLeft = (w - mouthWidth) / 2f
            val mouthTop = headTop + headHeight * 0.65f
            val mouthDepth = h * (0.04f + mouthOpenAmount * 0.12f)

            if (state == PipelineState.SLEEPING) {
                // Boca relajada durmiendo
                drawLine(
                    color = strokeColor,
                    start = Offset(w * 0.44f, mouthTop + h * 0.02f),
                    end = Offset(w * 0.56f, mouthTop + h * 0.02f),
                    strokeWidth = strokeWidth * 0.8f,
                    cap = StrokeCap.Round
                )
            } else if (state == PipelineState.SPEAKING) {
                // Boca abierta hablando dinámicamente
                val mouthPath = Path().apply {
                    moveTo(mouthLeft, mouthTop)
                    quadraticBezierTo(w / 2f, mouthTop + mouthDepth * 2.2f, mouthLeft + mouthWidth, mouthTop)
                    quadraticBezierTo(w / 2f, mouthTop - mouthDepth * 0.3f, mouthLeft, mouthTop)
                    close()
                }
                drawPath(path = mouthPath, color = strokeColor)

                // Brillo de lengua interior
                val tonguePath = Path().apply {
                    moveTo(w * 0.44f, mouthTop + mouthDepth * 1.5f)
                    quadraticBezierTo(w / 2f, mouthTop + mouthDepth * 2.0f, w * 0.56f, mouthTop + mouthDepth * 1.5f)
                    close()
                }
                drawPath(path = tonguePath, color = if (isDarkTheme) Color(0xFFF43F5E) else Color(0xFFE11D48))

            } else if (state == PipelineState.LISTENING) {
                // Pequeña 'o' curiosa mientras escucha atentamente
                drawCircle(
                    color = strokeColor,
                    radius = w * (0.04f + mouthOpenAmount * 0.03f),
                    center = Offset(w / 2f, mouthTop + h * 0.04f),
                    style = Stroke(width = strokeWidth * 0.85f)
                )
            } else {
                // Sonrisa alegre en reposo
                val mouthPath = Path().apply {
                    moveTo(mouthLeft, mouthTop)
                    quadraticBezierTo(w / 2f, mouthTop + mouthDepth * 2f, mouthLeft + mouthWidth, mouthTop)
                    quadraticBezierTo(w / 2f, mouthTop + mouthDepth * 0.4f, mouthLeft, mouthTop)
                    close()
                }
                drawPath(path = mouthPath, color = strokeColor)
            }
        }
    }
}
