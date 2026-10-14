package com.fifo.voicepipeline.data

import com.fifo.voicepipeline.ui.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Repositorio central reactivo de datos de Fifo.
 *
 * Actúa como la fuente de verdad ("Single Source of Truth") para:
 * 1. La aplicación Android (que observa los StateFlows en tiempo real)
 * 2. Las mutaciones ejecutadas autónomamente por el robot Fifo mediante Function Calling / Skills
 * 3. La sincronización futura bidireccional con backend en la nube (Firestore / Supabase)
 */
object FifoDataRepository {

    private var dbHelper: com.fifo.voicepipeline.data.local.FifoDatabaseHelper? = null
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val _isBackendConnected = MutableStateFlow(false)
    val isBackendConnected: StateFlow<Boolean> = _isBackendConnected.asStateFlow()

    /**
     * Inicializa la base de datos local SQLite y carga los datos persistidos en el teléfono.
     */
    fun initialize(context: android.content.Context) {
        if (dbHelper != null) return
        val app = context.applicationContext
        val helper = com.fifo.voicepipeline.data.local.FifoDatabaseHelper.getInstance(app)
        dbHelper = helper

        val prefs = app.getSharedPreferences("fifo_prefs", android.content.Context.MODE_PRIVATE)
        val activeUserId = prefs.getString("active_user_id", null)

        if (activeUserId != null) {
            try {
                // Cargar estado inicial inmediatamente desde SQLite (100% offline, cero latencia)
                val p = helper.getUserProfile(activeUserId)
                if (p.id.isNotBlank()) _userProfile.value = p
                _tastes.value = helper.getTastes(activeUserId)
                _tasteStories.value = helper.getTasteStories(activeUserId)
                _socialPosts.value = helper.getSocialPosts()
                _memories.value = helper.getMemories(activeUserId)
                _reminders.value = helper.getReminders(activeUserId)
                _conversations.value = helper.getPastConversations(activeUserId)
                _conversationFragments.value = helper.getConversationFragments(activeUserId)
                _relationalContacts.value = helper.getRelationalContacts(activeUserId)
                _dailyRoutines.value = helper.getDailyRoutines(activeUserId)
                _deviceLocation.value = helper.getDeviceLocation()
                android.util.Log.i("FifoDataRepository", "Base de datos relacional SQLite cargada con éxito en el dispositivo para $activeUserId")

                // Sincronizar libreta de contactos del teléfono celular en segundo plano si hay permiso
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.READ_CONTACTS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    repositoryScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        syncDeviceContactsToRelationalGraph(context)
                    }
                }

                // Sincronizar en segundo plano con el backend Docker / MySQL (database/api)
                syncFromBackend(activeUserId)
            } catch (e: Exception) {
                android.util.Log.e("FifoDataRepository", "Error inicializando base de datos SQLite: ${e.message}")
            }
        }
    }

    /**
     * Sincroniza todos los datos del usuario con el backend Docker (database/api en puerto 8090)
     * e integra cualquier cambio en SQLite local y en los StateFlows reactivos.
     */
    fun syncFromBackend(userId: String = _userProfile.value.id) {
        repositoryScope.launch {
            if (com.fifo.voicepipeline.network.FifoBackendClient.checkHealth()) {
                _isBackendConnected.value = true
                val bundle = com.fifo.voicepipeline.network.FifoBackendClient.fetchUserBundle(userId)
                if (bundle != null) {
                    // 1. Persistir el bundle completo en SQLite local
                    dbHelper?.bulkUpsertSyncBundle(userId, bundle)

                    // 2. Notificar a la UI reactiva de Android
                    _userProfile.value = bundle.userProfile
                    _tastes.value = bundle.tastes
                    if (bundle.tasteStories.isNotEmpty()) _tasteStories.value = bundle.tasteStories
                    if (bundle.socialPosts.isNotEmpty()) _socialPosts.value = bundle.socialPosts
                    if (bundle.memories.isNotEmpty()) _memories.value = bundle.memories
                    if (bundle.reminders.isNotEmpty()) _reminders.value = bundle.reminders
                    if (bundle.pastConversations.isNotEmpty()) _conversations.value = bundle.pastConversations
                    if (bundle.conversationFragments.isNotEmpty()) _conversationFragments.value = bundle.conversationFragments
                    bundle.relationalContacts?.let { if (it.isNotEmpty()) _relationalContacts.value = it }
                    bundle.dailyRoutines?.let { if (it.isNotEmpty()) _dailyRoutines.value = it }
                    bundle.deviceLocation?.let { _deviceLocation.value = it }
                    android.util.Log.i("FifoDataRepository", "Sincronización remota completada con Docker/MySQL")
                }
            } else {
                _isBackendConnected.value = false
                android.util.Log.d("FifoDataRepository", "Backend Docker/MySQL no disponible; operando 100% en SQLite local")
            }
        }
    }

    fun getActiveUserId(context: android.content.Context): String {
        val prefs = context.applicationContext.getSharedPreferences("fifo_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("active_user_id", _userProfile.value.id) ?: _userProfile.value.id
    }

    /**
     * Cambia el perfil de usuario activo en el teléfono, actualiza SharedPreferences,
     * recarga SQLite local y sincroniza en segundo plano con MySQL.
     */
    fun switchUserProfile(context: android.content.Context, newUserId: String) {
        val helper = dbHelper ?: return
        val prefs = context.applicationContext.getSharedPreferences("fifo_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("active_user_id", newUserId).apply()

        val p = helper.getUserProfile(newUserId)
        if (p.fullName.isNotBlank()) {
            _userProfile.value = p
        } else {
            _userProfile.value = UserProfileData(id = newUserId)
        }

        _tastes.value = helper.getTastes(newUserId)
        _tasteStories.value = helper.getTasteStories(newUserId)
        _memories.value = helper.getMemories(newUserId)
        _reminders.value = helper.getReminders(newUserId)
        _conversations.value = helper.getPastConversations(newUserId)
        _conversationFragments.value = helper.getConversationFragments(newUserId)
        _relationalContacts.value = helper.getRelationalContacts(newUserId)
        _dailyRoutines.value = helper.getDailyRoutines(newUserId)
        _socialPosts.value = helper.getSocialPosts()

        syncFromBackend(newUserId)
    }

    /**
     * Autenticación / Registro transparente con cuenta de Google.
     */
    suspend fun loginWithGoogle(
        context: android.content.Context,
        googleId: String,
        email: String,
        fullName: String,
        avatarUrl: String? = null,
        estimatedAge: Int? = null,
        city: String? = null
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val result = com.fifo.voicepipeline.network.FifoBackendClient.authenticateGoogleUser(
                googleId = googleId,
                email = email,
                fullName = fullName,
                avatarUrl = avatarUrl,
                estimatedAge = estimatedAge,
                city = city
            )
            val effectiveUserId = result?.userId ?: ("usr_g_" + googleId.takeLast(6))
            val prefs = context.applicationContext.getSharedPreferences("fifo_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("active_user_id", effectiveUserId).putBoolean("has_completed_setup", true).apply()

            if (result == null && dbHelper != null) {
                val ageVal = estimatedAge ?: 70
                val yearVal = 2026 - ageVal
                val handleVal = email.substringBefore('@').filter { it.isLetterOrDigit() || it == '_' }.lowercase().ifBlank { "usuario" }
                val cv = android.content.ContentValues().apply {
                    put("id", effectiveUserId)
                    put("full_name", fullName)
                    put("username", handleVal)
                    put("email", email)
                    put("birth_date", "Año $yearVal")
                    put("birth_year", yearVal)
                    put("estimated_age", ageVal)
                    put("gender_identity", "No especificado")
                    put("city", city ?: "Santiago, Chile")
                }
                dbHelper?.writableDatabase?.insertWithOnConflict("users", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                val cvProf = android.content.ContentValues().apply {
                    put("user_id", effectiveUserId)
                    put("bio_ai", "¡Hola! Soy $fullName. Me alegra unirme a Fifo con Google.")
                    put("interest_tags", com.google.gson.Gson().toJson(listOf("Música", "Paseos", "Charlas")))
                }
                dbHelper?.writableDatabase?.insertWithOnConflict("user_profiles", null, cvProf, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }

            switchUserProfile(context, effectiveUserId)
            true
        } catch (e: Exception) {
            android.util.Log.e("FifoDataRepository", "Error en loginWithGoogle: ${e.message}")
            false
        }
    }

    /**
     * Verifica si ya existe un perfil local o remoto para un email o cuenta de Google dada.
     */
    fun findUserByEmailOrId(email: String, googleId: String): String? {
        val helper = dbHelper ?: return null
        return try {
            val db = helper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id FROM users WHERE email = ? OR id = ? OR id = ? LIMIT 1",
                arrayOf(email, googleId, "usr_g_" + googleId.takeLast(6))
            )
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            android.util.Log.w("FifoDataRepository", "Error buscando usuario por email/googleId: ${e.message}")
            null
        }
    }

    /**
     * Crea un perfil de usuario en el onboarding y lo persiste inmediatamente en MySQL y SQLite.
     */
    suspend fun createProfileAndStart(
        context: android.content.Context,
        fullName: String,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        estimatedAge: Int? = null,
        gender: String = "Mujer",
        city: String = "Santiago, Chile",
        initialTastes: List<String> = emptyList(),
        email: String? = null
    ): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val rawHandle = (username?.ifBlank { null } ?: email?.substringBefore('@') ?: fullName)
                .filter { it.isLetterOrDigit() || it == '_' }
                .lowercase()
                .removePrefix("@")
                .ifBlank { "usuario" }

            val result = com.fifo.voicepipeline.network.FifoBackendClient.createUserProfile(
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

            val prefs = context.applicationContext.getSharedPreferences("fifo_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("active_user_id", newUserId).putBoolean("has_completed_setup", true).apply()

            if (result == null && dbHelper != null) {
                val ageVal = estimatedAge ?: (if (birthYear != null) 2026 - birthYear else 68)
                val yearVal = birthYear ?: (2026 - ageVal)
                val dateVal = birthDate ?: "Año $yearVal"
                val cv = android.content.ContentValues().apply {
                    put("id", newUserId)
                    put("full_name", fullName)
                    put("username", rawHandle)
                    put("email", email ?: "${newUserId}@fifo.app")
                    put("birth_date", dateVal)
                    put("birth_year", yearVal)
                    put("estimated_age", ageVal)
                    put("gender_identity", gender)
                    put("city", city)
                }
                dbHelper?.writableDatabase?.insertWithOnConflict("users", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                val cvProf = android.content.ContentValues().apply {
                    put("user_id", newUserId)
                    put("bio_ai", "Amante de ${initialTastes.take(3).joinToString(", ")}. Disfruta conversar con calma sobre sus pasatiempos favoritos.")
                    put("interest_tags", com.google.gson.Gson().toJson(initialTastes))
                }
                dbHelper?.writableDatabase?.insertWithOnConflict("user_profiles", null, cvProf, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                initialTastes.forEach { t -> dbHelper?.addTaste(newUserId, t) }
            }

            switchUserProfile(context, newUserId)
            newUserId
        } catch (e: Exception) {
            android.util.Log.e("FifoDataRepository", "Error en createProfileAndStart: ${e.message}")
            null
        }
    }

    /**
     * Inicia sesión buscando el usuario por id, nombre o email.
     */
    suspend fun loginWithIdentifier(
        context: android.content.Context,
        identifier: String
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val result = com.fifo.voicepipeline.network.FifoBackendClient.loginUser(identifier)
            if (result != null) {
                val prefs = context.applicationContext.getSharedPreferences("fifo_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putString("active_user_id", result.userId).putBoolean("has_completed_setup", true).apply()
                switchUserProfile(context, result.userId)
                return@withContext true
            }
            // Buscar en SQLite local por email, username, full_name o id
            val localUserId = dbHelper?.findUserIdByIdentifier(identifier)
            if (localUserId != null) {
                val prefs = context.applicationContext.getSharedPreferences("fifo_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putString("active_user_id", localUserId).putBoolean("has_completed_setup", true).apply()
                switchUserProfile(context, localUserId)
                return@withContext true
            }
            false
        } catch (e: Exception) {
            android.util.Log.e("FifoDataRepository", "Error en loginWithIdentifier: ${e.message}")
            false
        }
    }

    // ── 1. Perfil del Usuario ──────────────────────────────────
    private val _userProfile = MutableStateFlow(UserProfileData())
    val userProfile: StateFlow<UserProfileData> = _userProfile.asStateFlow()

    // ── 2. Gustos e Intereses ──────────────────────────────────
    private val _tastes = MutableStateFlow<List<String>>(emptyList())
    val tastes: StateFlow<List<String>> = _tastes.asStateFlow()

    // ── 3. Historias y Artículos de Blog ("Gustos en detalle") ──
    private val _tasteStories = MutableStateFlow<List<FifoTasteStory>>(emptyList())
    val tasteStories: StateFlow<List<FifoTasteStory>> = _tasteStories.asStateFlow()

    // ── 4. Publicaciones Sociales ("Fifo Amigos") ────
    private val _socialPosts = MutableStateFlow<List<UserSocialPost>>(emptyList())
    val socialPosts: StateFlow<List<UserSocialPost>> = _socialPosts.asStateFlow()

    // ── 5. Recuerdos de Conversaciones ─────────────────────────
    private val _memories = MutableStateFlow<List<FifoMemoryItem>>(emptyList())
    val memories: StateFlow<List<FifoMemoryItem>> = _memories.asStateFlow()

    // ── 6. Recordatorios y Alarmas del Sistema ──────────────────
    private val _reminders = MutableStateFlow<List<FifoReminderItem>>(emptyList())
    val reminders: StateFlow<List<FifoReminderItem>> = _reminders.asStateFlow()

    // ── 7. Conversaciones Pasadas ───────────────────────────────
    private val _conversations = MutableStateFlow<List<PastConversationItem>>(emptyList())
    val conversations: StateFlow<List<PastConversationItem>> = _conversations.asStateFlow()

    // ── 8. Grafo Relacional de Contactos Cercanos ───────────────
    private val _relationalContacts = MutableStateFlow<List<RelationalContact>>(emptyList())
    val relationalContacts: StateFlow<List<RelationalContact>> = _relationalContacts.asStateFlow()

    // ── 9. Rutinas Diarias y Patrones de Actividad ───────────────
    private val _dailyRoutines = MutableStateFlow<List<DailyRoutine>>(emptyList())
    val dailyRoutines: StateFlow<List<DailyRoutine>> = _dailyRoutines.asStateFlow()

    // ══════════════════════════════════════════════════════════
    //  MUTACIONES (Llamadas por los Skills de Fifo o por la UI)
    // ══════════════════════════════════════════════════════════

    /**
     * Actualiza la información demográfica del usuario.
     */
    fun updateDemographics(
        fullName: String? = null,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        gender: String? = null,
        city: String? = null
    ) {
        val current = _userProfile.value
        val newYear = birthYear ?: current.birthYear
        val newAge = if (birthYear != null) (2026 - birthYear) else current.estimatedAge
        val cleanUsername = username?.trim()?.removePrefix("@")?.filter { it.isLetterOrDigit() || it == '_' }?.lowercase()

        val updated = current.copy(
            fullName = fullName?.trim()?.ifBlank { current.fullName } ?: current.fullName,
            username = cleanUsername?.ifBlank { current.username } ?: current.username,
            birthDate = birthDate?.trim()?.ifBlank { current.birthDate } ?: current.birthDate,
            birthYear = newYear,
            estimatedAge = newAge,
            genderIdentity = gender?.trim()?.ifBlank { current.genderIdentity } ?: current.genderIdentity,
            city = city?.trim()?.ifBlank { current.city } ?: current.city
        )
        _userProfile.value = updated

        // Guardar en SQLite local
        dbHelper?.updateDemographics(
            userId = current.id,
            fullName = fullName,
            username = cleanUsername,
            birthDate = birthDate,
            birthYear = newYear,
            estimatedAge = newAge,
            gender = gender,
            city = city
        )

        // Sincronizar con Docker / MySQL en segundo plano si está disponible
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.patchDemographics(
                userId = current.id,
                fullName = fullName,
                birthDate = birthDate,
                birthYear = newYear,
                gender = gender,
                city = city
            )
        }
    }

    /**
     * Actualiza la biografía creada por la IA a partir de las conversaciones.
     */
    fun updateBio(newBio: String) {
        if (newBio.isNotBlank()) {
            val clean = newBio.trim()
            _userProfile.value = _userProfile.value.copy(bioAi = clean)
            dbHelper?.updateBio(_userProfile.value.id, clean)
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.putBio(_userProfile.value.id, clean)
            }
        }
    }

    /**
     * Actualiza los tags de intereses específicos del usuario identificados por IA.
     */
    fun updateInterestTags(tags: List<String>) {
        if (tags.isNotEmpty()) {
            val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            _userProfile.value = _userProfile.value.copy(interestTags = cleanTags)
            dbHelper?.updateInterestTags(_userProfile.value.id, cleanTags)
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.putInterestTags(_userProfile.value.id, cleanTags)
            }
        }
    }

    /**
     * Agrega un nuevo gusto aprendido por Fifo.
     */
    fun addTaste(tasteName: String): Boolean {
        val clean = tasteName.trim()
        if (clean.isBlank()) return false
        val current = _tastes.value
        if (!current.any { it.equals(clean, ignoreCase = true) }) {
            _tastes.value = current + clean
            dbHelper?.addTaste(_userProfile.value.id, clean)
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.postTaste(_userProfile.value.id, "add", clean)
            }
            return true
        }
        return false
    }

    /**
     * Elimina un gusto a petición del usuario.
     */
    fun removeTaste(tasteName: String): Boolean {
        val clean = tasteName.trim()
        val current = _tastes.value
        val filtered = current.filterNot { it.equals(clean, ignoreCase = true) }
        if (filtered.size != current.size) {
            _tastes.value = filtered
            dbHelper?.removeTaste(_userProfile.value.id, clean)
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.postTaste(_userProfile.value.id, "remove", clean)
            }
            return true
        }
        return false
    }

    /**
     * Añade una historia de vida ("Gusto en detalle").
     */
    fun addTasteStory(story: FifoTasteStory) {
        _tasteStories.value = listOf(story) + _tasteStories.value
        dbHelper?.addTasteStory(_userProfile.value.id, story)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postTasteStory(_userProfile.value.id, story)
        }
    }

    /**
     * Publica un mensaje en Fifo Amigos.
     */
    fun addSocialPost(content: String, category: String = "Bienestar"): UserSocialPost {
        val user = _userProfile.value
        val newPost = UserSocialPost(
            id = "post_${UUID.randomUUID()}",
            author = user.fullName.split(" ").firstOrNull() ?: user.fullName,
            age = user.estimatedAge,
            category = category,
            timeAgo = "Recién publicado",
            content = content.trim(),
            likes = 1,
            commentsCount = 0,
            accentColorHex = 0xFF38BDF8
        )
        _socialPosts.value = listOf(newPost) + _socialPosts.value
        dbHelper?.addSocialPost(newPost, _userProfile.value.id)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postSocialPost(user.id, content.trim(), category)
        }
        return newPost
    }

    /**
     * Añade un recuerdo aprendido por Fifo.
     */
    fun addMemory(emoji: String, title: String, detail: String) {
        val newMem = FifoMemoryItem(
            id = "m_${UUID.randomUUID()}",
            emoji = emoji.ifBlank { "⭐" },
            title = title.trim(),
            detail = detail.trim(),
            learnedDate = "Aprendido recién"
        )
        _memories.value = listOf(newMem) + _memories.value
        dbHelper?.addMemory(_userProfile.value.id, newMem)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postMemory(_userProfile.value.id, newMem.emoji, newMem.title, newMem.detail)
        }
    }

    /**
     * Programa un nuevo recordatorio en el sistema.
     */
    fun addReminder(title: String, timeStr: String, category: String = "medication"): FifoReminderItem {
        val item = FifoReminderItem(
            id = "rem_${UUID.randomUUID()}",
            title = title.trim(),
            timeStr = timeStr.trim(),
            category = category,
            isCompleted = false,
            createdAt = "Hoy"
        )
        _reminders.value = listOf(item) + _reminders.value
        dbHelper?.addReminder(_userProfile.value.id, item)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postReminder(_userProfile.value.id, item.title, item.timeStr, item.category)
        }
        return item
    }

    /**
     * Marca un recordatorio como completado.
     */
    fun completeReminder(id: String) {
        _reminders.value = _reminders.value.map {
            if (it.id == id) it.copy(isCompleted = true) else it
        }
        dbHelper?.completeReminder(_userProfile.value.id, id, true)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.patchReminderComplete(id)
        }
    }

    /**
     * Elimina un recordatorio del teléfono y lo sincroniza con el backend.
     */
    fun removeReminder(id: String) {
        _reminders.value = _reminders.value.filterNot { it.id == id }
        dbHelper?.deleteReminder(_userProfile.value.id, id)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.deleteReminder(id)
        }
    }

    /**
     * Guarda un recuerdo directamente en la memoria viva de celular y SQLite local
     * sin disparar llamada HTTP redundante al backend (útil post-consolidación de IA).
     */
    fun addMemoryDirect(item: FifoMemoryItem) {
        val current = _memories.value
        if (!current.any { it.title.equals(item.title, ignoreCase = true) }) {
            _memories.value = listOf(item) + current
            dbHelper?.addMemory(_userProfile.value.id, item)
        }
    }

    /**
     * Guarda una historia de gusto detallada directamente en la memoria viva y SQLite local.
     */
    fun addTasteStoryDirect(story: FifoTasteStory) {
        val current = _tasteStories.value
        if (!current.any { it.title.equals(story.title, ignoreCase = true) }) {
            _tasteStories.value = listOf(story) + current
            dbHelper?.addTasteStory(_userProfile.value.id, story)
        }
    }

    /**
     * Guarda un recordatorio directamente en la memoria viva y SQLite local.
     */
    fun addReminderDirect(reminder: FifoReminderItem) {
        val current = _reminders.value
        if (!current.any { it.title.equals(reminder.title, ignoreCase = true) && it.timeStr == reminder.timeStr }) {
            _reminders.value = listOf(reminder) + current
            dbHelper?.addReminder(_userProfile.value.id, reminder)
        }
    }

    /**
     * Registra una conversación pasada en el historial.
     */
    fun addConversation(item: PastConversationItem) {
        _conversations.value = listOf(item) + _conversations.value
        dbHelper?.addPastConversation(_userProfile.value.id, item)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postConversation(
                userId = _userProfile.value.id,
                title = item.title,
                summary = item.summary,
                primaryTag = item.tag,
                durationSeconds = 180
            )
        }
    }

    fun addPastConversation(item: PastConversationItem) = addConversation(item)

    /**
     * Elimina una conversación por id.
     */
    fun removeConversation(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
        dbHelper?.deletePastConversation(_userProfile.value.id, id)
    }

    /**
     * Elimina un recuerdo por id.
     */
    fun removeMemory(id: String) {
        _memories.value = _memories.value.filterNot { it.id == id }
    }

    // ══════════════════════════════════════════════════════════════
    //  GESTIÓN DEL GRAFO RELACIONAL Y PATRONES DE RUTINA
    // ══════════════════════════════════════════════════════════════

    /**
     * Agrega o actualiza un contacto en el grafo relacional de vínculos cercanos.
     */
    fun addOrUpdateRelationalContact(contact: RelationalContact) {
        val current = _relationalContacts.value.toMutableList()
        val idx = current.indexOfFirst {
            it.id == contact.id ||
            (it.contactName.equals(contact.contactName, ignoreCase = true) && it.phoneNumber == contact.phoneNumber)
        }
        if (idx >= 0) {
            current[idx] = contact
        } else {
            current.add(contact)
        }
        _relationalContacts.value = current.sortedByDescending { it.closenessScore }
        dbHelper?.addOrUpdateRelationalContact(_userProfile.value.id, contact)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postRelationalContact(_userProfile.value.id, contact)
        }
    }

    /**
     * Resuelve un contacto por su rol relacional, apodo o nombre (ej: "hija", "doctor", "Rosa", "Tomás").
     * Ordena las coincidencias por nivel de cercanía (closeness_score) descendente.
     */
    fun findContactByRoleOrName(query: String): RelationalContact? {
        val q = query.lowercase().trim()
        val normalizedQ = java.text.Normalizer.normalize(q, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

        val list = _relationalContacts.value

        // 1. Coincidencia exacta de apodo, rol o nombre completo
        list.firstOrNull { c ->
            val cName = java.text.Normalizer.normalize(c.contactName.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val cRole = java.text.Normalizer.normalize(c.relationshipRole.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val cAlias = c.alias?.let { java.text.Normalizer.normalize(it.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "") } ?: ""
            normalizedQ == cName || normalizedQ == cRole || normalizedQ == cAlias
        }?.let { return it }

        // 2. Coincidencia parcial o semántica ordenada por mayor cercanía emocional y confianza
        return list.sortedByDescending { it.closenessScore }.firstOrNull { c ->
            val cName = java.text.Normalizer.normalize(c.contactName.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val cRole = java.text.Normalizer.normalize(c.relationshipRole.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val cAlias = c.alias?.let { java.text.Normalizer.normalize(it.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "") } ?: ""

            cName.contains(normalizedQ) || normalizedQ.contains(cName) ||
            cRole.contains(normalizedQ) || normalizedQ.contains(cRole) ||
            (cAlias.isNotBlank() && (cAlias.contains(normalizedQ) || normalizedQ.contains(cAlias))) ||
            (normalizedQ.contains("hija") && cRole.contains("hija")) ||
            (normalizedQ.contains("hijo") && cRole.contains("hijo")) ||
            (normalizedQ.contains("doctor") && (cRole.contains("doctor") || cRole.contains("cardio") || cRole.contains("medico"))) ||
            (normalizedQ.contains("medico") && (cRole.contains("doctor") || cRole.contains("cardio") || cRole.contains("medico"))) ||
            (normalizedQ.contains("nieto") && cRole.contains("nieto")) ||
            (normalizedQ.contains("nieta") && cRole.contains("nieta")) ||
            (normalizedQ.contains("amiga") && cRole.contains("amiga")) ||
            (normalizedQ.contains("vecina") && cRole.contains("vecina"))
        }
    }

    private data class InferredContactAttributes(
        val role: String,
        val alias: String?,
        val initialScore: Float,
        val tier: Int,
        val valence: String
    )

    /**
     * Ingesta y sincroniza la libreta real de contactos del teléfono celular en el Grafo Relacional de Fifo.
     * Preserva la memoria aprendida y puntuaciones de cercanía si el contacto ya existía.
     * Infiere roles iniciales (Hijo, Nieto, Doctor, etc.) a partir del nombre guardado.
     */
    fun syncDeviceContactsToRelationalGraph(context: android.content.Context): Int {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_CONTACTS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.d("FifoDataRepository", "Permiso READ_CONTACTS no concedido; omitiendo ingesta de libreta.")
            return 0
        }

        val phoneContacts = mutableListOf<RelationalContact>()
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seenKeys = mutableSetOf<String>()

                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim() ?: "" else ""
                    val rawPhone = if (numIdx >= 0) cursor.getString(numIdx)?.trim() ?: "" else ""
                    if (name.isBlank()) continue

                    val cleanPhone = rawPhone.replace(Regex("[^0-9+]"), "")
                    val dedupeKey = name.lowercase()
                    if (!seenKeys.add(dedupeKey)) continue

                    val nameLower = name.lowercase()
                    val attrs = when {
                        nameLower.contains("hija") -> InferredContactAttributes("Hija", "hija", 0.95f, 1, "afectuoso_familiar")
                        nameLower.contains("hijo") -> InferredContactAttributes("Hijo", "hijo", 0.95f, 1, "afectuoso_familiar")
                        nameLower.contains("mama") || nameLower.contains("mamá") || nameLower.contains("mami") || nameLower.contains("madre") ->
                            InferredContactAttributes("Madre", "mamá", 0.98f, 1, "afectuoso_familiar")
                        nameLower.contains("papa") || nameLower.contains("papá") || nameLower.contains("papi") || nameLower.contains("padre") ->
                            InferredContactAttributes("Padre", "papá", 0.98f, 1, "afectuoso_familiar")
                        nameLower.contains("nieto") -> InferredContactAttributes("Nieto", "nieto", 0.90f, 1, "afectuoso_familiar")
                        nameLower.contains("nieta") -> InferredContactAttributes("Nieta", "nieta", 0.90f, 1, "afectuoso_familiar")
                        nameLower.contains("hermana") -> InferredContactAttributes("Hermana", "hermana", 0.85f, 2, "afectuoso_familiar")
                        nameLower.contains("hermano") -> InferredContactAttributes("Hermano", "hermano", 0.85f, 2, "afectuoso_familiar")
                        nameLower.contains("doctor") || nameLower.contains("dr.") || nameLower.contains("dr ") ||
                        nameLower.contains("médico") || nameLower.contains("medico") || nameLower.contains("dra.") ||
                        nameLower.contains("dra ") || nameLower.contains("cardio") ->
                            InferredContactAttributes("Médico de cabecera", "doctor", 0.82f, 2, "respeto_salud")
                        nameLower.contains("tía") || nameLower.contains("tia") -> InferredContactAttributes("Tía", "tía", 0.78f, 2, "afectuoso")
                        nameLower.contains("tío") || nameLower.contains("tio") -> InferredContactAttributes("Tío", "tío", 0.78f, 2, "afectuoso")
                        nameLower.contains("primo") || nameLower.contains("prima") -> InferredContactAttributes("Primo/a", "primo/a", 0.75f, 3, "afectuoso")
                        nameLower.contains("amiga") -> InferredContactAttributes("Amiga cercana", "amiga", 0.75f, 3, "afectuoso_amigo")
                        nameLower.contains("amigo") -> InferredContactAttributes("Amigo cercano", "amigo", 0.75f, 3, "afectuoso_amigo")
                        nameLower.contains("vecina") -> InferredContactAttributes("Vecina", "vecina", 0.70f, 3, "afectuoso_vecindad")
                        nameLower.contains("vecino") -> InferredContactAttributes("Vecino", "vecino", 0.70f, 3, "afectuoso_vecindad")
                        else -> InferredContactAttributes("Contacto del teléfono", null, 0.50f, 3, "neutral")
                    }

                    val sanitizedId = "ctc_phone_" + name.replace(Regex("[^a-zA-Z0-9]"), "_").take(20).lowercase() + "_" + cleanPhone.takeLast(4)

                    phoneContacts.add(
                        RelationalContact(
                            id = sanitizedId,
                            contactName = name,
                            phoneNumber = cleanPhone.ifBlank { null },
                            relationshipRole = attrs.role,
                            closenessScore = attrs.initialScore,
                            trustTier = attrs.tier,
                            emotionalValence = attrs.valence,
                            contextualMemory = "Contacto guardado en la libreta del celular.",
                            mentionCount = 0,
                            lastMentionedAt = "",
                            alias = attrs.alias
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FifoDataRepository", "Error leyendo contactos de Android: ${e.message}", e)
        }

        if (phoneContacts.isEmpty()) return 0

        // Merge inteligente preservando memoria y puntuaciones aprendidas
        val currentList = _relationalContacts.value.toMutableList()
        val toSaveInDb = mutableListOf<RelationalContact>()

        for (phoneContact in phoneContacts) {
            val existingIdx = currentList.indexOfFirst {
                it.contactName.equals(phoneContact.contactName, ignoreCase = true) ||
                (!it.phoneNumber.isNullOrBlank() && it.phoneNumber == phoneContact.phoneNumber)
            }

            if (existingIdx >= 0) {
                val existing = currentList[existingIdx]
                val updated = existing.copy(
                    phoneNumber = phoneContact.phoneNumber ?: existing.phoneNumber,
                    relationshipRole = if (existing.relationshipRole in listOf("Contacto del teléfono", "Desconocido") && phoneContact.relationshipRole != "Contacto del teléfono")
                        phoneContact.relationshipRole else existing.relationshipRole,
                    alias = existing.alias ?: phoneContact.alias
                )
                currentList[existingIdx] = updated
                toSaveInDb.add(updated)
            } else {
                currentList.add(phoneContact)
                toSaveInDb.add(phoneContact)
            }
        }

        _relationalContacts.value = currentList.sortedByDescending { it.closenessScore }

        // Persistir en SQLite local
        dbHelper?.syncDeviceContactsBatch(_userProfile.value.id, toSaveInDb)

        // Sincronizar en segundo plano con el Backend MySQL
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.syncRelationalContactsBatch(_userProfile.value.id, toSaveInDb)
        }

        android.util.Log.i("FifoDataRepository", "Sincronizados ${phoneContacts.size} contactos del celular al Grafo Relacional de Fifo.")
        return phoneContacts.size
    }

    /**
     * Procesa las personas mencionadas en una conversación reciente:
     * - Si la persona ya existe en el grafo o en los contactos del teléfono:
     *   Aumenta su puntuación de cercanía (closeness_score) y frecuencia de mención.
     *   Enriquece su descripción y notas de memoria episódica.
     * - Si es una persona nueva mencionada (ej: "mi nieto Lucas", "la vecina Marta"):
     *   Crea un nuevo vínculo en el grafo relacional para recordarla y mencionarla a futuro.
     */
    fun updateContactsFromSessionMentions(mentions: List<com.fifo.voicepipeline.network.MentionedPersonExtraction>) {
        if (mentions.isEmpty()) return

        val currentList = _relationalContacts.value.toMutableList()
        val modifiedOrCreated = mutableListOf<RelationalContact>()

        for (mention in mentions) {
            val mentionName = mention.name.trim()
            if (mentionName.isBlank()) continue

            // 1. Buscar coincidencia exacta o semántica en el grafo relacional
            val existingIdx = currentList.indexOfFirst { c ->
                val cName = c.contactName.lowercase().trim()
                val mName = mentionName.lowercase()
                cName == mName || cName.contains(mName) || mName.contains(cName) ||
                (c.alias != null && c.alias.equals(mName, ignoreCase = true)) ||
                (mention.inferredRole != null && c.relationshipRole.contains(mention.inferredRole, ignoreCase = true))
            }

            if (existingIdx >= 0) {
                val existing = currentList[existingIdx]
                val newMentions = existing.mentionCount + 1
                val valenceBonus = when (mention.emotionalValence.lowercase()) {
                    "afectuoso", "gratitud" -> 0.04f
                    "preocupacion", "apoyo" -> 0.03f
                    else -> 0.02f
                }
                val newScore = minOf(1.0f, existing.closenessScore + mention.closenessDelta.coerceAtLeast(0.03f) + valenceBonus)
                val newRole = if (existing.relationshipRole in listOf("Contacto del teléfono", "Desconocido") && !mention.inferredRole.isNullOrBlank()) {
                    mention.inferredRole
                } else existing.relationshipRole

                val enrichedMemory = buildString {
                    val prev = existing.contextualMemory?.trim() ?: ""
                    if (prev.isNotBlank() && !prev.contains("libreta del celular")) {
                        append(prev)
                        if (!prev.endsWith(".")) append(".")
                        if (mention.contextSnippet.isNotBlank() && !prev.contains(mention.contextSnippet)) {
                            append(" ")
                            append(mention.contextSnippet)
                        }
                    } else if (mention.contextSnippet.isNotBlank()) {
                        append(mention.contextSnippet)
                    } else {
                        append("Mencionado(a) con afecto en conversaciones recientes.")
                    }
                }.take(400)

                val updated = existing.copy(
                    closenessScore = newScore,
                    mentionCount = newMentions,
                    relationshipRole = newRole,
                    contextualMemory = enrichedMemory,
                    emotionalValence = mention.emotionalValence,
                    lastMentionedAt = "Hoy"
                )
                currentList[existingIdx] = updated
                modifiedOrCreated.add(updated)
                android.util.Log.i("FifoDataRepository", "Contacto actualizado en grafo relacional: ${updated.contactName} (score=${(updated.closenessScore * 100).toInt()}%, menciones=$newMentions)")
            } else {
                // 2. Persona nueva aprendida en la conversación
                val newId = "ctc_rel_${java.util.UUID.randomUUID().toString().take(8)}"
                val newContact = RelationalContact(
                    id = newId,
                    contactName = mentionName,
                    phoneNumber = null,
                    relationshipRole = mention.inferredRole ?: "Persona cercana",
                    closenessScore = 0.65f, // Score inicial significativo por haber sido mencionada en charla personal
                    trustTier = 3,
                    emotionalValence = mention.emotionalValence,
                    contextualMemory = mention.contextSnippet.ifBlank { "Mencionado(a) en charla reciente con Fifo." },
                    mentionCount = 1,
                    lastMentionedAt = "Hoy",
                    alias = mention.inferredRole?.lowercase()
                )
                currentList.add(newContact)
                modifiedOrCreated.add(newContact)
                android.util.Log.i("FifoDataRepository", "Nuevo contacto aprendido en grafo relacional: ${newContact.contactName} (${newContact.relationshipRole})")
            }
        }

        _relationalContacts.value = currentList.sortedByDescending { it.closenessScore }

        // Persistir en SQLite y Backend
        for (c in modifiedOrCreated) {
            dbHelper?.addOrUpdateRelationalContact(_userProfile.value.id, c)
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.postRelationalContact(_userProfile.value.id, c)
            }
        }
    }

    /**
     * Agrega o actualiza un patrón o rutina diaria del usuario.
     */
    fun addOrUpdateDailyRoutine(routine: DailyRoutine) {
        val current = _dailyRoutines.value.toMutableList()
        val idx = current.indexOfFirst { it.id == routine.id || it.routineName.equals(routine.routineName, ignoreCase = true) }
        if (idx >= 0) {
            current[idx] = routine
        } else {
            current.add(routine)
        }
        _dailyRoutines.value = current
        dbHelper?.addOrUpdateDailyRoutine(_userProfile.value.id, routine)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postDailyRoutine(_userProfile.value.id, routine)
        }
    }

    /**
     * Obtiene las rutinas activas para un período del día (morning, noon, afternoon, evening, night).
     */
    fun getRoutinesForCurrentTime(timePeriod: String? = null): List<DailyRoutine> {
        val currentList = _dailyRoutines.value
        if (timePeriod.isNullOrBlank()) return currentList
        return currentList.filter { it.timeAnchor.equals(timePeriod, ignoreCase = true) }
    }

    // ══════════════════════════════════════════════════════════════
    //  MEMORIA DE DOBLE CAPA: Fragmentos Livianos (on-device)
    //  + Búsqueda de Contexto Profundo (server-side)
    // ══════════════════════════════════════════════════════════════

    // ── 8. Fragmentos Compactos de Conversaciones Pasadas ─────────
    /**
     * Almacenamiento local liviano de fragmentos compactos.
     *
     * ARQUITECTURA:
     * - Servidor (Firestore/Supabase): Guarda la transcripción COMPLETA de cada
     *   conversación con todos los detalles, turnos de voz y contexto íntegro.
     * - Celular (aquí): Solo almacena estos fragmentos livianos con temas clave,
     *   entidades nombradas, estado de ánimo y un resumen compacto de ≤150 palabras.
     *
     * VENTAJAS:
     * 1. Latencia mínima: Claude recibe solo fragmentos compactos como contexto previo,
     *    reduciendo el tamaño del payload y acelerando la respuesta.
     * 2. Continuidad conversacional: Los temas clave y entidades evitan que Fifo se pierda
     *    cuando el usuario menciona datos de nicho o temas pasados.
     * 3. Si necesita más detalle → el skill `recall_past_context` busca la conversación
     *    completa en el servidor y retorna solo los extractos relevantes.
     */
    private val _conversationFragments = MutableStateFlow<List<ConversationFragment>>(emptyList())
    val conversationFragments: StateFlow<List<ConversationFragment>> = _conversationFragments.asStateFlow()

    /**
     * Registra un nuevo fragmento compacto extraído del final de una conversación.
     * Se llama al cerrar cada sesión de voz con Fifo.
     *
     * El servidor almacena la conversación completa de forma independiente;
     * aquí solo se guarda el fragmento liviano para que Claude tenga
     * contexto rápido en la próxima sesión sin latencia extra.
     */
    fun addConversationFragment(fragment: ConversationFragment) {
        _conversationFragments.value = listOf(fragment) + _conversationFragments.value
        dbHelper?.addConversationFragment(_userProfile.value.id, fragment)
    }

    /**
     * Construye un resumen ultra-compacto (<80 tokens) de la identidad y estado del usuario
     * para el prompt de sistema en vivo, previniendo sobrecosto de tokens y errores 429 en Groq.
     * Si se necesita memoria profunda o anécdotas pasadas, Fifo invoca 'recall_past_context'.
     *
     * @param maxFragments Cantidad máxima de fragmentos recientes a incluir (default 1)
     * @return String listo para inyectar como contexto previo
     */
    fun buildCompactContextWindow(maxFragments: Int = 1): String {
        val profile = _userProfile.value
        val tastes = _tastes.value.take(4)
        val topContacts = _relationalContacts.value.sortedByDescending { it.closenessScore }.take(2)
        val activeReminders = _reminders.value.filter { !it.isCompleted }.take(2)
        val fragments = if (maxFragments > 0) _conversationFragments.value.take(maxFragments) else emptyList()

        return buildString {
            appendLine("=== CONTEXTO DEL USUARIO ===")
            appendLine("Nombre: ${profile.fullName.ifBlank { "Usuario" }} | Edad: ${profile.estimatedAge} | Ciudad: ${profile.city}")
            if (tastes.isNotEmpty()) {
                appendLine("Intereses y gustos: ${tastes.joinToString(", ")}")
            }
            if (topContacts.isNotEmpty()) {
                append("Contactos clave: ")
                appendLine(topContacts.joinToString("; ") { "${it.contactName} (${it.relationshipRole})" })
            }
            if (activeReminders.isNotEmpty()) {
                append("Recordatorios pendientes: ")
                appendLine(activeReminders.joinToString("; ") { "${it.timeStr}: ${it.title}" })
            }
            if (fragments.isNotEmpty()) {
                val lastFrag = fragments.first()
                if (lastFrag.compactSummary.isNotBlank()) {
                    appendLine("Última charla: ${lastFrag.compactSummary.take(120)}")
                }
            }
        }.trim()
    }

    /**
     * Busca contexto profundo en los fragmentos locales por palabra clave.
     * En producción esto llamará al servidor para obtener la transcripción completa;
     * por ahora busca en los fragmentos locales como fallback.
     *
     * @param query Consulta de búsqueda (tema, persona, lugar)
     * @return DeepContextResult con extractos relevantes encontrados
     */
    fun searchDeepContext(query: String): DeepContextResult {
        // 1. Intentar consultar contexto profundo en el microservicio Docker / MySQL si está online
        val serverResult = runCatching {
            kotlinx.coroutines.runBlocking {
                com.fifo.voicepipeline.network.FifoBackendClient.queryDeepContextFromServer(
                    userId = _userProfile.value.id,
                    query = query
                )
            }
        }.getOrNull()

        if (serverResult != null && serverResult.relevantExcerpts.isNotEmpty()) {
            return serverResult
        }

        // 2. Búsqueda profunda multinivel en SQLite (fragmentos, recuerdos, charlas y gustos)
        val queryLower = query.lowercase().trim()
        val allFragments = _conversationFragments.value
        val allMemories = _memories.value
        val allConversations = _conversations.value
        val allTastes = _tastes.value
        val allTasteStories = _tasteStories.value
        val allReminders = _reminders.value

        val stopWords = setOf(
            "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "a", "al", "en", "con",
            "por", "para", "que", "es", "fue", "fui", "como", "hace", "donde", "cual", "mi", "mis",
            "tu", "tus", "su", "sus", "ese", "esa", "esos", "esas", "este", "esta", "estos", "estas",
            "quiero", "ir", "nombre", "llama", "llamaba", "local", "lugar", "sitio", "cosa", "algo", "pero"
        )

        val queryTokens = queryLower
            .split(Regex("[^a-záéíóúñ0-9]+"))
            .filter { it.length >= 3 && it !in stopWords }

        val isRecentTemporal = queryLower.contains("ayer") || queryLower.contains("antier") ||
                queryLower.contains("semana pasada") || queryLower.contains("hace poco") ||
                queryLower.contains("recien") || queryLower.contains("reciente") || queryLower.contains("último") || queryLower.contains("ultimo")

        val excerpts = mutableListOf<String>()
        val foundEntities = mutableSetOf<String>()

        // A. Búsqueda en Grafo Relacional de Contactos
        for (c in _relationalContacts.value) {
            val contactText = "${c.contactName} ${c.relationshipRole} ${c.alias ?: ""} ${c.contextualMemory}".lowercase()
            if (queryTokens.any { contactText.contains(it) } || queryLower.contains(c.contactName.lowercase()) || (c.alias != null && queryLower.contains(c.alias.lowercase()))) {
                excerpts.add("Contacto cercano: ${c.contactName} (${c.relationshipRole}, cercanía ${(c.closenessScore * 100).toInt()}%). Memoria: ${c.contextualMemory}")
                foundEntities.add(c.contactName)
                foundEntities.add(c.relationshipRole)
            }
        }

        // B. Búsqueda en Rutinas y Hábitos Diarios
        for (r in _dailyRoutines.value) {
            val routineText = "${r.routineName} ${r.notes ?: ""} ${r.timeAnchor} ${r.typicalTimeStr ?: ""} ${r.category}".lowercase()
            if (queryTokens.any { routineText.contains(it) } || queryLower.contains(r.routineName.lowercase())) {
                excerpts.add("Patrón de rutina diaria: ${r.routineName} a las ${r.typicalTimeStr ?: ""} (${r.timeAnchor}). Detalle: ${r.notes ?: ""}")
                foundEntities.add(r.routineName)
            }
        }

        // C. Búsqueda en Recuerdos Persistentes (memories)
        for (mem in allMemories) {
            val memText = "${mem.title} ${mem.detail}".lowercase()
            val matchesToken = queryTokens.any { memText.contains(it) }
            val matchesLiteral = queryLower.length >= 4 && memText.contains(queryLower)
            if (matchesToken || matchesLiteral) {
                excerpts.add("Recuerdo guardado: ${mem.title} - ${mem.detail}")
            }
        }

        // D. Búsqueda en Gustos en Detalle (tasteStories con fragmento corto y descripción completa)
        for (story in allTasteStories) {
            val storyText = "${story.title} ${story.subtitle} ${story.description} ${story.tags.joinToString(" ")}".lowercase()
            val matchesToken = queryTokens.any { storyText.contains(it) }
            val matchesLiteral = queryLower.length >= 4 && storyText.contains(queryLower)
            if (matchesToken || matchesLiteral) {
                excerpts.add("Gusto en detalle (${story.title}): ${story.subtitle}. Detalle completo: ${story.description}")
                foundEntities.addAll(story.tags)
            }
        }

        // E. Búsqueda en Recordatorios (reminders)
        for (rem in allReminders) {
            val remText = "${rem.title} ${rem.timeStr}".lowercase()
            val matchesToken = queryTokens.any { remText.contains(it) }
            val matchesLiteral = queryLower.length >= 4 && remText.contains(queryLower)
            if (matchesToken || matchesLiteral) {
                val status = if (rem.isCompleted) "Completado" else "Pendiente"
                excerpts.add("Recordatorio ($status): '${rem.title}' para las ${rem.timeStr}")
            }
        }

        // F. Búsqueda en Fragmentos de Conversaciones Pasadas (conversationFragments)
        val scoredFragments = allFragments.mapIndexed { index, frag ->
            val fragText = "${frag.primaryTag} ${frag.compactSummary} ${frag.keyTopics.joinToString(" ")} ${frag.namedEntities.joinToString(" ")}".lowercase()
            var score = 0
            for (token in queryTokens) {
                if (fragText.contains(token)) score += 2
            }
            if (queryLower.length >= 4 && fragText.contains(queryLower)) score += 4
            // Bonus por temporalidad ("ayer", "última charla")
            if (isRecentTemporal && index == 0) score += 3
            if (isRecentTemporal && index == 1) score += 1
            Pair(frag, score)
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        for ((frag, _) in scoredFragments.take(3)) {
            excerpts.add("Charla pasada (${frag.primaryTag}): ${frag.compactSummary}")
            foundEntities.addAll(frag.namedEntities)
            foundEntities.addAll(frag.keyTopics)
        }

        // G. Búsqueda en Charlas Pasadas (past_conversations)
        for (conv in allConversations) {
            val convText = "${conv.title} ${conv.summary} ${conv.tag}".lowercase()
            if (queryTokens.any { convText.contains(it) } || (queryLower.length >= 4 && convText.contains(queryLower))) {
                if (!excerpts.any { it.contains(conv.title, ignoreCase = true) }) {
                    excerpts.add("Registro de charla: ${conv.title} - ${conv.summary}")
                }
            }
        }

        // H. Búsqueda en Gustos e Intereses (tastes simples)
        val matchedTastes = allTastes.filter { taste ->
            val tLower = taste.lowercase()
            queryTokens.any { tLower.contains(it) } || queryLower.contains(tLower)
        }
        if (matchedTastes.isNotEmpty()) {
            excerpts.add("Gustos registrados del usuario: ${matchedTastes.joinToString(", ")}")
        }

        // Si es consulta temporal ("ayer", "dónde fui") y no hubo match exacto por palabra clave, tomar la última charla
        if (excerpts.isEmpty() && isRecentTemporal && allFragments.isNotEmpty()) {
            val lastFrag = allFragments.first()
            excerpts.add("Última conversación registrada (${lastFrag.recordedAt}): ${lastFrag.compactSummary}")
            foundEntities.addAll(lastFrag.namedEntities)
            foundEntities.addAll(lastFrag.keyTopics)
        }

        if (excerpts.isEmpty()) {
            return DeepContextResult(
                relevantExcerpts = emptyList(),
                foundEntities = emptyList(),
                synthesizedContext = "Revisé la memoria y conversaciones pasadas, pero no encontré registros sobre '$query'.",
                conversationsSearched = allFragments.size + allMemories.size
            )
        }

        val synthesized = buildString {
            append("En la memoria y registros pasados del usuario: ")
            excerpts.forEach { append("$it. ") }
            if (foundEntities.isNotEmpty()) {
                append("Entidades y lugares relacionados: ${foundEntities.take(5).joinToString(", ")}.")
            }
        }.trim()

        return DeepContextResult(
            relevantExcerpts = excerpts,
            foundEntities = foundEntities.toList(),
            synthesizedContext = synthesized,
            conversationsSearched = allFragments.size + allMemories.size
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  9. Rastreo de Ubicación del Dispositivo Fifo (ESP32)
    // ══════════════════════════════════════════════════════════════

    private val _deviceLocation = MutableStateFlow(FifoDeviceLocation())
    val deviceLocation: StateFlow<FifoDeviceLocation> = _deviceLocation.asStateFlow()

    /**
     * Actualiza el estado de conexión y coordenadas GPS de la última vez visto.
     */
    fun updateDeviceConnectionStatus(
        connected: Boolean,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        roomHint: String? = null,
        rssi: Int? = null
    ) {
        val current = _deviceLocation.value
        val nowTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val updatedLoc = current.copy(
            isConnected = connected,
            lastConnectedTime = if (connected) "Conectado ahora" else "Hoy a las $nowTime",
            lastKnownLatitude = latitude ?: current.lastKnownLatitude,
            lastKnownLongitude = longitude ?: current.lastKnownLongitude,
            lastKnownAddress = address ?: current.lastKnownAddress,
            lastKnownRoom = roomHint ?: current.lastKnownRoom,
            signalStrengthRssi = rssi ?: current.signalStrengthRssi,
            isBeeping = if (!connected) false else current.isBeeping
        )
        _deviceLocation.value = updatedLoc
        dbHelper?.updateDeviceLocation(loc = updatedLoc)

        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.patchDeviceLocation(
                userId = _userProfile.value.id,
                isConnected = connected,
                lastConnectedTime = updatedLoc.lastConnectedTime,
                latitude = latitude,
                longitude = longitude,
                address = address,
                roomHint = roomHint,
                rssi = rssi,
                isBeeping = updatedLoc.isBeeping
            )
        }
    }

    /**
     * Activa o desactiva la alarma sonora en el robot para encontrarlo cuando se pierde.
     */
    fun triggerDeviceBeep(beeping: Boolean) {
        val updatedLoc = _deviceLocation.value.copy(isBeeping = beeping)
        _deviceLocation.value = updatedLoc
        dbHelper?.updateDeviceLocation(loc = updatedLoc)

        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.patchDeviceLocation(
                userId = _userProfile.value.id,
                isBeeping = beeping
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  10. Detección y Gestión de Llamadas Entrantes (Tipo Alexa)
    // ══════════════════════════════════════════════════════════════

    private val _incomingCall = MutableStateFlow<IncomingCallInfo?>(null)
    val incomingCall: StateFlow<IncomingCallInfo?> = _incomingCall.asStateFlow()

    // Callback para que la UI o los comandos por voz ejecuten contestar/colgar
    var onAnswerCallAction: (() -> Boolean)? = null
    var onHangupCallAction: (() -> Boolean)? = null

    fun setIncomingCall(call: IncomingCallInfo?) {
        _incomingCall.value = call
    }

    fun answerCurrentCall(): Boolean {
        val handled = onAnswerCallAction?.invoke() ?: false
        if (handled) {
            _incomingCall.value = _incomingCall.value?.copy(isRinging = false)
        }
        return handled
    }

    fun hangupCurrentCall(): Boolean {
        val handled = onHangupCallAction?.invoke() ?: false
        _incomingCall.value = null
        return handled
    }

    // ══════════════════════════════════════════════════════════════
    //  11. Modo de Escucha Continua ("Fifo, sigue escuchando")
    // ══════════════════════════════════════════════════════════════

    private val _isContinuousListening = MutableStateFlow(false)
    val isContinuousListening: StateFlow<Boolean> = _isContinuousListening.asStateFlow()

    fun setContinuousListening(active: Boolean) {
        _isContinuousListening.value = active
    }

    // ══════════════════════════════════════════════════════════════
    //  12. Estado de Hardware del Teléfono (Linterna, Batería, etc.)
    // ══════════════════════════════════════════════════════════════

    private val _phoneHardware = MutableStateFlow(
        PhoneHardwareStatus(
            batteryPercent = 85,
            isCharging = false,
            isFlashlightOn = false,
            volumePercent = 80
        )
    )
    val phoneHardware: StateFlow<PhoneHardwareStatus> = _phoneHardware.asStateFlow()

    fun updateHardwareStatus(
        batteryPercent: Int? = null,
        isCharging: Boolean? = null,
        isFlashlightOn: Boolean? = null,
        volumePercent: Int? = null
    ) {
        val current = _phoneHardware.value
        _phoneHardware.value = current.copy(
            batteryPercent = batteryPercent ?: current.batteryPercent,
            isCharging = isCharging ?: current.isCharging,
            isFlashlightOn = isFlashlightOn ?: current.isFlashlightOn,
            volumePercent = volumePercent ?: current.volumePercent
        )
    }
}

