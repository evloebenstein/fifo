package com.fifo.voicepipeline.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fifo.voicepipeline.ui.components.FifoFace
import com.fifo.voicepipeline.ui.theme.FifoColors
import kotlinx.coroutines.launch

/** Pestañas de contenido estilo Instagram en el perfil */
private enum class ProfileTab {
    CONVERSATIONS,
    MEMORIES
}

/** Modelo de un recuerdo que Fifo aprendió de las conversaciones */
data class FifoMemoryItem(
    val id: String,
    val emoji: String,
    val title: String,
    val detail: String,
    val learnedDate: String
)

/** Modelo de una conversación pasada con Fifo */
data class PastConversationItem(
    val id: String,
    val title: String,
    val date: String,
    val duration: String,
    val summary: String,
    val tag: String,
    val icon: ImageVector
)

/**
 * Pantalla de Perfil estilo Instagram para "Tu Amigo Fifo":
 * - Encabezado estilo Instagram con métricas de bienestar (Charlas, Días juntos, Recuerdos).
 * - Biografía cálida generada y actualizada automáticamente por Fifo a partir de las charlas.
 * - Gustos e intereses reconocidos por Fifo en pastillas interactivas.
 * - Botón "Editar perfil" grande y táctil (para cambiar datos o gustos).
 * - Botón "Configurar app" grande y accesible.
 * - Pestañas de "Conversaciones pasadas" y "Recuerdos de Fifo" para ver cómo se adapta a ti.
 * - Pantalla de configuraciones simplificada al 100% para personas adultas mayores.
 */
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
    var isEditProfileOpen by remember { mutableStateOf(false) }
    var isAddMemoryOpen by remember { mutableStateOf(false) }

    // Datos del perfil
    var name by remember { mutableStateOf(currentName.ifBlank { "Lucía" }) }
    var age by remember { mutableStateOf("68") }
    var gender by remember { mutableStateOf("Mujer") }
    var location by remember { mutableStateOf("Santiago, Chile") }

    // Gustos reconocidos
    var interests by remember {
        mutableStateOf(
            listOf("Lectura histórica", "Música clásica", "Paseos en el parque", "Jardinería", "Cocina tradicional")
        )
    }

    // Biografía generada por Fifo
    var bioText by remember {
        mutableStateOf(
            "Amante de las novelas de historia, la música clásica de piano y las mañanas tranquilas con café. Disfruta compartir con Fifo anécdotas de su familia, preparar recetas caseras y cuidar las orquídeas de su jardín."
        )
    }

    // Pestaña activa en el perfil (Conversaciones vs Recuerdos)
    var selectedTab by remember { mutableStateOf(ProfileTab.CONVERSATIONS) }

    // Lista de recuerdos aprendidos por Fifo
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

    // Lista de conversaciones pasadas
    val pastConversations = remember {
        listOf(
            PastConversationItem(
                id = "c1",
                title = "Receta familiar y tarta de manzana",
                date = "Hoy · 10:30 hrs",
                duration = "4 minutos con Fifo",
                summary = "Fifo te ayudó a recordar la receta de tu abuela Elena para la tarta de manzana con canela y nueces. Guardó tu preferencia de usar manzanas verdes.",
                tag = "Cocina y familia",
                icon = Icons.Outlined.Restaurant
            ),
            PastConversationItem(
                id = "c2",
                title = "Caminata matutina y música clásica",
                date = "Ayer · 16:45 hrs",
                duration = "6 minutos con Fifo",
                summary = "Charlaron sobre cómo estuvo el día en el parque y escucharon una melodía suave de piano de Chopin para relajarse juntos.",
                tag = "Bienestar y música",
                icon = Icons.Outlined.MusicNote
            ),
            PastConversationItem(
                id = "c3",
                title = "Planes para el cumpleaños de Mateo",
                date = "Martes · 11:15 hrs",
                duration = "5 minutos con Fifo",
                summary = "Comentaste ideas de regalo para tu nieto en mayo y Fifo sugirió escribirle una carta con una anécdota especial de la familia.",
                tag = "Afectos",
                icon = Icons.Outlined.FavoriteBorder
            ),
            PastConversationItem(
                id = "c4",
                title = "Respiración y descanso nocturno",
                date = "Domingo · 20:00 hrs",
                duration = "3 minutos con Fifo",
                summary = "Fifo te acompañó con su ejercicio de respiración guiada de 2 minutos para descansar con la mente tranquila.",
                tag = "Relajación",
                icon = Icons.Outlined.Air
            )
        )
    }

    // Configuraciones amigables para personas mayores
    var fifoVolume by remember { mutableStateOf("Normal") }
    var fifoSpeechRate by remember { mutableStateOf("Pausado") }
    var textSizeSelection by remember { mutableStateOf("Grande") }
    var highContrast by remember { mutableStateOf(false) }
    var nightQuietHours by remember { mutableStateOf(true) }

    // Opciones técnicas avanzadas colapsables
    var isAdvancedTechOpen by remember { mutableStateOf(false) }
    var claudeInput by remember { mutableStateOf(currentClaudeKey) }
    var isTestingKey by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testResultSuccess by remember { mutableStateOf(false) }
    var keySavedSuccess by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FifoColors.LightBackground)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        if (isSettingsOpen) {
            // ══════════════════════════════════════════════════════════
            // ── VISTA DE CONFIGURACIONES SENCILLA PARA PERSONAS ADULTAS ──
            // ══════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { isSettingsOpen = false },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Volver al perfil",
                        tint = FifoColors.NavyPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Configuración fácil",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )
                    Text(
                        text = "Ajusta Fifo para que te sientas muy a gusto.",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 1. Tarjeta Robot Fifo (Estado claro sin tecnicismos)
            Surface(
                color = FifoColors.HeroBlueCard,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FifoFace(size = 54.dp, isDarkTheme = false)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBleConnected) "Tu robot Fifo está conectado" else "Tu robot Fifo está en reposo",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isBleConnected) "Listo para escucharte y conversar." else "Enciende tu robot y conéctalo para hablar.",
                            fontSize = 13.sp,
                            color = FifoColors.NavyPrimary.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onConnectBle,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBleConnected) FifoColors.NavyPrimary else FifoColors.BlueAccentDark
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (isBleConnected) Icons.Default.Check else Icons.Default.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBleConnected) "Robot Conectado ✓" else "Conectar Robot",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Volumen y Sonido de Fifo
            Text(
                text = "Sonido y Voz de Fifo",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "¿Qué tan fuerte quieres escuchar a Fifo?",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Suave", "Normal", "Alto").forEach { volOpt ->
                            val isSel = fifoVolume == volOpt
                            Surface(
                                onClick = { fifoVolume = volOpt },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSel) FifoColors.BlueSoftPill else Color(0xFFF8FAFC),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSel) 2.dp else 1.dp,
                                    if (isSel) FifoColors.NavyPrimary else FifoColors.LightCardBorder
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                ) {
                                    Text(
                                        text = volOpt,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSel) FifoColors.NavyPrimary else FifoColors.LightTextPrimary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFFF1F5F9))
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Velocidad de habla de Fifo",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Pausado y claro", "Velocidad normal").forEach { rateOpt ->
                            val isSel = fifoSpeechRate.startsWith(rateOpt.take(7))
                            Surface(
                                onClick = { fifoSpeechRate = rateOpt },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSel) FifoColors.BlueSoftPill else Color(0xFFF8FAFC),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSel) 2.dp else 1.dp,
                                    if (isSel) FifoColors.NavyPrimary else FifoColors.LightCardBorder
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                ) {
                                    Text(
                                        text = rateOpt,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSel) FifoColors.NavyPrimary else FifoColors.LightTextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Vista y Lectura Fácil
            Text(
                text = "Pantalla y Lectura",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Tamaño de las letras en pantalla",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Normal", "Grande", "Muy grande").forEach { sizeOpt ->
                            val isSel = textSizeSelection == sizeOpt
                            Surface(
                                onClick = { textSizeSelection = sizeOpt },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSel) FifoColors.BlueSoftPill else Color(0xFFF8FAFC),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSel) 2.dp else 1.dp,
                                    if (isSel) FifoColors.NavyPrimary else FifoColors.LightCardBorder
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                ) {
                                    Text(
                                        text = sizeOpt,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSel) FifoColors.NavyPrimary else FifoColors.LightTextPrimary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFFF1F5F9))
                    Spacer(modifier = Modifier.height(16.dp))

                    // Horario de descanso nocturno
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Silencio nocturno",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextPrimary
                            )
                            Text(
                                text = "Fifo no emitirá sonidos entre las 22:00 y las 08:00 para cuidar tu descanso.",
                                fontSize = 12.sp,
                                color = FifoColors.LightTextSecondary,
                                lineHeight = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = nightQuietHours,
                            onCheckedChange = { nightQuietHours = it }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Opciones técnicas avanzadas (colapsables para no abrumar a personas mayores)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isAdvancedTechOpen = !isAdvancedTechOpen }
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = FifoColors.LightTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Ajustes avanzados para familiares / técnicos",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextPrimary
                            )
                        }
                        Icon(
                            imageVector = if (isAdvancedTechOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = FifoColors.LightTextMuted
                        )
                    }

                    AnimatedVisibility(visible = isAdvancedTechOpen) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            Text(
                                text = "Clave de Inteligencia Claude (Anthropic API)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Configura la clave API de Claude para la inteligencia conversacional.",
                                fontSize = 12.sp,
                                color = FifoColors.LightTextSecondary
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
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (keySavedSuccess) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("✓ Clave guardada con éxito", fontSize = 12.sp, color = FifoColors.StatusListening, fontWeight = FontWeight.Bold)
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isTestingKey) "Probando..." else "Probar Clave", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        onSaveClaudeKey(claudeInput.trim())
                                        keySavedSuccess = true
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Guardar", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Cerrar sesión
            Surface(
                onClick = onLogout,
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cerrar sesión", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                }
            }

            Spacer(modifier = Modifier.height(100.dp))

        } else {
            // ══════════════════════════════════════════════════════════
            // ── VISTA PRINCIPAL: PERFIL ESTILO INSTAGRAM PARA FIFO ──
            // ══════════════════════════════════════════════════════════

            // Cabecera superior
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Mi Perfil",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )
                    Text(
                        text = "Tu historia y recuerdos con Fifo",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextSecondary
                    )
                }

                IconButton(
                    onClick = { isSettingsOpen = true },
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.White, CircleShape)
                        .border(1.dp, FifoColors.LightCardBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configuración",
                        tint = FifoColors.NavyPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 1. BLOQUE SUPERIOR ESTILO INSTAGRAM (Avatar + Métricas) ───
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(26.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar de Lucía con anillo de gradiente cálido y badge de Fifo
                        Box(contentAlignment = Alignment.BottomEnd) {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .background(
                                        brush = Brush.sweepGradient(
                                            listOf(
                                                Color(0xFF38BDF8),
                                                Color(0xFF818CF8),
                                                Color(0xFFF472B6),
                                                Color(0xFF38BDF8)
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .padding(3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // Inicial decorativa elegante
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(FifoColors.BlueSoftPill, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name.take(1).uppercase(),
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.NavyPrimary
                                    )
                                }
                            }

                            // Badge pequeño de Fifo compañero en la esquina
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(Color.White, CircleShape)
                                    .border(1.5.dp, Color(0xFFE2E8F0), CircleShape)
                                    .padding(2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                FifoFace(size = 24.dp, isDarkTheme = false)
                            }
                        }

                        Spacer(modifier = Modifier.width(20.dp))

                        // Tres columnas de métricas estilo Instagram adaptadas al bienestar
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${pastConversations.size}",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                                Text(
                                    text = "Charlas",
                                    fontSize = 12.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "12",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                                Text(
                                    text = "Días juntos",
                                    fontSize = 12.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${memories.size}",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                                Text(
                                    text = "Recuerdos",
                                    fontSize = 12.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Nombre y datos demográficos
                    Text(
                        text = "$name González",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "$age años",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = gender,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = location,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── 2. BIOGRAFÍA GENERADA POR FIFO (Magia de IA empática) ───
                    Surface(
                        color = FifoColors.HeroBlueCard,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = FifoColors.BlueAccentDark,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Biografía creada por Fifo",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.NavyPrimary
                                    )
                                }
                                Surface(
                                    color = Color.White.copy(alpha = 0.8f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Actualizada por IA",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.NavyPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = bioText,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = FifoColors.NavyPrimary.copy(alpha = 0.90f)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "✨ Fifo aprende de cada charla y redacta tu biografía automáticamente.",
                                fontSize = 11.sp,
                                color = FifoColors.LightTextSecondary,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── 3. TUS GUSTOS RECONOCIDOS POR FIFO ───
                    Text(
                        text = "Gustos que Fifo recuerda de ti",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        interests.forEach { gusto ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = FifoColors.BlueSoftPill,
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.NavyPrimary.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = gusto,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = FifoColors.NavyPrimary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── 4. BOTONES ESTILO INSTAGRAM (Grandes y accesibles) ───
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Botón "Editar perfil"
                        Surface(
                            onClick = { isEditProfileOpen = true },
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, FifoColors.LightCardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = FifoColors.LightTextPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Editar perfil",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.LightTextPrimary
                                )
                            }
                        }

                        // Botón "Configurar app"
                        Surface(
                            onClick = { isSettingsOpen = true },
                            shape = RoundedCornerShape(16.dp),
                            color = FifoColors.BlueSoftPill,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, FifoColors.NavyPrimary.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = FifoColors.NavyPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Configurar app",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 5. PESTAÑAS ESTILO INSTAGRAM (Conversaciones vs Recuerdos) ───
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Pestaña Charlas Pasadas
                    val isConvSel = selectedTab == ProfileTab.CONVERSATIONS
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = ProfileTab.CONVERSATIONS }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = null,
                                    tint = if (isConvSel) FifoColors.NavyPrimary else FifoColors.LightTextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Charlas (${pastConversations.size})",
                                    fontSize = 14.sp,
                                    fontWeight = if (isConvSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isConvSel) FifoColors.NavyPrimary else FifoColors.LightTextSecondary
                                )
                            }
                            if (isConvSel) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(3.dp)
                                        .background(FifoColors.NavyPrimary, CircleShape)
                                )
                            }
                        }
                    }

                    // Pestaña Recuerdos de Fifo
                    val isMemSel = selectedTab == ProfileTab.MEMORIES
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = ProfileTab.MEMORIES }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = if (isMemSel) FifoColors.NavyPrimary else FifoColors.LightTextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Recuerdos (${memories.size})",
                                    fontSize = 14.sp,
                                    fontWeight = if (isMemSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isMemSel) FifoColors.NavyPrimary else FifoColors.LightTextSecondary
                                )
                            }
                            if (isMemSel) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(3.dp)
                                        .background(FifoColors.NavyPrimary, CircleShape)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 6. CONTENIDO SEGÚN LA PESTAÑA SELECCIONADA ───
            when (selectedTab) {
                ProfileTab.CONVERSATIONS -> {
                    // Lista de conversaciones pasadas
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        pastConversations.forEach { conv ->
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .background(FifoColors.BlueSoftPill, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = conv.icon,
                                                    contentDescription = null,
                                                    tint = FifoColors.NavyPrimary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = conv.title,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = FifoColors.LightTextPrimary
                                                )
                                                Text(
                                                    text = conv.date,
                                                    fontSize = 12.sp,
                                                    color = FifoColors.LightTextMuted
                                                )
                                            }
                                        }

                                        Surface(
                                            color = Color(0xFFF1F5F9),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(
                                                text = conv.tag,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = FifoColors.LightTextSecondary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text(
                                        text = conv.summary,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        color = FifoColors.LightTextSecondary
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = FifoColors.BlueAccentDark,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = conv.duration,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = FifoColors.BlueAccentDark
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ProfileTab.MEMORIES -> {
                    // Lista de recuerdos guardados por Fifo
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Botón para agregar recuerdo manual
                        Surface(
                            onClick = { isAddMemoryOpen = true },
                            shape = RoundedCornerShape(18.dp),
                            color = FifoColors.BlueSoftPill,
                            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.NavyPrimary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = FifoColors.NavyPrimary)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Añadir un recuerdo para Fifo",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.NavyPrimary
                                    )
                                    Text(
                                        text = "Cuéntale algo importante para que siempre lo recuerde.",
                                        fontSize = 12.sp,
                                        color = FifoColors.NavyPrimary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        memories.forEach { mem ->
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(18.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(text = mem.emoji, fontSize = 26.sp)
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = mem.title,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = FifoColors.LightTextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = mem.detail,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            color = FifoColors.LightTextSecondary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = mem.learnedDate,
                                            fontSize = 11.sp,
                                            color = FifoColors.LightTextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // ══════════════════════════════════════════════════════════
    // ── DIÁLOGO GRANDE Y ACCESIBLE DE EDITAR PERFIL ───────────
    // ══════════════════════════════════════════════════════════
    if (isEditProfileOpen) {
        var editName by remember { mutableStateOf(name) }
        var editAge by remember { mutableStateOf(age) }
        var editGender by remember { mutableStateOf(gender) }
        var newInterestInput by remember { mutableStateOf("") }
        var editInterests by remember { mutableStateOf(interests) }

        Dialog(onDismissRequest = { isEditProfileOpen = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Editar Perfil",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        IconButton(onClick = { isEditProfileOpen = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Nombre", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Edad", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = editAge,
                                onValueChange = { editAge = it },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Género", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = editGender,
                                onValueChange = { editGender = it },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Gustos e intereses", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        editInterests.forEach { gusto ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = FifoColors.BlueSoftPill
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(gusto, fontSize = 12.sp, color = FifoColors.NavyPrimary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Eliminar",
                                        tint = FifoColors.NavyPrimary,
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clickable { editInterests = editInterests - gusto }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newInterestInput,
                            onValueChange = { newInterestInput = it },
                            placeholder = { Text("Nuevo gusto (ej: Pintura)", fontSize = 12.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newInterestInput.isNotBlank()) {
                                    editInterests = editInterests + newInterestInput.trim()
                                    newInterestInput = ""
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary)
                        ) {
                            Text("Añadir", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            name = editName
                            age = editAge
                            gender = editGender
                            interests = editInterests
                            // Regenerar biografía adaptada
                            bioText = "Amante de ${editInterests.take(3).joinToString(", ")}. Le gusta conversar con Fifo sobre sus pasatiempos favoritos y compartir anécdotas de su día a día."
                            isEditProfileOpen = false
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("Guardar cambios", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // ── DIÁLOGO PARA AÑADIR UN RECUERDO MANUAL PARA FIFO ───────
    // ══════════════════════════════════════════════════════════
    if (isAddMemoryOpen) {
        var memoryTitle by remember { mutableStateOf("") }
        var memoryDetail by remember { mutableStateOf("") }

        Dialog(onDismissRequest = { isAddMemoryOpen = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Nuevo recuerdo para Fifo",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.NavyPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Fifo usará este recuerdo para personalizar lo que habla contigo.",
                        fontSize = 12.sp,
                        color = FifoColors.LightTextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Título del recuerdo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = memoryTitle,
                        onValueChange = { memoryTitle = it },
                        placeholder = { Text("Ej: Cumpleaños de mi hija", fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Detalle o anécdota", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = memoryDetail,
                        onValueChange = { memoryDetail = it },
                        placeholder = { Text("Ej: Es el 14 de agosto y le gusta el pastel de chocolate.", fontSize = 12.sp) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            if (memoryTitle.isNotBlank()) {
                                memories = listOf(
                                    FifoMemoryItem(
                                        id = System.currentTimeMillis().toString(),
                                        emoji = "💡",
                                        title = memoryTitle.trim(),
                                        detail = memoryDetail.trim().ifEmpty { memoryTitle.trim() },
                                        learnedDate = "Añadido hoy"
                                    )
                                ) + memories
                                isAddMemoryOpen = false
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text("Guardar recuerdo", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
