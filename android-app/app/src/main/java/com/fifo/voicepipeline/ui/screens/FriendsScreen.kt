package com.fifo.voicepipeline.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.ui.components.FifoFace
import com.fifo.voicepipeline.ui.theme.FifoColors
import kotlinx.coroutines.launch
import java.util.UUID

data class FriendPost(
    val id: String,
    val author: String,
    val username: String = "",
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
    val username: String,
    val age: Int,
    val quote: String,
    val tags: List<String>,
    val avatarBg: Color,
    val location: String = "Santiago, Chile",
    val bio: String = "",
    val talksCount: Int = 18,
    val commonInterestsCount: Int = 2,
    val samplePosts: List<String> = emptyList()
)

data class PostComment(
    val id: String,
    val author: String,
    val username: String,
    val avatarBg: Color,
    val text: String,
    val timeAgo: String
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FriendsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userProfileState by FifoDataRepository.userProfile.collectAsState()
    val currentUserName = userProfileState.fullName.ifBlank { "Tú" }
    val currentUsername = userProfileState.username.ifBlank { "@mi_usuario" }

    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    // Posts del muro: vienen del repositorio real (publicaciones de usuarios reales)
    val rawSocialPosts by FifoDataRepository.socialPosts.collectAsState()
    val posts = remember(rawSocialPosts) {
        rawSocialPosts.map { sp ->
            FriendPost(
                id = sp.id,
                author = sp.author.ifBlank { "Usuario Fifo" },
                username = sp.username.ifBlank { "@usuario" },
                age = sp.age,
                relation = sp.category,
                timeAgo = sp.timeAgo,
                content = sp.content,
                initialLikes = sp.likes,
                initialComments = sp.commentsCount
            )
        }
    }

    // Perfiles de búsqueda: vienen del backend (recomendaciones de amigos)
    var searchProfiles by remember { mutableStateOf<List<FriendProfile>>(emptyList()) }
    var isLoadingProfiles by remember { mutableStateOf(false) }
    LaunchedEffect(userProfileState.id) {
        if (userProfileState.id.isNotBlank()) {
            isLoadingProfiles = true
            scope.launch {
                val recs = com.fifo.voicepipeline.network.FifoBackendClient.fetchFriendRecommendations(userProfileState.id)
                searchProfiles = recs.map { r ->
                    FriendProfile(
                        id = r.friendId,
                        name = r.fullName,
                        username = "@${r.fullName.filter { it.isLetterOrDigit() || it == '_' }.lowercase().take(12)}",
                        age = r.age,
                        quote = r.quote,
                        tags = r.sharedTags,
                        avatarBg = Color(r.avatarBgHex),
                        location = "Santiago, Chile",
                        bio = r.bioAi,
                        talksCount = 0,
                        commonInterestsCount = r.sharedTags.size,
                        samplePosts = emptyList()
                    )
                }
                isLoadingProfiles = false
            }
        }
    }

    var connectedFriends by remember { mutableStateOf(setOf<String>()) }
    var selectedProfileForView by remember { mutableStateOf<FriendProfile?>(null) }
    var activeCommentsPost by remember { mutableStateOf<FriendPost?>(null) }

    // Estado reactivo y persistente de Likes (conteo y estado de me gusta por post)
    var likedPostIds by remember { mutableStateOf(setOf<String>()) }
    var postLikesMap by remember {
        mutableStateOf(posts.associate { it.id to it.initialLikes })
    }

    // Estado reactivo y persistente de Comentarios por post
    var postCommentsMap by remember {
        mutableStateOf(emptyMap<String, List<PostComment>>())
    }

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
                    modifier = Modifier.size(36.dp)
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

            // Barra de búsqueda con soporte para @ID, nombre o gustos
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar por nombre, @ID o gusto...", color = FifoColors.LightTextMuted) },
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

            val cleanQuery = searchQuery.trim().lowercase().removePrefix("@")
            val filteredProfiles = remember(searchQuery, searchProfiles) {
                if (cleanQuery.isBlank()) {
                    searchProfiles
                } else {
                    searchProfiles.filter { p ->
                        p.name.lowercase().contains(cleanQuery) ||
                        p.username.lowercase().removePrefix("@").contains(cleanQuery) ||
                        p.tags.any { it.lowercase().contains(cleanQuery) } ||
                        p.quote.lowercase().contains(cleanQuery)
                    }
                }
            }

            Text("Personas para ti (${filteredProfiles.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FifoColors.LightTextPrimary)
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                if (isLoadingProfiles) {
                    item {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = FifoColors.NavyPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Buscando personas afines...",
                                    fontSize = 14.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }
                    }
                } else if (filteredProfiles.isEmpty()) {
                    item {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = if (cleanQuery.isBlank()) Icons.Default.Groups else Icons.Default.Search,
                                    contentDescription = null,
                                    tint = FifoColors.NavyPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (cleanQuery.isBlank()) "Aún no hay recomendaciones disponibles" else "No encontramos a nadie con «$searchQuery»",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (cleanQuery.isBlank()) "Habla con Fifo para que identifique tus gustos y te conecte con personas afines." else "Intenta buscar por su nombre o gustos compartidos.",
                                    fontSize = 12.sp,
                                    color = FifoColors.LightTextSecondary,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                items(filteredProfiles, key = { it.id }) { p ->
                    val isConnected = p.id in connectedFriends
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Cabecera clickeable que también abre el perfil
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedProfileForView = p },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = p.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = FifoColors.LightTextPrimary
                                        )
                                        Text(
                                            text = "${p.age} años",
                                            fontSize = 12.sp,
                                            color = FifoColors.LightTextSecondary
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = p.username,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF0284C7)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "· ${p.commonInterestsCount} gustos afines",
                                            fontSize = 11.sp,
                                            color = FifoColors.LightTextSecondary
                                        )
                                    }
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

                            // Tags de gustos
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                p.tags.take(3).forEach { tag ->
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

                            Spacer(modifier = Modifier.height(14.dp))

                            // ── BOTONES DE ACCIÓN: VER PERFIL + CONECTAR ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Botón para ver el perfil completo
                                OutlinedButton(
                                    onClick = { selectedProfileForView = p },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, Color(0xFF0284C7)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF0284C7)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Ver perfil",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                // Botón para conectar o desconectar
                                Button(
                                    onClick = {
                                        connectedFriends = if (isConnected) connectedFriends - p.id else connectedFriends + p.id
                                        val toastMsg = if (!isConnected) "¡Te has conectado con ${p.name}!" else "Has dejado de conectar con ${p.name}"
                                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isConnected) FifoColors.StatusListening else FifoColors.NavyPrimary,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
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
                border = BorderStroke(1.dp, FifoColors.LightCardBorder),
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                if (posts.isEmpty()) {
                    item {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Feed,
                                    contentDescription = null,
                                    tint = FifoColors.NavyPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Aún no hay publicaciones en el muro",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Cuando tú o personas de la comunidad compartan historias o anécdotas, aparecerán aquí.",
                                    fontSize = 12.sp,
                                    color = FifoColors.LightTextSecondary,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                items(posts, key = { it.id }) { post ->
                    val isLiked = post.id in likedPostIds
                    val currentLikes = (postLikesMap[post.id] ?: post.initialLikes) + if (isLiked) 1 else 0
                    val commentsForPost = postCommentsMap[post.id] ?: emptyList()
                    val commentsCount = commentsForPost.size

                    // Resolver el perfil del autor para "Ver perfil"
                    val authorProfile = remember(post.author, post.username) {
                        searchProfiles.firstOrNull {
                            it.username.equals(post.username, ignoreCase = true) ||
                            it.name.equals(post.author, ignoreCase = true)
                        } ?: FriendProfile(
                            id = "p_${post.id}",
                            name = post.author,
                            username = post.username,
                            age = post.age,
                            quote = post.content,
                            tags = emptyList(),
                            avatarBg = Color(0xFFE0E7FF),
                            location = "Chile",
                            bio = "${post.author} es miembro de la comunidad de Fifo Amigos.",
                            talksCount = 0,
                            commonInterestsCount = 0,
                            samplePosts = listOf(post.content)
                        )
                    }

                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            // Cabecera de la publicación con acceso directo al perfil
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Avatar con clic para ver perfil
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(authorProfile.avatarBg, CircleShape)
                                        .clip(CircleShape)
                                        .clickable { selectedProfileForView = authorProfile },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = post.author.take(1),
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FifoColors.NavyPrimary
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // Nombre, @ID y detalles con clic para ver perfil
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedProfileForView = authorProfile }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = post.author,
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
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (post.username.startsWith("@")) post.username else "@${post.username}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF0284C7)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "· ${post.age} años",
                                            fontSize = 11.sp,
                                            color = FifoColors.LightTextSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Pastilla clara de "Ver perfil"
                                Surface(
                                    onClick = { selectedProfileForView = authorProfile },
                                    color = FifoColors.BlueSoftPill,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.AccountCircle,
                                            contentDescription = "Ver perfil",
                                            tint = FifoColors.BlueAccentDark,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Perfil",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = FifoColors.BlueAccentDark
                                        )
                                    }
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

                            // ── BARRA DE INTERACCIÓN ROBUSTA (LIKES, COMENTARIOS, COMPARTIR) ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Botón de Me Gusta (accesible, reactivo y con retroalimentación visual)
                                Surface(
                                    onClick = {
                                        likedPostIds = if (isLiked) likedPostIds - post.id else likedPostIds + post.id
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isLiked) Color(0xFFFEE2E2) else Color(0xFFF8FAFC),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isLiked) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
                                    )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                            contentDescription = if (isLiked) "Te gusta" else "Dar me gusta",
                                            tint = if (isLiked) Color(0xFFEF4444) else FifoColors.LightTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "$currentLikes",
                                            fontSize = 12.sp,
                                            fontWeight = if (isLiked) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isLiked) Color(0xFFB91C1C) else FifoColors.LightTextPrimary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isLiked) "Te gusta" else "Me gusta",
                                            fontSize = 11.sp,
                                            color = if (isLiked) Color(0xFFB91C1C) else FifoColors.LightTextSecondary
                                        )
                                    }
                                }

                                // 2. Botón de Comentarios interactivo (abre el diálogo de comentarios)
                                Surface(
                                    onClick = { activeCommentsPost = post },
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFFF8FAFC),
                                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ChatBubbleOutline,
                                            contentDescription = "Ver comentarios",
                                            tint = FifoColors.BlueAccentDark,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "$commentsCount",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = FifoColors.LightTextPrimary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Comentarios",
                                            fontSize = 11.sp,
                                            color = FifoColors.LightTextSecondary
                                        )
                                    }
                                }

                                // 3. Botón de Compartir
                                Surface(
                                    onClick = {
                                        Toast.makeText(context, "Publicación compartida con tus amigos en Fifo", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFFF8FAFC),
                                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Share,
                                            contentDescription = "Compartir",
                                            tint = FifoColors.LightTextSecondary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Compartir",
                                            fontSize = 11.sp,
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

    // ══════════════════════════════════════════════════════════════════
    // ── DIÁLOGO COMPLETO DE VER PERFIL DE AMIGO (DETALLE DE PERFIL) ──
    // ══════════════════════════════════════════════════════════════════
    selectedProfileForView?.let { profile ->
        val isFriendConnected = profile.id in connectedFriends
        FriendProfileDetailDialog(
            profile = profile,
            isConnected = isFriendConnected,
            onToggleConnect = {
                connectedFriends = if (isFriendConnected) connectedFriends - profile.id else connectedFriends + profile.id
                val toastMsg = if (!isFriendConnected) "¡Conectado con ${profile.name}!" else "Has dejado de conectar con ${profile.name}"
                Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
            },
            onSendGreeting = {
                Toast.makeText(context, "Fifo le envió tu saludo a ${profile.name}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { selectedProfileForView = null }
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // ── DIÁLOGO DE COMENTARIOS INTERACTIVOS (LEER Y ESCRIBIR) ─────────
    // ══════════════════════════════════════════════════════════════════
    activeCommentsPost?.let { post ->
        val currentComments = postCommentsMap[post.id] ?: emptyList()
        PostCommentsDialog(
            post = post,
            comments = currentComments,
            onAddComment = { commentText ->
                val newComment = PostComment(
                    id = UUID.randomUUID().toString(),
                    author = currentUserName,
                    username = currentUsername,
                    avatarBg = Color(0xFFBAE6FD),
                    text = commentText,
                    timeAgo = "Ahora mismo"
                )
                val updatedList = currentComments + newComment
                postCommentsMap = postCommentsMap + (post.id to updatedList)
                Toast.makeText(context, "Comentario publicado", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { activeCommentsPost = null }
        )
    }
}

/**
 * Diálogo de Detalle de Perfil de un Amigo en Fifo.
 * Presenta biografía generada por Fifo, gustos en común, publicaciones y opciones de conexión.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FriendProfileDetailDialog(
    profile: FriendProfile,
    isConnected: Boolean,
    onToggleConnect: () -> Unit,
    onSendGreeting: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp)
            ) {
                // Barra superior con botón de cerrar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Perfil en Fifo Amigos",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.NavyPrimary
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = FifoColors.LightTextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Contenido desplazable del perfil
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Tarjeta superior: Avatar, Nombre, @ID, Ciudad y Estado
                    Surface(
                        color = FifoColors.LightBackground,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(profile.avatarBg, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = profile.name.take(1),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = profile.name,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = FifoColors.LightTextPrimary
                            )

                            Spacer(modifier = Modifier.height(3.dp))

                            Surface(
                                color = FifoColors.BlueSoftPill,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = profile.username,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF0284C7),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "${profile.age} años · ${profile.location}",
                                fontSize = 12.sp,
                                color = FifoColors.LightTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Métricas de bienestar y afinidad con Fifo
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = FifoColors.NavyPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${profile.talksCount}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                                Text(
                                    text = "Charlas Fifo",
                                    fontSize = 10.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }

                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = null,
                                    tint = Color(0xFF0284C7),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${profile.commonInterestsCount} afines",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0284C7)
                                )
                                Text(
                                    text = "Compatibilidad",
                                    fontSize = 10.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }

                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, FifoColors.LightCardBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (isConnected) FifoColors.StatusListening else FifoColors.NavyPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isConnected) "Amigo" else "Sugerido",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isConnected) FifoColors.StatusListening else FifoColors.NavyPrimary
                                )
                                Text(
                                    text = "Estado",
                                    fontSize = 10.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // "Lo que Fifo dice sobre este perfil"
                    Text(
                        text = "Lo que Fifo dice de ${profile.name.split(" ").first()}:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = FifoColors.BlueSoftPill,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            FifoFace(size = 32.dp, isDarkTheme = false)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = profile.bio.ifBlank { profile.quote },
                                fontSize = 12.sp,
                                color = FifoColors.NavyPrimary,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Gustos e intereses reconocidos
                    Text(
                        text = "Gustos e intereses:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = FifoColors.LightTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        profile.tags.forEach { tag ->
                            Surface(
                                color = Color(0xFFF1F5F9),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                            ) {
                                Text(
                                    text = tag,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = FifoColors.LightTextPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    if (profile.samplePosts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Publicaciones recientes:",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.LightTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        profile.samplePosts.forEach { postContent ->
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = "«$postContent»",
                                    fontSize = 12.sp,
                                    color = FifoColors.LightTextPrimary,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Botones de acción inferiores
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onSendGreeting,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF0284C7)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0284C7)),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.WavingHand, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Saludar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onToggleConnect,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) FifoColors.StatusListening else FifoColors.NavyPrimary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
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
}

/**
 * Diálogo interactivo para ver los comentarios de una publicación y redactar nuevos comentarios.
 */
@Composable
fun PostCommentsDialog(
    post: FriendPost,
    comments: List<PostComment>,
    onAddComment: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newCommentText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Cabecera del diálogo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Comentarios (${comments.size})",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = FifoColors.NavyPrimary
                        )
                        Text(
                            text = "Publicación de ${post.author}",
                            fontSize = 11.sp,
                            color = FifoColors.LightTextSecondary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = FifoColors.LightTextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Resumen del post original
                Surface(
                    color = FifoColors.LightBackground,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "«${post.content.take(90)}${if (post.content.length > 90) "..." else ""}»",
                        fontSize = 11.sp,
                        color = FifoColors.LightTextSecondary,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Lista de comentarios
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (comments.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 30.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = FifoColors.NavyPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Aún no hay comentarios",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                                Text(
                                    text = "Sé la primera persona en dejar un mensaje cariñoso.",
                                    fontSize = 12.sp,
                                    color = FifoColors.LightTextSecondary
                                )
                            }
                        }
                    }

                    items(comments, key = { it.id }) { c ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(c.avatarBg, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = c.author.take(1),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FifoColors.NavyPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Surface(
                                color = Color(0xFFF8FAFC),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = c.author,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = FifoColors.LightTextPrimary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = c.username,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF0284C7)
                                            )
                                        }
                                        Text(
                                            text = c.timeAgo,
                                            fontSize = 10.sp,
                                            color = FifoColors.LightTextMuted
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = c.text,
                                        fontSize = 12.sp,
                                        color = FifoColors.LightTextPrimary,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Campo de entrada para redactar nuevo comentario
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newCommentText,
                            onValueChange = { newCommentText = it },
                            placeholder = { Text("Escribe un comentario amable...", fontSize = 12.sp, color = FifoColors.LightTextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        val canSend = newCommentText.trim().isNotBlank()
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    onAddComment(newCommentText.trim())
                                    newCommentText = ""
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Enviar comentario",
                                tint = if (canSend) FifoColors.BlueAccentDark else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
        }
    }
}
