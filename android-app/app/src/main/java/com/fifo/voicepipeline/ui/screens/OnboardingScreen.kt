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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onComplete: (name: String, age: String, interests: List<String>) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf("Lucía") }
    var password by remember { mutableStateOf("••••••••") }
    var age by remember { mutableStateOf("68") }
    var gender by remember { mutableStateOf("Mujer") }
    var isGenderMenuOpen by remember { mutableStateOf(false) }

    val defaultInterests = listOf("Lectura", "Música", "Paseos", "Cocina", "Jardinería", "Cine")
    var selectedInterests by remember { mutableStateOf(setOf("Lectura", "Música", "Paseos")) }
    var customCategoryText by remember { mutableStateOf("") }
    var customCategories by remember { mutableStateOf(listOf<String>()) }

    var isBluetoothEnabled by remember { mutableStateOf(true) }
    var isLocationLinked by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FifoColors.LightBackground)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // ── Header: Volver + Logo ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Volver",
                    tint = FifoColors.LightTextPrimary
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Volver a inicio",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = FifoColors.LightTextPrimary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Tarjeta de Identidad Fifo
        Surface(
            color = FifoColors.HeroBlueCard,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FifoFace(
                    size = 54.dp,
                    isDarkTheme = false
                )
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

        Spacer(modifier = Modifier.height(26.dp))

        // ── Título y Subtítulo ───
        Text(
            text = "Cuéntame sobre ti",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = FifoColors.LightTextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Vamos a conocernos un poquito. ¿Cómo te gusta que te llamen?",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = FifoColors.LightTextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Campos de Texto ───
        Text("Nombre", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Ej. Carmen", color = FifoColors.LightTextMuted) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = FifoColors.BlueAccentDark,
                unfocusedBorderColor = FifoColors.LightCardBorder,
                focusedTextColor = FifoColors.LightTextPrimary,
                unfocusedTextColor = FifoColors.LightTextPrimary
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
            placeholder = { Text("Ej. ********", color = FifoColors.LightTextMuted) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = FifoColors.BlueAccentDark,
                unfocusedBorderColor = FifoColors.LightCardBorder,
                focusedTextColor = FifoColors.LightTextPrimary,
                unfocusedTextColor = FifoColors.LightTextPrimary
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Edad", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = age,
            onValueChange = { age = it },
            placeholder = { Text("Ej. 68 años", color = FifoColors.LightTextMuted) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = FifoColors.BlueAccentDark,
                unfocusedBorderColor = FifoColors.LightCardBorder,
                focusedTextColor = FifoColors.LightTextPrimary,
                unfocusedTextColor = FifoColors.LightTextPrimary
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Género", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isGenderMenuOpen = true },
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(gender, color = FifoColors.LightTextPrimary, fontSize = 14.sp)
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = FifoColors.LightTextSecondary
                    )
                }
            }

            DropdownMenu(
                expanded = isGenderMenuOpen,
                onDismissRequest = { isGenderMenuOpen = false }
            ) {
                listOf("Mujer", "Hombre", "No binario", "Prefiero no decirlo").forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            gender = option
                            isGenderMenuOpen = false
                        }
                    )
                }
            }
        }
        Text("También puedes elegir no indicarlo.", fontSize = 11.sp, color = FifoColors.LightTextMuted, modifier = Modifier.padding(top = 4.dp))

        Spacer(modifier = Modifier.height(24.dp))

        // ── ¿Qué te gusta? ───
        Text(
            text = "¿Qué te gusta?",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = FifoColors.LightTextPrimary
        )
        Text("Elige todos los gustos que quieras.", fontSize = 12.sp, color = FifoColors.LightTextSecondary)

        Spacer(modifier = Modifier.height(12.dp))

        // Chips con dos columnas como en Figma
        val allOptions = defaultInterests + customCategories
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            allOptions.chunked(2).forEach { rowPair ->
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
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) FifoColors.BlueSoftPill else Color.White,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) FifoColors.BlueAccentDark else FifoColors.LightCardBorder
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .background(
                                            if (isSelected) FifoColors.BlueAccentDark else Color.Transparent,
                                            CircleShape
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) FifoColors.BlueAccentDark else Color(0xFFCBD5E1),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = item,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) FifoColors.BlueSoftText else FifoColors.LightTextPrimary
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

        // Categorías personalizadas
        Text("Categorías personalizadas", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.LightTextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = customCategoryText,
            onValueChange = { customCategoryText = it },
            placeholder = { Text("Ej. Pintura, baile, costura...", color = FifoColors.LightTextMuted) },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = FifoColors.BlueAccentDark,
                unfocusedBorderColor = FifoColors.LightCardBorder,
                focusedTextColor = FifoColors.LightTextPrimary,
                unfocusedTextColor = FifoColors.LightTextPrimary
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            onClick = {
                if (customCategoryText.isNotBlank()) {
                    customCategories = customCategories + customCategoryText.trim()
                    selectedInterests = selectedInterests + customCategoryText.trim()
                    customCategoryText = ""
                }
            },
            shape = RoundedCornerShape(14.dp),
            color = FifoColors.BlueSoftPill,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "+ Añadir categoría",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FifoColors.NavyPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ── Servicios Necesarios (Bluetooth & Ubicación) ───
        Text(
            text = "Servicios Necesarios",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = FifoColors.LightTextPrimary
        )
        Text(
            text = "Debes activar estos servicios para usar tu Fifo.",
            fontSize = 12.sp,
            color = FifoColors.LightTextSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Item Bluetooth
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Activar Bluetooth",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FifoColors.LightTextPrimary
                    )
                    Text(
                        text = if (isBluetoothEnabled) "Activo para vincular Fifo" else "Pendiente de activar",
                        fontSize = 11.sp,
                        color = if (isBluetoothEnabled) FifoColors.StatusListening else FifoColors.LightTextMuted
                    )
                    Text(
                        text = "Permite el acceso a Bluetooth para usar tu Fifo.",
                        fontSize = 11.sp,
                        color = FifoColors.LightTextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Switch(
                    checked = isBluetoothEnabled,
                    onCheckedChange = { isBluetoothEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = FifoColors.NavyPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Item Ubicación
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ubicación",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FifoColors.LightTextPrimary
                    )
                    Text(
                        text = if (isLocationLinked) "Vinculado" else "Pendiente de vincular",
                        fontSize = 11.sp,
                        color = if (isLocationLinked) FifoColors.StatusListening else FifoColors.LightTextMuted
                    )
                    Text(
                        text = "Permite el acceso a tu ubicación para usar tu Fifo.",
                        fontSize = 11.sp,
                        color = FifoColors.LightTextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                OutlinedButton(
                    onClick = { isLocationLinked = !isLocationLinked },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = FifoColors.NavyPrimary
                    )
                ) {
                    Text(if (isLocationLinked) "Vinculado" else "Vincular", fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // ── Botón Continuar ───
        Button(
            onClick = {
                onComplete(name.ifEmpty { "Lucía" }, age.ifEmpty { "68" }, selectedInterests.toList())
            },
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = FifoColors.NavyPrimary,
                contentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Continuar",
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

        Spacer(modifier = Modifier.height(36.dp))
    }
}
