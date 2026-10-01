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
import androidx.compose.material.icons.outlined.*
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    currentName: String,
    onLogout: () -> Unit,
    isBleConnected: Boolean = false,
    onConnectBle: () -> Unit = {},
    isAwake: Boolean = false,
    onWakeUp: () -> Unit = {},
    onSleep: () -> Unit = {},
    currentClaudeKey: String = "",
    onSaveClaudeKey: (String) -> Unit = {},
    onTestClaudeKey: (suspend (String) -> Pair<Boolean, String>)? = null,
    isMicMuted: Boolean = false,
    onToggleMicMute: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isSettingsOpen by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf(currentName) }
    var age by remember { mutableStateOf("68") }
    var gender by remember { mutableStateOf("Mujer") }
    var interests by remember { mutableStateOf(listOf("Lectura", "Música", "Paseos")) }

    // Configuraciones de accesibilidad
    var textSizeSelection by remember { mutableStateOf("Normal") }
    var highContrast by remember { mutableStateOf(false) }
    var reduceMotion by remember { mutableStateOf(true) }
    var screenReader by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FifoColors.LightBackground)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        if (isSettingsOpen) {
            // ── VISTA CONFIGURACIONES (Figma Screen 8) ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { isSettingsOpen = false },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Configuraciones",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.LightTextPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Banner: Fifo, a tu manera
            Surface(
                color = FifoColors.HeroBlueCard,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FifoFace(size = 46.dp, isDarkTheme = false)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Fifo, a tu manera",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        Text(
                            text = "Ajusta tu espacio para sentirte más a gusto.",
                            fontSize = 12.sp,
                            color = FifoColors.LightTextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // SECCIÓN ACCESIBILIDAD
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccessibilityNew, contentDescription = null, tint = FifoColors.NavyPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Accesibilidad", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Tamaño de texto", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Pequeño", "Normal", "Grande").forEach { sizeOpt ->
                            val isSel = textSizeSelection == sizeOpt
                            Surface(
                                onClick = { textSizeSelection = sizeOpt },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) FifoColors.BlueSoftPill else Color(0xFFF8FAFC),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSel) FifoColors.NavyPrimary else FifoColors.LightCardBorder
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Text(
                                        text = sizeOpt,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) FifoColors.NavyPrimary else FifoColors.LightTextPrimary
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Así se verá el texto en Fifo.",
                        fontSize = 11.sp,
                        color = FifoColors.LightTextMuted,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Divider(modifier = Modifier.padding(vertical = 14.dp), color = Color(0xFFF1F5F9))

                    // Contraste alto
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Contraste alto", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                            Text("Resaltar textos y contrastes para verlos mejor.", fontSize = 11.sp, color = FifoColors.LightTextSecondary)
                        }
                        Switch(checked = highContrast, onCheckedChange = { highContrast = it })
                    }

                    Divider(modifier = Modifier.padding(vertical = 14.dp), color = Color(0xFFF1F5F9))

                    // Reducir movimiento
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Reducir movimiento", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                            Text("Menos animaciones, más tranquilidad.", fontSize = 11.sp, color = FifoColors.LightTextSecondary)
                        }
                        Switch(checked = reduceMotion, onCheckedChange = { reduceMotion = it })
                    }

                    Divider(modifier = Modifier.padding(vertical = 14.dp), color = Color(0xFFF1F5F9))

                    // Lector de pantalla
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Lector de pantalla", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                            Text("Etiquetas y descripciones para escuchar la app.", fontSize = 11.sp, color = FifoColors.LightTextSecondary)
                        }
                        Switch(checked = screenReader, onCheckedChange = { screenReader = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // SECCIÓN GENERAL
            Text("General", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    // Robot FIFO (ESP32-S3 BLE)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConnectBle() }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = if (isBleConnected) FifoColors.StatusListening else FifoColors.BlueAccentDark,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Robot FIFO (ESP32-S3)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextPrimary
                            )
                            Text(
                                text = if (isBleConnected) "Conectado por Bluetooth (BLE)" else "Desconectado · Toca para vincular",
                                fontSize = 11.sp,
                                color = if (isBleConnected) FifoColors.StatusListening else FifoColors.LightTextSecondary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isBleConnected) FifoColors.StatusListening.copy(alpha = 0.12f) else FifoColors.NavyPrimary.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = if (isBleConnected) "Conectado" else "Vincular",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBleConnected) FifoColors.StatusListening else FifoColors.NavyPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Divider(color = Color(0xFFF8FAFC))

                    // Estado de Activación por Voz (Wake Word "FIFO")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isAwake) Icons.Default.Mic else Icons.Default.Bedtime,
                            contentDescription = null,
                            tint = if (isAwake) FifoColors.StatusListening else FifoColors.NavyPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Activación 'FIFO'",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextPrimary
                            )
                            Text(
                                text = if (isAwake) "Despierto y escuchando · Di tu consulta" else "Durmiendo · Di 'Fifo' para despertar",
                                fontSize = 11.sp,
                                color = if (isAwake) FifoColors.StatusListening else FifoColors.LightTextSecondary
                            )
                        }
                        Surface(
                            onClick = { if (isAwake) onSleep() else onWakeUp() },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAwake) FifoColors.NavyPrimary.copy(alpha = 0.1f) else FifoColors.StatusListening.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isAwake) "Dormir" else "Despertar",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAwake) FifoColors.NavyPrimary else FifoColors.StatusListening,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Divider(color = Color(0xFFF8FAFC))

                    // ── Control de Silencio de Micrófono (Mute) ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleMicMute() }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isMicMuted) FifoColors.StatusError else FifoColors.NavyPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Micrófono",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextPrimary
                            )
                            Text(
                                text = if (isMicMuted) "Silenciado (Mute) · Fifo no escucha" else "Activo · Escuchando",
                                fontSize = 11.sp,
                                color = if (isMicMuted) FifoColors.StatusError else FifoColors.StatusListening
                            )
                        }
                        Surface(
                            onClick = { onToggleMicMute() },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isMicMuted) Color(0xFFFEE2E2) else FifoColors.NavyPrimary.copy(alpha = 0.08f)
                        ) {
                            Text(
                                text = if (isMicMuted) "Activar" else "Silenciar",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMicMuted) Color(0xFFDC2626) else FifoColors.NavyPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Divider(color = Color(0xFFF8FAFC))

                    // ── Configuración de Cerebro de IA: Claude (Anthropic) ──
                    var claudeInput by remember { mutableStateOf(currentClaudeKey) }
                    var keySavedSuccess by remember { mutableStateOf(false) }
                    var isTestingKey by remember { mutableStateOf(false) }
                    var testResultText by remember { mutableStateOf<String?>(null) }
                    var testResultSuccess by remember { mutableStateOf(false) }
                    val coroutineScope = rememberCoroutineScope()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = FifoColors.NavyPrimary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cerebro Claude (Anthropic API)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Ingresa tu clave de Claude (sk-ant-...). El reconocimiento de voz funciona gratis con Google de forma nativa sin requerir ninguna otra clave.",
                            fontSize = 12.sp,
                            color = FifoColors.LightTextSecondary,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = claudeInput,
                            onValueChange = {
                                claudeInput = it
                                keySavedSuccess = false
                                testResultText = null
                            },
                            placeholder = { Text("sk-ant-api03-...", fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (keySavedSuccess) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("✓ Clave guardada correctamente", fontSize = 12.sp, color = FifoColors.StatusListening, fontWeight = FontWeight.SemiBold)
                        }

                        if (testResultText != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = testResultText!!,
                                fontSize = 12.sp,
                                color = if (testResultSuccess) FifoColors.StatusListening else Color(0xFFEF4444),
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Botón Probar Conexión con Claude
                            OutlinedButton(
                                onClick = {
                                    val keyToTest = claudeInput.trim()
                                    if (keyToTest.isBlank()) {
                                        testResultText = "Ingresa tu clave de Claude primero."
                                        testResultSuccess = false
                                        return@OutlinedButton
                                    }
                                    isTestingKey = true
                                    testResultText = null
                                    coroutineScope.launch {
                                        val result = onTestClaudeKey?.invoke(keyToTest) ?: Pair(false, "Prueba no disponible")
                                        isTestingKey = false
                                        testResultSuccess = result.first
                                        testResultText = result.second
                                    }
                                },
                                enabled = !isTestingKey,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                if (isTestingKey) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Probando...", fontSize = 11.sp)
                                } else {
                                    Text("Probar Claude", fontSize = 11.sp)
                                }
                            }

                            // Botón Guardar Clave
                            Button(
                                onClick = {
                                    val cleaned = claudeInput.trim()
                                    onSaveClaudeKey(cleaned)
                                    keySavedSuccess = true
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("Guardar Clave", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.MicNone, contentDescription = null, tint = FifoColors.NavyPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Motor de voz: Reconocimiento nativo de Google (Gratis · Cero claves extras)",
                                    fontSize = 11.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }
                    }

                    Divider(color = Color(0xFFF8FAFC))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Notifications, contentDescription = null, tint = FifoColors.LightTextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Notificaciones", fontSize = 14.sp, color = FifoColors.LightTextPrimary, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = FifoColors.LightTextMuted)
                    }

                    Divider(color = Color(0xFFF8FAFC))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = FifoColors.LightTextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Privacidad y seguridad", fontSize = 14.sp, color = FifoColors.LightTextPrimary, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = FifoColors.LightTextMuted)
                    }

                    Divider(color = Color(0xFFF8FAFC))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Language, contentDescription = null, tint = FifoColors.LightTextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Idioma", fontSize = 14.sp, color = FifoColors.LightTextPrimary, modifier = Modifier.weight(1f))
                        Text("Español", fontSize = 12.sp, color = FifoColors.LightTextMuted)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = FifoColors.LightTextMuted)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Cerrar sesión
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLogout() }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cerrar sesión", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFEF4444))
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text("tu amigo fifo · Versión 1.0", fontSize = 11.sp, color = FifoColors.LightTextMuted, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.height(100.dp))

        } else {
            // ── VISTA PRINCIPAL DEL PERFIL (Figma Screen 7) ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Perfil",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )
                    Text(
                        text = "Tu espacio para ser tú.",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextSecondary
                    )
                }

                IconButton(
                    onClick = { isSettingsOpen = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configuraciones",
                        tint = FifoColors.NavyPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Tarjeta de Avatar
            Surface(
                color = FifoColors.HeroBlueCard,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FifoFace(size = 58.dp, isDarkTheme = false)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = FifoColors.NavyPrimary)
                        Text(
                            text = "Tus gustos ayudan a encontrar tu gente.",
                            fontSize = 12.sp,
                            color = FifoColors.NavyPrimary.copy(alpha = 0.8f),
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Cambiar avatar",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FifoColors.BlueAccentDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Campo Nombre
            Text("Nombre", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                trailingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = FifoColors.LightTextMuted, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = FifoColors.BlueAccentDark,
                    unfocusedBorderColor = FifoColors.LightCardBorder
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Campo Edad
            Text("Edad", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = age,
                onValueChange = { age = it },
                trailingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = FifoColors.LightTextMuted, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = FifoColors.BlueAccentDark,
                    unfocusedBorderColor = FifoColors.LightCardBorder
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Campo Género
            Text("Género", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(gender, fontSize = 14.sp, color = FifoColors.LightTextPrimary)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = FifoColors.LightTextMuted)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Gustos
            Text("Gustos", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                interests.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = FifoColors.BlueSoftPill,
                        border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.BlueAccentDark)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item, fontSize = 12.sp, color = FifoColors.BlueSoftText, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Eliminar",
                                tint = FifoColors.BlueSoftText,
                                modifier = Modifier
                                    .size(12.dp)
                                    .clickable { interests = interests - item }
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                    modifier = Modifier.clickable { interests = interests + "Cine" }
                ) {
                    Text(
                        text = "+ Añadir gusto",
                        fontSize = 12.sp,
                        color = FifoColors.LightTextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { /* Guardado */ },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FifoColors.NavyPrimary,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Guardar cambios", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Acceso rápido a configuraciones
            Surface(
                onClick = { isSettingsOpen = true },
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = FifoColors.NavyPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Configuraciones", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
                        Text("Accesibilidad, privacidad y más", fontSize = 11.sp, color = FifoColors.LightTextSecondary)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = FifoColors.LightTextMuted)
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}
