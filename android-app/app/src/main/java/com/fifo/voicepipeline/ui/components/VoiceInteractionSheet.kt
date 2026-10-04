package com.fifo.voicepipeline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
    var isPhoneMicMode by remember(micSource) { mutableStateOf(micSource == MicSource.PHONE) }
    var showDeviceFinderDialog by remember { mutableStateOf(false) }

    val isContinuousListening by com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.collectAsState()
    val incomingCall by com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.collectAsState()

    LaunchedEffect(isBleConnected) {
        if (wasEverDisconnected && isBleConnected && !isPhoneMicMode && micSource != MicSource.PHONE) {
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
                    if (!isBleConnected && !isPhoneMicMode && micSource != MicSource.PHONE) {
                        // ══════════════════════════════════════════════════════════
                        // PANTALLA 1: CONECTAR A FIFO O HABLAR DESDE EL CELULAR
                        // ══════════════════════════════════════════════════════════

                        // Barra superior limpia: Título sutil + Botón Cerrar claro y accesible
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFFF59E0B), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Fifo Asistente",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }

                            // Botón Cerrar con texto e ícono visible para adultos mayores
                            Surface(
                                onClick = onDismiss,
                                color = FifoColors.DarkInputBg,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder),
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cerrar",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Cerrar",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
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
                            text = "Prende a tu Fifo",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Enciende el robot cerca de ti y dale click al botón para comenzar a conversar.",
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
                                    text = "Buscando a tu Fifo...",
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
                                    text = "Conectar con Fifo",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // BOTÓN SECUNDARIO: Hablar desde el celular sin necesidad de robot
                        Button(
                            onClick = {
                                isPhoneMicMode = true
                                onTalkFromPhone()
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FifoColors.DarkInputBg,
                                contentColor = Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF0284C7)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Hablar desde el celular",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // ACCIÓN TERCIARIA: Rastrear robot si se perdió
                        Surface(
                            onClick = { showDeviceFinderDialog = true },
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFF78350F).copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationSearching,
                                    contentDescription = null,
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "¿Dónde dejé a mi robot? (Buscar Fifo)",
                                    fontSize = 15.sp,
                                    color = Color(0xFFFDE68A),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    } else {
                        // ══════════════════════════════════════════════════════════
                        // PANTALLA 2: INTERACCIÓN DE VOZ COMPLETA (FIFO CONECTADO O CELULAR)
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
                                                if (isContinuousListening) Color(0xFF34D399)
                                                else if (!isBleConnected) Color(0xFFFBBF24)
                                                else if (micSource == MicSource.PHONE) Color(0xFF38BDF8)
                                                else Color(0xFF10B981),
                                                CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isContinuousListening) "Escucha Continua (Sin 'Fifo')"
                                               else if (!isBleConnected) "Modo Celular (Sin Robot)"
                                               else if (micSource == MicSource.PHONE) "Micrófono Celular"
                                               else "Fifo Conectado · Mic Robot",
                                        fontSize = 12.sp,
                                        color = if (isContinuousListening) Color(0xFF34D399)
                                                else if (!isBleConnected) Color(0xFFFBBF24)
                                                else if (micSource == MicSource.PHONE) Color(0xFF38BDF8)
                                                else Color(0xFF10B981),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Botón Cerrar claro con texto e ícono (sin íconos misteriosos en la esquina)
                            Surface(
                                onClick = onDismiss,
                                color = FifoColors.DarkInputBg,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder),
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cerrar",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Cerrar",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // BANNER DE LLAMADA ENTRANTE (ALEXA STYLE)
                        incomingCall?.let { call ->
                            if (call.isRinging) {
                                Surface(
                                    color = Color(0xFF450A0A),
                                    shape = RoundedCornerShape(20.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFEF4444)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color(0xFFEF4444),
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.Call,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "¡Llamada de ${call.callerName}!",
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                                Text(
                                                    text = "Diga 'Fifo contesta' o use los botones",
                                                    fontSize = 12.sp,
                                                    color = Color(0xFFFCA5A5)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(14.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Button(
                                                onClick = { com.fifo.voicepipeline.data.FifoDataRepository.answerCurrentCall() },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.weight(1f).height(44.dp)
                                            ) {
                                                Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Contestar", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                            }

                                            OutlinedButton(
                                                onClick = { com.fifo.voicepipeline.data.FifoDataRepository.hangupCurrentCall() },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444)),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.weight(1f).height(44.dp)
                                            ) {
                                                Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Colgar", fontWeight = FontWeight.Bold, color = Color(0xFFEF4444), fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                            }
                        }

                        // Rostro animado y expresivo de Fifo (táctil para despertar o activar)
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    if (isMicMuted) {
                                        onToggleMicMute()
                                    } else {
                                        onTalkFromPhone()
                                    }
                                }
                        ) {
                            FifoFace(
                                size = 130.dp,
                                state = if (isMicMuted) PipelineState.SLEEPING else state,
                                isDarkTheme = true,
                                rmsLevel = if (isMicMuted) 0f else rmsLevel
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Estado del pipeline (Único badge de estado)
                        val (statusText, statusBg, statusColor) = if (isMicMuted) {
                            Triple("Micrófono Silenciado · Toque para activar", Color(0xFFEF4444).copy(alpha = 0.2f), Color(0xFFEF4444))
                        } else if (isContinuousListening && state == PipelineState.LISTENING) {
                            Triple("Escucha continua activa · Hable sin 'Fifo'", Color(0xFF064E3B).copy(alpha = 0.6f), Color(0xFF34D399))
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

                        Spacer(modifier = Modifier.height(16.dp))

                        // ── BOTÓN DE MICRÓFONO PRINCIPAL (GRANDE, ACCESIBLE Y ENTENDIBLE) ──
                        if (isMicMuted) {
                            Surface(
                                onClick = onToggleMicMute,
                                shape = RoundedCornerShape(22.dp),
                                color = Color(0xFFDC2626),
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 56.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Tocar para hablar con Fifo",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "El micrófono está pausado en este momento",
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.9f)
                                        )
                                    }
                                }
                            }
                        } else {
                            Surface(
                                onClick = onToggleMicMute,
                                shape = RoundedCornerShape(22.dp),
                                color = FifoColors.DarkInputBg,
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 52.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MicOff,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Silenciar micrófono",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // ── ACCIÓN SECUNDARIA SEGÚN ESTADO (RASTREAR O HABLAR DESDE CELULAR) ──
                        if (!isBleConnected) {
                            // Modo Celular: Opción clara para buscar el robot físico si no sabe dónde está
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                onClick = { showDeviceFinderDialog = true },
                                shape = RoundedCornerShape(22.dp),
                                color = Color(0xFF1E293B).copy(alpha = 0.5f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 54.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .background(Color(0xFFF59E0B).copy(alpha = 0.20f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocationSearching,
                                            contentDescription = null,
                                            tint = Color(0xFFFBBF24),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "¿Dónde dejé a mi robot? (Buscar Fifo)",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFDE68A)
                                        )
                                        Text(
                                            text = "Ver última ubicación GPS o hacerlo sonar",
                                            fontSize = 11.sp,
                                            color = Color(0xFFFBBF24).copy(alpha = 0.85f)
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = Color(0xFFFBBF24).copy(alpha = 0.70f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            // Robot conectado: Opción para hablar desde el teléfono si el robot está en otra habitación
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                onClick = onTalkFromPhone,
                                shape = RoundedCornerShape(22.dp),
                                color = if (micSource == MicSource.PHONE) Color(0xFF0284C7).copy(alpha = 0.25f) else FifoColors.DarkInputBg,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (micSource == MicSource.PHONE) Color(0xFF0284C7) else FifoColors.DarkInputBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 54.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .background(
                                                (if (micSource == MicSource.PHONE) Color(0xFF38BDF8) else FifoColors.BlueAccent).copy(alpha = 0.20f),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PhoneAndroid,
                                            contentDescription = null,
                                            tint = if (micSource == MicSource.PHONE) Color(0xFF38BDF8) else FifoColors.BlueAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (micSource == MicSource.PHONE) "Escuchando desde este celular" else "¿Robot lejos? Hablar desde este celular",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = if (micSource == MicSource.PHONE) "Micrófono del teléfono activo" else "Usa el micrófono del teléfono para responder",
                                            fontSize = 11.sp,
                                            color = FifoColors.DarkBadgeText
                                        )
                                    }
                                }
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

        // Modal de búsqueda de robot Fifo (Localizador GPS + Sonido Beep)
        if (showDeviceFinderDialog) {
            FifoDeviceFinderDialog(
                onDismiss = { showDeviceFinderDialog = false },
                onScanBle = {
                    showDeviceFinderDialog = false
                    onConnectBle()
                }
            )
        }
    }
}
