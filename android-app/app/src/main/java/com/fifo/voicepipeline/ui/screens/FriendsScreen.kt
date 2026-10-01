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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
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

data class FriendPost(
    val id: String,
    val author: String,
    val age: Int,
    val relation: String,
    val timeAgo: String,
    val content: String,
    val initialLikes: Int,
    val initialComments: Int
)

data class FriendProfile(
    val id: String,
    val name: String,
    val age: Int,
    val quote: String,
    val tags: List<String>,
    val avatarBg: Color
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FriendsScreen(
    modifier: Modifier = Modifier
) {
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val posts = remember {
        listOf(
            FriendPost(
                id = "1",
                author = "Lucía",
                age = 68,
                relation = "Amiga cercana · Lectura y paseos",
                timeAgo = "2 h",
                content = "Acabo de terminar una novela de Elena Ferrante en el parque. Me encantaría encontrar a alguien para comentarla con calma y luego dar un paseo por el jardín.",
                initialLikes = 18,
                initialComments = 6
            ),
            FriendPost(
                id = "2",
                author = "Sofía",
                age = 66,
                relation = "Amiga cercana · Música y tertulia",
                timeAgo = "5 h",
                content = "Esta tarde escuché un concierto de piano en el auditorio y me recordó a mis clases de juventud. ¿Alguien quiere hablar de música clásica y compartir recomendaciones?",
                initialLikes = 24,
                initialComments = 9
            ),
            FriendPost(
                id = "3",
                author = "Mateo",
                age = 72,
                relation = "Amigo cercano · Paseos y lectura",
                timeAgo = "1 d",
                content = "Ayer descubrí un nuevo sendero cerca del río y me gustaría repetirlo con alguien que disfrute de la naturaleza y la conversación tranquila.",
                initialLikes = 12,
                initialComments = 4
            )
        )
    }

    val searchProfiles = remember {
        listOf(
            FriendProfile("1", "Sofía", 66, "Un libro y una buena charla, mi plan ideal.", listOf("Lectura", "Música", "Café"), Color(0xFFE9D5FF)),
            FriendProfile("2", "Mateo", 72, "Me encanta descubrir rutas y recetas nuevas.", listOf("Paseos", "Cine", "Cocina"), Color(0xFFCCFBF1)),
            FriendProfile("3", "Valentina", 64, "Siempre con una canción o un dibujo en mente.", listOf("Música", "Arte", "Lectura"), Color(0xFFFFEDD5)),
            FriendProfile("4", "Diego", 70, "Busco compañía para caminar sin prisa.", listOf("Cine", "Fotografía", "Paseos"), Color(0xFFFEF08A))
        )
    }

    var connectedFriends by remember { mutableStateOf(setOf<String>()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FifoColors.LightBackground)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        if (isSearching) {
            // ── VISTA BÚSQUEDA DE PERSONAS (Figma Screen 6) ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { isSearching = false },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Busca a tus amigos",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = FifoColors.LightTextPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Barra de búsqueda
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Nombre o gusto", color = FifoColors.LightTextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = FifoColors.LightTextMuted) },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = FifoColors.BlueAccentDark,
                    unfocusedBorderColor = FifoColors.LightCardBorder
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Chips de filtro
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = FifoColors.NavyPrimary,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Filtros · 2",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    color = FifoColors.BlueSoftPill,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Gustos afines",
                        color = FifoColors.NavyPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    color = FifoColors.BlueSoftPill,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "65-80 años",
                        color = FifoColors.NavyPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text("Personas para ti", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(searchProfiles) { p ->
                    val isConnected = p.id in connectedFriends
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(p.avatarBg, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = p.name.take(1),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.NavyPrimary
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${p.name} · ${p.age} años",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.LightTextPrimary
                                    )
                                    Text(
                                        text = "2 gustos en común",
                                        fontSize = 11.sp,
                                        color = FifoColors.LightTextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = p.quote,
                                fontSize = 13.sp,
                                color = FifoColors.LightTextPrimary,
                                lineHeight = 18.sp
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Tags
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                p.tags.forEach { tag ->
                                    Surface(
                                        color = FifoColors.BlueSoftPill,
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = tag,
                                            fontSize = 11.sp,
                                            color = FifoColors.BlueSoftText,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Botón Conectar
                            OutlinedButton(
                                onClick = {
                                    connectedFriends = if (isConnected) connectedFriends - p.id else connectedFriends + p.id
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (isConnected) FifoColors.StatusListening else FifoColors.NavyPrimary
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isConnected) FifoColors.StatusListening else FifoColors.LightCardBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.Check else Icons.Default.PersonAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isConnected) "Conectado" else "Conectar",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

        } else {
            // ── VISTA MURO FIFO AMIGOS (Figma Screen 5) ───
            Text(
                text = "Fifo amigos",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Text(
                text = "Un gusto en común, un buen comienzo.",
                fontSize = 13.sp,
                color = FifoColors.LightTextSecondary
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Tarjeta Hero: "Lo que te gusta, mejor en compañía."
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Lo que te gusta, mejor en compañía.",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.LightTextPrimary,
                                lineHeight = 22.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Hay alguien con quien compartir.",
                                fontSize = 12.sp,
                                color = FifoColors.LightTextSecondary
                            )
                        }
                        Box(modifier = Modifier.padding(start = 8.dp)) {
                            FifoFace(size = 46.dp, isDarkTheme = false)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { isSearching = true },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FifoColors.NavyPrimary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Conecta con gente con tus gustos", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Publicaciones para ti",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = FifoColors.LightTextPrimary
            )
            Text(
                text = "Historias de lectura, música y paseos para compartir con amigos cercanos.",
                fontSize = 12.sp,
                color = FifoColors.LightTextSecondary,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(posts) { post ->
                    var likes by remember { mutableStateOf(post.initialLikes) }
                    var hasLiked by remember { mutableStateOf(false) }

                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, FifoColors.LightCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(FifoColors.BlueSoftPill, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = post.author.take(1),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.NavyPrimary
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${post.author}, ${post.age} años",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = FifoColors.LightTextPrimary
                                        )
                                        Text(
                                            text = post.timeAgo,
                                            fontSize = 11.sp,
                                            color = FifoColors.LightTextMuted
                                        )
                                    }
                                    Text(
                                        text = post.relation,
                                        fontSize = 11.sp,
                                        color = FifoColors.LightTextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = post.content,
                                fontSize = 13.sp,
                                color = FifoColors.LightTextPrimary,
                                lineHeight = 19.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Acciones de interacción (Likes, comentarios, compartir)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        hasLiked = !hasLiked
                                        likes += if (hasLiked) 1 else -1
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (hasLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = "Me gusta",
                                        tint = if (hasLiked) Color(0xFFEF4444) else FifoColors.LightTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "$likes",
                                        fontSize = 12.sp,
                                        color = FifoColors.LightTextSecondary
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = "Comentarios",
                                        tint = FifoColors.LightTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${post.initialComments}",
                                        fontSize = 12.sp,
                                        color = FifoColors.LightTextSecondary
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Share,
                                        contentDescription = "Compartir",
                                        tint = FifoColors.LightTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Compartir",
                                        fontSize = 12.sp,
                                        color = FifoColors.LightTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
