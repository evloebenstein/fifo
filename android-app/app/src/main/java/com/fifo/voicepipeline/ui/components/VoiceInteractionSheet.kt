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
    micSource: MicSource = MicSource.ESP32,
    statusMessage: String = "",
    transcription: String,
    aiResponse: String,
    rmsLevel: Float,
    isBleConnected: Boolean,
    onDismiss: () -> Unit,
    onConnectBle: () -> Unit,
    onTalkFromPhone: () -> Unit = {},
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
                    // Barra superior: Estado Conexión/Mic + Botón Mute + Botón Cerrar (Targets ≥48dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Badge de estado de conexión y micrófono
                        Surface(
                            color = if (!isBleConnected) Color(0xFFF59E0B).copy(alpha = 0.2f)
                            else if (isMicMuted) Color(0xFFEF4444).copy(alpha = 0.2f)
                            else FifoColors.DarkInputBg,
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (!isBleConnected) Color(0xFFF59E0B)
                                else if (isMicMuted) Color(0xFFEF4444)
                                else FifoColors.DarkInputBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (!isBleConnected) Icons.Default.Bluetooth
                                    else if (isMicMuted) Icons.Default.MicOff
                                    else if (micSource == MicSource.PHONE) Icons.Default.PhoneAndroid
                                    else Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = if (!isBleConnected) Color(0xFFF59E0B)
                                    else if (isMicMuted) Color(0xFFEF4444)
                                    else FifoColors.BlueAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (!isBleConnected) "Fifo Desconectado"
                                    else if (isMicMuted) "Micrófono Silenciado"
                                    else if (micSource == MicSource.PHONE) "Micrófono Celular Activo"
                                    else "Fifo Conectado · Mic Robot",
                                    fontSize = 12.sp,
                                    color = if (!isBleConnected) Color(0xFFF59E0B)
                                    else if (isMicMuted) Color(0xFFEF4444)
                                    else FifoColors.BlueAccent,
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

                    Spacer(modifier = Modifier.height(18.dp))

                    // Rostro de Fifo animado (si no está conectado, muestra reposo/desconectado)
                    FifoFace(
                        size = 140.dp,
                        state = if (!isBleConnected) PipelineState.DISCONNECTED
                        else if (isMicMuted) PipelineState.SLEEPING
                        else state,
                        isDarkTheme = true,
                        rmsLevel = if (isMicMuted || !isBleConnected) 0f else rmsLevel
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Estado del pipeline (Badge interactivo de alta legibilidad)
                    val (statusText, statusBg, statusColor) = if (!isBleConnected) {
                        Triple("Fifo no conectado · Conecte por Bluetooth", Color(0xFFF59E0B).copy(alpha = 0.2f), Color(0xFFF59E0B))
                    } else if (isMicMuted) {
                        Triple("Micrófono Silenciado (Mute)", Color(0xFFEF4444).copy(alpha = 0.2f), Color(0xFFEF4444))
                    } else when (state) {
                        PipelineState.LISTENING -> Triple(
                            if (micSource == MicSource.PHONE) "Escuchando por el celular..." else "Escuchando por el robot Fifo...",
                            FifoColors.StatusListening.copy(alpha = 0.2f),
                            FifoColors.StatusListening
                        )
                        PipelineState.PROCESSING -> Triple("Fifo está pensando...", FifoColors.StatusProcessing.copy(alpha = 0.2f), FifoColors.StatusProcessing)
                        PipelineState.SPEAKING -> Triple("Fifo le responde...", FifoColors.StatusSpeaking.copy(alpha = 0.2f), FifoColors.StatusSpeaking)
                        PipelineState.SLEEPING -> Triple("En reposo · Diga 'Fifo' al robot", FifoColors.NavyPrimary.copy(alpha = 0.3f), Color(0xFF93C5FD))
                        PipelineState.IDLE -> Triple("Hable cuando desee", FifoColors.DarkInputBg, Color(0xFF94A3B8))
                        PipelineState.DISCONNECTED -> Triple("Fifo no conectado · Conecte por Bluetooth", Color(0xFFF59E0B).copy(alpha = 0.2f), Color(0xFFF59E0B))
                        PipelineState.ERROR -> Triple("Reintentando conexión...", FifoColors.StatusError.copy(alpha = 0.2f), FifoColors.StatusError)
                    }

                    Surface(
                        color = statusBg,
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, statusColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Botón destacado táctil (52dp) para silenciar / activar micrófono
                    Surface(
                        onClick = onToggleMicMute,
                        shape = RoundedCornerShape(22.dp),
                        color = if (isMicMuted) Color(0xFFEF4444).copy(alpha = 0.25f) else FifoColors.DarkInputBg,
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            if (isMicMuted) Color(0xFFEF4444) else FifoColors.DarkInputBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 18.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (isMicMuted) Color(0xFFEF4444) else FifoColors.BlueAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isMicMuted) "Micrófono Silenciado · Toque para activar" else "Micrófono Activo · Toque para silenciar",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMicMuted) Color(0xFFFCA5A5) else Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── CONDICIONAL: SI NO ESTÁ CONECTADO, PIDE CONECTAR BLUETOOTH PRIMERO ──
                    if (!isBleConnected) {
                        Surface(
                            color = FifoColors.DarkInputBg,
                            shape = RoundedCornerShape(22.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = FifoColors.BlueAccent,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Conecte su robot Fifo",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Encienda su robot Fifo cerca de usted y toque aquí para conectarlo por Bluetooth.",
                                    fontSize = 13.sp,
                                    color = FifoColors.DarkBadgeText,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 19.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = onConnectBle,
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF0284C7),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                ) {
                                    Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Conectar Fifo por Bluetooth", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Opción alternativa si no tiene el robot cerca
                        Surface(
                            onClick = onTalkFromPhone,
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFF1E263D).copy(alpha = 0.8f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = FifoColors.BlueAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "¿No tiene el robot cerca? Hablar desde este celular",
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        // ── SI ESTÁ CONECTADO: FIFO ESCUCHA POR SU MICRÓFONO FÍSICO + OPCIÓN CELULAR ──
                        Surface(
                            color = FifoColors.DarkInputBg,
                            shape = RoundedCornerShape(22.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = FifoColors.BlueAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Fifo le escucha por su micrófono físico",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "Puede hablarle a Fifo directamente diciendo su nombre al robot. Si está lejos o prefiere, también puede usar el micrófono de este celular:",
                                    fontSize = 13.sp,
                                    color = FifoColors.DarkBadgeText,
                                    lineHeight = 19.sp
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = onTalkFromPhone,
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (micSource == MicSource.PHONE) Color(0xFF0284C7) else Color(0xFF1E3A5F),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhoneAndroid,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = if (micSource == MicSource.PHONE) "Escuchando desde el celular..." else "Hablar desde este celular",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

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
                                        imageVector = Icons.Default.VolumeUp,
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
                                    text = if (isBleConnected)
                                        "Hable con calma cerca del micrófono de su robot Fifo diciendo 'Fifo', o use el botón de arriba para hablar por el celular."
                                    else
                                        "Conecte su robot por Bluetooth o pulse 'Hablar desde este celular'.",
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

