package com.fifo.voicepipeline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fifo.voicepipeline.pipeline.PipelineState
import com.fifo.voicepipeline.ui.components.FifoFace
import com.fifo.voicepipeline.ui.model.FifoMemoryItem
import com.fifo.voicepipeline.ui.model.PastConversationItem
import com.fifo.voicepipeline.ui.theme.FifoColors

@Composable
fun HomeScreen(
    userName: String,
    onStartVoiceChat: () -> Unit,
    onOpenBreathingExercise: () -> Unit,
    isMicMuted: Boolean = false,
    onToggleMicMute: () -> Unit = {},
    isBleConnected: Boolean = false,
    onConnectBle: () -> Unit = {},
    onTalkFromPhone: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedMood by remember { mutableStateOf<String?>("Bien") }

    val liveReminders by com.fifo.voicepipeline.data.FifoDataRepository.reminders.collectAsState()
    val liveConversations by com.fifo.voicepipeline.data.FifoDataRepository.conversations.collectAsState()
    val liveMemories by com.fifo.voicepipeline.data.FifoDataRepository.memories.collectAsState()
    val incomingCall by com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.collectAsState()
    val isContinuousListening by com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.collectAsState()

    var isConversationsSheetOpen by remember { mutableStateOf(false) }
    var isAddMemoryOpen by remember { mutableStateOf(false) }
    var isFinderOpen by remember { mutableStateOf(false) }
    var selectedHistoryTab by remember { mutableStateOf(0) } // 0: Charlas, 1: Recuerdos

    var conversations by remember {
        mutableStateOf(
            listOf(
                PastConversationItem(
                    id = "c1",
                    title = "Receta familiar y tarta de manzana",
                    date = "Hoy · 10:30 hrs",
                    duration = "4 minutos con Fifo",
                    summary = "Fifo te ayudó a recordar la receta de tu abuela Elena para la tarta de manzana con canela y nueces. Guardó tu preferencia de usar manzanas verdes.",
                    tag = "Cocina y familia",
                    iconName = "restaurant"
                ),
                PastConversationItem(
                    id = "c2",
                    title = "Caminata matutina y música clásica",
                    date = "Ayer · 16:45 hrs",
                    duration = "6 minutos con Fifo",
                    summary = "Charlaron sobre cómo estuvo el día en el parque y escucharon una melodía suave de piano de Chopin para relajarse juntos.",
                    tag = "Bienestar y música",
                    iconName = "music"
                ),
                PastConversationItem(
                    id = "c3",
                    title = "Planes para el cumpleaños de Mateo",
                    date = "Martes · 11:15 hrs",
                    duration = "5 minutos con Fifo",
                    summary = "Comentaste ideas de regalo para tu nieto en mayo y Fifo sugirió escribirle una carta con una anécdota especial de la familia.",
                    tag = "Afectos",
                    iconName = "heart"
                ),
                PastConversationItem(
                    id = "c4",
                    title = "Respiración y descanso nocturno",
                    date = "Domingo · 20:00 hrs",
                    duration = "3 minutos con Fifo",
                    summary = "Fifo te acompañó con su ejercicio de respiración guiada de 2 minutos para descansar con la mente tranquila.",
                    tag = "Relajación",
                    iconName = "air"
                )
            )
        )
    }

    var memories by remember {
        mutableStateOf(
            listOf(
                FifoMemoryItem(
                    id = "1",
                    emoji = "🎂",
                    title = "Cumpleaños de tu nieto Mateo",
                    detail = "Tu nieto Mateo cumple años en mayo y le encantan los libros sobre planetas y astronomía.",
                    learnedDate = "Aprendido hace 3 días"
                ),
                FifoMemoryItem(
                    id = "2",
                    emoji = "☕",
                    title = "Té de manzanilla antes de dormir",
                    detail = "Prefieres tomar infusión de manzanilla tibia antes de acostarte para descansar plácidamente.",
                    learnedDate = "Aprendido hace 5 días"
                ),
                FifoMemoryItem(
                    id = "3",
                    emoji = "🎹",
                    title = "Música de Chopin para relajarse",
                    detail = "Te calma escuchar los nocturnos de piano de Frédéric Chopin por la tarde.",
                    learnedDate = "Aprendido hace 1 semana"
                ),
                FifoMemoryItem(
                    id = "4",
                    emoji = "🌸",
                    title = "Cuidado de las orquídeas",
                    detail = "Riegas y ventilas tus orquídeas del balcón los martes por la mañana.",
                    learnedDate = "Aprendido hace 2 semanas"
                ),
                FifoMemoryItem(
                    id = "5",
                    emoji = "🚶‍♀️",
                    title = "Caminatas cuando no hay viento",
                    detail = "Sales a caminar al parque cuando el sol está tibio y no hay viento frío.",
                    learnedDate = "Aprendido hace 2 semanas"
                )
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FifoColors.LightBackground)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // ── Top Header: Saludo cálido y Accesos de Audio / Notificaciones ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hola, $userName",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.LightTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Qué bueno tenerle por aquí hoy.",
                    fontSize = 14.sp,
                    color = FifoColors.LightTextSecondary
                )
            }

            // Botón notificaciones táctil grande (48dp)
            Surface(
                shape = CircleShape,
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = "Notificaciones",
                        tint = FifoColors.LightTextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ── BANNER DE LLAMADA ENTRANTE / EN CURSO TIPO ALEXA ─────────────
        incomingCall?.let { call ->
            if (call.isRinging) {
                Surface(
                    color = Color(0xFFFEF2F2),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFEF4444)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFEF4444),
                                modifier = Modifier.size(46.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Call,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "¡Llamada de ${call.callerName}!",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF991B1B)
                                )
                                Text(
                                    text = "Diga 'Fifo contesta' o 'Fifo cuelga', o use los botones",
                                    fontSize = 13.sp,
                                    color = Color(0xFFB91C1C)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { com.fifo.voicepipeline.data.FifoDataRepository.answerCurrentCall() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Contestar", fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            OutlinedButton(
                                onClick = { com.fifo.voicepipeline.data.FifoDataRepository.hangupCurrentCall() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFDC2626)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Colgar", fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                // LLAMADA ACTIVA / EN CURSO
                Surface(
                    color = Color(0xFFF0FDF4),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF16A34A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF16A34A),
                                modifier = Modifier.size(46.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PhoneInTalk,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "En llamada con ${call.callerName}",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF166534)
                                )
                                Text(
                                    text = "Fifo escuchando · Diga 'Fifo cuelga' para terminar",
                                    fontSize = 13.sp,
                                    color = Color(0xFF15803D)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { com.fifo.voicepipeline.data.FifoDataRepository.hangupCurrentCall() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Colgar llamada", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // ── BANNER DE ESCUCHA CONTINUA ("Fifo sigue escuchando") ─────
        if (isContinuousListening) {
            Surface(
                color = Color(0xFFECFDF5),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF10B981)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF10B981),
                            modifier = Modifier.size(12.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Fifo sigue escuchando activamente",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF065F46)
                            )
                            Text(
                                text = "Hable libremente sin decir 'Fifo'. Diga 'Fifo descansa' para pausar.",
                                fontSize = 12.sp,
                                color = Color(0xFF047857)
                            )
                        }
                    }

                    TextButton(
                        onClick = { com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(false) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Pausar",
                            color = Color(0xFF047857),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── HERO CARD: Premium redesign ───────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            if (isBleConnected) Color(0xFFD1F0E8) else Color(0xFFD6E8FA),
                            if (isBleConnected) Color(0xFFEAFBF5) else Color(0xFFEBF4FF)
                        )
                    )
                )
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                // Fila principal: Título y descripción a la izquierda, rostro de Fifo a la derecha
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (isBleConnected) "Tu Fifo está listo" else "Prende a tu Fifo",
                            fontSize = 25.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = FifoColors.NavyPrimary
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (isBleConnected)
                                "Dile 'Fifo' cuando quieras hablar, o toca el botón de abajo."
                            else
                                "Enciende el robot y toca el botón para que Fifo te acompañe hoy.",
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = FifoColors.NavyPrimary.copy(alpha = 0.75f)
                        )
                    }

                    // Fifo face a la derecha
                    FifoFace(
                        size = 76.dp,
                        isDarkTheme = false,
                        state = if (!isBleConnected) PipelineState.DISCONNECTED else PipelineState.IDLE
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // BOTÓN PRINCIPAL — grande, accesible, premium
                Button(
                    onClick = if (!isBleConnected) onConnectBle else onStartVoiceChat,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FifoColors.NavyPrimary,
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 2.dp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                ) {
                    if (!isBleConnected) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Conectar con Fifo",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Hablar con Fifo",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        )
                    }
                }

                // ACCIÓN SECUNDARIA — solo cuando no está conectado
                if (!isBleConnected) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Hablar por celular
                        OutlinedButton(
                            onClick = onTalkFromPhone,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = FifoColors.NavyPrimary
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.2.dp, FifoColors.NavyPrimary.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = FifoColors.NavyPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Por celular",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Dónde está
                        OutlinedButton(
                            onClick = { isFinderOpen = true },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFD97706)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.2.dp, Color(0xFFD97706).copy(alpha = 0.50f)
                            ),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationSearching,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFFD97706)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "¿Dónde está?",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFD97706)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(26.dp))

        // ── "¿Cómo se siente hoy?" (Selector de estado de ánimo holgado) ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "¿Cómo se siente hoy?",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Text(
                text = "Su momento",
                fontSize = 13.sp,
                color = FifoColors.LightTextMuted
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val moods = listOf(
                Pair("Bien", "Bien"),
                Pair("Regular", "Más o menos"),
                Pair("Bajo", "No muy bien")
            )

            moods.forEach { (id, label) ->
                val isSelected = selectedMood == id
                Surface(
                    onClick = { selectedMood = id },
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) FifoColors.BlueSoftPill else Color.White,
                    border = androidx.compose.foundation.BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) FifoColors.NavyPrimary else FifoColors.LightCardBorder
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 84.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp)
                    ) {
                        MoodFaceIcon(moodId = id, isSelected = isSelected)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) FifoColors.NavyPrimary else FifoColors.LightTextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Sea como sea, está muy bien sentirlo. Aquí estoy para acompañarle.",
            fontSize = 13.sp,
            color = FifoColors.LightTextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Tarjeta de Respiración: "Baja el ritmo" ───
        Surface(
            onClick = onOpenBreathingExercise,
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = FifoColors.BlueSoftPill,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Air,
                            contentDescription = null,
                            tint = FifoColors.NavyPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Baja el ritmo",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "2 minutos de respiración guiada para relajarse",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextSecondary
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Abrir ejercicio de respiración",
                    tint = FifoColors.LightTextMuted,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(26.dp))

        // ── Sección Recordatorios (badge "3 hoy") ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recordatorios de Fifo",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Surface(
                color = FifoColors.BlueSoftPill,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "${liveReminders.count { !it.isCompleted }} activos",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.NavyPrimary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (liveReminders.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No tienes recordatorios pendientes para hoy. Pídele a Fifo: 'Recuérdame tomar mi medicina'.",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                liveReminders.forEach { item ->
                    val icon = when (item.category) {
                        "medication" -> Icons.Default.Favorite
                        "family" -> Icons.Default.Phone
                        "health" -> Icons.Outlined.Air
                        "sport" -> Icons.Default.FitnessCenter
                        else -> Icons.Outlined.Schedule
                    }
                    ReminderItem(
                        icon = icon,
                        title = item.title,
                        subtitle = "${item.timeStr} hrs · ${item.createdAt}",
                        badgeText = if (item.isCompleted) "Listo" else "Pendiente"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(26.dp))

        // ── Sección: Charlas y Recuerdos con Fifo ───
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = FifoColors.HeroBlueCard.copy(alpha = 0.55f),
            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.HeroBlueBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FifoFace(size = 42.dp, isDarkTheme = false)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Charlas y Recuerdos",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.NavyPrimary
                            )
                            Text(
                                text = "Planes, gustos y notas de Fifo",
                                fontSize = 13.sp,
                                color = FifoColors.LightTextSecondary
                            )
                        }
                    }

                    val memoryCount = memories.size
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "$memoryCount ${if (memoryCount == 1) "recuerdo" else "recuerdos"}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (memories.isEmpty()) {
                    Text(
                        text = "Conversa con Fifo para que recuerde tus rutinas, planes de entrenamiento (como Jiu-Jitsu) o proyectos sin tener que repetirlos.",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextSecondary,
                        lineHeight = 18.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        memories.take(3).forEach { mem ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(text = mem.emoji, fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = mem.title,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.LightTextPrimary
                                    )
                                    Text(
                                        text = mem.detail,
                                        fontSize = 12.sp,
                                        color = FifoColors.LightTextSecondary,
                                        maxLines = 2,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = { isConversationsSheetOpen = true },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FifoColors.NavyPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ver charlas y recuerdos con Fifo", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }

    // ══════════════════════════════════════════════════════════
    // ── DIÁLOGO COMPLETO DE CHARLAS Y RECUERDOS CON FIFO ───────
    // ══════════════════════════════════════════════════════════
    if (isConversationsSheetOpen) {
        Dialog(onDismissRequest = { isConversationsSheetOpen = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Cabecera con botón de cerrar accesible
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Charlas y Recuerdos",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.NavyPrimary
                            )
                            Text(
                                text = "Tu historial de confianza con Fifo",
                                fontSize = 12.sp,
                                color = FifoColors.LightTextSecondary
                            )
                        }
                        IconButton(
                            onClick = { isConversationsSheetOpen = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = FifoColors.NavyPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Selector de Pestañas (Charlas vs Recuerdos)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(14.dp))
                            .padding(4.dp)
                    ) {
                        Surface(
                            onClick = { selectedHistoryTab = 0 },
                            shape = RoundedCornerShape(11.dp),
                            color = if (selectedHistoryTab == 0) Color.White else Color.Transparent,
                            shadowElevation = if (selectedHistoryTab == 0) 2.dp else 0.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Charlas (${conversations.size})",
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedHistoryTab == 0) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedHistoryTab == 0) FifoColors.NavyPrimary else FifoColors.LightTextSecondary
                                )
                            }
                        }

                        Surface(
                            onClick = { selectedHistoryTab = 1 },
                            shape = RoundedCornerShape(11.dp),
                            color = if (selectedHistoryTab == 1) Color.White else Color.Transparent,
                            shadowElevation = if (selectedHistoryTab == 1) 2.dp else 0.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Recuerdos (${memories.size})",
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedHistoryTab == 1) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedHistoryTab == 1) FifoColors.NavyPrimary else FifoColors.LightTextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Contenido según pestaña
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (selectedHistoryTab == 0) {
                            // ── PESTAÑA: CHARLAS PASADAS ───
                            if (conversations.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No tienes charlas guardadas aún.",
                                        fontSize = 14.sp,
                                        color = FifoColors.LightTextMuted
                                    )
                                }
                            } else {
                                conversations.forEach { conv ->
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xFFF8FAFC),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = conv.title,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = FifoColors.LightTextPrimary
                                                    )
                                                    Text(
                                                        text = "${conv.date} · ${conv.duration}",
                                                        fontSize = 11.sp,
                                                        color = FifoColors.BlueAccentDark
                                                    )
                                                }

                                                // Botón accesible para borrar charla
                                                IconButton(
                                                    onClick = { conversations = conversations.filterNot { it.id == conv.id } },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteOutline,
                                                        contentDescription = "Borrar esta charla",
                                                        tint = Color(0xFFDC2626),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Text(
                                                text = conv.summary,
                                                fontSize = 12.sp,
                                                lineHeight = 17.sp,
                                                color = FifoColors.LightTextSecondary
                                            )

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Surface(
                                                color = Color.White,
                                                shape = RoundedCornerShape(8.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
                                            ) {
                                                Text(
                                                    text = conv.tag,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = FifoColors.LightTextSecondary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // ── PESTAÑA: RECUERDOS DE FIFO ───
                            // Botón para añadir recuerdo
                            Surface(
                                onClick = { isAddMemoryOpen = true },
                                shape = RoundedCornerShape(14.dp),
                                color = FifoColors.BlueSoftPill,
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.NavyPrimary.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = FifoColors.NavyPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "+ Añadir un recuerdo para Fifo",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.NavyPrimary
                                    )
                                }
                            }

                            if (memories.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Fifo aún no tiene recuerdos guardados.",
                                        fontSize = 14.sp,
                                        color = FifoColors.LightTextMuted
                                    )
                                }
                            } else {
                                memories.forEach { mem ->
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xFFF8FAFC),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Text(text = mem.emoji, fontSize = 22.sp)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = mem.title,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = FifoColors.LightTextPrimary
                                                )
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = mem.detail,
                                                    fontSize = 12.sp,
                                                    lineHeight = 17.sp,
                                                    color = FifoColors.LightTextSecondary
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = mem.learnedDate,
                                                    fontSize = 10.sp,
                                                    color = FifoColors.LightTextMuted
                                                )
                                            }

                                            // Botón accesible para borrar recuerdo
                                            IconButton(
                                                onClick = { memories = memories.filterNot { it.id == mem.id } },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteOutline,
                                                    contentDescription = "Borrar recuerdo",
                                                    tint = Color(0xFFDC2626),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // ── DIÁLOGO PARA AÑADIR UN RECUERDO (Con botón salir/cancelar)
    // ══════════════════════════════════════════════════════════
    if (isAddMemoryOpen) {
        var newTitle by remember { mutableStateOf("") }
        var newDetail by remember { mutableStateOf("") }

        Dialog(onDismissRequest = { isAddMemoryOpen = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    // Cabecera con título y botón de salir claramente visible
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Nuevo recuerdo para Fifo",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        IconButton(
                            onClick = { isAddMemoryOpen = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Salir sin guardar",
                                tint = FifoColors.NavyPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Fifo recordará este dato para personalizar sus conversaciones contigo.",
                        fontSize = 12.sp,
                        color = FifoColors.LightTextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Título del recuerdo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        placeholder = { Text("Ej: Cumpleaños de mi nieto Mateo", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Detalle o anécdota", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newDetail,
                        onValueChange = { newDetail = it },
                        placeholder = { Text("Ej: Es en mayo y le gustan los libros sobre planetas.", fontSize = 13.sp) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Botones de acción: Cancelar / Salir y Guardar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { isAddMemoryOpen = false },
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFCBD5E1)),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Text("Cancelar", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextSecondary)
                        }

                        Button(
                            onClick = {
                                if (newTitle.isNotBlank()) {
                                    memories = listOf(
                                        FifoMemoryItem(
                                            id = System.currentTimeMillis().toString(),
                                            emoji = "💡",
                                            title = newTitle.trim(),
                                            detail = newDetail.trim().ifEmpty { newTitle.trim() },
                                            learnedDate = "Añadido hoy"
                                        )
                                    ) + memories
                                    isAddMemoryOpen = false
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(50.dp)
                        ) {
                            Text("Guardar recuerdo", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Modal para encontrar y rastrear al robot Fifo
    if (isFinderOpen) {
        com.fifo.voicepipeline.ui.components.FifoDeviceFinderDialog(
            onDismiss = { isFinderOpen = false },
            onScanBle = {
                isFinderOpen = false
                onStartVoiceChat()
            }
        )
    }
}

@Composable
fun ReminderItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    badgeText: String
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = FifoColors.BlueSoftPill,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = FifoColors.NavyPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FifoColors.LightTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = FifoColors.LightTextSecondary
                )
            }

            Surface(
                color = Color(0xFFF1F5F9),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = FifoColors.LightTextSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Icono vectorial armónico y sereno para el selector de estado de ánimo.
 * Evita emojis crudos del sistema y mantiene coherencia de trazo y paleta terapéutica.
 */
@Composable
private fun MoodFaceIcon(
    moodId: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val accentColor = when (moodId) {
        "Bien" -> if (isSelected) Color(0xFF0F766E) else Color(0xFF0D9488)
        "Regular" -> if (isSelected) Color(0xFFB45309) else Color(0xFFD97706)
        else -> if (isSelected) Color(0xFF4338CA) else Color(0xFF6366F1)
    }
    val circleBg = when (moodId) {
        "Bien" -> if (isSelected) Color(0xFFCCFBF1) else Color(0xFFF0FDFA)
        "Regular" -> if (isSelected) Color(0xFFFEF3C7) else Color(0xFFFFFBEB)
        else -> if (isSelected) Color(0xFFE0E7FF) else Color(0xFFEEF2FF)
    }
    val circleBorder = when (moodId) {
        "Bien" -> if (isSelected) Color(0xFF5EEAD4) else Color(0xFFCCFBF1)
        "Regular" -> if (isSelected) Color(0xFFFCD34D) else Color(0xFFFEF3C7)
        else -> if (isSelected) Color(0xFFA5B4FC) else Color(0xFFE0E7FF)
    }

    Box(
        modifier = modifier
            .size(38.dp)
            .background(circleBg, CircleShape)
            .border(1.dp, circleBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(26.dp)) {
            val w = size.width
            val h = size.height
            val strokeW = 2.3.dp.toPx()

            when (moodId) {
                "Bien" -> {
                    // Ojos felices curvados ^  ^
                    drawArc(
                        color = accentColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(w * 0.18f, h * 0.28f),
                        size = Size(w * 0.26f, h * 0.22f),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = accentColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(w * 0.56f, h * 0.28f),
                        size = Size(w * 0.26f, h * 0.22f),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                    // Sonrisa amplia y acogedora
                    drawArc(
                        color = accentColor,
                        startAngle = 20f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(w * 0.24f, h * 0.38f),
                        size = Size(w * 0.52f, h * 0.42f),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }
                "Regular" -> {
                    // Ojos redondos tranquilos
                    drawCircle(
                        color = accentColor,
                        radius = 2.4.dp.toPx(),
                        center = Offset(w * 0.32f, h * 0.38f)
                    )
                    drawCircle(
                        color = accentColor,
                        radius = 2.4.dp.toPx(),
                        center = Offset(w * 0.68f, h * 0.38f)
                    )
                    // Boca recta calmada
                    drawLine(
                        color = accentColor,
                        start = Offset(w * 0.30f, h * 0.64f),
                        end = Offset(w * 0.70f, h * 0.64f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
                else -> {
                    // "Bajo": Ojos tiernos con cuidado y boca suavemente empática
                    drawCircle(
                        color = accentColor,
                        radius = 2.4.dp.toPx(),
                        center = Offset(w * 0.32f, h * 0.40f)
                    )
                    drawCircle(
                        color = accentColor,
                        radius = 2.4.dp.toPx(),
                        center = Offset(w * 0.68f, h * 0.40f)
                    )
                    // Arco suave y empático pidiendo compañía
                    drawArc(
                        color = accentColor,
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(w * 0.26f, h * 0.58f),
                        size = Size(w * 0.48f, h * 0.30f),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}
