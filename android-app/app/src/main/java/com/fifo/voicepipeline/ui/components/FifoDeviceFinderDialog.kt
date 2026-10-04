package com.fifo.voicepipeline.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.location.FifoLocationHelper
import com.fifo.voicepipeline.ui.theme.FifoColors

/**
 * Diálogo accesible para encontrar y localizar el robot físico Fifo:
 * - Si está conectado: Muestra proximidad BLE y permite hacer sonar el parlante (beeper).
 * - Si está desconectado: Muestra la última ubicación GPS registrada por el teléfono,
 *   la hora de desconexión y permite abrir el punto en Google Maps.
 */
@Composable
fun FifoDeviceFinderDialog(
    onDismiss: () -> Unit,
    onScanBle: () -> Unit = {}
) {
    val context = LocalContext.current
    val deviceLoc by FifoDataRepository.deviceLocation.collectAsState()
    var isBeepTriggered by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.70f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(28.dp),
                color = FifoColors.DarkBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder),
                tonalElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Barra superior con título y botón cerrar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color(0xFF0284C7).copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationSearching,
                                    contentDescription = null,
                                    tint = FifoColors.BlueAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "¿Dónde está mi Fifo?",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            onClick = onDismiss,
                            color = FifoColors.DarkInputBg,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 42.dp)
                                .shadow(elevation = 2.dp, shape = RoundedCornerShape(20.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cerrar",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Cerrar",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Tarjeta de Estado Principal
                    if (deviceLoc.isConnected) {
                        // ── CASO 1: CONECTADO Y CERCA ───
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            color = Color(0xFF064E3B).copy(alpha = 0.4f),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF10B981))
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .background(Color(0xFF10B981).copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BluetoothConnected,
                                        contentDescription = null,
                                        tint = Color(0xFF34D399),
                                        modifier = Modifier.size(30.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "¡Tu Fifo está conectado y cerca!",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399),
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "Señal fuerte (${deviceLoc.signalStrengthRssi} dBm). Debe estar en esta habitación o en una habitación contigua.",
                                    fontSize = 13.sp,
                                    color = FifoColors.DarkBadgeText,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )

                                Spacer(modifier = Modifier.height(18.dp))

                                Surface(
                                    onClick = {
                                        isBeepTriggered = true
                                        FifoDataRepository.triggerDeviceBeep(true)
                                    },
                                    shape = RoundedCornerShape(22.dp),
                                    color = Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 56.dp)
                                        .shadow(
                                            elevation = 6.dp,
                                            shape = RoundedCornerShape(22.dp),
                                            spotColor = Color(0xFF10B981).copy(alpha = 0.5f)
                                        )
                                        .background(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(Color(0xFF059669), Color(0xFF10B981))
                                            ),
                                            shape = RoundedCornerShape(22.dp)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.30f),
                                            shape = RoundedCornerShape(22.dp)
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 18.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .background(Color.White.copy(alpha = 0.22f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isBeepTriggered) "¡Sonando! Bip bip bip..." else "Hacer sonar a Fifo",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "Emitir sonido audible por el parlante",
                                                fontSize = 12.sp,
                                                color = Color.White.copy(alpha = 0.85f)
                                            )
                                        }

                                        Icon(
                                            imageVector = Icons.Default.Hearing,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.85f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // ── CASO 2: DESCONECTADO (ÚLTIMA UBICACIÓN GUARDADA) ───
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            color = Color(0xFF78350F).copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF59E0B))
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .background(Color(0xFFF59E0B).copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOff,
                                        contentDescription = null,
                                        tint = Color(0xFFFBBF24),
                                        modifier = Modifier.size(30.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "Robot actualmente desconectado",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFBBF24),
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "El celular registró el último punto donde estuvieron conectados antes de perder la señal.",
                                    fontSize = 13.sp,
                                    color = FifoColors.DarkBadgeText,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Detalles de la Última Ubicación Conocida
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = FifoColors.DarkInputBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.DarkInputBorder)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "HISTORIAL DE CONEXIÓN",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.BlueAccent,
                                letterSpacing = 1.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            LocationDetailRow(
                                icon = Icons.Default.AccessTime,
                                title = "Última vez visto",
                                detail = deviceLoc.lastConnectedTime
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            LocationDetailRow(
                                icon = Icons.Default.Place,
                                title = "Dirección aproximada",
                                detail = deviceLoc.lastKnownAddress
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            LocationDetailRow(
                                icon = Icons.Default.Home,
                                title = "Habitación sugerida",
                                detail = deviceLoc.lastKnownRoom
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    // Acciones Principales con estética impecable y táctil
                    Surface(
                        onClick = {
                            FifoLocationHelper.openMapPin(
                                context = context,
                                latitude = deviceLoc.lastKnownLatitude,
                                longitude = deviceLoc.lastKnownLongitude,
                                label = "Última ubicación de Fifo"
                            )
                        },
                        shape = RoundedCornerShape(22.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 58.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(22.dp),
                                spotColor = Color(0xFF0284C7).copy(alpha = 0.5f)
                            )
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF0284C7), Color(0xFF2563EB))
                                ),
                                shape = RoundedCornerShape(22.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.30f),
                                shape = RoundedCornerShape(22.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color.White.copy(alpha = 0.20f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ver en Google Maps",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Abrir GPS para llegar al robot",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        onClick = onScanBle,
                        shape = RoundedCornerShape(22.dp),
                        color = FifoColors.DarkInputBg,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.40f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .shadow(elevation = 2.dp, shape = RoundedCornerShape(22.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color(0xFF0284C7).copy(alpha = 0.20f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Buscar señal Bluetooth ahora",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Escanear si el robot está cerca en casa",
                                    fontSize = 12.sp,
                                    color = FifoColors.DarkBadgeText
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.NearMe,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Consejo cálido para el adulto mayor
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.6f)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "💡", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Consejo: Revisa sobre la mesa de noche, el velador del dormitorio o cerca del sillón donde sueles descansar.",
                                fontSize = 12.sp,
                                color = FifoColors.DarkBadgeText,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FifoColors.BlueAccent,
            modifier = Modifier
                .size(18.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 11.sp,
                color = FifoColors.DarkBadgeText
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}
