package com.fifo.voicepipeline.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fifo.voicepipeline.pipeline.MicSource
import com.fifo.voicepipeline.pipeline.PipelineState
import com.fifo.voicepipeline.ui.theme.FifoColors

@Composable
fun VoiceInteractionSheet(
    state: PipelineState,
    micSource: MicSource,
    statusMessage: String,
    transcription: String,
    aiResponse: String,
    rmsLevel: Float,
    isBleConnected: Boolean,
    onDismiss: () -> Unit,
    onSelectMicSource: (MicSource) -> Unit,
    onConnectBle: () -> Unit,
    onClearConversation: () -> Unit,
    isMicMuted: Boolean = false,
    onToggleMicMute: () -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = FifoColors.DarkBackground,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Barra superior: Estado Mic + Botón Mute + Botón Cerrar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Badge de fuente de micrófono activa
                        Surface(
                            color = if (isMicMuted) Color(0xFFEF4444).copy(alpha = 0.2f) else FifoColors.DarkInputBg,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isMicMuted) Color(0xFFEF4444) else FifoColors.DarkInputBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isMicMuted) Icons.Default.MicOff else (if (micSource == MicSource.ESP32) Icons.Default.Mic else Icons.Default.PhoneAndroid),
                                    contentDescription = null,
                                    tint = if (isMicMuted) Color(0xFFEF4444) else FifoColors.BlueAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isMicMuted) "Mic Silenciado" else (if (micSource == MicSource.ESP32) "Mic ESP32 (UCC)" else "Mic Celular"),
                                    fontSize = 11.sp,
                                    color = if (isMicMuted) Color(0xFFEF4444) else FifoColors.BlueAccent,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onToggleMicMute,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (isMicMuted) Color(0xFFEF4444).copy(alpha = 0.25f) else FifoColors.DarkInputBg,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = if (isMicMuted) "Activar micrófono" else "Silenciar micrófono",
                                    tint = if (isMicMuted) Color(0xFFEF4444) else FifoColors.BlueAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(FifoColors.DarkInputBg, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cerrar",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Rostro de Fifo animado reaccionando a la voz
                    FifoFace(
                        size = 130.dp,
                        state = if (isMicMuted) PipelineState.SLEEPING else state,
                        isDarkTheme = true,
                        rmsLevel = if (isMicMuted) 0f else rmsLevel
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Estado del pipeline (Badge interactivo)
                    val (statusText, statusBg, statusColor) = if (isMicMuted) {
                        Triple("Micrófono Silenciado (Mute)", Color(0xFFEF4444).copy(alpha = 0.2f), Color(0xFFEF4444))
                    } else when (state) {
                        PipelineState.LISTENING -> Triple("Escuchando...", FifoColors.StatusListening.copy(alpha = 0.2f), FifoColors.StatusListening)
                        PipelineState.PROCESSING -> Triple("Fifo está pensando...", FifoColors.StatusProcessing.copy(alpha = 0.2f), FifoColors.StatusProcessing)
                        PipelineState.SPEAKING -> Triple("Fifo te responde...", FifoColors.StatusSpeaking.copy(alpha = 0.2f), FifoColors.StatusSpeaking)
                        PipelineState.SLEEPING -> Triple("Durmiendo · Di 'Fifo'", FifoColors.NavyPrimary.copy(alpha = 0.3f), Color(0xFF93C5FD))
                        PipelineState.IDLE -> Triple("Habla cuando quieras", FifoColors.DarkInputBg, Color(0xFF94A3B8))
                        PipelineState.DISCONNECTED -> Triple("Esperando conexión...", FifoColors.DarkInputBg, Color(0xFF94A3B8))
                        PipelineState.ERROR -> Triple("Reintentando...", FifoColors.StatusError.copy(alpha = 0.2f), FifoColors.StatusError)
                    }

                    Surface(
                        color = statusBg,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Botón destacado para mutear / activar micrófono
                    Surface(
                        onClick = onToggleMicMute,
                        shape = RoundedCornerShape(20.dp),
                        color = if (isMicMuted) Color(0xFFEF4444).copy(alpha = 0.22f) else FifoColors.DarkInputBg,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isMicMuted) Color(0xFFEF4444) else FifoColors.DarkInputBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (isMicMuted) Color(0xFFEF4444) else FifoColors.BlueAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isMicMuted) "Micrófono Silenciado · Toca para activar" else "Micrófono Activo · Toca para silenciar",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isMicMuted) Color(0xFFFCA5A5) else Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Selector rápido de micrófono (Pestañas ESP32 / Celular)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(FifoColors.DarkInputBg, RoundedCornerShape(24.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val isEsp32 = micSource == MicSource.ESP32
                        Surface(
                            onClick = { onSelectMicSource(MicSource.ESP32) },
                            color = if (isEsp32) FifoColors.DarkButtonPrimary else Color.Transparent,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = if (isEsp32) FifoColors.DarkButtonText else FifoColors.DarkBadgeText,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Mic ESP32",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isEsp32) FifoColors.DarkButtonText else FifoColors.DarkBadgeText
                                )
                            }
                        }

                        val isPhone = micSource == MicSource.PHONE
                        Surface(
                            onClick = { onSelectMicSource(MicSource.PHONE) },
                            color = if (isPhone) FifoColors.DarkButtonPrimary else Color.Transparent,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = if (isPhone) FifoColors.DarkButtonText else FifoColors.DarkBadgeText,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Mic Celular",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPhone) FifoColors.DarkButtonText else FifoColors.DarkBadgeText
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Tarjeta de Conversación: Transcripción del usuario
                    if (transcription.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = FifoColors.DarkInputBg,
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "TÚ DIJISTE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.DarkBadgeText,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "\"$transcription\"",
                                    fontSize = 15.sp,
                                    color = Color.White,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Tarjeta de Conversación: Respuesta de Fifo
                    if (aiResponse.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF1E3A5F).copy(alpha = 0.5f),
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        tint = FifoColors.BlueAccent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "FIFO TE RESPONDE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.BlueAccent,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = aiResponse,
                                    fontSize = 15.sp,
                                    color = Color.White,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Si no hay conversación aún, mensaje acogedor
                    if (transcription.isEmpty() && aiResponse.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = FifoColors.DarkInputBg,
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "¡Hola! Estoy listo para escucharte.",
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (micSource == MicSource.ESP32)
                                        "Habla cerca del micrófono de tu Fifo (ESP32)."
                                    else
                                        "Habla directo al micrófono de tu teléfono.",
                                    fontSize = 12.sp,
                                    color = FifoColors.DarkBadgeText,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    // Botón de estado y conexión Bluetooth con el robot
                    OutlinedButton(
                        onClick = onConnectBle,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isBleConnected) FifoColors.StatusListening else FifoColors.BlueAccent
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isBleConnected) FifoColors.StatusListening else FifoColors.DarkInputBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isBleConnected) "Fifo Conectado por Bluetooth" else "Conectar Fifo por Bluetooth",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (transcription.isNotEmpty() || aiResponse.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(onClick = onClearConversation) {
                            Text("Limpiar diálogo", fontSize = 12.sp, color = FifoColors.DarkBadgeText)
                        }
                    }
                }
            }
        }
    }
}
