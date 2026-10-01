package com.fifo.voicepipeline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.fifo.voicepipeline.network.ProvisioningState
import com.fifo.voicepipeline.ui.theme.FifoColors

@Composable
fun BleProvisioningDialog(
    provisioningState: ProvisioningState,
    defaultSsid: String,
    defaultPass: String,
    onDismiss: () -> Unit,
    onStartProvisioning: (String, String) -> Unit
) {
    var ssid by remember { mutableStateOf(defaultSsid) }
    var pass by remember { mutableStateOf(defaultPass) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = FifoColors.DarkBackground,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Configurar ESP32 por Bluetooth",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "El teléfono buscará 'FIFO-ESP32' y le enviará el nombre y clave de tu red o hotspot.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("Nombre Wi-Fi / Hotspot (SSID)", color = Color(0xFF94A3B8)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = FifoColors.DarkInputBg,
                        unfocusedContainerColor = FifoColors.DarkInputBg,
                        focusedBorderColor = FifoColors.BlueAccent,
                        unfocusedBorderColor = FifoColors.DarkInputBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Contraseña", color = Color(0xFF94A3B8)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = FifoColors.DarkInputBg,
                        unfocusedContainerColor = FifoColors.DarkInputBg,
                        focusedBorderColor = FifoColors.BlueAccent,
                        unfocusedBorderColor = FifoColors.DarkInputBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Estado del proceso
                when (provisioningState) {
                    is ProvisioningState.Scanning -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = FifoColors.BlueAccent)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Buscando FIFO-ESP32 por Bluetooth...", fontSize = 12.sp, color = FifoColors.BlueAccent)
                    }
                    is ProvisioningState.Connecting -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = FifoColors.StatusProcessing)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Conectando al ESP32...", fontSize = 12.sp, color = FifoColors.StatusProcessing)
                    }
                    is ProvisioningState.Sending -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = FifoColors.StatusListening)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Enviando credenciales por Bluetooth...", fontSize = 12.sp, color = FifoColors.StatusListening)
                    }
                    is ProvisioningState.Success -> {
                        Text("✅ ¡Credenciales guardadas con éxito!", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FifoColors.StatusListening)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("El ESP32 se está conectando a tu red.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                    is ProvisioningState.Error -> {
                        Text("❌ ${provisioningState.message}", fontSize = 12.sp, color = FifoColors.StatusError, textAlign = TextAlign.Center)
                    }
                    ProvisioningState.Idle -> {}
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (provisioningState is ProvisioningState.Success) "Cerrar" else "Cancelar", color = Color(0xFF94A3B8))
                    }
                    if (provisioningState !is ProvisioningState.Success) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onStartProvisioning(ssid, pass) },
                            enabled = ssid.isNotBlank() &&
                                    provisioningState !is ProvisioningState.Scanning &&
                                    provisioningState !is ProvisioningState.Connecting &&
                                    provisioningState !is ProvisioningState.Sending,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FifoColors.DarkButtonPrimary,
                                contentColor = FifoColors.DarkButtonText
                            )
                        ) {
                            Text("Enviar al ESP32", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
