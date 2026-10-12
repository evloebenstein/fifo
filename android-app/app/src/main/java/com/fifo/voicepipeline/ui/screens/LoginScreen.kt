package com.fifo.voicepipeline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.network.FifoBackendClient
import com.fifo.voicepipeline.ui.components.FifoFace
import com.fifo.voicepipeline.ui.components.GoogleSignInButton
import com.fifo.voicepipeline.ui.components.GoogleSignInDialog
import com.fifo.voicepipeline.ui.theme.FifoColors
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoginSuccess: (String) -> Unit,
    onBackToWelcome: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var identifier by remember { mutableStateOf("Lucía") }
    var availableUsers by remember { mutableStateOf<List<FifoBackendClient.UserProfileSummary>>(emptyList()) }
    var showGoogleDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Cargar los perfiles existentes en la base de datos MySQL para acceso rápido sin teclear
    LaunchedEffect(Unit) {
        val users = FifoBackendClient.fetchAllUsers()
        if (users.isNotEmpty()) {
            availableUsers = users
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FifoColors.DarkBackground)
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Top Header / Logo ───
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                color = FifoColors.DarkBadgeBg,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "TU AMIGO FIFO",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.DarkBadgeText,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Hero Central: Fifo + Title ───
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            FifoFace(
                size = 96.dp,
                isDarkTheme = true
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "¡Qué bueno verte!",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Elige tu perfil o entra con tu cuenta favorita.",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            // ── Botón Principal de Google Sign-In ───
            GoogleSignInButton(
                onClick = { showGoogleDialog = true },
                text = "Continuar con Google"
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Separador con texto "O con tu perfil"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF334155))
                Text(
                    text = "O ELIGE TU PERFIL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF334155))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Selector horizontal de perfiles desde la base de datos MySQL
            if (availableUsers.isNotEmpty()) {
                Text(
                    text = "Perfiles registrados en la base de datos:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFCBD5E1),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    availableUsers.forEach { user ->
                        val isSelected = identifier.equals(user.fullName, ignoreCase = true) ||
                                identifier.equals(user.id, ignoreCase = true)
                        Surface(
                            onClick = {
                                identifier = user.fullName
                                FifoDataRepository.switchUserProfile(context, user.id)
                                onLoginSuccess(user.fullName)
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) Color(0xFF1E3A8A) else Color(0xFF1E293B),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (isSelected) Color(0xFF60A5FA) else Color(0xFF334155)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Color(user.avatarBgHex), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = user.fullName.take(1),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = user.fullName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${user.age} años · ${user.city.take(12)}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
            }

            // Campo Nombre o Correo manual
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Nombre o Identificador",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    placeholder = { Text("Ej. Lucía, Diego o tu correo", color = Color(0xFF64748B)) },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = FifoColors.DarkInputBg,
                        unfocusedContainerColor = FifoColors.DarkInputBg,
                        focusedBorderColor = FifoColors.BlueAccent,
                        unfocusedBorderColor = FifoColors.DarkInputBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage!!,
                    color = Color(0xFFF87171),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Bottom Action Button ───
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    if (identifier.isBlank()) {
                        errorMessage = "Por favor escribe tu nombre o elige un perfil"
                        return@Button
                    }
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        val ok = FifoDataRepository.loginWithIdentifier(context, identifier.trim())
                        isLoading = false
                        if (ok) {
                            onLoginSuccess(identifier.trim())
                        } else {
                            // Si no se encuentra en MySQL, cambiar perfil localmente para no bloquear al usuario
                            FifoDataRepository.switchUserProfile(context, "usr_lucia_01")
                            onLoginSuccess(identifier.trim().ifEmpty { "Lucía" })
                        }
                    }
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FifoColors.DarkButtonPrimary,
                    contentColor = FifoColors.DarkButtonText
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = FifoColors.DarkButtonText, modifier = Modifier.size(24.dp))
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Iniciar Sesión",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Volver atrás",
                fontSize = 13.sp,
                color = FifoColors.DarkBadgeText,
                modifier = Modifier.clickable { onBackToWelcome() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Tu espacio para ser tú.",
                fontSize = 12.sp,
                color = Color(0xFF64748B)
            )
        }
    }

    if (showGoogleDialog) {
        GoogleSignInDialog(
            onDismiss = { showGoogleDialog = false },
            onSignInSuccess = { name ->
                showGoogleDialog = false
                onLoginSuccess(name)
            }
        )
    }
}
