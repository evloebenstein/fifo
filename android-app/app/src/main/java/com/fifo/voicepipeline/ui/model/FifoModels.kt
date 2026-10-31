package com.fifo.voicepipeline.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Modelos de presentación de Fifo.
 *
 * Los datos viven en [com.fifo.voicepipeline.data.model] (puros, sin Android ni
 * Compose). Aquí solo se conserva la compatibilidad de los imports históricos
 * (`com.fifo.voicepipeline.ui.model.*`) y se añade lo que sí es de UI: iconos.
 *
 * Regla (ADR-0049): no declares `data class` en este paquete. Si un modelo
 * necesita un `ImageVector`, añade una extensión aquí.
 */

typealias PastConversationItem = com.fifo.voicepipeline.data.model.PastConversationItem
typealias FifoMemoryItem = com.fifo.voicepipeline.data.model.FifoMemoryItem
typealias UserSocialPost = com.fifo.voicepipeline.data.model.UserSocialPost
typealias FifoTasteStory = com.fifo.voicepipeline.data.model.FifoTasteStory
typealias UserProfileData = com.fifo.voicepipeline.data.model.UserProfileData
typealias FifoReminderItem = com.fifo.voicepipeline.data.model.FifoReminderItem
typealias ConversationFragment = com.fifo.voicepipeline.data.model.ConversationFragment
typealias DeepContextResult = com.fifo.voicepipeline.data.model.DeepContextResult
typealias ContactIdentityResult = com.fifo.voicepipeline.data.model.ContactIdentityResult
typealias NavigationContextResult = com.fifo.voicepipeline.data.model.NavigationContextResult
typealias BackendConsolidationResult = com.fifo.voicepipeline.data.model.BackendConsolidationResult
typealias FriendRecommendation = com.fifo.voicepipeline.data.model.FriendRecommendation
typealias FifoDeviceLocation = com.fifo.voicepipeline.data.model.FifoDeviceLocation
typealias CurrentLocationInfo = com.fifo.voicepipeline.data.model.CurrentLocationInfo
typealias IncomingCallInfo = com.fifo.voicepipeline.data.model.IncomingCallInfo
typealias PhoneHardwareStatus = com.fifo.voicepipeline.data.model.PhoneHardwareStatus
typealias RelationalContact = com.fifo.voicepipeline.data.model.RelationalContact
typealias ContactSource = com.fifo.voicepipeline.data.model.ContactSource
typealias ContactAppearance = com.fifo.voicepipeline.data.model.ContactAppearance
typealias PhoneHintStatus = com.fifo.voicepipeline.data.model.PhoneHintStatus
typealias ContactPhoneHint = com.fifo.voicepipeline.data.model.ContactPhoneHint
typealias DailyRoutine = com.fifo.voicepipeline.data.model.DailyRoutine
typealias RoutineAnomaly = com.fifo.voicepipeline.data.model.RoutineAnomaly
typealias CommunityActivityItem = com.fifo.voicepipeline.data.model.CommunityActivityItem

/**
 * Icono de la conversación según su etiqueta temática.
 */
val PastConversationItem.icon: ImageVector
    get() = when (iconName) {
        "restaurant" -> Icons.Outlined.Restaurant
        "music" -> Icons.Outlined.MusicNote
        "heart" -> Icons.Outlined.FavoriteBorder
        "air" -> Icons.Outlined.Air
        else -> Icons.Outlined.ChatBubbleOutline
    }

/**
 * Icono de la actividad comunitaria según su categoría.
 */
val CommunityActivityItem.icon: ImageVector
    get() = when (iconCategory.lowercase()) {
        "music", "musica" -> Icons.Outlined.MusicNote
        "park", "parque", "naturaleza" -> Icons.Outlined.Park
        "cooking", "cocina" -> Icons.Outlined.Restaurant
        "walk", "paseo", "caminata" -> Icons.Outlined.DirectionsWalk
        "book", "lectura", "libro" -> Icons.Outlined.MenuBook
        else -> Icons.Outlined.Event
    }
