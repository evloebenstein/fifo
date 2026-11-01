package com.fifo.voicepipeline.data

import android.content.Context
import com.fifo.voicepipeline.data.model.*
import com.fifo.voicepipeline.data.modules.ActivitySuggestionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Accessor histórico de los datos de usuario.
 *
 * **Deprecado (ADR-0049).** Ya no posee estado propio: delega en [AppGraph],
 * que es el composition root instanciable y el dueño de cada módulo. Existe
 * solo para la migración strangler: data → red → pipeline → skills → UI. Al
 * llegar a cero llamadas se borra.
 *
 * Reglas durante la migración:
 * - no agregues lógica nueva aquí;
 * - no crees un segundo almacenamiento ni un segundo VAD/TTS/telefonía;
 * - la telefonía sigue fuera de `VoiceSession` y se migra en su propia tarjeta.
 */
@Deprecated(
    message = "Usa AppGraph y sus módulos. Este objeto solo delega y se borrará al terminar la migración.",
    replaceWith = ReplaceWith("AppGraph.requireInstance()")
)
object FifoDataRepository {

    private val graph get() = AppGraph.requireInstance()

    /**
     * Instala el grafo si aún no existe. Idempotente; hoy la llama la actividad
     * y el servicio, y pronto solo `FifoApplication`.
     */
    fun initialize(context: Context) {
        AppGraph.install(context)
    }

    // ── 1. Perfil del Usuario ──────────────────────────────────
    val userProfile: StateFlow<UserProfileData> get() = graph.identity.userProfile

    // ── 2. Gustos e Intereses ──────────────────────────────────
    val tastes: StateFlow<List<String>> get() = graph.memory.tastes

    // ── 3. Historias y Artículos de Blog ("Gustos en detalle") ──
    val tasteStories: StateFlow<List<FifoTasteStory>> get() = graph.memory.tasteStories

    // ── 4. Publicaciones Sociales ("Fifo Amigos") ────
    val socialPosts: StateFlow<List<UserSocialPost>> get() = graph.community.socialPosts

    // ── 5. Recuerdos de Conversaciones ─────────────────────────
    val memories: StateFlow<List<FifoMemoryItem>> get() = graph.memory.memories

    // ── 6. Recordatorios y Alarmas del Sistema ──────────────────
    val reminders: StateFlow<List<FifoReminderItem>> get() = graph.memory.reminders

    // ── 7. Conversaciones Pasadas ───────────────────────────────
    val conversations: StateFlow<List<PastConversationItem>> get() = graph.memory.conversations

    // ── 8. Grafo Relacional de Contactos Cercanos ───────────────
    val relationalContacts: StateFlow<List<RelationalContact>> get() = graph.relationalGraph.contacts

    // ── 9. Rutinas Diarias y Patrones de Actividad ───────────────
    val dailyRoutines: StateFlow<List<DailyRoutine>> get() = graph.memory.dailyRoutines

    // ── 10. Actividades y Encuentros Comunitarios ────────────────
    val communityActivities: StateFlow<List<CommunityActivityItem>> get() = graph.community.communityActivities

    // ── 11. Fragmentos compactos de conversaciones pasadas ───────
    val conversationFragments: StateFlow<List<ConversationFragment>> get() = graph.memory.conversationFragments

    // ── Sesión ──────────────────────────────────────────────────

    fun getActiveUserId(context: Context): String = graph.session.activeUserId()

    fun switchUserProfile(context: Context, newUserId: String) {
        graph.session.switchUserProfile(newUserId)
    }

    suspend fun loginWithGoogle(
        context: Context,
        googleId: String,
        email: String,
        fullName: String,
        avatarUrl: String? = null,
        estimatedAge: Int? = null,
        city: String? = null
    ): Boolean = graph.session.loginWithGoogle(
        googleId = googleId,
        email = email,
        fullName = fullName,
        avatarUrl = avatarUrl,
        estimatedAge = estimatedAge,
        city = city
    )

    fun findUserByEmailOrId(email: String, googleId: String): String? =
        graph.session.findUserByEmailOrId(email, googleId)

    suspend fun createProfileAndStart(
        context: Context,
        fullName: String,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        estimatedAge: Int? = null,
        gender: String = "Mujer",
        city: String = "Santiago, Chile",
        initialTastes: List<String> = emptyList(),
        email: String? = null
    ): String? = graph.session.createProfileAndStart(
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

    suspend fun loginWithIdentifier(context: Context, identifier: String): Boolean =
        graph.session.loginWithIdentifier(identifier)

    fun syncFromBackend(userId: String = userProfile.value.id) {
        graph.session.syncFromBackend(userId)
    }

    // ══════════════════════════════════════════════════════════
    //  MUTACIONES (delegadas a los módulos)
    // ══════════════════════════════════════════════════════════

    fun updateDemographics(
        fullName: String? = null,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        gender: String? = null,
        city: String? = null
    ) = graph.session.updateDemographics(fullName, username, birthDate, birthYear, gender, city)

    fun updateBio(newBio: String) = graph.session.updateBio(newBio)

    fun updateInterestTags(tags: List<String>) = graph.session.updateInterestTags(tags)

    fun addTaste(tasteName: String): Boolean {
        val clean = tasteName.trim()
        if (clean.isBlank()) return false
        val added = graph.memory.addTaste(userProfile.value.id, clean)
        if (added) {
            graph.scope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.postTaste(userProfile.value.id, "add", clean)
            }
        }
        return added
    }

    fun removeTaste(tasteName: String): Boolean {
        val clean = tasteName.trim()
        val removed = graph.memory.removeTaste(userProfile.value.id, clean)
        if (removed) {
            graph.scope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.postTaste(userProfile.value.id, "remove", clean)
            }
        }
        return removed
    }

    fun addTasteStory(story: FifoTasteStory) {
        graph.memory.addTasteStory(userProfile.value.id, story)
        graph.scope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postTasteStory(userProfile.value.id, story)
        }
    }

    fun addSocialPost(content: String, category: String = "Bienestar"): UserSocialPost {
        val user = userProfile.value
        val author = user.fullName.split(" ").firstOrNull() ?: user.fullName
        val newPost = graph.community.addSocialPost(
            content = content,
            category = category,
            author = author,
            username = user.username,
            age = user.estimatedAge,
            userId = user.id
        )
        graph.scope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postSocialPost(user.id, content.trim(), category)
        }
        return newPost
    }

    fun addMemory(emoji: String, title: String, detail: String) {
        graph.memory.addMemory(userProfile.value.id, emoji, title, detail)
    }

    fun addReminder(title: String, timeStr: String, category: String = "medication"): FifoReminderItem {
        val item = graph.memory.addReminder(userProfile.value.id, title, timeStr, category)
        graph.scope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postReminder(
                userProfile.value.id, item.title, item.timeStr, item.category
            )
        }
        return item
    }

    fun completeReminder(id: String) {
        graph.memory.completeReminder(userProfile.value.id, id)
        graph.scope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.patchReminderComplete(id)
        }
    }

    fun removeReminder(id: String) {
        graph.memory.removeReminder(userProfile.value.id, id)
        graph.scope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.deleteReminder(id)
        }
    }

    fun addMemoryDirect(item: FifoMemoryItem) {
        val current = memories.value
        if (current.any { it.title.equals(item.title, ignoreCase = true) }) return
        graph.memory.setDirectMemories(listOf(item) + current)
        graph.database.memories.addMemory(userProfile.value.id, item)
    }

    fun addTasteStoryDirect(story: FifoTasteStory) {
        val current = tasteStories.value
        if (current.any { it.title.equals(story.title, ignoreCase = true) }) return
        graph.memory.setDirectTasteStories(listOf(story) + current)
        graph.database.tastes.addTasteStory(userProfile.value.id, story)
    }

    fun addReminderDirect(reminder: FifoReminderItem) {
        val current = reminders.value
        if (current.any { it.title.equals(reminder.title, ignoreCase = true) && it.timeStr == reminder.timeStr }) return
        graph.memory.setDirectReminders(listOf(reminder) + current)
        graph.database.reminders.addReminder(userProfile.value.id, reminder)
    }

    fun addConversation(item: PastConversationItem) {
        graph.memory.addConversation(userProfile.value.id, item)
        graph.scope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postConversation(
                userId = userProfile.value.id,
                title = item.title,
                summary = item.summary,
                primaryTag = item.tag,
                durationSeconds = 180
            )
        }
    }

    fun addPastConversation(item: PastConversationItem) = addConversation(item)

    fun removeConversation(id: String) = graph.memory.removeConversation(userProfile.value.id, id)

    fun removeMemory(id: String) = graph.memory.removeMemory(userProfile.value.id, id)

    fun removeSocialPost(id: String) = graph.community.removeSocialPost(id)

    fun removeTasteStory(id: String) = graph.memory.removeTasteStory(userProfile.value.id, id)

    // ══════════════════════════════════════════════════════════════
    //  GRAFO RELACIONAL
    // ══════════════════════════════════════════════════════════════

    fun addOrUpdateRelationalContact(contact: RelationalContact) =
        graph.relationalGraph.addOrUpdate(contact)

    fun findContactByRoleOrName(query: String): RelationalContact? =
        graph.relationalGraph.findContactByRoleOrName(query)

    fun getContactAppearances(contactId: String): List<ContactAppearance> =
        graph.relationalGraph.appearances(contactId)

    fun getContactAppearancesByContact(): Map<String, List<ContactAppearance>> =
        graph.relationalGraph.appearancesByContact()

    fun getPendingPhoneHintsByContact(): Map<String, List<ContactPhoneHint>> =
        graph.relationalGraph.pendingPhoneHintsByContact()

    fun confirmPhoneHint(hintId: String): Boolean = graph.relationalGraph.confirmPhoneHint(hintId)

    fun discardPhoneHint(hintId: String) = graph.relationalGraph.discardPhoneHint(hintId)

    fun syncDeviceContactsToRelationalGraph(context: Context): Int =
        graph.contactsSync.sync()

    fun syncFifoFriendsToRelationalGraph(friends: List<FriendRecommendation>): Int =
        graph.relationalGraph.ingestFifoFriends(friends)

    fun updateContactsFromSessionMentions(
        mentions: List<com.fifo.voicepipeline.data.memory.MentionedPersonExtraction>,
        conversationId: String? = null
    ) = graph.relationalGraph.ingestSessionMentions(mentions, conversationId)

    fun addOrUpdateDailyRoutine(routine: DailyRoutine) {
        graph.memory.addOrUpdateDailyRoutine(userProfile.value.id, routine)
        graph.scope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postDailyRoutine(userProfile.value.id, routine)
        }
    }

    fun getRoutinesForCurrentTime(timePeriod: String? = null): List<DailyRoutine> {
        val currentList = dailyRoutines.value
        if (timePeriod.isNullOrBlank()) return currentList
        return currentList.filter { it.timeAnchor.equals(timePeriod, ignoreCase = true) }
    }

    // ══════════════════════════════════════════════════════════════
    //  MEMORIA DE DOBLE CAPA
    // ══════════════════════════════════════════════════════════════

    fun addConversationFragment(fragment: ConversationFragment) =
        graph.memory.addConversationFragment(userProfile.value.id, fragment)

    fun buildCompactContextWindow(maxFragments: Int = 1): String =
        graph.memory.buildCompactContextWindow(userProfile.value, maxFragments)

    /**
     * Capa 2: backend primero, índice local como respaldo. Ahora es `suspend`:
     * ya no bloquea el turno con `runBlocking`.
     */
    suspend fun searchDeepContext(query: String): DeepContextResult =
        graph.conversationContext.searchDeep(query)

    // ══════════════════════════════════════════════════════════════
    //  9. Rastreo de Ubicación del Dispositivo Fifo (ESP32)
    // ══════════════════════════════════════════════════════════════

    val deviceLocation: StateFlow<FifoDeviceLocation> get() = graph.device.location

    fun updateDeviceConnectionStatus(
        connected: Boolean,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        roomHint: String? = null,
        rssi: Int? = null
    ) = graph.device.updateConnectionStatus(connected, latitude, longitude, address, roomHint, rssi)

    fun triggerDeviceBeep(beeping: Boolean) = graph.device.triggerBeep(beeping)

    // ══════════════════════════════════════════════════════════════
    //  10. Detección y Gestión de Llamadas Entrantes (Tipo Alexa)
    //  El estado vive en AppGraph.telephony; aquí solo se delega.
    //  La acción sigue siendo de FifoCallManager / AndroidTelephonyPort.
    // ══════════════════════════════════════════════════════════════

    private val callState get() = graph.telephony

    val incomingCall: StateFlow<IncomingCallInfo?> get() = callState.incomingCall

    var onAnswerCallAction: (() -> Boolean)?
        get() = callState.onAnswerCallAction
        set(value) {
            callState.onAnswerCallAction = value
        }

    var onHangupCallAction: (() -> Boolean)?
        get() = callState.onHangupCallAction
        set(value) {
            callState.onHangupCallAction = value
        }

    val lastActiveCall: IncomingCallInfo?
        get() = callState.lastActiveCall

    fun setIncomingCall(call: IncomingCallInfo?) = callState.setIncomingCall(call)

    fun answerCurrentCall(): Boolean = callState.answerCurrentCall()

    fun hangupCurrentCall(): Boolean = callState.hangupCurrentCall()

    // ══════════════════════════════════════════════════════════════
    //  11. Modo de Escucha Continua
    //  Dueño en el camino nuevo: TurnOrchestrator. La UI la lee por
    //  graph.listening / VoiceUiStateHolder; aquí solo queda el puente
    //  que usa el router hasta su propia tarjeta.
    // ══════════════════════════════════════════════════════════════

    /**
     * Proyección de escucha cuando no hay composition root. Solo ocurre en
     * pruebas JVM que ejercitan el router sin Android; en producción el grafo
     * siempre existe y esta instancia nunca se lee (ADR-0049).
     */
    private val detachedListening = MutableStateFlow(false)

    val isContinuousListening: StateFlow<Boolean>
        get() = AppGraph.instanceOrNull()?.listening?.active ?: detachedListening

    /**
     * Puente temporal hacia el dueño en el camino nuevo. Lo instala
     * `VoicePipelineManager` solo cuando `VoiceSession` está activo.
     */
    var continuousListeningHandler: ((Boolean) -> Unit)?
        get() = AppGraph.instanceOrNull()?.listening?.handler
        set(value) {
            AppGraph.instanceOrNull()?.listening?.handler = value
        }

    fun setContinuousListening(active: Boolean) {
        val listening = AppGraph.instanceOrNull()?.listening
        if (listening == null) {
            detachedListening.value = active
        } else {
            listening.request(active)
        }
    }

    /** El dueño (TurnOrchestrator) publica aquí su estado para la UI. */
    fun mirrorContinuousListening(active: Boolean) {
        val listening = AppGraph.instanceOrNull()?.listening
        if (listening == null) {
            detachedListening.value = active
        } else {
            listening.mirror(active)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  12. Estado de Hardware del Teléfono
    // ══════════════════════════════════════════════════════════════

    val phoneHardware: StateFlow<PhoneHardwareStatus> get() = graph.device.hardware

    fun updateHardwareStatus(
        batteryPercent: Int? = null,
        isCharging: Boolean? = null,
        isFlashlightOn: Boolean? = null,
        volumePercent: Int? = null
    ) = graph.device.updateHardware(batteryPercent, isCharging, isFlashlightOn, volumePercent)

    // ══════════════════════════════════════════════════════════════
    //  13. Gestión Dinámica de Actividades y Encuentros Comunitarios
    // ══════════════════════════════════════════════════════════════

    fun toggleJoinActivity(activityId: String) {
        graph.community.toggleJoinActivity(activityId, userProfile.value.id)
    }

    fun createCommunityActivity(
        title: String,
        description: String,
        dateLabel: String,
        locationName: String,
        spotsMax: Int = 10,
        iconCategory: String = "nature",
        targetTastes: List<String> = emptyList(),
        organizerType: String = "user",
        organizerName: String? = null
    ): String {
        val user = userProfile.value
        return graph.community.createActivity(
            title = title,
            description = description,
            dateLabel = dateLabel,
            locationName = locationName,
            organizerId = user.id,
            spotsMax = spotsMax,
            iconCategory = iconCategory,
            targetTastes = targetTastes,
            organizerType = organizerType,
            organizerName = organizerName ?: user.fullName.ifBlank { "Vecino de la comunidad" }
        )
    }

    fun deleteCommunityActivity(activityId: String) = graph.community.deleteCommunityActivity(activityId)

    fun suggestActivityWithFifo(ideaPrompt: String, userTastes: List<String>): CommunityActivityItem =
        ActivitySuggestionPolicy.draft(
            ideaPrompt = ideaPrompt,
            userTastes = userTastes,
            organizerId = userProfile.value.id,
            organizerName = userProfile.value.fullName
        )
}
