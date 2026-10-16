package com.fifo.voicepipeline.ui.components

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fifo.voicepipeline.R
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.ui.theme.FifoColors
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

/**
 * Datos del usuario extraídos directamente de su cuenta oficial de Google.
 */
data class GoogleUserData(
    val googleId: String,
    val email: String,
    val fullName: String,
    val photoUrl: String? = null
)

/**
 * Hook composable para lanzar el flujo nativo oficial de Google Sign-In
 * de Google Play Services sin pedirle al usuario que escriba ni dicte su correo.
 */
@Composable
fun rememberGoogleSignInLauncher(
    onSuccess: (GoogleUserData) -> Unit,
    onError: (String) -> Unit = {}
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Launcher secundario de selector oficial de cuentas del sistema Android (sin requerir SHA-1 de GCP registrado)
    val systemAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val chosenEmail = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        if (!chosenEmail.isNullOrBlank()) {
            val name = chosenEmail.substringBefore("@")
                .split(".", "_", "-")
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
            val googleId = "goog_" + chosenEmail.filter { it.isLetterOrDigit() }.lowercase().take(12)
            Log.i("GoogleSignIn", "Cuenta de Google seleccionada vía AccountPicker del sistema: $name ($chosenEmail)")
            onSuccess(GoogleUserData(googleId = googleId, email = chosenEmail, fullName = name, photoUrl = null))
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data == null) {
            Log.d("GoogleSignIn", "Resultado vacío de Google Sign-In")
            return@rememberLauncherForActivityResult
        }

        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            val email = account?.email ?: ""
            val extractedName: String? = account?.displayName?.ifBlank { null }
                ?: listOfNotNull(account?.givenName, account?.familyName).joinToString(" ").ifBlank { null }
                ?: if (email.isNotEmpty()) {
                    email.substringBefore("@")
                        .split(".", "_", "-")
                        .filter { it.isNotBlank() && it.length > 1 }
                        .joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
                        .ifBlank { null }
                } else null
            val name: String = extractedName ?: ""
            val photoUrl = account?.photoUrl?.toString()
            val googleId = account?.id ?: ("goog_" + email.filter { it.isLetterOrDigit() }.lowercase().take(12))

            Log.i("GoogleSignIn", "Cuenta de Google autenticada exitosamente: $name ($email)")
            onSuccess(GoogleUserData(googleId = googleId, email = email, fullName = name, photoUrl = photoUrl))
        } catch (e: ApiException) {
            val errorMsg = when (e.statusCode) {
                12501 -> "Selección cancelada por el usuario"
                12500 -> "Error interno de Google Play Services (12500)"
                7 -> "Sin conexión a internet (código 7)"
                10 -> "Código 10 (Falta registrar SHA-1 en Google Cloud Console)"
                else -> "Código de respuesta de Google: ${e.statusCode}"
            }
            Log.w("GoogleSignIn", "Google sign-in ApiException: $errorMsg (código ${e.statusCode})")
            if (e.statusCode == 10 || e.statusCode == 12500 || e.statusCode == 7) {
                // Si el SHA-1 aún no fue subido o hay error de red en el emulador (código 7),
                // abrimos el selector nativo del sistema para elegir la cuenta con 1 toque sin bloquear al usuario
                try {
                    val chooseIntent = android.accounts.AccountManager.newChooseAccountIntent(
                        null, null, arrayOf("com.google"), null, null, null, null
                    )
                    systemAccountPickerLauncher.launch(chooseIntent)
                    return@rememberLauncherForActivityResult
                } catch (fallbackEx: Exception) {
                    Log.w("GoogleSignIn", "Fallo al abrir AccountManager chooser: ${fallbackEx.message}")
                }
            }
            if (e.statusCode != 12501) {
                onError(errorMsg)
            }
        } catch (e: Exception) {
            Log.e("GoogleSignIn", "Error procesando cuenta de Google: ${e.message}", e)
            onError(e.message ?: "Error al autenticar")
        }
    }

    return {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .build()
            val client = GoogleSignIn.getClient(context, gso)
            launcher.launch(client.signInIntent)
        } catch (e: Exception) {
            Log.e("GoogleSignIn", "No se pudo abrir Google Sign-In, intentando selector directo: ${e.message}", e)
            try {
                val chooseIntent = android.accounts.AccountManager.newChooseAccountIntent(
                    null, null, arrayOf("com.google"), null, null, null, null
                )
                systemAccountPickerLauncher.launch(chooseIntent)
            } catch (fallbackEx: Exception) {
                onError("No se pudo abrir el servicio de Google: ${e.message}")
            }
        }
    }
}

/**
 * Ícono vectorial oficial de la 'G' de Google (4 colores oficiales: azul, rojo, amarillo, verde).
 * Renderizado nítido a través del vector drawable estándar de Google Identity.
 */
@Composable
fun GoogleIcon(modifier: Modifier = Modifier.size(22.dp)) {
    Image(
        painter = painterResource(id = R.drawable.ic_google_logo),
        contentDescription = "Google",
        modifier = modifier
    )
}

/**
 * Botón estándar de Google Sign-In accesible, táctil y de alta fidelidad visual.
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
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDADCE0)),
        shadowElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            GoogleIcon(modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF3C4043)
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

    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val launchNative = rememberGoogleSignInLauncher(
        onSuccess = { u ->
            onSignInSuccess(u.fullName)
        },
        onError = { err ->
            errorMessage = err
        }
    )

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
                        Icon(androidx.compose.material.icons.Icons.Default.Lock, contentDescription = null, tint = Color(0xFF166534), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Sin contraseñas difíciles. Tu perfil y tus recuerdos con Fifo se guardan de forma segura vinculados a Google.",
                            fontSize = 12.sp,
                            color = Color(0xFF166534),
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Selector oficial de Google del sistema operativo
                GoogleSignInButton(
                    onClick = launchNative,
                    text = "Seleccionar cuenta de Google"
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE2E8F0))
                    Text(
                        text = "O INGRESO DIRECTO",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFFE2E8F0))
                }

                Spacer(modifier = Modifier.height(16.dp))

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
                        placeholder = { Text("Ej. Tu nombre", color = Color(0xFF94A3B8)) },
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
                        placeholder = { Text("Ej. 65", color = Color(0xFF94A3B8)) },
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
