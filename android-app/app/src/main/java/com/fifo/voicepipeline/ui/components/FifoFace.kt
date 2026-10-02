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
 * Ilustración vectorial moderna y adorable del robot compañero "Fifo".
 * Diseño impecable: cuerpo de robot redondeado de cerámica mate, auriculares/sensores acústicos laterales,
 * antena con baliza luminosa que respira, pantalla visor OLED curva con brillo de cristal, y
 * ojos LED expresivos y cálidos que parpadean, escuchan y sonríen con ternura.
 */
@Composable
fun FifoFace(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    state: PipelineState = PipelineState.IDLE,
    isDarkTheme: Boolean = true,
    rmsLevel: Float = 0f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fifo_companion_anim")

    // Pulso suave de respiración adaptado al estado
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.00f,
        targetValue = when (state) {
            PipelineState.SPEAKING -> 1.035f
            PipelineState.LISTENING -> 1.025f
            PipelineState.PROCESSING -> 1.015f
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

    // Parpadeo natural periódico cada ~4 segundos
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

    // Aleteo rítmico de boca al hablar (modulado por voz)
    val speakingMouthFlap by infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(140, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speak_flap"
    )

    // Ondas expansivas de escucha desde los sensores laterales
    val listeningWaveRadius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "listen_waves"
    )

    // Movimiento pensativo de ojos cuando procesa
    val eyeOffsetX by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = if (state == PipelineState.PROCESSING) 4f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eye_look_x"
    )

    val eyeOffsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (state == PipelineState.PROCESSING) -2f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eye_look_y"
    )

    // Brillo cálido de mejillas
    val blushAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = if (state == PipelineState.SPEAKING || state == PipelineState.LISTENING) 0.85f else 0.50f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blush_alpha"
    )

    // Brillo de la baliza de la antena
    val beaconPulse by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "beacon_glow"
    )

    // Apertura reactiva de boca modulada por RMS
    val clampedRms = rmsLevel.coerceIn(0f, 1f)
    val mouthOpenAmount = when (state) {
        PipelineState.SPEAKING -> {
            if (clampedRms > 0.04f) {
                (speakingMouthFlap * 0.45f + clampedRms * 0.55f).coerceIn(0.25f, 1.0f)
            } else {
                speakingMouthFlap
            }
        }
        PipelineState.LISTENING -> (0.15f + (clampedRms * 0.70f)).coerceIn(0.15f, 0.70f)
        PipelineState.PROCESSING -> 0.12f
        PipelineState.SLEEPING, PipelineState.DISCONNECTED -> 0f
        else -> 0.18f
    }

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
            val cx = w / 2f

            // Paleta del robot compañero: cuerpo cerámico blanco/titanio y pantalla OLED
            val shellColor = if (isDarkTheme) Color(0xFF1E293B) else Color(0xFFFFFFFF)
            val shellBorderColor = if (isDarkTheme) Color(0xFF334155) else Color(0xFFDCE3ED)
            val visorColor = Color(0xFF0F172A) // Negro azulado OLED profundo
            val visorBorderColor = if (isDarkTheme) Color(0xFF334155) else Color(0xFF1E293B)

            // Colores LED según estado
            val ledEyeColor = when (state) {
                PipelineState.LISTENING -> Color(0xFF38BDF8)   // Celeste luminoso
                PipelineState.SPEAKING -> Color(0xFF67E8F9)    // Cian radiante
                PipelineState.PROCESSING -> Color(0xFF818CF8)  // Azul índigo inteligente
                PipelineState.ERROR -> Color(0xFFF87171)       // Coral suave
                PipelineState.SLEEPING -> Color(0xFF60A5FA)    // Azul suave en reposo
                else -> Color(0xFF38BDF8)                      // Celeste amigable por defecto (IDLE y DISCONNECTED)
            }

            val beaconColor = when (state) {
                PipelineState.LISTENING -> Color(0xFF38BDF8)
                PipelineState.SPEAKING -> Color(0xFF34D399)
                PipelineState.PROCESSING -> Color(0xFFA855F7)
                PipelineState.ERROR -> Color(0xFFF87171)
                PipelineState.SLEEPING -> Color(0xFF64748B)
                else -> Color(0xFF38BDF8)
            }

            // ── 0. ONDAS DE ESCUCHA LATERALES (LISTENING) ───
            if (state == PipelineState.LISTENING) {
                val waveAlpha = (1f - listeningWaveRadius).coerceIn(0.1f, 0.85f)
                val waveColor = Color(0xFF38BDF8).copy(alpha = waveAlpha)
                val waveOffset = listeningWaveRadius * w * 0.12f

                // Lado izquierdo
                drawArc(
                    color = waveColor,
                    startAngle = 135f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(-waveOffset, h * 0.32f),
                    size = Size(w * 0.28f, h * 0.40f),
                    style = Stroke(width = w * 0.035f, cap = StrokeCap.Round)
                )

                // Lado derecho
                drawArc(
                    color = waveColor,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(w * 0.72f + waveOffset, h * 0.32f),
                    size = Size(w * 0.28f, h * 0.40f),
                    style = Stroke(width = w * 0.035f, cap = StrokeCap.Round)
                )
            }

            // ── 1. ANTENA CON BALIZA LUMINOSA (Adios a la manija de maleta) ───
            val antennaStemTop = h * 0.05f
            val antennaStemBottom = h * 0.16f
            val stemWidth = w * 0.045f

            // Poste de la antena
            drawLine(
                color = shellBorderColor,
                start = Offset(cx, antennaStemBottom),
                end = Offset(cx, antennaStemTop),
                strokeWidth = stemWidth,
                cap = StrokeCap.Round
            )

            // Resplandor exterior de la baliza
            val beaconRadius = w * 0.055f
            drawCircle(
                color = beaconColor.copy(alpha = 0.30f * beaconPulse),
                radius = beaconRadius * 1.7f,
                center = Offset(cx, antennaStemTop)
            )

            // Esfera luminosa de la baliza
            drawCircle(
                color = beaconColor,
                radius = beaconRadius,
                center = Offset(cx, antennaStemTop)
            )
            // Punto de brillo blanco en la baliza
            drawCircle(
                color = Color.White.copy(alpha = 0.8f),
                radius = beaconRadius * 0.35f,
                center = Offset(cx - beaconRadius * 0.25f, antennaStemTop - beaconRadius * 0.25f)
            )

            // ── 2. AURICULARES / SENSORES ACÚSTICOS LATERALES ───
            val earW = w * 0.085f
            val earH = h * 0.28f
            val earY = h * 0.38f

            // Auricular Izquierdo
            val leftEarX = w * 0.03f
            drawRoundRect(
                color = shellColor,
                topLeft = Offset(leftEarX, earY),
                size = Size(earW, earH),
                cornerRadius = CornerRadius(earW / 2f, earW / 2f)
            )
            drawRoundRect(
                color = shellBorderColor,
                topLeft = Offset(leftEarX, earY),
                size = Size(earW, earH),
                cornerRadius = CornerRadius(earW / 2f, earW / 2f),
                style = Stroke(width = w * 0.025f)
            )
            // Acento central del auricular izquierdo
            drawRoundRect(
                color = if (isDarkTheme) Color(0xFF38BDF8).copy(alpha = 0.3f) else Color(0xFF93C5FD).copy(alpha = 0.5f),
                topLeft = Offset(leftEarX + earW * 0.25f, earY + earH * 0.25f),
                size = Size(earW * 0.5f, earH * 0.5f),
                cornerRadius = CornerRadius(earW * 0.25f, earW * 0.25f)
            )

            // Auricular Derecho
            val rightEarX = w * 0.885f
            drawRoundRect(
                color = shellColor,
                topLeft = Offset(rightEarX, earY),
                size = Size(earW, earH),
                cornerRadius = CornerRadius(earW / 2f, earW / 2f)
            )
            drawRoundRect(
                color = shellBorderColor,
                topLeft = Offset(rightEarX, earY),
                size = Size(earW, earH),
                cornerRadius = CornerRadius(earW / 2f, earW / 2f),
                style = Stroke(width = w * 0.025f)
            )
            // Acento central del auricular derecho
            drawRoundRect(
                color = if (isDarkTheme) Color(0xFF38BDF8).copy(alpha = 0.3f) else Color(0xFF93C5FD).copy(alpha = 0.5f),
                topLeft = Offset(rightEarX + earW * 0.25f, earY + earH * 0.25f),
                size = Size(earW * 0.5f, earH * 0.5f),
                cornerRadius = CornerRadius(earW * 0.25f, earW * 0.25f)
            )

            // ── 3. CABEZA DEL ROBOT (Cuerpo redondeado ergonómico) ───
            val headLeft = w * 0.09f
            val headTop = h * 0.15f
            val headWidth = w * 0.82f
            val headHeight = h * 0.74f
            val headCorner = w * 0.26f

            // Relleno del cuerpo
            drawRoundRect(
                color = shellColor,
                topLeft = Offset(headLeft, headTop),
                size = Size(headWidth, headHeight),
                cornerRadius = CornerRadius(headCorner, headCorner)
            )
            // Borde exterior suave del cuerpo
            drawRoundRect(
                color = shellBorderColor,
                topLeft = Offset(headLeft, headTop),
                size = Size(headWidth, headHeight),
                cornerRadius = CornerRadius(headCorner, headCorner),
                style = Stroke(width = w * 0.035f)
            )

            // ── 4. PANTALLA VISOR OLED CURVA ───
            val visorLeft = w * 0.17f
            val visorTop = h * 0.23f
            val visorWidth = w * 0.66f
            val visorHeight = h * 0.58f
            val visorCorner = w * 0.19f

            // Fondo negro azabache de la pantalla OLED
            drawRoundRect(
                color = visorColor,
                topLeft = Offset(visorLeft, visorTop),
                size = Size(visorWidth, visorHeight),
                cornerRadius = CornerRadius(visorCorner, visorCorner)
            )
            // Marco interno sutil del cristal
            drawRoundRect(
                color = visorBorderColor,
                topLeft = Offset(visorLeft, visorTop),
                size = Size(visorWidth, visorHeight),
                cornerRadius = CornerRadius(visorCorner, visorCorner),
                style = Stroke(width = w * 0.02f)
            )

            // Reflejo suave de cristal en la esquina superior del visor
            drawRoundRect(
                color = Color.White.copy(alpha = 0.08f),
                topLeft = Offset(visorLeft + visorWidth * 0.08f, visorTop + visorHeight * 0.08f),
                size = Size(visorWidth * 0.45f, visorHeight * 0.22f),
                cornerRadius = CornerRadius(visorCorner * 0.6f, visorCorner * 0.6f)
            )

            // ── 5. OJOS LED EXPRESIVOS Y BRILLANTES ───
            val eyeRadius = w * 0.082f
            val eyeHeight = eyeRadius * 1.35f
            val leftEyeCenter = Offset(w * 0.35f + eyeOffsetX, h * 0.47f + eyeOffsetY)
            val rightEyeCenter = Offset(w * 0.65f + eyeOffsetX, h * 0.47f + eyeOffsetY)

            // Factor de parpadeo (activo en IDLE, LISTENING y DISCONNECTED)
            val isBlinking = (state == PipelineState.IDLE || state == PipelineState.LISTENING || state == PipelineState.DISCONNECTED) && blinkFraction > 0.05f
            val eyeHeightScale = if (isBlinking) (1f - blinkFraction * 0.95f).coerceIn(0.08f, 1f) else 1f

            if (state == PipelineState.SLEEPING) {
                // Ojos cerrados plácidos / descansando: arcos serenos ‿  ‿
                val eyeArcW = eyeRadius * 1.9f
                val eyeArcH = eyeRadius * 1.0f
                drawArc(
                    color = ledEyeColor.copy(alpha = 0.85f),
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(leftEyeCenter.x - eyeArcW / 2f, leftEyeCenter.y - eyeArcH / 2f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = w * 0.038f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ledEyeColor.copy(alpha = 0.85f),
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(rightEyeCenter.x - eyeArcW / 2f, rightEyeCenter.y - eyeArcH / 2f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = w * 0.038f, cap = StrokeCap.Round)
                )
            } else if (state == PipelineState.SPEAKING) {
                // Ojos felices al hablar: arcos radiantes ^ ^
                val eyeArcW = eyeRadius * 2.0f
                val eyeArcH = eyeRadius * 1.3f
                drawArc(
                    color = ledEyeColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(leftEyeCenter.x - eyeArcW / 2f, leftEyeCenter.y - eyeArcH / 2f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = w * 0.042f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ledEyeColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(rightEyeCenter.x - eyeArcW / 2f, rightEyeCenter.y - eyeArcH / 2f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = w * 0.042f, cap = StrokeCap.Round)
                )
            } else if (isBlinking && eyeHeightScale <= 0.18f) {
                // Parpadeo suave momentáneo
                val blinkW = eyeRadius * 1.8f
                drawLine(
                    color = ledEyeColor,
                    start = Offset(leftEyeCenter.x - blinkW / 2f, leftEyeCenter.y),
                    end = Offset(leftEyeCenter.x + blinkW / 2f, leftEyeCenter.y),
                    strokeWidth = w * 0.035f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = ledEyeColor,
                    start = Offset(rightEyeCenter.x - blinkW / 2f, rightEyeCenter.y),
                    end = Offset(rightEyeCenter.x + blinkW / 2f, rightEyeCenter.y),
                    strokeWidth = w * 0.035f,
                    cap = StrokeCap.Round
                )
            } else {
                // Ojos cápsula digitales modernos (estilo EVE / Astro Bot)
                val currentEyeH = eyeHeight * eyeHeightScale
                val eyeR = eyeRadius

                // Resplandor difuso del LED izquierdo
                drawRoundRect(
                    color = ledEyeColor.copy(alpha = 0.25f),
                    topLeft = Offset(leftEyeCenter.x - eyeR * 1.15f, leftEyeCenter.y - currentEyeH * 1.15f),
                    size = Size(eyeR * 2.3f, currentEyeH * 2.3f),
                    cornerRadius = CornerRadius(eyeR, eyeR)
                )
                // Ojo izquierdo principal
                drawRoundRect(
                    color = ledEyeColor,
                    topLeft = Offset(leftEyeCenter.x - eyeR, leftEyeCenter.y - currentEyeH),
                    size = Size(eyeR * 2f, currentEyeH * 2f),
                    cornerRadius = CornerRadius(eyeR, eyeR)
                )
                // Destello de vida blanco en el ojo izquierdo
                if (eyeHeightScale > 0.4f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = eyeR * 0.28f,
                        center = Offset(leftEyeCenter.x + eyeR * 0.22f, leftEyeCenter.y - currentEyeH * 0.35f)
                    )
                }

                // Resplandor difuso del LED derecho
                drawRoundRect(
                    color = ledEyeColor.copy(alpha = 0.25f),
                    topLeft = Offset(rightEyeCenter.x - eyeR * 1.15f, rightEyeCenter.y - currentEyeH * 1.15f),
                    size = Size(eyeR * 2.3f, currentEyeH * 2.3f),
                    cornerRadius = CornerRadius(eyeR, eyeR)
                )
                // Ojo derecho principal
                drawRoundRect(
                    color = ledEyeColor,
                    topLeft = Offset(rightEyeCenter.x - eyeR, rightEyeCenter.y - currentEyeH),
                    size = Size(eyeR * 2f, currentEyeH * 2f),
                    cornerRadius = CornerRadius(eyeR, eyeR)
                )
                // Destello de vida blanco en el ojo derecho
                if (eyeHeightScale > 0.4f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = eyeR * 0.28f,
                        center = Offset(rightEyeCenter.x + eyeR * 0.22f, rightEyeCenter.y - currentEyeH * 0.35f)
                    )
                }
            }

            // ── 6. MEJILLAS SONROSADAS LUMINOSAS ───
            val blushW = w * 0.085f
            val blushH = h * 0.032f
            val blushY = h * 0.59f
            val blushColor = Color(0xFFF472B6).copy(alpha = blushAlpha)

            drawRoundRect(
                color = blushColor,
                topLeft = Offset(w * 0.22f, blushY),
                size = Size(blushW, blushH),
                cornerRadius = CornerRadius(blushH / 2f, blushH / 2f)
            )
            drawRoundRect(
                color = blushColor,
                topLeft = Offset(w * 0.695f, blushY),
                size = Size(blushW, blushH),
                cornerRadius = CornerRadius(blushH / 2f, blushH / 2f)
            )

            // ── 7. SONRISA DIGITAL LED CURVA ───
            val mouthW = w * 0.24f
            val mouthY = h * 0.64f

            if (state == PipelineState.SLEEPING) {
                // Línea neutra relajada descansando
                drawLine(
                    color = Color(0xFF94A3B8).copy(alpha = 0.60f),
                    start = Offset(cx - mouthW * 0.35f, mouthY + h * 0.02f),
                    end = Offset(cx + mouthW * 0.35f, mouthY + h * 0.02f),
                    strokeWidth = w * 0.028f,
                    cap = StrokeCap.Round
                )
            } else if (state == PipelineState.SPEAKING) {
                // Boca hablando animada con apertura suave y pulso
                val mouthOpenH = h * (0.025f + mouthOpenAmount * 0.065f)
                drawRoundRect(
                    color = ledEyeColor,
                    topLeft = Offset(cx - mouthW / 2f, mouthY),
                    size = Size(mouthW, mouthOpenH),
                    cornerRadius = CornerRadius(mouthOpenH / 2f, mouthOpenH / 2f)
                )
            } else if (state == PipelineState.LISTENING) {
                // Pequeño círculo atento mientras escucha atentamente
                drawCircle(
                    color = ledEyeColor,
                    radius = w * (0.035f + mouthOpenAmount * 0.02f),
                    center = Offset(cx, mouthY + h * 0.02f),
                    style = Stroke(width = w * 0.030f)
                )
            } else {
                // Sonrisa acogedora y cálida en reposo (curva suave hacia arriba)
                val smilePath = Path().apply {
                    moveTo(cx - mouthW / 2f, mouthY)
                    quadraticBezierTo(cx, mouthY + h * 0.045f, cx + mouthW / 2f, mouthY)
                }
                drawPath(
                    path = smilePath,
                    color = ledEyeColor,
                    style = Stroke(width = w * 0.032f, cap = StrokeCap.Round)
                )
            }
        }
    }
}
