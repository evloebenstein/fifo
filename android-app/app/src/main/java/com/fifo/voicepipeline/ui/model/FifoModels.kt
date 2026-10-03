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

/**
 * Datos del perfil del usuario gestionados por Fifo y sincronizados con la Base de Datos
 */
data class UserProfileData(
    val id: String = "usr_01",
    val fullName: String = "Lucía González",
    val birthDate: String = "14 de Mayo, 1958",
    val birthYear: Int = 1958,
    val estimatedAge: Int = 68,
    val genderIdentity: String = "Mujer",
    val city: String = "Santiago, Chile",
    val bioAi: String = "Amante de las novelas de historia, la música clásica de piano y las mañanas tranquilas con café. Disfruta compartir con Fifo anécdotas de su familia, preparar recetas caseras y cuidar las orquídeas de su jardín.",
    val emergencyContactName: String = "Carmen (Hija)",
    val emergencyContactPhone: String = "+56987654321"
)

/**
 * Recordatorio creado por voz por Fifo (para pastillas, compromisos, familia)
 */
data class FifoReminderItem(
    val id: String,
    val title: String,
    val timeStr: String,
    val category: String = "medication", // "medication", "family", "hobby", "health"
    val isCompleted: Boolean = false,
    val createdAt: String = "Hoy"
)
