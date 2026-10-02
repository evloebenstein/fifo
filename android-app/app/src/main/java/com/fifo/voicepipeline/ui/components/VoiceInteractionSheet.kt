package com.fifo.voicepipeline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay

@Composable
fun VoiceInteractionSheet(
    state: PipelineState,
    micSource: MicSource = MicSource.ESP32,
    statusMessage: String = "",
    transcription: String,
    aiResponse: String,
    rmsLevel: Float,
    isBleConnected: Boolean,
    isBleConnecting: Boolean = false,
    onDismiss: () -> Unit,
    onConnectBle: () -> Unit,
    onTalkFromPhone: () -> Unit = {},
    onClearConversation: () -> Unit,
    isMicMuted: Boolean = false,
    onToggleMicMute: () -> Unit = {}
) {
    // Si se abrió sin conexión y se conecta exitosamente, notificar y cerrar automáticamente
    val wasEverDisconnected by remember { mutableStateOf(!isBleConnected) }

    LaunchedEffect(isBleConnected) {
        if (wasEverDisconnected && isBleConnected) {
            delay(1200)
            onDismiss()
        }
    }

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
                    if (!isBleConnected) {
                        // ══════════════════════════════════════════════════════════
                        // PANTALLA 1: CONECTAR A FIFO (SIN SOBRECARGA VISUAL)
                        // ══════════════════════════════════════════════════════════

                        // Barra superior: Sólo botón de cerrar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(FifoColors.DarkInputBg, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cerrar",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(30.dp))

                        // Rostro de Fifo en modo desconectado / buscando
                        FifoFace(
                            size = 140.dp,
                            state = if (isBleConnecting) PipelineState.LISTENING else PipelineState.DISCONNECTED,
                            isDarkTheme = true,
                            rmsLevel = 0f
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = "Conecte a Fifo a su celular",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Encienda su robot Fifo cerca de usted y toque el botón para comenzar a conversar.",
                            fontSize = 15.sp,
                            color = FifoColors.DarkBadgeText,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = onConnectBle,
                            enabled = !isBleConnecting,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0284C7),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            if (isBleConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Color.White,
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Buscando robot Fifo...",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Conectar Fifo por Bluetooth",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        // ══════════════════════════════════════════════════════════
                        // PANTALLA 2: INTERACCIÓN DE VOZ COMPLETA (FIFO CONECTADO)
                        // ══════════════════════════════════════════════════════════

                        // Barra superior: Indicador sutil de conexión + Botón Mute + Botón Cerrar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Badge sutil de conexión
                            Surface(
                                color = FifoColors.DarkInputBg,
                                shape = RoundedCornerShape(18.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (micSource == MicSource.PHONE) Color(0xFF38BDF8) else Color(0xFF10B981),
                                                CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (micSource == MicSource.PHONE) "Micrófono Celular" else "Fifo Conectado · Mic Robot",
                                        fontSize = 12.sp,
                                        color = if (micSource == MicSource.PHONE) Color(0xFF38BDF8) else Color(0xFF10B981),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = onToggleMicMute,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(
                                            if (isMicMuted) Color(0xFFEF4444).copy(alpha = 0.25f) else FifoColors.DarkInputBg,
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = if (isMicMuted) "Activar micrófono" else "Silenciar micrófono",
                                        tint = if (isMicMuted) Color(0xFFEF4444) else FifoColors.BlueAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(FifoColors.DarkInputBg, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cerrar ventana de voz",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Rostro animado y expresivo de Fifo
                        FifoFace(
                            size = 130.dp,
                            state = if (isMicMuted) PipelineState.SLEEPING else state,
                            isDarkTheme = true,
                            rmsLevel = if (isMicMuted) 0f else rmsLevel
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Estado del pipeline (Único badge de estado)
                        val (statusText, statusBg, statusColor) = if (isMicMuted) {
                            Triple("Micrófono Silenciado · Toque para activar", Color(0xFFEF4444).copy(alpha = 0.2f), Color(0xFFEF4444))
                        } else when (state) {
                            PipelineState.LISTENING -> Triple(
                                if (micSource == MicSource.PHONE) "Escuchando por el celular..." else "Escuchando... Diga 'Fifo'",
                                FifoColors.StatusListening.copy(alpha = 0.2f),
                                FifoColors.StatusListening
                            )
                            PipelineState.PROCESSING -> Triple("Fifo está pensando...", FifoColors.StatusProcessing.copy(alpha = 0.2f), FifoColors.StatusProcessing)
                            PipelineState.SPEAKING -> Triple("Fifo le responde...", FifoColors.StatusSpeaking.copy(alpha = 0.2f), FifoColors.StatusSpeaking)
                            PipelineState.SLEEPING -> Triple("En reposo · Diga 'Fifo' al robot", FifoColors.NavyPrimary.copy(alpha = 0.3f), Color(0xFF93C5FD))
                            PipelineState.IDLE -> Triple("Listo para escuchar", FifoColors.DarkInputBg, Color(0xFF94A3B8))
                            PipelineState.DISCONNECTED -> Triple("Fifo desconectado", Color(0xFFF59E0B).copy(alpha = 0.2f), Color(0xFFF59E0B))
                            PipelineState.ERROR -> Triple("Reintentando conexión...", FifoColors.StatusError.copy(alpha = 0.2f), FifoColors.StatusError)
                        }

                        Surface(
                            color = statusBg,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, statusColor.copy(alpha = 0.6f)),
                            modifier = if (isMicMuted) Modifier.clickable { onToggleMicMute() } else Modifier
                        ) {
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Botón de conveniencia para hablar desde el celular si el robot está lejos
                        Surface(
                            onClick = onTalkFromPhone,
                            shape = RoundedCornerShape(18.dp),
                            color = if (micSource == MicSource.PHONE) Color(0xFF0284C7).copy(alpha = 0.3f) else FifoColors.DarkInputBg,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (micSource == MicSource.PHONE) Color(0xFF0284C7) else FifoColors.DarkInputBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = if (micSource == MicSource.PHONE) Color(0xFF38BDF8) else FifoColors.BlueAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (micSource == MicSource.PHONE) "Escuchando desde el celular..." else "¿No lo tienes cerca? Hablar desde este celular",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Tarjeta de Conversación: Lo que dijo el usuario
                        if (transcription.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = FifoColors.DarkInputBg,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder)
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Text(
                                        text = "Usted dijo:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = FifoColors.DarkBadgeText
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "\"$transcription\"",
                                        fontSize = 17.sp,
                                        color = Color.White,
                                        lineHeight = 24.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        // Tarjeta de Conversación: Respuesta cariñosa de Fifo
                        if (aiResponse.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF1E3A5F).copy(alpha = 0.55f),
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.45f))
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = null,
                                            tint = FifoColors.BlueAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Fifo le responde:",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = FifoColors.BlueAccent
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = aiResponse,
                                        fontSize = 17.sp,
                                        color = Color.White,
                                        lineHeight = 25.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                        }

                        // Si no hay conversación aún, mensaje acogedor y claro
                        if (transcription.isEmpty() && aiResponse.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = FifoColors.DarkInputBg,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder)
                            ) {
                                Column(
                                    modifier = Modifier.padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "¡Hola! Estoy listo para escucharle con agrado.",
                                        fontSize = 16.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Diga 'Fifo' a su robot o use el botón de arriba para hablar por su celular.",
                                        fontSize = 14.sp,
                                        color = FifoColors.DarkBadgeText,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        }

                        if (transcription.isNotEmpty() || aiResponse.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(
                                onClick = onClearConversation,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("Limpiar diálogo", fontSize = 14.sp, color = FifoColors.DarkBadgeText)
                            }
                        }
                    }
                }
            }
        }
    }
}
