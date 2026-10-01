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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fifo.voicepipeline.ui.components.FifoFace
import com.fifo.voicepipeline.ui.theme.FifoColors

@Composable
fun HomeScreen(
    userName: String,
    onStartVoiceChat: () -> Unit,
    onOpenBreathingExercise: () -> Unit,
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

        // ── Top Header: Saludo y Notificaciones ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Hola, $userName",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.LightTextPrimary
                )
                Text(
                    text = "Qué bueno verte por aquí.",
                    fontSize = 13.sp,
                    color = FifoColors.LightTextSecondary
                )
            }

            Surface(
                shape = CircleShape,
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = "Notificaciones",
                        tint = FifoColors.LightTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── HERO CARD: "¿Qué tienes en mente?" (Tarjeta celeste de Figma) ───
        Surface(
            color = FifoColors.HeroBlueCard,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "+ AQUÍ PARA TI",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "¿Qué tienes\nen mente?",
                            fontSize = 22.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Te escucho. Podemos hablar de tu día o simplemente parar un rato juntos.",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = FifoColors.NavyPrimary.copy(alpha = 0.8f)
                        )
                    }

                    // Rostro de Fifo animado en la esquina derecha
                    Box(modifier = Modifier.padding(start = 12.dp)) {
                        FifoFace(
                            size = 76.dp,
                            isDarkTheme = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // BOTÓN PRINCIPAL DE VOZ: "Hablar con Fifo ->"
                Button(
                    onClick = onStartVoiceChat,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FifoColors.NavyPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hablar con Fifo",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── "¿Cómo te sientes hoy?" (Selector de estado de ánimo) ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "¿Cómo te sientes hoy?",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Text(
                text = "Tu momento",
                fontSize = 11.sp,
                color = FifoColors.LightTextMuted
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val moods = listOf(
                Triple("Bien", "😊", "Bien"),
                Triple("Regular", "😐", "Más o menos"),
                Triple("Bajo", "☹️", "No muy bien")
            )

            moods.forEach { (id, emoji, label) ->
                val isSelected = selectedMood == id
                Surface(
                    onClick = { selectedMood = id },
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) FifoColors.BlueSoftPill else Color.White,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) FifoColors.BlueAccentDark else FifoColors.LightCardBorder
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp)
                    ) {
                        Text(emoji, fontSize = 22.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) FifoColors.NavyPrimary else FifoColors.LightTextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Sea como sea, está bien sentirlo.",
            fontSize = 11.sp,
            color = FifoColors.LightTextMuted
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Tarjeta: "Baja el ritmo" (Respiración guiada) ───
        Surface(
            onClick = onOpenBreathingExercise,
            shape = RoundedCornerShape(20.dp),
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
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Air,
                            contentDescription = null,
                            tint = FifoColors.NavyPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "UN RESPIRO PARA TI",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextMuted,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Baja el ritmo",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )
                    Text(
                        text = "2 min · Respiración guiada",
                        fontSize = 12.sp,
                        color = FifoColors.LightTextSecondary
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = FifoColors.LightTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Sección Recordatorios (badge "3 hoy") ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recordatorios",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Surface(
                color = FifoColors.BlueSoftPill,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "3 hoy",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.NavyPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReminderItem(
                icon = Icons.Outlined.Schedule,
                title = "Llamar a mamá",
                subtitle = "10:30 · Hoy",
                badgeText = "Hoy"
            )
            ReminderItem(
                icon = Icons.Default.PhoneAndroid,
                title = "Revisar agenda",
                subtitle = "19:00 · Hoy",
                badgeText = "Hoy"
            )
            ReminderItem(
                icon = Icons.Outlined.Air,
                title = "Respiración guiada",
                subtitle = "Antes de dormir",
                badgeText = "Rutina"
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Sección: Resumen con Fifo ───
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = FifoColors.HeroBlueCard.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.HeroBlueBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FifoFace(size = 38.dp, isDarkTheme = false)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Resumen con Fifo",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.NavyPrimary
                            )
                            Text(
                                text = "Conversaciones anteriores",
                                fontSize = 11.sp,
                                color = FifoColors.LightTextSecondary
                            )
                        }
                    }

                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "2 puntos",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Revisemos lo que hablamos con Fifo para que no tengas que repetirte.",
                    fontSize = 12.sp,
                    color = FifoColors.LightTextSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(FifoColors.BlueSoftPill, CircleShape)
                            .border(1.dp, FifoColors.NavyPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = FifoColors.NavyPrimary,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hablaste de tu proyecto y te sentiste más tranquila al compartirlo.",
                        fontSize = 12.sp,
                        color = FifoColors.LightTextPrimary,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(FifoColors.BlueSoftPill, CircleShape)
                            .border(1.dp, FifoColors.NavyPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = FifoColors.NavyPrimary,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recordaste que Fifo te ayudó a priorizar tu agenda de hoy.",
                        fontSize = 12.sp,
                        color = FifoColors.LightTextPrimary,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onStartVoiceChat,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FifoColors.NavyPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ver conversaciones", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
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
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = FifoColors.BlueSoftPill,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = FifoColors.NavyPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FifoColors.LightTextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = FifoColors.LightTextSecondary
                )
            }

            Surface(
                color = Color(0xFFF1F5F9),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 11.sp,
                    color = FifoColors.LightTextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
