package com.fifo.voicepipeline.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Modelo de una conversación pasada con Fifo
 */
data class PastConversationItem(
    val id: String,
    val title: String,
    val date: String,
    val duration: String,
    val summary: String,
    val tag: String,
    val iconName: String = "chat"
) {
    val icon: ImageVector
        get() = when (iconName) {
            "restaurant" -> Icons.Outlined.Restaurant
            "music" -> Icons.Outlined.MusicNote
            "heart" -> Icons.Outlined.FavoriteBorder
            "air" -> Icons.Outlined.Air
            else -> Icons.Outlined.ChatBubbleOutline
        }
}

/**
 * Modelo de un recuerdo que Fifo aprendió de las conversaciones
 */
data class FifoMemoryItem(
    val id: String,
    val emoji: String,
    val title: String,
    val detail: String,
    val learnedDate: String
)

/**
 * Publicación creada por la usuaria en su perfil / Fifo Amigos
 */
data class UserSocialPost(
    val id: String,
    val author: String = "Lucía",
    val age: Int = 68,
    val category: String,
    val timeAgo: String,
    val content: String,
    var likes: Int,
    val commentsCount: Int,
    val accentColorHex: Long = 0xFF38BDF8
)

/**
 * Artículo o historia de blog sobre un gusto del usuario generado por Fifo
 * a partir de las charlas cotidianas (sin información sensible)
 */
data class FifoTasteStory(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val tags: List<String>,
    val iconCategory: String,
    val learnedFrom: String
)
