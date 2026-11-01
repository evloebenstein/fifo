package com.fifo.voicepipeline.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.fifo.voicepipeline.data.AppGraph
import com.fifo.voicepipeline.data.device.DeviceState
import com.fifo.voicepipeline.data.modules.CommunityModule
import com.fifo.voicepipeline.data.modules.EpisodicMemoryModule
import com.fifo.voicepipeline.data.modules.RelationalGraphModule
import com.fifo.voicepipeline.data.modules.UserIdentityModule
import com.fifo.voicepipeline.data.session.ContinuousListeningState
import com.fifo.voicepipeline.data.session.UserSession
import com.fifo.voicepipeline.telephony.CallStateCoordinator

/**
 * Crea un *state holder* de pantalla a partir del [AppGraph] (ADR-0049).
 *
 * Los dueños del estado son los módulos del grafo; el holder es una proyección
 * de solo lectura más los comandos que la pantalla necesita. Por eso `remember`
 * alcanza: al recrearse la actividad no se pierde nada, porque los `StateFlow`
 * viven en los módulos y no en el holder.
 */
@Composable
fun <T> fifoStateHolder(create: (AppGraph) -> T): T =
    remember { create(AppGraph.requireInstance()) }

/**
 * Proyección de la sesión: perfil activo, login y cambio de perfil.
 * La usan la pantalla principal, el login y el diálogo de Google.
 */
class SessionStateHolder(
    private val session: UserSession,
    identity: UserIdentityModule
) {
    val userProfile = identity.userProfile

    fun findUserByEmailOrId(email: String, googleId: String): String? =
        session.findUserByEmailOrId(email, googleId)

    fun switchUserProfile(newUserId: String) = session.switchUserProfile(newUserId)

    suspend fun loginWithGoogle(
        googleId: String,
        email: String,
        fullName: String,
        avatarUrl: String? = null,
        estimatedAge: Int? = null,
        city: String? = null
    ): Boolean = session.loginWithGoogle(
        googleId = googleId,
        email = email,
        fullName = fullName,
        avatarUrl = avatarUrl,
        estimatedAge = estimatedAge,
        city = city
    )

    suspend fun loginWithIdentifier(identifier: String): Boolean =
        session.loginWithIdentifier(identifier)

    suspend fun createProfileAndStart(
        fullName: String,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        estimatedAge: Int? = null,
        gender: String = "Mujer",
        city: String = "Santiago, Chile",
        initialTastes: List<String> = emptyList(),
        email: String? = null
    ): String? = session.createProfileAndStart(
        fullName = fullName,
        username = username,
        birthDate = birthDate,
        birthYear = birthYear,
        estimatedAge = estimatedAge,
        gender = gender,
        city = city,
        initialTastes = initialTastes,
        email = email
    )
}

/**
 * Proyección de la conversación para la UI: memoria visible, llamada entrante y
 * escucha continua. `HomeScreen` y `VoiceInteractionSheet` la comparten para no
 * duplicar comandos de telefonía.
 */
class VoiceUiStateHolder(
    private val memory: EpisodicMemoryModule,
    private val identity: UserIdentityModule,
    private val telephony: CallStateCoordinator,
    private val listening: ContinuousListeningState
) {
    val memories = memory.memories
    val reminders = memory.reminders
    val conversations = memory.conversations
    val incomingCall = telephony.incomingCall
    val isContinuousListening = listening.active

    fun answerCall(): Boolean = telephony.answerCurrentCall()

    fun hangupCall(): Boolean = telephony.hangupCurrentCall()

    fun stopContinuousListening() = listening.request(false)

    fun addMemory(emoji: String, title: String, detail: String) {
        memory.addMemory(identity.userProfile.value.id, emoji, title, detail)
    }

    fun removeMemory(id: String) {
        memory.removeMemory(identity.userProfile.value.id, id)
    }

    fun removeConversation(id: String) {
        memory.removeConversation(identity.userProfile.value.id, id)
    }
}

/** Proyección de comunidad y grafo relacional para `ActivitiesScreen`. */
class ActivitiesStateHolder(
    private val community: CommunityModule,
    private val relationalGraph: RelationalGraphModule,
    private val memory: EpisodicMemoryModule,
    private val identity: UserIdentityModule
) {
    val activities = community.communityActivities
    val userProfile = identity.userProfile
    val tastes = memory.tastes
    val contacts = relationalGraph.contacts

    fun toggleJoin(activityId: String) =
        community.toggleJoinActivity(activityId, identity.userProfile.value.id)

    fun suggestActivity(ideaPrompt: String, userTastes: List<String>) =
        com.fifo.voicepipeline.data.modules.ActivitySuggestionPolicy.draft(
            ideaPrompt = ideaPrompt,
            userTastes = userTastes,
            organizerId = identity.userProfile.value.id,
            organizerName = identity.userProfile.value.fullName
        )

    fun createActivity(
        title: String,
        description: String,
        dateLabel: String,
        locationName: String,
        spotsMax: Int = 10,
        iconCategory: String = "nature",
        targetTastes: List<String> = emptyList(),
        organizerType: String = "user",
        organizerName: String? = null
    ): String = community.createActivity(
        title = title,
        description = description,
        dateLabel = dateLabel,
        locationName = locationName,
        organizerId = identity.userProfile.value.id,
        spotsMax = spotsMax,
        iconCategory = iconCategory,
        targetTastes = targetTastes,
        organizerType = organizerType,
        organizerName = organizerName
    )

    fun appearancesByContact() = relationalGraph.appearancesByContact()

    fun pendingPhoneHintsByContact() = relationalGraph.pendingPhoneHintsByContact()

    fun confirmPhoneHint(hintId: String): Boolean = relationalGraph.confirmPhoneHint(hintId)

    fun discardPhoneHint(hintId: String) = relationalGraph.discardPhoneHint(hintId)
}

/** Proyección del perfil para `ProfileScreen`. */
class ProfileStateHolder(
    private val session: UserSession,
    identity: UserIdentityModule,
    memory: EpisodicMemoryModule,
    community: CommunityModule
) {
    val userProfile = identity.userProfile
    val tastes = memory.tastes
    val tasteStories = memory.tasteStories
    val socialPosts = community.socialPosts

    fun updateDemographics(
        fullName: String? = null,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        gender: String? = null,
        city: String? = null
    ) = session.updateDemographics(fullName, username, birthDate, birthYear, gender, city)
}

/** Proyección de la red de amigos para `FriendsScreen`. */
class FriendsStateHolder(
    identity: UserIdentityModule,
    community: CommunityModule,
    private val relationalGraph: RelationalGraphModule
) {
    val userProfile = identity.userProfile
    val socialPosts = community.socialPosts

    fun syncFifoFriends(friends: List<com.fifo.voicepipeline.data.model.FriendRecommendation>): Int =
        relationalGraph.ingestFifoFriends(friends)
}

/** Proyección del robot Fifo para el buscador de dispositivo. */
class DeviceUiStateHolder(
    private val device: DeviceState
) {
    val location = device.location

    fun triggerBeep(beeping: Boolean) = device.triggerBeep(beeping)
}
