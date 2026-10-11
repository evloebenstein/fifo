package com.fifo.voicepipeline.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fifo.voicepipeline.ui.theme.FifoColors

private enum class MicPhoneBrand {
    XIAOMI,
    SAMSUNG,
    MOTOROLA_OTHER
}

/**
 * Diálogo interactivo para guiar al usuario a configurar el permiso de micrófono
 * en "Permitir Siempre" para escucha continua 24/7 (especialmente en Xiaomi / MIUI / HyperOS).
 */
@Composable
fun BackgroundMicGuideDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val initialBrand = remember {
        val manufacturer = Build.MANUFACTURER.lowercase()
        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> MicPhoneBrand.XIAOMI
            manufacturer.contains("samsung") -> MicPhoneBrand.SAMSUNG
            else -> MicPhoneBrand.MOTOROLA_OTHER
        }
    }
    var selectedBrand by remember { mutableStateOf(initialBrand) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp)
            ) {
                // Barra superior: Título y Cerrar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color(0xFFE0F2FE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color(0xFF0284C7),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Micrófono Continuo 24/7",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        Text(
                            text = "Para que Fifo te escuche siempre",
                            fontSize = 12.sp,
                            color = FifoColors.LightTextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = FifoColors.LightTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Selector de Marca
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(14.dp))
                        .padding(4.dp)
                ) {
                    val tabs = listOf(
                        MicPhoneBrand.XIAOMI to "Xiaomi / POCO",
                        MicPhoneBrand.SAMSUNG to "Samsung",
                        MicPhoneBrand.MOTOROLA_OTHER to "Otro Android"
                    )
                    tabs.forEach { (brand, label) ->
                        val isSelected = selectedBrand == brand
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedBrand = brand }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) FifoColors.NavyPrimary else FifoColors.LightTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Contenido desplazable con los 3 pasos
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Por qué es necesario
                    Surface(
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "¿Por qué configurar 'Permitir siempre'?",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.NavyPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "En teléfonos modernos, el sistema corta el micrófono al apagar la pantalla o abrir otra app (como WhatsApp o el navegador). Para responder cuando digas 'Fifo', el celular debe autorizar el micrófono continuo.",
                                fontSize = 12.sp,
                                color = FifoColors.LightTextSecondary,
                                lineHeight = 17.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Sigue estos 3 pasos rápidos:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.NavyPrimary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    when (selectedBrand) {
                        MicPhoneBrand.XIAOMI -> {
                            StepItem(
                                stepNumber = 1,
                                title = "Abre Permisos de la aplicación",
                                description = "Toca el botón azul de abajo. Se abrirá la pantalla de Fifo en Ajustes. Entra en 'Permisos' (o 'Permisos de la app')."
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            StepItem(
                                stepNumber = 2,
                                title = "Selecciona 'Micrófono'",
                                description = "Elige la opción 'Permitir siempre' (o 'Permitir solo mientras la app está en uso')."
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            StepItem(
                                stepNumber = 3,
                                title = "Inicio automático y Batería (Clave en Xiaomi)",
                                description = "Regresa a la pantalla de Fifo en Ajustes: activa el interruptor 'Inicio automático' y en Ahorro de batería elige 'Sin restricciones'."
                            )
                        }
                        MicPhoneBrand.SAMSUNG -> {
                            StepItem(
                                stepNumber = 1,
                                title = "Abre Ajustes de la app",
                                description = "Toca el botón azul de abajo y entra en la sección 'Accesos' o 'Permisos'."
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            StepItem(
                                stepNumber = 2,
                                title = "Elige 'Micrófono'",
                                description = "Selecciona 'Permitir siempre' para que Fifo funcione con la pantalla apagada."
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            StepItem(
                                stepNumber = 3,
                                title = "Batería sin restricciones",
                                description = "En Ajustes de Fifo > Batería, selecciona 'No optimizada' o 'Sin restricciones'."
                            )
                        }
                        MicPhoneBrand.MOTOROLA_OTHER -> {
                            StepItem(
                                stepNumber = 1,
                                title = "Abre Permisos de Fifo",
                                description = "Toca el botón azul de abajo y selecciona 'Permisos'."
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            StepItem(
                                stepNumber = 2,
                                title = "Configura el Micrófono",
                                description = "Toca 'Micrófono' y marca 'Permitir siempre' o el nivel máximo disponible."
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            StepItem(
                                stepNumber = 3,
                                title = "Regresa a Fifo",
                                description = "Al volver a Fifo, ya podrá escucharte en cualquier momento."
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Botones de acción inferiores
                Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:com.fifo.voicepipeline")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback a Ajustes generales
                                try {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    })
                                } catch (_: Exception) {}
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Abrir Ajustes de Permisos de Fifo",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Entendido, continuar",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FifoColors.NavyPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(
    stepNumber: Int,
    title: String,
    description: String
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFF0284C7), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$stepNumber",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.NavyPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = FifoColors.LightTextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
