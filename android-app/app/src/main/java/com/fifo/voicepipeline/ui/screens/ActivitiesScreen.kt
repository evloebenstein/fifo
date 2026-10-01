package com.fifo.voicepipeline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fifo.voicepipeline.ui.theme.FifoColors

data class ActivityItem(
    val id: String,
    val title: String,
    val spots: String,
    val date: String,
    val location: String,
    val distance: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    var isJoined: Boolean
)

@Composable
fun ActivitiesScreen(
    userName: String,
    modifier: Modifier = Modifier
) {
    var activities by remember {
        mutableStateOf(
            listOf(
                ActivityItem("1", "Club de lectura", "12 plazas disponibles", "Jueves 8 oct · 17:00", "Biblioteca central", "A 800 m", Icons.Outlined.MenuBook, true),
                ActivityItem("2", "Música clásica", "8 plazas disponibles", "Sábado 10 oct · 11:30", "Auditorio municipal", "A 1.2 km", Icons.Outlined.MusicNote, false),
                ActivityItem("3", "Paseo por el jardín", "15 plazas disponibles", "Domingo 11 oct · 10:00", "Parque central", "A 600 m", Icons.Outlined.Park, false)
            )
        )
    }

    var selectedActivityForInvite by remember { mutableStateOf<ActivityItem?>(null) }
    var selectedFriendsForInvite by remember { mutableStateOf(setOf("Sofía", "Valentina")) }
    var inviteSentSuccess by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FifoColors.LightBackground)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Actividades",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = FifoColors.LightTextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Banner informativo
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Un buen plan, cerca de ti.",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.NavyPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$userName, comparte lo que te gusta y disfruta en compañía.",
                    fontSize = 13.sp,
                    color = FifoColors.LightTextSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Próximas actividades",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Text(
                text = "${activities.size} planes",
                fontSize = 12.sp,
                color = FifoColors.LightTextMuted
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            items(activities) { act ->
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = FifoColors.BlueSoftPill,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = act.icon,
                                        contentDescription = null,
                                        tint = FifoColors.NavyPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = act.title,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.LightTextPrimary
                                )
                                Text(
                                    text = act.spots,
                                    fontSize = 11.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Fecha
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = FifoColors.LightTextMuted, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(act.date, fontSize = 12.sp, color = FifoColors.LightTextPrimary, fontWeight = FontWeight.Medium)
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Ubicación y distancia
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = FifoColors.LightTextMuted, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(act.location, fontSize = 12.sp, color = FifoColors.LightTextSecondary)
                            }
                            Text(act.distance, fontSize = 11.sp, color = FifoColors.LightTextMuted)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (act.isJoined) {
                            // Estado: Unido + Botón Invitar
                            Surface(
                                color = FifoColors.BlueSoftPill,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = FifoColors.NavyPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Te has unido", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FifoColors.NavyPrimary)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { selectedActivityForInvite = act },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = FifoColors.NavyPrimary,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.People, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Invita a tus amigos", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else {
                            // Botón Unirme a la actividad
                            Button(
                                onClick = {
                                    activities = activities.map {
                                        if (it.id == act.id) it.copy(isJoined = true) else it
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = FifoColors.NavyPrimary,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Unirme a la actividad", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── MODAL: INVITA A TUS AMIGOS (Figma Screen 10) ───
    selectedActivityForInvite?.let { act ->
        val friendsList = listOf(
            Triple("Sofía", "66 años", "Lectura · Música · Café"),
            Triple("Mateo", "72 años", "Paseos · Cine · Cocina"),
            Triple("Valentina", "64 años", "Música · Arte · Lectura"),
            Triple("Diego", "70 años", "Cine · Fotografía · Paseos")
        )

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
                                Text("Actividades", fontSize = 14.sp, color = FifoColors.LightTextSecondary)
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

                        Spacer(modifier = Modifier.height(16.dp))

                        // Tarjeta de la actividad
                        Surface(
                            color = FifoColors.HeroBlueCard,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Para compartir contigo", fontSize = 11.sp, color = FifoColors.NavyPrimary, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(act.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = FifoColors.NavyPrimary)
                                Text(act.date, fontSize = 12.sp, color = FifoColors.NavyPrimary.copy(alpha = 0.8f))
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${act.location} · ${act.distance}", fontSize = 11.sp, color = FifoColors.NavyPrimary.copy(alpha = 0.7f))
                                    Text(act.spots, fontSize = 11.sp, color = FifoColors.NavyPrimary.copy(alpha = 0.7f))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tus amigos", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
                            Text("Elige con quién te gustaría ir.", fontSize = 11.sp, color = FifoColors.LightTextMuted)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(friendsList) { (fName, fAge, fTags) ->
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
                                    border = androidx.compose.foundation.BorderStroke(
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
                                            Text("$fName · $fAge", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FifoColors.LightTextPrimary)
                                            Text(fTags, fontSize = 11.sp, color = FifoColors.LightTextSecondary)
                                            Text(if (isChecked) "Seleccionada" else "Toca para invitar", fontSize = 10.sp, color = if (isChecked) FifoColors.NavyPrimary else FifoColors.LightTextMuted)
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

                        Spacer(modifier = Modifier.height(16.dp))

                        // Botón de Enviar Invitaciones
                        Button(
                            onClick = {
                                inviteSentSuccess = true
                                selectedActivityForInvite = null
                            },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FifoColors.NavyPrimary,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Enviar ${selectedFriendsForInvite.size} invitaciones", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Recibirán el plan y podrán decidir si se unen. Sin prisa, sin presión.",
                            fontSize = 11.sp,
                            color = FifoColors.LightTextMuted,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}
