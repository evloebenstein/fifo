package com.fifo.voicepipeline.network

import com.fifo.voicepipeline.data.memory.MentionedPersonExtraction
import com.fifo.voicepipeline.ui.model.*

/**
 * Fachada HTTP del backend propio (FastAPI/MySQL, `fifo-api` en el puerto 8090).
 *
 * Conserva la API pública que consumen la UI, los skills y `FifoDataRepository`,
 * pero delega cada dominio en un adaptador:
 *
 * - [BackendSyncAdapter]: salud y bundle completo;
 * - [BackendAccountAdapter]: autenticación, usuarios y perfil;
 * - [BackendContentAdapter]: gustos, posts, recuerdos, recordatorios y rutinas;
 * - [BackendMemoryAdapter]: consolidación profunda y GraphRAG;
 * - [BackendContactAdapter]: contactos relacionales y telefonía;
 * - [BackendDeviceAdapter]: ubicación del dispositivo y navegación;
 * - [BackendCommunityAdapter]: amigos y actividades comunitarias.
 *
 * Soporta conexión directa por USB mediante `adb reverse tcp:8090 tcp:8090`
 * (http://127.0.0.1:8090) o IP LAN configurable.
 */
object FifoBackendClient {

    private val http = BackendHttp()

    private val syncAdapter = BackendSyncAdapter(http)
    private val accountAdapter = BackendAccountAdapter(http)
    private val contentAdapter = BackendContentAdapter(http)
    private val memoryAdapter = BackendMemoryAdapter(http)
    private val contactAdapter = BackendContactAdapter(http)
    private val deviceAdapter = BackendDeviceAdapter(http)
    private val communityAdapter = BackendCommunityAdapter(http)

    var baseUrl: String
        get() = http.baseUrl
        set(value) {
            http.baseUrl = value
        }

    data class SyncedUserBundle(
        val userProfile: UserProfileData,
        val tastes: List<String>,
        val tasteStories: List<FifoTasteStory>,
        val socialPosts: List<UserSocialPost>,
        val communityFeedPosts: List<UserSocialPost>,
        val memories: List<FifoMemoryItem>,
        val reminders: List<FifoReminderItem>,
        val pastConversations: List<PastConversationItem>,
        val conversationFragments: List<ConversationFragment>,
        val deviceLocation: FifoDeviceLocation?,
        val relationalContacts: List<RelationalContact>? = null,
        val dailyRoutines: List<DailyRoutine>? = null
    )

    data class AuthResult(
        val status: String,
        val action: String?,
        val userId: String,
        val message: String
    )

    data class UserProfileSummary(
        val id: String,
        val fullName: String,
        val email: String,
        val age: Int,
        val city: String,
        val quote: String?,
        val avatarBgHex: Long = 4293514751L,
        val bioAi: String = "",
        val tastes: List<String> = emptyList()
    )

    // ── Salud y sincronización ────────────────────────────────

    suspend fun checkHealth(): Boolean = syncAdapter.checkHealth()

    suspend fun fetchUserBundle(userId: String): SyncedUserBundle? =
        syncAdapter.fetchUserBundle(userId)

    // ── Cuenta y perfil ───────────────────────────────────────

    suspend fun patchDemographics(
        userId: String,
        fullName: String?,
        birthDate: String?,
        birthYear: Int?,
        gender: String?,
        city: String?
    ) = accountAdapter.patchDemographics(userId, fullName, birthDate, birthYear, gender, city)

    suspend fun putBio(userId: String, newBio: String) =
        accountAdapter.putBio(userId, newBio)

    suspend fun putInterestTags(userId: String, tags: List<String>) =
        accountAdapter.putInterestTags(userId, tags)

    suspend fun authenticateGoogleUser(
        googleId: String,
        email: String,
        fullName: String,
        avatarUrl: String? = null,
        estimatedAge: Int? = null,
        city: String? = null
    ) = accountAdapter.authenticateGoogleUser(googleId, email, fullName, avatarUrl, estimatedAge, city)

    suspend fun createUserProfile(
        fullName: String,
        email: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        estimatedAge: Int? = null,
        genderIdentity: String? = "Mujer",
        city: String? = "Santiago, Chile",
        initialTastes: List<String> = emptyList()
    ) = accountAdapter.createUserProfile(
        fullName = fullName,
        email = email,
        birthDate = birthDate,
        birthYear = birthYear,
        estimatedAge = estimatedAge,
        genderIdentity = genderIdentity,
        city = city,
        initialTastes = initialTastes
    )

    suspend fun loginUser(identifier: String) = accountAdapter.loginUser(identifier)

    suspend fun fetchAllUsers() = accountAdapter.fetchAllUsers()

    // ── Contenido y memoria ───────────────────────────────────

    suspend fun postTaste(userId: String, action: String, tasteName: String) =
        contentAdapter.postTaste(userId, action, tasteName)

    suspend fun postTasteStory(userId: String, story: FifoTasteStory) =
        contentAdapter.postTasteStory(userId, story)

    suspend fun postSocialPost(userId: String, content: String, category: String) =
        contentAdapter.postSocialPost(userId, content, category)

    suspend fun postReminder(userId: String, title: String, timeStr: String, category: String) =
        contentAdapter.postReminder(userId, title, timeStr, category)

    suspend fun postMemory(userId: String, emoji: String, title: String, detail: String) =
        contentAdapter.postMemory(userId, emoji, title, detail)

    suspend fun postDailyRoutine(userId: String, routine: DailyRoutine) =
        contentAdapter.postDailyRoutine(userId, routine)

    suspend fun patchReminderComplete(reminderId: String) =
        contentAdapter.patchReminderComplete(reminderId)

    suspend fun deleteReminder(reminderId: String) =
        contentAdapter.deleteReminder(reminderId)

    suspend fun postConversation(
        userId: String,
        title: String,
        summary: String,
        primaryTag: String = "Conversación",
        durationSeconds: Int = 180,
        detectedMood: String = "tranquilo",
        keyTopics: List<String> = emptyList(),
        namedEntities: List<String> = emptyList(),
        fullTranscript: String? = null
    ) = contentAdapter.postConversation(
        userId = userId,
        title = title,
        summary = summary,
        primaryTag = primaryTag,
        durationSeconds = durationSeconds,
        detectedMood = detectedMood,
        keyTopics = keyTopics,
        namedEntities = namedEntities,
        fullTranscript = fullTranscript
    )

    suspend fun analyzeAndConsolidateSession(
        userId: String,
        turns: List<Pair<String, String>>,
        durationSeconds: Int = 180,
        primaryTag: String = "Conversación",
        mentionedPersons: List<MentionedPersonExtraction> = emptyList(),
        groqApiKey: String? = null
    ) = memoryAdapter.analyzeAndConsolidateSession(
        userId = userId,
        turns = turns,
        durationSeconds = durationSeconds,
        primaryTag = primaryTag,
        mentionedPersons = mentionedPersons,
        groqApiKey = groqApiKey
    )

    suspend fun queryDeepContextFromServer(userId: String, query: String) =
        memoryAdapter.queryDeepContextFromServer(userId, query)

    // ── Contactos y telefonía ─────────────────────────────────

    suspend fun postRelationalContact(userId: String, contact: RelationalContact) =
        contactAdapter.postRelationalContact(userId, contact)

    suspend fun syncRelationalContactsBatch(userId: String, contacts: List<RelationalContact>): Boolean =
        contactAdapter.syncRelationalContactsBatch(userId, contacts)

    suspend fun identifyContact(
        userId: String,
        callerName: String?,
        phoneNumber: String?
    ) = contactAdapter.identifyContact(userId, callerName, phoneNumber)

    suspend fun logCallEvent(
        userId: String,
        action: String,
        callerName: String,
        phoneNumber: String? = null,
        durationSeconds: Int = 0,
        notes: String? = null
    ) = contactAdapter.logCallEvent(userId, action, callerName, phoneNumber, durationSeconds, notes)

    // ── Dispositivo y navegación ──────────────────────────────

    suspend fun patchDeviceLocation(
        userId: String,
        isConnected: Boolean? = null,
        lastConnectedTime: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        roomHint: String? = null,
        rssi: Int? = null,
        isBeeping: Boolean? = null
    ) = deviceAdapter.patchDeviceLocation(
        userId = userId,
        isConnected = isConnected,
        lastConnectedTime = lastConnectedTime,
        latitude = latitude,
        longitude = longitude,
        address = address,
        roomHint = roomHint,
        rssi = rssi,
        isBeeping = isBeeping
    )

    suspend fun fetchNavigationContextualIntent(
        userId: String,
        destination: String,
        lat: Double? = null,
        lon: Double? = null
    ) = deviceAdapter.fetchNavigationContextualIntent(userId, destination, lat, lon)

    // ── Comunidad ─────────────────────────────────────────────

    suspend fun fetchFriendRecommendations(userId: String) =
        communityAdapter.fetchFriendRecommendations(userId)

    suspend fun createCommunityActivity(item: CommunityActivityItem): Boolean =
        communityAdapter.createCommunityActivity(item)

    suspend fun toggleJoinActivity(activityId: String, userId: String, join: Boolean): Boolean =
        communityAdapter.toggleJoinActivity(activityId, userId, join)
}
