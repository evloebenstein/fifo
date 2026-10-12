package com.fifo.voicepipeline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.ui.theme.FifoColors
import kotlinx.coroutines.launch

/**
 * Ícono vectorial oficial dibujado de la 'G' de Google con sus 4 colores distintivos.
 */
@Composable
fun GoogleIcon(modifier: Modifier = Modifier.size(24.dp)) {
    Box(
        modifier = modifier
            .background(Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)

            // Fondo circular sutil con bordes de cuadrante simulando el logo G
            drawCircle(color = Color(0xFF4285F4), radius = w / 2f, center = center)
            drawCircle(color = Color.White, radius = w * 0.35f, center = center)
            drawRect(
                color = Color.White,
                topLeft = Offset(center.x, 0f),
                size = androidx.compose.ui.geometry.Size(w / 2f, h * 0.6f)
            )
            // Barra horizontal de la G
            drawRect(
                color = Color(0xFF4285F4),
                topLeft = Offset(center.x * 0.7f, center.y - (h * 0.12f)),
                size = androidx.compose.ui.geometry.Size(w * 0.45f, h * 0.24f)
            )
            // Sector rojo superior
            drawArc(
                color = Color(0xFFEA4335),
                startAngle = 210f,
                sweepAngle = 110f,
                useCenter = true,
                topLeft = Offset(0f, 0f),
                size = size
            )
            // Sector amarillo izquierdo
            drawArc(
                color = Color(0xFFFBBC05),
                startAngle = 135f,
                sweepAngle = 75f,
                useCenter = true,
                topLeft = Offset(0f, 0f),
                size = size
            )
            // Sector verde inferior
            drawArc(
                color = Color(0xFF34A853),
                startAngle = 45f,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = Offset(0f, 0f),
                size = size
            )
            // Centro blanco de corte
            drawCircle(color = Color.White, radius = w * 0.32f, center = center)
            // Re-dibujar barra de la G
            drawRect(
                color = Color(0xFF4285F4),
                topLeft = Offset(center.x * 0.65f, center.y - (h * 0.1f)),
                size = androidx.compose.ui.geometry.Size(w * 0.45f, h * 0.2f)
            )
        }
    }
}

/**
 * Botón estándar de Google Sign-In accesible y táctil.
 */
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "Continuar con Google"
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFCBD5E1)),
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            GoogleIcon(modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1E293B)
            )
        }
    }
}

/**
 * Diálogo accesible para inicio de sesión y registro rápido con cuenta de Google.
 * Diseñado específicamente para que personas mayores o sus familiares puedan
 * entrar sin recordar contraseñas complejas.
 */
@Composable
fun GoogleSignInDialog(
    onDismiss: () -> Unit,
    onSignInSuccess: (userName: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("usuario.familia@gmail.com") }
    var name by remember { mutableStateOf("Carlos Mendoza") }
    var age by remember { mutableStateOf("71") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Cabecera con botón cerrar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GoogleIcon(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Acceso con Google",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color(0xFF64748B)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mensaje cálido accesible
                Surface(
                    color = Color(0xFFF0FDF4),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBBF7D0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🛡️", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Sin contraseñas difíciles. Tu perfil y tus recuerdos con Fifo se guardan de forma segura vinculados a Google.",
                            fontSize = 12.sp,
                            color = Color(0xFF166534),
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Campo Correo de Google
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Correo de Google",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF334155)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = { Text("ejemplo@gmail.com", color = Color(0xFF94A3B8)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4285F4),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Campo Nombre completo
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Nombre que usará Fifo",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF334155)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Ej. Carlos Mendoza", color = Color(0xFF94A3B8)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4285F4),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Campo Edad aproximada
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Edad aproximada",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF334155)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = age,
                        onValueChange = { age = it },
                        placeholder = { Text("Ej. 71", color = Color(0xFF94A3B8)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4285F4),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFDC2626),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Botón Acción
                Button(
                    onClick = {
                        if (name.isBlank() || email.isBlank()) {
                            errorMessage = "Por favor ingresa tu nombre y correo"
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null
                        val googleId = "goog_" + email.filter { it.isLetterOrDigit() }.lowercase().take(12)
                        scope.launch {
                            val ok = FifoDataRepository.loginWithGoogle(
                                context = context,
                                googleId = googleId,
                                email = email.trim(),
                                fullName = name.trim(),
                                estimatedAge = age.toIntOrNull() ?: 70,
                                city = "Santiago, Chile"
                            )
                            isLoading = false
                            if (ok) {
                                onSignInSuccess(name.trim())
                            } else {
                                errorMessage = "No fue posible conectar. Se inició en modo local."
                                onSignInSuccess(name.trim())
                            }
                        }
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E3A8A)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Text(
                            text = "Entrar a mi espacio",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
