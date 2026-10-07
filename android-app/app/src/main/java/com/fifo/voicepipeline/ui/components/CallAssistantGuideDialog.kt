package com.fifo.voicepipeline.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fifo.voicepipeline.service.FifoAccessibilityService

private enum class PhoneBrand {
    XIAOMI,
    SAMSUNG,
    MOTOROLA_OTHER
}

/**
 * Diálogo interactivo adaptativo y de alta accesibilidad para adultos mayores.
 * Detecta la marca del teléfono (Xiaomi, Samsung, Motorola u otros)
 * y muestra únicamente 3 pasos claros con texto grande y botones directos.
 */
@Composable
fun CallAssistantGuideDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isServiceActive by remember { mutableStateOf(FifoAccessibilityService.isEnabled(context)) }

    // Detectar automáticamente la marca del teléfono
    val initialBrand = remember {
        val manufacturer = Build.MANUFACTURER.lowercase()
        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> PhoneBrand.XIAOMI
            manufacturer.contains("samsung") -> PhoneBrand.SAMSUNG
            else -> PhoneBrand.MOTOROLA_OTHER
        }
    }
    var selectedBrand by remember { mutableStateOf(initialBrand) }

    // Revisa en vivo cada segundo si el usuario acaba de encender el interruptor
    LaunchedEffect(Unit) {
        while (true) {
            isServiceActive = FifoAccessibilityService.isEnabled(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.92f),
                shape = RoundedCornerShape(26.dp),
                color = Color(0xFF0F172A),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Encabezado
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF0284C7).copy(alpha = 0.25f),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PhoneInTalk,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Asistente en Llamadas",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Estado actual: Si ya está activo o pendiente
                    if (isServiceActive) {
                        Surface(
                            color = Color(0xFF064E3B),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF10B981)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF34D399),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "¡Todo listo y activado!",
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Fifo ya puede asistirte en llamadas normales, WhatsApp o videollamadas.",
                                    fontSize = 14.sp,
                                    color = Color(0xFFA7F3D0),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = onDismiss,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text("Entendido, cerrar", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    } else {
                        // Tarjeta de aviso amigable
                        Surface(
                            color = Color(0xFF451A03),
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF59E0B)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Actívalo en 3 toques",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Para que Fifo pueda ayudarte durante cualquier llamada, sigue estos 3 pasos simples:",
                                        fontSize = 13.sp,
                                        color = Color(0xFFFDE68A),
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Selector de marca adaptativo
                        Text(
                            text = "Selecciona la marca de tu teléfono:",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BrandTabChip(
                                title = "Xiaomi / Redmi",
                                selected = selectedBrand == PhoneBrand.XIAOMI,
                                modifier = Modifier.weight(1f)
                            ) { selectedBrand = PhoneBrand.XIAOMI }

                            BrandTabChip(
                                title = "Samsung",
                                selected = selectedBrand == PhoneBrand.SAMSUNG,
                                modifier = Modifier.weight(1f)
                            ) { selectedBrand = PhoneBrand.SAMSUNG }

                            BrandTabChip(
                                title = "Motorola / Otros",
                                selected = selectedBrand == PhoneBrand.MOTOROLA_OTHER,
                                modifier = Modifier.weight(1f)
                            ) { selectedBrand = PhoneBrand.MOTOROLA_OTHER }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Pasos adaptados según la marca
                        when (selectedBrand) {
                            PhoneBrand.XIAOMI -> {
                                SimpleStepCard(
                                    number = "1",
                                    title = "Abre los ajustes con el botón azul de abajo",
                                    subtitle = "Te llevará directamente a la pantalla de Accesibilidad."
                                )
                                SimpleStepCard(
                                    number = "2",
                                    title = "Toca «Apps descargadas» (al final de la lista)",
                                    subtitle = "En la pestaña General de arriba, baja hasta encontrar «Apps descargadas»."
                                )
                                SimpleStepCard(
                                    number = "3",
                                    title = "Toca «Fifo» y enciende el interruptor",
                                    subtitle = "Espera los 10 segundos del aviso de seguridad de Xiaomi y toca «Aceptar»."
                                )
                            }
                            PhoneBrand.SAMSUNG -> {
                                SimpleStepCard(
                                    number = "1",
                                    title = "Abre los ajustes con el botón azul de abajo",
                                    subtitle = "Se abrirá la pantalla de Accesibilidad de tu Samsung."
                                )
                                SimpleStepCard(
                                    number = "2",
                                    title = "Toca «Aplicaciones instaladas»",
                                    subtitle = "Verás la lista de aplicaciones instaladas en tu teléfono."
                                )
                                SimpleStepCard(
                                    number = "3",
                                    title = "Toca «Fifo» y enciende el interruptor",
                                    subtitle = "Toca «Permitir» para que Fifo pueda ayudarte en llamadas."
                                )
                            }
                            PhoneBrand.MOTOROLA_OTHER -> {
                                SimpleStepCard(
                                    number = "1",
                                    title = "Abre los ajustes con el botón azul de abajo",
                                    subtitle = "Se abrirá la pantalla de Accesibilidad de tu teléfono."
                                )
                                SimpleStepCard(
                                    number = "2",
                                    title = "Busca y toca «Fifo» en la lista",
                                    subtitle = "Aparecerá entre las opciones de la pantalla."
                                )
                                SimpleStepCard(
                                    number = "3",
                                    title = "Enciende el interruptor «Usar Fifo»",
                                    subtitle = "Toca «Permitir» para confirmar la activación."
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Botón principal GRANDE para abrir ajustes directamente
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e("CallAssistantGuide", "Error abriendo ajustes: ${e.message}")
                                }
                            },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Toca aquí para abrir los Ajustes",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        // Botón secundario si el interruptor sale en gris (Android 14)
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:com.fifo.voicepipeline")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e("CallAssistantGuide", "Error abriendo detalles: ${e.message}")
                                }
                            }
                        ) {
                            Text(
                                text = "¿Aparece en gris o dice 'restringido'? Toca aquí",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandTabChip(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFF0284C7) else Color(0xFF1E293B),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Color(0xFF38BDF8) else Color(0xFF334155)
        ),
        modifier = modifier.height(38.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SimpleStepCard(
    number: String,
    title: String,
    subtitle: String
) {
    Surface(
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF0284C7),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = number,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )
            }
        }
    }
}
