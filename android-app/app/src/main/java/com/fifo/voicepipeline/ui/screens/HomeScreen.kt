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
import com.fifo.voicepipeline.pipeline.PipelineState
import com.fifo.voicepipeline.ui.components.FifoFace
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
    modifier: Modifier = Modifier
) {
    var selectedMood by remember { mutableStateOf<String?>("Bien") }

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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Si está conectado, botón táctil grande (48dp) para silenciar/activar micrófono. Si está desconectado, no mostramos botón de Bluetooth para evitar confusión.
                if (isBleConnected) {
                    Surface(
                        shape = CircleShape,
                        color = if (isMicMuted) Color(0xFFFEE2E2) else Color.White,
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            if (isMicMuted) Color(0xFFDC2626) else FifoColors.LightCardBorder
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { onToggleMicMute() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = if (isMicMuted) "Micrófono silenciado. Toque para activar" else "Micrófono activo. Toque para silenciar",
                                tint = if (isMicMuted) Color(0xFFDC2626) else FifoColors.NavyPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
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
        }

        Spacer(modifier = Modifier.height(22.dp))

        // ── HERO CARD: Dinámica según conexión ("Conecte a Fifo" o "Tu Fifo está conectado") ───
        Surface(
            color = FifoColors.HeroBlueCard,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBleConnected) "Tu Fifo está conectado" else "Prende a tu Fifo",
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = if (isBleConnected)
                                "¿No lo tienes cerca? Habla con él desde aquí en tu celular o dile 'Fifo' a su micrófono."
                            else
                                "Enciende el robot y dale click al botón para que te acompañe hoy.",
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = FifoColors.NavyPrimary.copy(alpha = 0.90f)
                        )
                    }

                    // Rostro de Fifo animado y expresivo
                    Box(modifier = Modifier.padding(start = 14.dp)) {
                        FifoFace(
                            size = 80.dp,
                            isDarkTheme = false,
                            state = if (!isBleConnected) PipelineState.DISCONNECTED else PipelineState.IDLE
                        )
                    }
                }

                // Banner de micrófono silenciado SOLO si está conectado
                if (isBleConnected && isMicMuted) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFCA5A5)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleMicMute() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.MicOff,
                                contentDescription = null,
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "El micrófono está en silencio · Toque aquí para activarlo",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFDC2626)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // BOTÓN PRINCIPAL: "Conectar con Fifo" o "Hablar con Fifo"
                Button(
                    onClick = onStartVoiceChat,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FifoColors.NavyPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isBleConnected) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Conectar con Fifo",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "Hablar con Fifo",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
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
                text = "Recordatorios",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Surface(
                color = FifoColors.BlueSoftPill,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "3 para hoy",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.NavyPrimary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReminderItem(
                icon = Icons.Outlined.Schedule,
                title = "Llamar a mamá",
                subtitle = "10:30 hrs · Hoy",
                badgeText = "Hoy"
            )
            ReminderItem(
                icon = Icons.Default.PhoneAndroid,
                title = "Revisar agenda",
                subtitle = "19:00 hrs · Hoy",
                badgeText = "Hoy"
            )
            ReminderItem(
                icon = Icons.Outlined.Air,
                title = "Respiración de calma",
                subtitle = "Antes de dormir",
                badgeText = "Rutina"
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        // ── Sección: Resumen con Fifo ───
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
                                text = "Resumen con Fifo",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.NavyPrimary
                            )
                            Text(
                                text = "De nuestras conversaciones",
                                fontSize = 13.sp,
                                color = FifoColors.LightTextSecondary
                            )
                        }
                    }

                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "2 notas",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Recordemos lo que conversamos para que no tenga que repetirse:",
                    fontSize = 13.sp,
                    color = FifoColors.LightTextSecondary,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(FifoColors.BlueSoftPill, CircleShape)
                            .border(1.dp, FifoColors.NavyPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = FifoColors.NavyPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Conversó sobre sus proyectos y se sintió con más energía y tranquilidad.",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextPrimary,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(FifoColors.BlueSoftPill, CircleShape)
                            .border(1.dp, FifoColors.NavyPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = FifoColors.NavyPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Fifo le ayudó a organizar y priorizar sus actividades de hoy con calma.",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextPrimary,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onStartVoiceChat,
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
                        Text("Ver conversaciones anteriores", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
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
