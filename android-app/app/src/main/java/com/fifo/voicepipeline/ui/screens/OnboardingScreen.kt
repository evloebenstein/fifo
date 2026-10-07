package com.fifo.voicepipeline.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.fifo.voicepipeline.service.FifoAccessibilityService
import com.fifo.voicepipeline.ui.components.CallAssistantGuideDialog
import com.fifo.voicepipeline.ui.components.FifoFace
import com.fifo.voicepipeline.ui.theme.FifoColors

/**
 * Fases del Setup guiado de Fifo:
 * 1. CREATE_ACCOUNT: Crea tu cuenta básica (Nombre, Correo, Contraseña)
 * 2. TURN_ON_FIFO: Mensaje para encender el robot Fifo
 * 3. FIFO_QUESTION_BIRTHDAY: Fifo pregunta año de nacimiento y fecha de cumpleaños
 * 4. FIFO_QUESTION_GENDER: Fifo pregunta cómo se identifica (género)
 * 5. FIFO_QUESTION_CITY: Fifo pregunta ciudad / comuna
 * 6. FIFO_QUESTION_TASTES: Fifo pregunta primeros gustos e intereses
 * 7. FIFO_SETUP_READY: Resumen interactivo completado por Fifo
 */
private enum class SetupPhase {
    CREATE_ACCOUNT,
    TURN_ON_FIFO,
    FIFO_QUESTION_BIRTHDAY,
    FIFO_QUESTION_GENDER,
    FIFO_QUESTION_CITY,
    FIFO_QUESTION_TASTES,
    SETUP_PERMISSIONS,
    FIFO_SETUP_READY
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onComplete: (name: String, age: String, interests: List<String>) -> Unit,
    onBack: () -> Unit
) {
    var currentPhase by remember { mutableStateOf(SetupPhase.CREATE_ACCOUNT) }
    var showCallGuideDialogInSetup by remember { mutableStateOf(false) }

    // Datos recopilados en el Setup
    var fullName by remember { mutableStateOf("Lucía González") }
    var email by remember { mutableStateOf("lucia.gonzalez@correo.cl") }
    var password by remember { mutableStateOf("••••••••") }

    var birthDayMonth by remember { mutableStateOf("14 de Mayo") }
    var birthYear by remember { mutableStateOf("1958") }
    var ageCalculated by remember { mutableStateOf("68") }

    var gender by remember { mutableStateOf("Mujer") }
    var city by remember { mutableStateOf("Santiago, Chile") }

    val defaultInterests = listOf(
        "Lectura histórica",
        "Música clásica",
        "Paseos en el parque",
        "Jardinería",
        "Cocina tradicional",
        "Pintura y arte"
    )
    var selectedInterests by remember {
        mutableStateOf(
            setOf("Lectura histórica", "Música clásica", "Paseos en el parque", "Jardinería", "Cocina tradicional")
        )
    }

    // Actualizar edad estimada cuando cambia el año
    LaunchedEffect(birthYear) {
        val yearNum = birthYear.trim().toIntOrNull()
        if (yearNum != null && yearNum in 1900..2026) {
            ageCalculated = (2026 - yearNum).toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FifoColors.LightBackground)
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // ── Header: Botón Volver y Paso Actual ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    when (currentPhase) {
                        SetupPhase.CREATE_ACCOUNT -> onBack()
                        SetupPhase.TURN_ON_FIFO -> currentPhase = SetupPhase.CREATE_ACCOUNT
                        SetupPhase.FIFO_QUESTION_BIRTHDAY -> currentPhase = SetupPhase.TURN_ON_FIFO
                        SetupPhase.FIFO_QUESTION_GENDER -> currentPhase = SetupPhase.FIFO_QUESTION_BIRTHDAY
                        SetupPhase.FIFO_QUESTION_CITY -> currentPhase = SetupPhase.FIFO_QUESTION_GENDER
                        SetupPhase.FIFO_QUESTION_TASTES -> currentPhase = SetupPhase.FIFO_QUESTION_CITY
                        SetupPhase.SETUP_PERMISSIONS -> currentPhase = SetupPhase.FIFO_QUESTION_TASTES
                        SetupPhase.FIFO_SETUP_READY -> currentPhase = SetupPhase.SETUP_PERMISSIONS
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Volver",
                    tint = FifoColors.LightTextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Atrás",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = FifoColors.LightTextPrimary
                )
            }

            Surface(
                color = FifoColors.BlueSoftPill,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = when (currentPhase) {
                        SetupPhase.CREATE_ACCOUNT -> "Paso 1 de 2 · Cuenta"
                        SetupPhase.TURN_ON_FIFO -> "Paso 2 de 2 · Conectar"
                        SetupPhase.FIFO_QUESTION_BIRTHDAY -> "Pregunta 1 de 4"
                        SetupPhase.FIFO_QUESTION_GENDER -> "Pregunta 2 de 4"
                        SetupPhase.FIFO_QUESTION_CITY -> "Pregunta 3 de 4"
                        SetupPhase.FIFO_QUESTION_TASTES -> "Pregunta 4 de 4"
                        SetupPhase.SETUP_PERMISSIONS -> "Paso 5 de 5 · Permisos"
                        SetupPhase.FIFO_SETUP_READY -> "Setup completado"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.NavyPrimary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ══════════════════════════════════════════════════════════════
        // ── FASE 1: CREA TU CUENTA BÁSICA ────────────────────────────
        // ══════════════════════════════════════════════════════════════
        if (currentPhase == SetupPhase.CREATE_ACCOUNT) {
            Surface(
                color = FifoColors.HeroBlueCard,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FifoFace(size = 54.dp, isDarkTheme = false)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "tu amigo fifo",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        Text(
                            text = "A tu ritmo, sin prisa.",
                            fontSize = 12.sp,
                            color = FifoColors.LightTextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Crea tu cuenta",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Ingresa tus datos básicos para comenzar. Tu robot Fifo te ayudará con el resto de la configuración hablando contigo.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = FifoColors.LightTextSecondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Nombre completo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                placeholder = { Text("Ej. Lucía González", color = FifoColors.LightTextMuted) },
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

            Text("Correo electrónico", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = { Text("lucia@ejemplo.cl", color = FifoColors.LightTextMuted) },
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

            Text("Contraseña", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
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

            Spacer(modifier = Modifier.height(30.dp))

            Button(
                onClick = { currentPhase = SetupPhase.TURN_ON_FIFO },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Crear cuenta y encender a Fifo", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        // ══════════════════════════════════════════════════════════════
        // ── FASE 2: PRENDE A FIFO PARA TERMINAR EL SETUP ─────────────
        // ══════════════════════════════════════════════════════════════
        else if (currentPhase == SetupPhase.TURN_ON_FIFO) {
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = Color.White,
                shape = RoundedCornerShape(26.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(FifoColors.HeroBlueCard, CircleShape)
                            .border(2.dp, Color(0xFFBAE6FD), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        FifoFace(size = 76.dp, isDarkTheme = false)
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "¡Ahora prende a tu robot Fifo!",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.NavyPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Presiona el botón de encendido de tu Fifo. Una vez encendido, Fifo te hará unas preguntas para completar el setup por ti.",
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = FifoColors.LightTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(22.dp))

                    // Tarjeta explicativa amigable
                    Surface(
                        color = FifoColors.HeroBlueCard,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🤖", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Fifo se encargará del resto",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                                Text(
                                    text = "Te preguntará tu cumpleaños, cómo te identificas y qué cosas te gustan.",
                                    fontSize = 11.sp,
                                    color = FifoColors.NavyPrimary.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(26.dp))

                    Button(
                        onClick = { currentPhase = SetupPhase.FIFO_QUESTION_BIRTHDAY },
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("Ya encendí a Fifo · Iniciar preguntas", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        // ══════════════════════════════════════════════════════════════
        // ── PREGUNTA 1 DE FIFO: AÑO Y FECHA DE NACIMIENTO (CUMPLEAÑOS) ──
        // ══════════════════════════════════════════════════════════════
        else if (currentPhase == SetupPhase.FIFO_QUESTION_BIRTHDAY) {
            FifoConversationHeader(
                questionTitle = "¿En qué año naciste y tu cumpleaños?",
                questionSpeech = "¡Hola $fullName! Qué gran alegría conocerte. Para empezar a celebrar tus momentos especiales... ¿En qué año naciste y qué día celebras tu cumpleaños?"
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Día y mes de cumpleaños", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = birthDayMonth,
                onValueChange = { birthDayMonth = it },
                placeholder = { Text("Ej. 14 de Mayo", color = FifoColors.LightTextMuted) },
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

            Text("Año de nacimiento", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = birthYear,
                onValueChange = { birthYear = it },
                placeholder = { Text("Ej. 1958", color = FifoColors.LightTextMuted) },
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

            Spacer(modifier = Modifier.height(14.dp))

            // Tarjeta de cálculo y empatía de Fifo
            Surface(
                color = FifoColors.HeroBlueCard,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBAE6FD)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎂", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Edad estimada: $ageCalculated años",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        Text(
                            text = "Fifo guardará tu fecha ($birthDayMonth de $birthYear) para saludarte con cariño.",
                            fontSize = 11.sp,
                            color = FifoColors.NavyPrimary.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { currentPhase = SetupPhase.FIFO_QUESTION_GENDER },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Siguiente pregunta de Fifo →", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        // ══════════════════════════════════════════════════════════════
        // ── PREGUNTA 2 DE FIFO: GÉNERO E IDENTIDAD ───────────────────
        // ══════════════════════════════════════════════════════════════
        else if (currentPhase == SetupPhase.FIFO_QUESTION_GENDER) {
            FifoConversationHeader(
                questionTitle = "¿Cómo te identificas?",
                questionSpeech = "¿Cómo te identificas? Así sabré siempre cómo dirigirme a ti con el mayor cariño y respeto."
            )

            Spacer(modifier = Modifier.height(24.dp))

            val genderOptions = listOf(
                "Mujer" to "Dirigirse como doña, amiga o por tu nombre femenino",
                "Hombre" to "Dirigirse como don, amigo o por tu nombre masculino",
                "No binario" to "Dirigirse de forma neutra y personalizada",
                "Prefiero no decirlo" to "Dirigirse únicamente por tu nombre"
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                genderOptions.forEach { (option, desc) ->
                    val isSelected = gender == option
                    Surface(
                        onClick = { gender = option },
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) FifoColors.BlueSoftPill else Color.White,
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            if (isSelected) FifoColors.BlueAccentDark else FifoColors.LightCardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(if (isSelected) FifoColors.NavyPrimary else Color.Transparent, CircleShape)
                                    .border(1.5.dp, if (isSelected) FifoColors.NavyPrimary else Color(0xFFCBD5E1), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = option,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) FifoColors.NavyPrimary else FifoColors.LightTextPrimary
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { currentPhase = SetupPhase.FIFO_QUESTION_CITY },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Siguiente pregunta de Fifo →", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        // ══════════════════════════════════════════════════════════════
        // ── PREGUNTA 3 DE FIFO: CIUDAD O COMUNA ──────────────────────
        // ══════════════════════════════════════════════════════════════
        else if (currentPhase == SetupPhase.FIFO_QUESTION_CITY) {
            FifoConversationHeader(
                questionTitle = "¿En qué ciudad vives?",
                questionSpeech = "¿En qué ciudad o comuna vives? Me servirá para saber el clima de tus mañanas y actividades de tu zona."
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Ciudad o lugar de residencia", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = city,
                onValueChange = { city = it },
                placeholder = { Text("Ej. Santiago, Chile", color = FifoColors.LightTextMuted) },
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

            Spacer(modifier = Modifier.height(14.dp))

            Text("Sugerencias rápidas:", fontSize = 12.sp, color = FifoColors.LightTextSecondary)
            Spacer(modifier = Modifier.height(8.dp))

            val quickCities = listOf("Santiago, Chile", "Valparaíso", "Concepción", "La Serena", "Viña del Mar")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickCities.forEach { item ->
                    Surface(
                        onClick = { city = item },
                        shape = RoundedCornerShape(12.dp),
                        color = if (city == item) FifoColors.BlueSoftPill else Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder)
                    ) {
                        Text(
                            text = item,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = FifoColors.NavyPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { currentPhase = SetupPhase.FIFO_QUESTION_TASTES },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Siguiente pregunta de Fifo →", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        // ══════════════════════════════════════════════════════════════
        // ── PREGUNTA 4 DE FIFO: PRIMEROS GUSTOS E INTERESES ───────────
        // ══════════════════════════════════════════════════════════════
        else if (currentPhase == SetupPhase.FIFO_QUESTION_TASTES) {
            FifoConversationHeader(
                questionTitle = "¿Cuáles son tus pasatiempos favoritos?",
                questionSpeech = "Y para empezar nuestras charlas cotidianas... ¿Qué temas o pasatiempos te alegran el día? Elige los que más disfrutes:"
            )

            Spacer(modifier = Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                defaultInterests.chunked(2).forEach { rowPair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowPair.forEach { item ->
                            val isSelected = item in selectedInterests
                            Surface(
                                onClick = {
                                    selectedInterests = if (isSelected) {
                                        selectedInterests - item
                                    } else {
                                        selectedInterests + item
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) FifoColors.BlueSoftPill else Color.White,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) FifoColors.BlueAccentDark else FifoColors.LightCardBorder
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .background(
                                                if (isSelected) FifoColors.NavyPrimary else Color.Transparent,
                                                CircleShape
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) FifoColors.NavyPrimary else Color(0xFFCBD5E1),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = item,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) FifoColors.NavyPrimary else FifoColors.LightTextPrimary
                                    )
                                }
                            }
                        }
                        if (rowPair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Tarjeta recordatoria empática
            Surface(
                color = FifoColors.HeroBlueCard,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBAE6FD)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FifoFace(size = 28.dp, isDarkTheme = false)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Si falta algún gusto no te preocupes: Fifo te irá escuchando en tus charlas diarias para agregar más detalles a tu perfil.",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = FifoColors.NavyPrimary.copy(alpha = 0.85f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { currentPhase = SetupPhase.FIFO_SETUP_READY },
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Siguiente: Permisos y Llamadas →", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        // ══════════════════════════════════════════════════════════════
        // ── FASE 5: PERMISOS Y ASISTENTE EN LLAMADAS ─────────────────
        // ══════════════════════════════════════════════════════════════
        else if (currentPhase == SetupPhase.SETUP_PERMISSIONS) {
            val context = LocalContext.current
            var isA11yActive by remember { mutableStateOf(FifoAccessibilityService.isEnabled(context)) }

            LaunchedEffect(Unit) {
                while (true) {
                    isA11yActive = FifoAccessibilityService.isEnabled(context)
                    kotlinx.coroutines.delay(1000)
                }
            }

            Surface(
                color = FifoColors.HeroBlueCard,
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBAE6FD)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(Color.White, CircleShape)
                            .border(1.5.dp, Color(0xFFBAE6FD), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        FifoFace(size = 40.dp, isDarkTheme = false)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Permisos de Fifo",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        Text(
                            text = "Para escucharte y ayudarte con tus llamadas.",
                            fontSize = 12.sp,
                            color = FifoColors.LightTextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Configuración del celular",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Para que tu experiencia sea completa, Fifo necesita acceso a las funciones del teléfono.",
                fontSize = 13.sp,
                color = FifoColors.LightTextSecondary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Tarjeta 1: Permisos Básicos (Micrófono, Teléfono, Bluetooth, GPS)
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("1. Funciones básicas del teléfono", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = FifoColors.NavyPrimary)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "• Micrófono: Para escuchar cuando le hablas.\n• Llamadas y Contactos: Para saber quién llama y contestar.\n• Bluetooth: Para conectar con tu robot Fifo.\n• Ubicación: Para decirte dónde estás o ayudarte.",
                        fontSize = 12.sp,
                        color = FifoColors.LightTextSecondary,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:com.fifo.voicepipeline")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Ignore
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("Revisar Permisos de la App", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tarjeta 2: Asistente en Llamadas (Accesibilidad)
            Surface(
                color = if (isA11yActive) Color(0xFFF0FDF4) else Color(0xFFFFFBEB),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isA11yActive) Color(0xFF86EFAC) else Color(0xFFFDE68A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isA11yActive) Icons.Default.CheckCircle else Icons.Default.PhoneInTalk,
                            contentDescription = null,
                            tint = if (isA11yActive) Color(0xFF16A34A) else Color(0xFFD97706),
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "2. Asistente en Llamadas",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.NavyPrimary
                            )
                            Text(
                                text = if (isA11yActive) "¡Activo y listo!" else "Recomendado para llamadas",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isA11yActive) Color(0xFF16A34A) else Color(0xFFD97706)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Permite que Fifo te escuche y pueda colgar o contestar en llamadas normales de celular, WhatsApp, WeChat o videollamadas.",
                        fontSize = 12.sp,
                        color = FifoColors.LightTextSecondary,
                        lineHeight = 17.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (!isA11yActive) {
                        Button(
                            onClick = { showCallGuideDialogInSetup = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text("Ver pasos de activación (Xiaomi / Android)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        Surface(
                            color = Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "✓ Fifo podrá asistirte durante cualquier llamada o videollamada.",
                                fontSize = 12.sp,
                                color = Color(0xFF166534),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { currentPhase = SetupPhase.FIFO_SETUP_READY },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Continuar a Resumen Final →", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        // ══════════════════════════════════════════════════════════════
        // ── FASE FINAL: SETUP COMPLETADO POR FIFO ────────────────────
        // ══════════════════════════════════════════════════════════════
        else if (currentPhase == SetupPhase.FIFO_SETUP_READY) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(26.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .background(FifoColors.HeroBlueCard, CircleShape)
                            .border(2.dp, FifoColors.BlueAccentDark, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        FifoFace(size = 70.dp, isDarkTheme = false)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "¡Setup completado por Fifo!",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.NavyPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Fifo registró tus datos y preparó tu perfil a partir de tus respuestas.",
                        fontSize = 13.sp,
                        color = FifoColors.LightTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Tarjeta Resumen del Perfil creado por Fifo
                    Surface(
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Resumen de tu perfil:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.NavyPrimary
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            SetupSummaryRow(icon = "👤", label = "Nombre:", value = fullName)
                            SetupSummaryRow(icon = "🎂", label = "Cumpleaños:", value = "$birthDayMonth ($birthYear) · $ageCalculated años")
                            SetupSummaryRow(icon = "🌸", label = "Identidad:", value = gender)
                            SetupSummaryRow(icon = "📍", label = "Ciudad:", value = city)

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Gustos que Fifo recordará:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FifoColors.LightTextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                selectedInterests.forEach { item ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = FifoColors.BlueSoftPill
                                    ) {
                                        Text(
                                            text = item,
                                            fontSize = 11.sp,
                                            color = FifoColors.NavyPrimary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            onComplete(fullName.ifBlank { "Lucía González" }, ageCalculated, selectedInterests.toList())
                        },
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FifoColors.NavyPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("¡Empezar a hablar con Fifo!", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        if (showCallGuideDialogInSetup) {
            CallAssistantGuideDialog(
                onDismiss = { showCallGuideDialogInSetup = false }
            )
        }
    }
}

/**
 * Cabecera con globo de diálogo conversacional animado de Fifo
 */
@Composable
private fun FifoConversationHeader(
    questionTitle: String,
    questionSpeech: String
) {
    Surface(
        color = FifoColors.HeroBlueCard,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBAE6FD)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color.White, CircleShape)
                        .border(1.5.dp, Color(0xFFBAE6FD), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    FifoFace(size = 34.dp, isDarkTheme = false)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Fifo pregunta:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.BlueAccentDark
                    )
                    Text(
                        text = questionTitle,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.NavyPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "« $questionSpeech »",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = FifoColors.NavyPrimary,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun SetupSummaryRow(
    icon: String,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = FifoColors.LightTextSecondary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = FifoColors.LightTextPrimary
        )
    }
}
