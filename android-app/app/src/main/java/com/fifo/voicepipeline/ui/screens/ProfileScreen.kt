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
import com.fifo.voicepipeline.ui.model.UserSocialPost
import com.fifo.voicepipeline.ui.model.FifoTasteStory
import kotlinx.coroutines.launch

/** Pestañas de contenido estilo Instagram en el perfil */
private enum class ProfileTab {
    POSTS,
    TASTES
}

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
    onReopenSetup: () -> Unit = {},
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
    var isCreatePostOpen by remember { mutableStateOf(false) }
    var postToDelete by remember { mutableStateOf<UserSocialPost?>(null) }
    var tasteToDelete by remember { mutableStateOf<FifoTasteStory?>(null) }

    // Repositorio reactivo central de Fifo (sincronizado con base de datos)
    val userProfileState by com.fifo.voicepipeline.data.FifoDataRepository.userProfile.collectAsState()
    val tastesState by com.fifo.voicepipeline.data.FifoDataRepository.tastes.collectAsState()
    val storiesState by com.fifo.voicepipeline.data.FifoDataRepository.tasteStories.collectAsState()
    val postsState by com.fifo.voicepipeline.data.FifoDataRepository.socialPosts.collectAsState()

    // Datos del perfil (se inicializan con el repositorio o parámetro)
    var name by remember { mutableStateOf(userProfileState.fullName) }
    var birthDate by remember { mutableStateOf(userProfileState.birthDate) }
    var age by remember { mutableStateOf(userProfileState.estimatedAge.toString()) }
    var gender by remember { mutableStateOf(userProfileState.genderIdentity) }
    var location by remember { mutableStateOf(userProfileState.city) }

    // Gustos reconocidos
    var interests by remember { mutableStateOf(tastesState) }

    // Biografía generada por Fifo
    var bioText by remember { mutableStateOf(userProfileState.bioAi) }

    // Sincronización en vivo: cuando el robot Fifo ejecuta cambios por voz o BD, la UI se actualiza sola
    LaunchedEffect(userProfileState) {
        name = userProfileState.fullName
        birthDate = userProfileState.birthDate
        age = userProfileState.estimatedAge.toString()
        gender = userProfileState.genderIdentity
        location = userProfileState.city
        bioText = userProfileState.bioAi
    }

    LaunchedEffect(tastesState) {
        interests = tastesState
    }

    // Pestaña activa en el perfil (Mis Publicaciones vs Gustos en Detalle)
    var selectedTab by remember { mutableStateOf(ProfileTab.POSTS) }

    // Publicaciones realizadas por la usuaria (se ven en su perfil y en Fifo Amigos)
    var userPosts by remember {
        mutableStateOf(
            listOf(
                UserSocialPost(
                    id = "p1",
                    author = "Lucía",
                    age = 68,
                    category = "Lectura & Naturaleza",
                    timeAgo = "Hace 2 horas",
                    content = "Acabo de terminar una novela maravillosa de Elena Ferrante en el banco del parque bajo los árboles. Me encantaría encontrar a alguien para comentarla con calma y luego dar un paseo.",
                    likes = 18,
                    commentsCount = 6,
                    accentColorHex = 0xFF38BDF8
                ),
                UserSocialPost(
                    id = "p2",
                    author = "Lucía",
                    age = 68,
                    category = "Jardinería & Balcón",
                    timeAgo = "Ayer",
                    content = "¡Mis orquídeas blancas del balcón florecieron esta mañana! Les puse un poquito de agua tibia filtrada como me aconsejó Fifo y están hermosas. ¿Alguien más tiene plantas en su terraza?",
                    likes = 24,
                    commentsCount = 8,
                    accentColorHex = 0xFFF472B6
                ),
                UserSocialPost(
                    id = "p3",
                    author = "Lucía",
                    age = 68,
                    category = "Cocina tradicional",
                    timeAgo = "Hace 3 días",
                    content = "Hoy preparé la tarta de manzana casera de mi abuela Elena, con canela y nueces crujientes. Toda la casa huele a merienda de domingo. ¡Ojalá pudiera compartir un trozo con ustedes!",
                    likes = 31,
                    commentsCount = 12,
                    accentColorHex = 0xFFFBBF24
                )
            )
        )
    }

    // Historias y gustos en detalle curados por Fifo a partir de las charlas cotidianas (sin datos sensibles)
    var tasteStories by remember {
        mutableStateOf(
            listOf(
                FifoTasteStory(
                    id = "t_bday",
                    title = "Cumpleaños y orígenes familiares",
                    subtitle = "Nacida el 14 de mayo de 1958 en Santiago",
                    description = "Fifo recuerda con mucho cariño tu fecha de nacimiento (14 de mayo de 1958). Te gusta celebrar en familia recordando anécdotas de juventud y compartiendo una rica rebanada de tarta con café.",
                    tags = listOf("#Cumpleaños", "#14DeMayo", "#1958", "#Familia", "#Tradición"),
                    iconCategory = "heart",
                    learnedFrom = "Configurado durante el setup inicial con Fifo"
                ),
                FifoTasteStory(
                    id = "t1",
                    title = "Nocturnos de Frédéric Chopin",
                    subtitle = "Pasión por el piano y las melodías clásicas",
                    description = "En nuestras conversaciones de media tarde descubrimos tu amor por las sonatas y nocturnos de piano. Te evocan momentos de paz de tu juventud y te acompañan con serenidad mientras disfrutas de una taza de té.",
                    tags = listOf("#MúsicaClásica", "#PianoChopin", "#TardeDeTé", "#Serenidad"),
                    iconCategory = "music",
                    learnedFrom = "Extraído de 3 charlas cotidianas · Sin datos sensibles"
                ),
                FifoTasteStory(
                    id = "t2",
                    title = "Cuidado de orquídeas en balcón",
                    subtitle = "Paciencia y amor por las flores vivas",
                    description = "Fifo aprendió que cada martes dedicas tiempo a regar y ventilar tus orquídeas. Disfrutas ver crecer cada botón nuevo y prefieres la luz matutina indirecta para mantener sus hojas brillantes.",
                    tags = listOf("#Jardinería", "#Orquídeas", "#VidaVerde", "#Balcón"),
                    iconCategory = "gardening",
                    learnedFrom = "Extraído de 2 charlas cotidianas · Sin datos sensibles"
                ),
                FifoTasteStory(
                    id = "t3",
                    title = "Tarta de manzana de la abuela Elena",
                    subtitle = "Repostería con memoria y tradición familiar",
                    description = "Un recuerdo entrañable de tu infancia: cocinar recetas tradicionales con canela fresca y manzanas verdes. Compartiste con Fifo cómo este aroma despierta historias de reuniones familiares de los domingos.",
                    tags = listOf("#CocinaCasera", "#RecetasFamiliares", "#TartaManzana", "#Tradición"),
                    iconCategory = "cooking",
                    learnedFrom = "Extraído de 4 charlas cotidianas · Sin datos sensibles"
                ),
                FifoTasteStory(
                    id = "t4",
                    title = "Novelas históricas y biografías",
                    subtitle = "El placer de viajar a otras épocas",
                    description = "Prefieres sumergirte en libros que narran vidas extraordinarias y épocas pasadas con detalle. Disfrutas leer un capítulo al aire libre en el parque los fines de semana soleados.",
                    tags = listOf("#Lectura", "#Historia", "#Novelas", "#TiempoLibre"),
                    iconCategory = "book",
                    learnedFrom = "Extraído de 3 charlas cotidianas · Sin datos sensibles"
                ),
                FifoTasteStory(
                    id = "t5",
                    title = "Caminatas bajo el sol templado",
                    subtitle = "Bienestar, aire puro y paso tranquilo",
                    description = "Nos contaste que caminar a media mañana por el sendero arbolado te llena de energía positiva. Prefieres días despejados sin viento frío para respirar profundo y despejar la mente.",
                    tags = listOf("#Caminatas", "#Parque", "#Bienestar", "#AireLibre"),
                    iconCategory = "walk",
                    learnedFrom = "Extraído de 2 charlas cotidianas · Sin datos sensibles"
                )
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

            Spacer(modifier = Modifier.height(14.dp))

            // Acceso al Asistente de configuración de Fifo
            Surface(
                onClick = onReopenSetup,
                color = FifoColors.BlueSoftPill,
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBAE6FD)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FifoFace(size = 36.dp, isDarkTheme = false)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Asistente de configuración de Fifo",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Preguntas sobre cumpleaños, identidad y primeros gustos.",
                            fontSize = 12.sp,
                            color = FifoColors.LightTextSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = FifoColors.NavyPrimary,
                        modifier = Modifier.size(18.dp)
                    )
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
                                    text = "${userPosts.size}",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                                Text(
                                    text = "Publicaciones",
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
                                    text = "${tasteStories.size}",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                                Text(
                                    text = "Gustos",
                                    fontSize = 12.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Nombre y datos demográficos
                    Text(
                        text = name,
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // Información extra y cumpleaños recordado por Fifo
                    Surface(
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🎂", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Cumpleaños: $birthDate",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = FifoColors.LightTextPrimary
                                )
                                Text(
                                    text = "Fifo te saludará y recordará tus anécdotas en tu día especial",
                                    fontSize = 11.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
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

            // ── 5. PESTAÑAS ESTILO INSTAGRAM (Mis Publicaciones vs Gustos en Detalle) ───
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Pestaña Mis Publicaciones
                    val isPostsSel = selectedTab == ProfileTab.POSTS
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = ProfileTab.POSTS }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.GridOn,
                                    contentDescription = null,
                                    tint = if (isPostsSel) FifoColors.NavyPrimary else FifoColors.LightTextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Mis publicaciones (${userPosts.size})",
                                    fontSize = 13.sp,
                                    fontWeight = if (isPostsSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isPostsSel) FifoColors.NavyPrimary else FifoColors.LightTextSecondary
                                )
                            }
                            if (isPostsSel) {
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

                    // Pestaña Gustos en Detalle
                    val isTastesSel = selectedTab == ProfileTab.TASTES
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = ProfileTab.TASTES }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (isTastesSel) FifoColors.NavyPrimary else FifoColors.LightTextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Gustos en detalle (${tasteStories.size})",
                                    fontSize = 13.sp,
                                    fontWeight = if (isTastesSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isTastesSel) FifoColors.NavyPrimary else FifoColors.LightTextSecondary
                                )
                            }
                            if (isTastesSel) {
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
                ProfileTab.POSTS -> {
                    // Vista de publicaciones de la usuaria
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Botón para crear nueva publicación
                        Surface(
                            onClick = { isCreatePostOpen = true },
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
                                        .size(38.dp)
                                        .background(Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = FifoColors.NavyPrimary)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Crear nueva publicación",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.NavyPrimary
                                    )
                                    Text(
                                        text = "Comparte un momento o anécdota con tus amigos.",
                                        fontSize = 12.sp,
                                        color = FifoColors.NavyPrimary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        if (userPosts.isEmpty()) {
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("📝", fontSize = 32.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Aún no tienes publicaciones en tu perfil",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.LightTextPrimary,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Toca el botón 'Crear nueva publicación' para compartir algo lindo con tus amigos.",
                                        fontSize = 12.sp,
                                        color = FifoColors.LightTextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            userPosts.forEach { post ->
                                Surface(
                                    color = Color.White,
                                    shape = RoundedCornerShape(22.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(18.dp)) {
                                        // Encabezado del post
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
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
                                                    Text(
                                                        text = post.author.take(1).uppercase(),
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = FifoColors.NavyPrimary
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column {
                                                    Text(
                                                        text = "${post.author}, ${post.age} años",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = FifoColors.LightTextPrimary
                                                    )
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = post.category,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = FifoColors.BlueAccentDark
                                                        )
                                                        Text(
                                                            text = " · ${post.timeAgo}",
                                                            fontSize = 11.sp,
                                                            color = FifoColors.LightTextMuted
                                                        )
                                                    }
                                                }
                                            }

                                            // Botón para borrar publicación
                                            IconButton(
                                                onClick = { postToDelete = post },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.DeleteOutline,
                                                    contentDescription = "Eliminar publicación",
                                                    tint = Color(0xFFEF4444),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Contenido del post
                                        Text(
                                            text = post.content,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = FifoColors.LightTextPrimary
                                        )

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // Barra de interacciones
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Likes interactivos
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .clickable {
                                                            userPosts = userPosts.map {
                                                                if (it.id == post.id) it.copy(likes = it.likes + 1) else it
                                                            }
                                                        }
                                                        .padding(vertical = 4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Favorite,
                                                        contentDescription = "Me gusta",
                                                        tint = Color(0xFFF43F5E),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "${post.likes}",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = FifoColors.LightTextPrimary
                                                    )
                                                }

                                                // Comentarios
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(vertical = 4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                                        contentDescription = "Comentarios",
                                                        tint = FifoColors.NavyPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "${post.commentsCount}",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = FifoColors.LightTextPrimary
                                                    )
                                                }
                                            }

                                            Surface(
                                                color = Color(0xFFF1F5F9),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = "Visible en Fifo Amigos",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = FifoColors.LightTextSecondary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ProfileTab.TASTES -> {
                    // Vista de gustos detallados estilo blog generados por Fifo
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Banner de privacidad y confidencialidad garantizada
                        Surface(
                            color = Color(0xFFF0FDF4),
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF86EFAC).copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Gustos curados por Fifo (Sin datos sensibles)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF15803D)
                                    )
                                    Text(
                                        text = "Fifo resume tus pasatiempos favoritos de tus charlas habituales para tu perfil. La información médica o privada nunca se incluye aquí.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF166534),
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }

                        if (tasteStories.isEmpty()) {
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("🌱", fontSize = 32.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Sin gustos guardados",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.LightTextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Habla con Fifo para que identifique tus pasatiempos favoritos y prepare tus historias.",
                                        fontSize = 12.sp,
                                        color = FifoColors.LightTextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            tasteStories.forEach { story ->
                                Surface(
                                    color = Color.White,
                                    shape = RoundedCornerShape(22.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(18.dp)) {
                                        // Encabezado del artículo de gusto
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val tasteIcon = when (story.iconCategory) {
                                                    "music" -> Icons.Outlined.MusicNote
                                                    "gardening" -> Icons.Outlined.Park
                                                    "cooking" -> Icons.Outlined.Restaurant
                                                    "book" -> Icons.Outlined.MenuBook
                                                    "walk" -> Icons.Outlined.DirectionsWalk
                                                    else -> Icons.Outlined.FavoriteBorder
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .size(42.dp)
                                                        .background(FifoColors.BlueSoftPill, CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = tasteIcon,
                                                        contentDescription = null,
                                                        tint = FifoColors.NavyPrimary,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(
                                                        text = story.title,
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = FifoColors.LightTextPrimary
                                                    )
                                                    Text(
                                                        text = story.subtitle,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = FifoColors.BlueAccentDark
                                                    )
                                                }
                                            }

                                            // Botón para borrar gusto del perfil
                                            IconButton(
                                                onClick = { tasteToDelete = story },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.DeleteOutline,
                                                    contentDescription = "Eliminar gusto",
                                                    tint = Color(0xFFEF4444),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Redacción empática de Fifo sobre este gusto
                                        Text(
                                            text = story.description,
                                            fontSize = 13.sp,
                                            lineHeight = 19.sp,
                                            color = FifoColors.LightTextSecondary
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Etiquetas temáticas
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            story.tags.forEach { tag ->
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = FifoColors.BlueSoftPill
                                                ) {
                                                    Text(
                                                        text = tag,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = FifoColors.NavyPrimary,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))
                                        HorizontalDivider(color = Color(0xFFF1F5F9))
                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF16A34A),
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = story.learnedFrom,
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
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // ══════════════════════════════════════════════════════════
    // ── DIÁLOGO GRANDE Y ACCESIBLE DE EDITAR PERFIL ───────────
    // ══════════════════════════════════════════════════════════
    if (isEditProfileOpen) {
        var editName by remember { mutableStateOf(name) }
        var editBirthDate by remember { mutableStateOf(birthDate) }
        var editAge by remember { mutableStateOf(age) }
        var editGender by remember { mutableStateOf(gender) }
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

                    Text("Nombre completo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        placeholder = { Text("Ej. Lucía González", color = FifoColors.LightTextMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Fecha de nacimiento / Cumpleaños", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = editBirthDate,
                        onValueChange = { editBirthDate = it },
                        placeholder = { Text("Ej. 14 de Mayo, 1958", color = FifoColors.LightTextMuted) },
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
                            Text("Género / Identidad", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
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

                    Spacer(modifier = Modifier.height(14.dp))

                    // Tarjeta explicativa cálida: Fifo aprende tus gustos hablando contigo
                    Surface(
                        color = FifoColors.HeroBlueCard,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBAE6FD)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                FifoFace(size = 24.dp, isDarkTheme = false)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "¿Falta algún gusto tuyo?",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Explícale a Fifo tus otros gustos durante sus charlas para que aparezcan aquí. Si uno no aparece, solo dale el detalle a él y Fifo lo pondrá en tu perfil.",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = FifoColors.NavyPrimary.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            name = editName
                            birthDate = editBirthDate
                            age = editAge
                            gender = editGender
                            interests = editInterests
                            // Regenerar biografía adaptada
                            bioText = "Amante de ${editInterests.take(3).joinToString(", ")}. Le gusta conversar con Fifo sobre sus pasatiempos favoritos y compartir anécdotas de su día a día."
                            com.fifo.voicepipeline.data.FifoDataRepository.updateDemographics(
                                fullName = editName,
                                birthDate = editBirthDate,
                                birthYear = editBirthDate.takeLast(4).toIntOrNull(),
                                gender = editGender
                            )
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
    // ── DIÁLOGO PARA CREAR NUEVA PUBLICACIÓN ──────────────────
    // ══════════════════════════════════════════════════════════
    if (isCreatePostOpen) {
        var postCategory by remember { mutableStateOf("Lectura & Naturaleza") }
        var postContent by remember { mutableStateOf("") }
        val categoryOptions = listOf(
            "Lectura & Naturaleza",
            "Jardinería & Balcón",
            "Cocina tradicional",
            "Música clásica",
            "Paseos y bienestar"
        )

        Dialog(onDismissRequest = { isCreatePostOpen = false }) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
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
                            text = "Nueva publicación",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        IconButton(
                            onClick = { isCreatePostOpen = false },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = FifoColors.LightTextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Comparte tus vivencias e historias favoritas con tus amigos en Fifo Amigos.",
                        fontSize = 12.sp,
                        color = FifoColors.LightTextSecondary,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Elige un tema o categoría",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categoryOptions.forEach { cat ->
                            val isSel = postCategory == cat
                            Surface(
                                onClick = { postCategory = cat },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) FifoColors.NavyPrimary else FifoColors.BlueSoftPill,
                                border = if (isSel) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
                            ) {
                                Text(
                                    text = cat,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) Color.White else FifoColors.NavyPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "¿Qué te gustaría compartir hoy?",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = postContent,
                        onValueChange = { postContent = it },
                        placeholder = {
                            Text(
                                "Ej: Hoy terminé de leer una novela hermosa y salí a caminar por el parque con una tarde soleada...",
                                fontSize = 13.sp,
                                color = FifoColors.LightTextMuted
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        color = FifoColors.HeroBlueCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = FifoColors.BlueAccentDark,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Aparecerá en tu perfil y también en el muro de Fifo Amigos.",
                                fontSize = 11.sp,
                                color = FifoColors.NavyPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { isCreatePostOpen = false },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Cancelar", fontSize = 14.sp)
                        }

                        Button(
                            onClick = {
                                if (postContent.isNotBlank()) {
                                    val newPost = UserSocialPost(
                                        id = System.currentTimeMillis().toString(),
                                        author = name,
                                        age = age.toIntOrNull() ?: 68,
                                        category = postCategory,
                                        timeAgo = "Recién publicado",
                                        content = postContent.trim(),
                                        likes = 0,
                                        commentsCount = 0,
                                        accentColorHex = 0xFF38BDF8
                                    )
                                    userPosts = listOf(newPost) + userPosts
                                    isCreatePostOpen = false
                                }
                            },
                            enabled = postContent.isNotBlank(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Publicar", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ── DIÁLOGO PARA CONFIRMAR ELIMINACIÓN DE PUBLICACIÓN ─────
    if (postToDelete != null) {
        val target = postToDelete!!
        AlertDialog(
            onDismissRequest = { postToDelete = null },
            title = {
                Text(
                    text = "Eliminar publicación",
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.NavyPrimary
                )
            },
            text = {
                Text(
                    text = "¿Deseas eliminar esta publicación de tu perfil y de Fifo Amigos?",
                    color = FifoColors.LightTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        userPosts = userPosts.filter { it.id != target.id }
                        postToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Eliminar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { postToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // ── DIÁLOGO PARA CONFIRMAR ELIMINACIÓN DE GUSTO ───────────
    if (tasteToDelete != null) {
        val target = tasteToDelete!!
        AlertDialog(
            onDismissRequest = { tasteToDelete = null },
            title = {
                Text(
                    text = "Eliminar gusto del perfil",
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.NavyPrimary
                )
            },
            text = {
                Text(
                    text = "¿Deseas quitar '${target.title}' de tus gustos curados en el perfil?",
                    color = FifoColors.LightTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        tasteStories = tasteStories.filter { it.id != target.id }
                        tasteToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Eliminar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { tasteToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
