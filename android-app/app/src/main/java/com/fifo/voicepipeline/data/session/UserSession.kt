package com.fifo.voicepipeline.data.session

import android.content.Context
import com.fifo.voicepipeline.data.contacts.DeviceContactsSync
import com.fifo.voicepipeline.data.device.DeviceState
import com.fifo.voicepipeline.data.local.FifoDatabaseHelper
import com.fifo.voicepipeline.data.model.UserProfileData
import com.fifo.voicepipeline.data.modules.CommunityModule
import com.fifo.voicepipeline.data.modules.EpisodicMemoryModule
import com.fifo.voicepipeline.data.modules.UserIdentityModule
import com.fifo.voicepipeline.network.FifoBackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dueño de la sesión del usuario (ADR-0049): quién está activo, cómo se
 * autentica, cómo se cambia de perfil y cómo se hidrata el estado local.
 *
 * Extraído de `FifoDataRepository` para que el grafo de aplicación componga la
 * sesión una sola vez. El repositorio histórico queda como accessor en
 * deprecación.
 *
 * No habla con la UI: publica cambios en los módulos y devuelve resultados.
 */
class UserSession(
    private val context: Context,
    private val identity: UserIdentityModule,
    private val memory: EpisodicMemoryModule,
    private val community: CommunityModule,
    private val database: FifoDatabaseHelper,
    private val device: DeviceState,
    private val contactsSync: DeviceContactsSync,
    private val scope: CoroutineScope
) {

    private val prefs
        get() = context.applicationContext.getSharedPreferences("fifo_prefs", Context.MODE_PRIVATE)

    /** Restaura la última sesión y deja los módulos hidratados. */
    fun restore() {
        val activeUserId = prefs.getString(KEY_ACTIVE_USER, null) ?: return
        try {
            loadLocalState(activeUserId)
            if (hasContactsPermission()) {
                scope.launch(Dispatchers.IO) { contactsSync.sync() }
            }
            syncFromBackend(activeUserId)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error restaurando la sesión: ${e.message}")
        }
    }

    fun activeUserId(): String =
        prefs.getString(KEY_ACTIVE_USER, null) ?: identity.userProfile.value.id

    fun findUserByEmailOrId(email: String, googleId: String): String? =
        identity.findUserByEmailOrId(email, googleId)

    /**
     * Cambia el perfil activo, recarga la foto local y sincroniza en segundo plano.
     */
    fun switchUserProfile(newUserId: String) {
        prefs.edit().putString(KEY_ACTIVE_USER, newUserId).apply()
        loadLocalState(newUserId)
        syncFromBackend(newUserId)
    }

    /** Autenticación / registro transparente con cuenta de Google. */
    suspend fun loginWithGoogle(
        googleId: String,
        email: String,
        fullName: String,
        avatarUrl: String? = null,
        estimatedAge: Int? = null,
        city: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = FifoBackendClient.authenticateGoogleUser(
                googleId = googleId,
                email = email,
                fullName = fullName,
                avatarUrl = avatarUrl,
                estimatedAge = estimatedAge,
                city = city
            )
            val effectiveUserId = result?.userId ?: ("usr_g_" + googleId.takeLast(6))
            prefs.edit()
                .putString(KEY_ACTIVE_USER, effectiveUserId)
                .putBoolean(KEY_HAS_COMPLETED_SETUP, true)
                .apply()

            if (result == null) {
                writeLocalGoogleIdentity(
                    userId = effectiveUserId,
                    email = email,
                    fullName = fullName,
                    estimatedAge = estimatedAge,
                    city = city
                )
            }

            switchUserProfile(effectiveUserId)
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error en loginWithGoogle: ${e.message}")
            false
        }
    }

    /** Crea un perfil en el onboarding y lo persiste en MySQL y SQLite. */
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
    ): String? = withContext(Dispatchers.IO) {
        try {
            val rawHandle = (username?.ifBlank { null } ?: email?.substringBefore('@') ?: fullName)
                .filter { it.isLetterOrDigit() || it == '_' }
                .lowercase()
                .removePrefix("@")
                .ifBlank { "usuario" }

            val result = FifoBackendClient.createUserProfile(
                fullName = fullName,
                email = email,
                birthDate = birthDate,
                birthYear = birthYear,
                estimatedAge = estimatedAge,
                genderIdentity = gender,
                city = city,
                initialTastes = initialTastes
            )
            val newUserId = result?.userId ?: run {
                val clean = fullName.filter { it.isLetterOrDigit() }.lowercase().take(8).ifBlank { "user" }
                "usr_${clean}_" + System.currentTimeMillis().toString().takeLast(4)
            }

            prefs.edit()
                .putString(KEY_ACTIVE_USER, newUserId)
                .putBoolean(KEY_HAS_COMPLETED_SETUP, true)
                .apply()

            if (result == null) {
                writeLocalProfile(
                    userId = newUserId,
                    fullName = fullName,
                    username = rawHandle,
                    birthDate = birthDate,
                    birthYear = birthYear,
                    estimatedAge = estimatedAge,
                    gender = gender,
                    city = city,
                    initialTastes = initialTastes,
                    email = email
                )
            }

            switchUserProfile(newUserId)
            newUserId
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error en createProfileAndStart: ${e.message}")
            null
        }
    }

    /** Inicia sesión por id, nombre, username o email. */
    suspend fun loginWithIdentifier(identifier: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = FifoBackendClient.loginUser(identifier)
            if (result != null) {
                prefs.edit()
                    .putString(KEY_ACTIVE_USER, result.userId)
                    .putBoolean(KEY_HAS_COMPLETED_SETUP, true)
                    .apply()
                switchUserProfile(result.userId)
                return@withContext true
            }
            val localUserId = database.userProfile.findUserIdByIdentifier(identifier)
            if (localUserId != null) {
                prefs.edit()
                    .putString(KEY_ACTIVE_USER, localUserId)
                    .putBoolean(KEY_HAS_COMPLETED_SETUP, true)
                    .apply()
                switchUserProfile(localUserId)
                return@withContext true
            }
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error en loginWithIdentifier: ${e.message}")
            false
        }
    }

    fun updateDemographics(
        fullName: String? = null,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        gender: String? = null,
        city: String? = null
    ) {
        val current = identity.userProfile.value
        val newYear = birthYear ?: current.birthYear
        val cleanUsername = username?.trim()?.removePrefix("@")
            ?.filter { it.isLetterOrDigit() || it == '_' }?.lowercase()

        identity.updateDemographics(
            fullName = fullName,
            username = cleanUsername,
            birthDate = birthDate,
            birthYear = newYear,
            gender = gender,
            city = city
        )

        scope.launch {
            FifoBackendClient.patchDemographics(
                userId = current.id,
                fullName = fullName,
                birthDate = birthDate,
                birthYear = newYear,
                gender = gender,
                city = city
            )
        }
    }

    fun updateBio(newBio: String) {
        if (newBio.isBlank()) return
        val clean = newBio.trim()
        identity.updateBio(clean)
        scope.launch { FifoBackendClient.putBio(identity.userProfile.value.id, clean) }
    }

    fun updateInterestTags(tags: List<String>) {
        if (tags.isEmpty()) return
        val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        identity.updateInterestTags(cleanTags)
        scope.launch { FifoBackendClient.putInterestTags(identity.userProfile.value.id, cleanTags) }
    }

    /** Hidrata SQLite y los módulos para un usuario; no toca la red. */
    fun loadLocalState(userId: String) {
        identity.loadUser(userId)
        memory.loadUserData(userId)
        database.communityActivities.seedDefaultCommunityActivitiesIfEmpty(userId)
        community.loadInitialData(userId)
        device.load()
    }

    /** Sincroniza el bundle completo del backend y avisa a cada módulo. */
    fun syncFromBackend(userId: String = identity.userProfile.value.id) {
        scope.launch {
            if (!FifoBackendClient.checkHealth()) {
                device.setBackendConnected(false)
                android.util.Log.d(TAG, "Backend no disponible; operando 100% en SQLite local")
                return@launch
            }

            device.setBackendConnected(true)
            val bundle = FifoBackendClient.fetchUserBundle(userId) ?: return@launch

            database.syncBundleWriter?.upsertBundle(userId, bundle)
            identity.setUserProfile(bundle.userProfile)
            memory.setDirectTastes(bundle.tastes)
            if (bundle.tasteStories.isNotEmpty()) memory.setDirectTasteStories(bundle.tasteStories)
            if (bundle.socialPosts.isNotEmpty()) community.setPosts(bundle.socialPosts)
            if (bundle.memories.isNotEmpty()) memory.setDirectMemories(bundle.memories)
            if (bundle.reminders.isNotEmpty()) memory.setDirectReminders(bundle.reminders)
            if (bundle.pastConversations.isNotEmpty()) memory.setDirectConversations(bundle.pastConversations)
            if (bundle.conversationFragments.isNotEmpty()) memory.setDirectFragments(bundle.conversationFragments)
            bundle.relationalContacts?.let { if (it.isNotEmpty()) memory.setDirectRelationalContacts(it) }
            bundle.dailyRoutines?.let { if (it.isNotEmpty()) memory.setDirectDailyRoutines(it) }
            bundle.deviceLocation?.let { device.importLocation(it) }
            android.util.Log.i(TAG, "Sincronización remota completada con Docker/MySQL")
        }
    }

    private fun writeLocalGoogleIdentity(
        userId: String,
        email: String,
        fullName: String,
        estimatedAge: Int?,
        city: String?
    ) {
        val ageVal = estimatedAge ?: 70
        val yearVal = CURRENT_YEAR - ageVal
        val handleVal = email.substringBefore('@')
            .filter { it.isLetterOrDigit() || it == '_' }
            .lowercase()
            .ifBlank { "usuario" }
        val userValues = android.content.ContentValues().apply {
            put("id", userId)
            put("full_name", fullName)
            put("username", handleVal)
            put("email", email)
            put("birth_date", "Año $yearVal")
            put("birth_year", yearVal)
            put("estimated_age", ageVal)
            put("gender_identity", "No especificado")
            put("city", city ?: "Santiago, Chile")
        }
        database.writableDatabase.insertWithOnConflict(
            "users", null, userValues, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        val profileValues = android.content.ContentValues().apply {
            put("user_id", userId)
            put("bio_ai", "¡Hola! Soy $fullName. Me alegra unirme a Fifo con Google.")
            put("interest_tags", com.google.gson.Gson().toJson(listOf("Música", "Paseos", "Charlas")))
        }
        database.writableDatabase.insertWithOnConflict(
            "user_profiles", null, profileValues, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun writeLocalProfile(
        userId: String,
        fullName: String,
        username: String,
        birthDate: String?,
        birthYear: Int?,
        estimatedAge: Int?,
        gender: String,
        city: String,
        initialTastes: List<String>,
        email: String?
    ) {
        val ageVal = estimatedAge ?: (if (birthYear != null) CURRENT_YEAR - birthYear else 0)
        val yearVal = birthYear ?: (if (ageVal > 0) CURRENT_YEAR - ageVal else 0)
        val dateVal = birthDate ?: if (yearVal > 0) "Año $yearVal" else ""
        val userValues = android.content.ContentValues().apply {
            put("id", userId)
            put("full_name", fullName)
            put("username", username)
            put("email", email ?: "${userId}@fifo.app")
            put("birth_date", dateVal)
            put("birth_year", yearVal)
            put("estimated_age", ageVal)
            put("gender_identity", gender)
            put("city", city)
        }
        database.writableDatabase.insertWithOnConflict(
            "users", null, userValues, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        val bio = if (initialTastes.isNotEmpty()) {
            "Amante de ${initialTastes.take(3).joinToString(", ")}. Disfruta conversar con calma sobre sus pasatiempos favoritos."
        } else {
            "Disfruta conversar con calma con Fifo y compartir anécdotas de su día a día."
        }
        val profileValues = android.content.ContentValues().apply {
            put("user_id", userId)
            put("bio_ai", bio)
            put("interest_tags", com.google.gson.Gson().toJson(initialTastes))
        }
        database.writableDatabase.insertWithOnConflict(
            "user_profiles", null, profileValues, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        initialTastes.forEach { taste -> database.tastes.addTaste(userId, taste) }
    }

    private fun hasContactsPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "UserSession"
        const val KEY_ACTIVE_USER = "active_user_id"
        const val KEY_HAS_COMPLETED_SETUP = "has_completed_setup"
        const val CURRENT_YEAR = 2026
    }
}
