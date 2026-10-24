package com.fifo.voicepipeline.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.fifo.voicepipeline.audio.NativeSpeechRecognizer
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.ui.model.CommunityActivityItem
import com.fifo.voicepipeline.ui.model.ContactSource
import com.fifo.voicepipeline.ui.theme.FifoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(
    userName: String,
    isBleConnected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val liveActivities by FifoDataRepository.communityActivities.collectAsState()
    val userProfile by FifoDataRepository.userProfile.collectAsState()
    val userTastes by FifoDataRepository.tastes.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("recommended") } // "recommended", "community"
    var selectedActivityForInvite by remember { mutableStateOf<CommunityActivityItem?>(null) }
    var selectedFriendsForInvite by remember { mutableStateOf(emptySet<String>()) }
    var isCreatingActivityDialogVisible by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    // Filtrado de actividades: Recomendados (afinidad con gustos y Fifo) vs Comunidad completa + Búsqueda manual
    val filteredActivities = remember(liveActivities, selectedFilter, userTastes, searchQuery) {
        val q = searchQuery.trim().lowercase()
        val userTastesLower = userTastes.map { it.lowercase().trim() }

        val baseList = when (selectedFilter) {
            "recommended" -> {
                liveActivities.filter { act ->
                    val hasTasteMatch = act.targetTastes.any { t ->
                        val tLow = t.lowercase().trim()
                        userTastesLower.any { ut -> ut.contains(tLow) || tLow.contains(ut) }
                    }
                    val isFifoCurated = act.organizerType == "fifo"
                    hasTasteMatch || isFifoCurated
                }.sortedByDescending { act ->
                    var score = 0
                    if (act.targetTastes.any { t -> userTastesLower.any { ut -> ut.contains(t.lowercase().trim()) } }) score += 2
                    if (act.organizerType == "fifo") score += 1
                    score
                }
            }
            else -> liveActivities
        }

        if (q.isBlank()) {
            baseList
        } else {
            baseList.filter { act ->
                act.title.lowercase().contains(q) ||
                act.description.lowercase().contains(q) ||
                act.locationName.lowercase().contains(q) ||
                act.organizerName.lowercase().contains(q) ||
                act.targetTastes.any { it.lowercase().contains(q) }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FifoColors.LightBackground)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // Encabezado principal sin kickers redundantes (Directo y claro según Impeccable)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Actividades y encuentros",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.LightTextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Espacios pensados para tus gustos y el encuentro en comunidad.",
                    fontSize = 13.sp,
                    color = FifoColors.LightTextSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Botón principal accesible (mínimo 48dp de alto) para proponer un nuevo encuentro
        Button(
            onClick = { isCreatingActivityDialogVisible = true },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = FifoColors.NavyPrimary,
                contentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "¿Deseas crear un encuentro? Proponer plan",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Barra de búsqueda manual accesible (por nombre, lugar o gusto)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Buscar evento, lugar o interés...", fontSize = 13.sp, color = FifoColors.LightTextMuted) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Buscar", tint = FifoColors.NavyPrimary, modifier = Modifier.size(20.dp))
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda", tint = FifoColors.LightTextMuted, modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Color.White,
                focusedContainerColor = Color.White,
                unfocusedBorderColor = FifoColors.LightCardBorder,
                focusedBorderColor = FifoColors.NavyPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Fila de Chips de filtrado accesibles: "Para ti (Recomendados)" y "Comunidad"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == "recommended",
                onClick = { selectedFilter = "recommended" },
                leadingIcon = {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selectedFilter == "recommended") Color.White else FifoColors.NavyPrimary
                    )
                },
                label = { Text("Para ti (Recomendados)", fontSize = 13.sp, fontWeight = if (selectedFilter == "recommended") FontWeight.Bold else FontWeight.Medium) },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FifoColors.NavyPrimary,
                    selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White
                )
            )

            FilterChip(
                selected = selectedFilter == "community",
                onClick = { selectedFilter = "community" },
                leadingIcon = {
                    Icon(
                        Icons.Default.PeopleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selectedFilter == "community") Color.White else FifoColors.NavyPrimary
                    )
                },
                label = { Text("Comunidad (${liveActivities.size})", fontSize = 13.sp, fontWeight = if (selectedFilter == "community") FontWeight.Bold else FontWeight.Medium) },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FifoColors.NavyPrimary,
                    selectedLabelColor = Color.White,
                )
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Lista reactiva de actividades comunitarias
        if (filteredActivities.isEmpty()) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.EventBusy,
                        contentDescription = null,
                        tint = FifoColors.LightTextMuted,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "No hay actividades en esta categoría",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Puedes ser el primero en proponer un encuentro vecinal sobre lo que más te apasiona.",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp)
            ) {
                items(filteredActivities, key = { it.id }) { act ->
                    val userHasAffinity = remember(act.targetTastes, userTastes) {
                        val ut = userTastes.map { it.lowercase().trim() }
                        act.targetTastes.any { t -> ut.any { it.contains(t.lowercase().trim()) } }
                    }

                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            // Fila superior: Badge de Organizador y Plazas Disponibles
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Badge del tipo de organizador (Fifo, Vecino, Taller Comunitario)
                                when (act.organizerType) {
                                    "fifo" -> {
                                        Surface(
                                            color = Color(0xFFE0F2FE),
                                            border = BorderStroke(1.dp, Color(0xFFBAE6FD)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Verified, contentDescription = null, tint = Color(0xFF0369A1), modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Patrocinado por Fifo", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0369A1))
                                            }
                                        }
                                    }
                                    "user" -> {
                                        val isMe = act.organizerId == userProfile.id
                                        Surface(
                                            color = Color(0xFFDCFCE7),
                                            border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(if (isMe) "Iniciado por ti" else "Iniciado por ${act.organizerName}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
                                            }
                                        }
                                    }
                                    else -> {
                                        Surface(
                                            color = Color(0xFFFEF3C7),
                                            border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Apartment, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Patrocinado por ${act.organizerName}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309))
                                            }
                                        }
                                    }
                                }

                                // Disponibilidad de cupos
                                Surface(
                                    color = if (act.spotsRemaining > 0) FifoColors.BlueSoftPill else Color(0xFFF3F4F6),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        text = if (act.spotsRemaining > 0) "Quedan ${act.spotsRemaining} cupos" else "Cupos completos",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (act.spotsRemaining > 0) FifoColors.NavyPrimary else FifoColors.LightTextMuted,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Título de la actividad
                            Text(
                                text = act.title,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.LightTextPrimary
                            )

                            if (act.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = act.description,
                                    fontSize = 13.sp,
                                    color = FifoColors.LightTextSecondary,
                                    lineHeight = 18.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Metadatos de Fecha y Lugar
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = FifoColors.LightTextMuted, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(act.dateLabel, fontSize = 12.sp, color = FifoColors.LightTextPrimary, fontWeight = FontWeight.Medium)
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = FifoColors.LightTextMuted, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(act.locationName, fontSize = 12.sp, color = FifoColors.LightTextSecondary)
                                }
                                Text(act.distanceLabel, fontSize = 11.sp, color = FifoColors.LightTextMuted)
                            }

                            // Tags de Gustos e Intereses Relacionados
                            if (act.targetTastes.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    act.targetTastes.take(3).forEach { taste ->
                                        val isShared = userTastes.any { it.contains(taste, ignoreCase = true) || taste.contains(it, ignoreCase = true) }
                                        Surface(
                                            color = if (isShared) Color(0xFFEFF6FF) else Color(0xFFF9FAFB),
                                            border = BorderStroke(1.dp, if (isShared) Color(0xFFBFDBFE) else FifoColors.LightCardBorder),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isShared) {
                                                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(11.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                }
                                                Text(
                                                    text = taste,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isShared) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isShared) Color(0xFF1D4ED8) else FifoColors.LightTextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Fila de Acciones accesibles (>=48dp de alto)
                            if (act.isJoined) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Estado: Te has unido (puede pulsar para ver estado o cancelar si lo desea)
                                    OutlinedButton(
                                        onClick = { FifoDataRepository.toggleJoinActivity(act.id) },
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.5.dp, Color(0xFF10B981)),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = Color(0xFFECFDF5),
                                            contentColor = Color(0xFF047857)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Inscrito(a)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Botón de invitar amigos (se desbloquea al estar inscrito)
                                    OutlinedButton(
                                        onClick = { selectedActivityForInvite = act },
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, FifoColors.NavyPrimary.copy(alpha = 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = Color.White,
                                            contentColor = FifoColors.NavyPrimary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Invitar amigos", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            } else {
                                // Antes de unirse: botón principal único que ocupa todo el ancho
                                Button(
                                    onClick = { FifoDataRepository.toggleJoinActivity(act.id) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = FifoColors.NavyPrimary,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Unirme al encuentro", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── DIALOG 1: CREAR / PROPONER ENCUENTRO ASISTIDO POR VOZ / BLUETOOTH CON FIFO ──────
    if (isCreatingActivityDialogVisible) {
        val context = LocalContext.current
        var generatedTitle by remember { mutableStateOf("") }
        var generatedDesc by remember { mutableStateOf("") }
        var generatedLoc by remember { mutableStateOf("Plaza o Centro Comunitario cercano") }
        var generatedDate by remember { mutableStateOf("Próximo sábado · 16:30") }
        var generatedSpots by remember { mutableStateOf("10") }
        var generatedTastes by remember { mutableStateOf(userTastes.take(3)) }
        var isFormFilledByVoice by remember { mutableStateOf(false) }

        var isListeningToSpeech by remember { mutableStateOf(false) }
        var voiceTranscript by remember { mutableStateOf("") }
        var voiceStatusText by remember { mutableStateOf("") }

        val speechRecognizer = remember {
            NativeSpeechRecognizer(
                context = context,
                onReady = {
                    voiceStatusText = "Fifo está escuchando tu voz..."
                },
                onPartialResult = { partial ->
                    voiceTranscript = partial
                },
                onResult = { finalResult ->
                    val text = finalResult.ifBlank { voiceTranscript }.trim()
                    isListeningToSpeech = false
                    voiceTranscript = text
                    if (text.isNotBlank()) {
                        val suggested = FifoDataRepository.suggestActivityWithFifo(text, userTastes)
                        generatedTitle = suggested.title
                        generatedDesc = suggested.description
                        generatedLoc = suggested.locationName
                        generatedDate = suggested.dateLabel
                        generatedSpots = suggested.spotsMax.toString()
                        if (suggested.targetTastes.isNotEmpty()) {
                            generatedTastes = suggested.targetTastes
                        }
                        isFormFilledByVoice = true
                        voiceStatusText = "Ficha completada a partir de tu voz"
                    }
                },
                onError = { _, _ ->
                    isListeningToSpeech = false
                    if (voiceTranscript.isNotBlank()) {
                        val suggested = FifoDataRepository.suggestActivityWithFifo(voiceTranscript, userTastes)
                        generatedTitle = suggested.title
                        generatedDesc = suggested.description
                        generatedLoc = suggested.locationName
                        generatedDate = suggested.dateLabel
                        generatedSpots = suggested.spotsMax.toString()
                        if (suggested.targetTastes.isNotEmpty()) {
                            generatedTastes = suggested.targetTastes
                        }
                        isFormFilledByVoice = true
                        voiceStatusText = "Ficha completada"
                    } else {
                        voiceStatusText = "No se detectó voz. Vuelve a tocar para hablar."
                    }
                }
            )
        }

        DisposableEffect(Unit) {
            onDispose {
                speechRecognizer.stop()
                speechRecognizer.release()
            }
        }

        Dialog(
            onDismissRequest = {
                speechRecognizer.stop()
                isCreatingActivityDialogVisible = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.92f),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = FifoColors.LightBackground
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp)
                    ) {
                        // Header del diálogo
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Proponer un encuentro",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.LightTextPrimary
                            )
                            IconButton(onClick = {
                                speechRecognizer.stop()
                                isCreatingActivityDialogVisible = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar")
                            }
                        }

                        Text(
                            text = "Fifo te ayuda a armar los detalles para reunir a vecinos con tus mismos intereses.",
                            fontSize = 12.sp,
                            color = FifoColors.LightTextSecondary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Asistente de Voz / Bluetooth con Fifo para planear el encuentro
                            item {
                                Surface(
                                    color = Color.White,
                                    border = BorderStroke(1.5.dp, if (isListeningToSpeech) Color(0xFFEF4444) else FifoColors.NavyPrimary.copy(alpha = 0.25f)),
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(18.dp)) {
                                        // Estado de hardware Bluetooth vs Micrófono
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.RecordVoiceOver,
                                                    contentDescription = null,
                                                    tint = FifoColors.NavyPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Planear encuentro por voz",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = FifoColors.NavyPrimary
                                                )
                                            }

                                            Surface(
                                                color = if (isBleConnected) Color(0xFFEFF6FF) else Color(0xFFF1F5F9),
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, if (isBleConnected) Color(0xFFBFDBFE) else Color(0xFFE2E8F0))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = if (isBleConnected) Icons.Default.BluetoothConnected else Icons.Default.Mic,
                                                        contentDescription = null,
                                                        tint = if (isBleConnected) Color(0xFF1D4ED8) else FifoColors.LightTextSecondary,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = if (isBleConnected) "Robot Bluetooth" else "Micrófono",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (isBleConnected) Color(0xFF1D4ED8) else FifoColors.LightTextSecondary
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = "Habla con Fifo para que organice todos los detalles del encuentro según lo que conversen.",
                                            fontSize = 12.sp,
                                            color = FifoColors.LightTextSecondary,
                                            lineHeight = 16.sp
                                        )

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // Botón principal de captura de voz (accesible, >=52dp)
                                        Button(
                                            onClick = {
                                                if (isListeningToSpeech) {
                                                    isListeningToSpeech = false
                                                    speechRecognizer.stop()
                                                    val text = voiceTranscript.trim()
                                                    if (text.isNotBlank()) {
                                                        val suggested = FifoDataRepository.suggestActivityWithFifo(text, userTastes)
                                                        generatedTitle = suggested.title
                                                        generatedDesc = suggested.description
                                                        generatedLoc = suggested.locationName
                                                        generatedDate = suggested.dateLabel
                                                        generatedSpots = suggested.spotsMax.toString()
                                                        if (suggested.targetTastes.isNotEmpty()) {
                                                            generatedTastes = suggested.targetTastes
                                                        }
                                                        isFormFilledByVoice = true
                                                        voiceStatusText = "Ficha completada a partir de tu voz"
                                                    }
                                                } else {
                                                    voiceTranscript = ""
                                                    voiceStatusText = "Iniciando micrófono..."
                                                    isListeningToSpeech = true
                                                    speechRecognizer.startContinuousListening()
                                                }
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isListeningToSpeech) Color(0xFFDC2626) else FifoColors.NavyPrimary,
                                                contentColor = Color.White
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(52.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = if (isListeningToSpeech) Icons.Default.Stop else Icons.Default.Mic,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = if (isListeningToSpeech) "Escuchando... Toca para finalizar" else "Hablar con Fifo para planear",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        // Cuadro de transcripción en vivo
                                        if (isListeningToSpeech || voiceTranscript.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Surface(
                                                color = if (isListeningToSpeech) Color(0xFFFEF2F2) else Color(0xFFF8FAFC),
                                                border = BorderStroke(1.dp, if (isListeningToSpeech) Color(0xFFFECACA) else FifoColors.LightCardBorder),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.GraphicEq,
                                                            contentDescription = null,
                                                            tint = if (isListeningToSpeech) Color(0xFFDC2626) else FifoColors.NavyPrimary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = if (isListeningToSpeech) "Fifo te está escuchando:" else "Lo que dijiste:",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isListeningToSpeech) Color(0xFF991B1B) else FifoColors.LightTextPrimary
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = if (voiceTranscript.isNotBlank()) "«$voiceTranscript»" else "Cuéntale a Fifo tu idea (ej: 'Quiero juntar a vecinos a cocinar recetas y tomar once el sábado')",
                                                        fontSize = 12.sp,
                                                        color = FifoColors.LightTextPrimary,
                                                        lineHeight = 17.sp
                                                    )
                                                }
                                            }
                                        }

                                        // Confirmación cuando se auto-completó la ficha
                                        if (isFormFilledByVoice) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Surface(
                                                color = Color(0xFFECFDF5),
                                                border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = Color(0xFF059669),
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "Fifo completó los datos del encuentro abajo a partir de tu voz.",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF047857)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Campos editables del formulario
                            item {
                                Text("Título del encuentro", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = generatedTitle,
                                    onValueChange = { generatedTitle = it },
                                    placeholder = { Text("Nombre claro y amigable", fontSize = 13.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }

                            item {
                                Text("Descripción / De qué se tratará", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = generatedDesc,
                                    onValueChange = { generatedDesc = it },
                                    placeholder = { Text("Comparte qué harán, si hay que llevar algo y el ambiente esperado", fontSize = 13.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }

                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1.3f)) {
                                        Text("Lugar o punto de reunión", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        OutlinedTextField(
                                            value = generatedLoc,
                                            onValueChange = { generatedLoc = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(0.7f)) {
                                        Text("Cupos", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        OutlinedTextField(
                                            value = generatedSpots,
                                            onValueChange = { generatedSpots = it.filter { ch -> ch.isDigit() } },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    }
                                }
                            }

                            item {
                                Text("Fecha y horario sugerido", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = generatedDate,
                                    onValueChange = { generatedDate = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }

                            // Etiquetas asignadas por Fifo para atraer personas con este gusto
                            item {
                                Surface(
                                    color = Color(0xFFF8FAFC),
                                    border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Psychology, contentDescription = null, tint = FifoColors.NavyPrimary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                "Etiquetas de afinidad identificadas por Fifo:",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = FifoColors.NavyPrimary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Fifo usará estas etiquetas para recomendar el encuentro a personas con gustos similares.",
                                            fontSize = 11.sp,
                                            color = FifoColors.LightTextSecondary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (generatedTastes.isEmpty()) {
                                            Text(
                                                "Habla con Fifo arriba para autogenerar las etiquetas.",
                                                fontSize = 12.sp,
                                                color = FifoColors.LightTextMuted
                                            )
                                        } else {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                generatedTastes.forEach { tag ->
                                                    Surface(
                                                        color = Color(0xFFE0F2FE),
                                                        border = BorderStroke(1.dp, Color(0xFFBAE6FD)),
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(Icons.Default.Tag, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(12.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(tag, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0369A1))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Botón de confirmación para publicar
                        Button(
                            onClick = {
                                if (generatedTitle.isBlank()) {
                                    generatedTitle = if (voiceTranscript.isNotBlank()) voiceTranscript.take(40) else "Encuentro vecinal ameno"
                                }
                                val maxSp = generatedSpots.toIntOrNull()?.coerceIn(2, 50) ?: 10
                                val finalTastes = if (generatedTastes.isNotEmpty()) generatedTastes else userTastes.take(2)
                                FifoDataRepository.createCommunityActivity(
                                    title = generatedTitle,
                                    description = generatedDesc.ifBlank { "Reunión agradable para conversar y disfrutar de un momento en comunidad." },
                                    dateLabel = generatedDate,
                                    locationName = generatedLoc,
                                    spotsMax = maxSp,
                                    iconCategory = "nature",
                                    targetTastes = finalTastes,
                                    organizerType = "user",
                                    organizerName = userProfile.fullName.ifBlank { "Vecino" }
                                )
                                isCreatingActivityDialogVisible = false
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FifoColors.NavyPrimary,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text("Publicar encuentro comunitario", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ── DIALOG 2: INVITA A TUS AMIGOS A UNA ACTIVIDAD ──────────────
    selectedActivityForInvite?.let { act ->
        val liveContacts by FifoDataRepository.relationalContacts.collectAsState()
        var contactSources by remember {
            mutableStateOf<Map<String, List<ContactSource>>>(emptyMap())
        }
        LaunchedEffect(liveContacts) {
            contactSources = withContext(Dispatchers.IO) {
                FifoDataRepository.getContactAppearancesByContact()
                    .mapValues { (_, appearances) -> appearances.map { it.source }.distinct() }
            }
        }

        Dialog(
            onDismissRequest = { selectedActivityForInvite = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.9f),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = FifoColors.LightBackground
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { selectedActivityForInvite = null }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                                }
                                Text("Volver a actividades", fontSize = 14.sp, color = FifoColors.LightTextSecondary)
                            }
                            IconButton(onClick = { selectedActivityForInvite = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Invita a tus amigos",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.LightTextPrimary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Tarjeta resumen de la actividad seleccionada
                        Surface(
                            color = FifoColors.HeroBlueCard,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Encuentro seleccionado", fontSize = 11.sp, color = FifoColors.NavyPrimary, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(act.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = FifoColors.NavyPrimary)
                                Text(act.dateLabel, fontSize = 12.sp, color = FifoColors.NavyPrimary.copy(alpha = 0.8f))
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${act.locationName} · ${act.distanceLabel}", fontSize = 11.sp, color = FifoColors.NavyPrimary.copy(alpha = 0.7f))
                                    Text(if (act.spotsRemaining > 0) "Quedan ${act.spotsRemaining} cupos" else "Cupos completos", fontSize = 11.sp, color = FifoColors.NavyPrimary.copy(alpha = 0.8f))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Personas de tu red", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
                            Text("Selecciona a quién enviar", fontSize = 11.sp, color = FifoColors.LightTextMuted)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (liveContacts.isEmpty()) {
                                item {
                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Group,
                                                contentDescription = null,
                                                tint = FifoColors.NavyPrimary,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Aún no tienes contactos vinculados",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = FifoColors.NavyPrimary,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "En la sección 'Fifo amigos' puedes conectar con personas afines.",
                                                fontSize = 12.sp,
                                                color = FifoColors.LightTextSecondary,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }

                            items(liveContacts) { contact ->
                                val fName = contact.contactName
                                val fRole = contact.relationshipRole.ifBlank { "Contacto" }
                                val fMemory = contact.contextualMemory ?: "Vínculo de confianza"
                                val sources = contactSources[contact.id].orEmpty()
                                val isChecked = fName in selectedFriendsForInvite
                                Surface(
                                    onClick = {
                                        selectedFriendsForInvite = if (isChecked) {
                                            selectedFriendsForInvite - fName
                                        } else {
                                            selectedFriendsForInvite + fName
                                        }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color.White,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isChecked) FifoColors.NavyPrimary else FifoColors.LightCardBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(FifoColors.BlueSoftPill, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(fName.take(1), fontWeight = FontWeight.Bold, color = FifoColors.NavyPrimary)
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(fName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FifoColors.LightTextPrimary)
                                            Text(fRole, fontSize = 11.sp, color = FifoColors.LightTextSecondary)
                                            Text(fMemory, fontSize = 10.sp, color = FifoColors.LightTextMuted, maxLines = 1)
                                            if (sources.isNotEmpty()) {
                                                Surface(
                                                    color = FifoColors.BlueSoftPill,
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.padding(top = 3.dp)
                                                ) {
                                                    Text(
                                                        text = "Visto en " + sources.map(::contactSourceLabel).joinToString(" · "),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = FifoColors.NavyPrimary,
                                                        maxLines = 1,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = FifoColors.NavyPrimary
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                selectedActivityForInvite = null
                                selectedFriendsForInvite = emptySet()
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FifoColors.NavyPrimary,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enviar ${selectedFriendsForInvite.size} invitaciones", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Etiqueta breve y neutral para mostrar de dónde conoce Fifo a una persona.
 */
private fun contactSourceLabel(source: ContactSource): String = when (source) {
    ContactSource.PHONE_CONTACT -> "Libreta"
    ContactSource.FIFO_FRIEND -> "FifoAmigos"
    ContactSource.CONVERSATION -> "Conversación"
    ContactSource.CALL -> "Llamada"
    ContactSource.MANUAL -> "Manual"
}
