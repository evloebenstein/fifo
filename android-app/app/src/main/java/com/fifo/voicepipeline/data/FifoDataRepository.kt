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

    // ── Módulos de Dominio Profundo ───────────────────────────────────────
    val userIdentityModule = com.fifo.voicepipeline.data.modules.UserIdentityModule(
        com.fifo.voicepipeline.data.modules.SqliteUserStorageAdapter { dbHelper }
    )
    val communityModule = com.fifo.voicepipeline.data.modules.CommunityModule(
        com.fifo.voicepipeline.data.modules.SqliteCommunityStorageAdapter { dbHelper }
    )
    val episodicMemoryModule = com.fifo.voicepipeline.data.modules.EpisodicMemoryModule(
        com.fifo.voicepipeline.data.modules.SqliteMemoryStorageAdapter { dbHelper }
    )

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
                // Cargar estado inicial inmediatamente mediante los módulos del dominio
                userIdentityModule.loadUser(activeUserId)
                episodicMemoryModule.loadUserData(activeUserId)
                helper.communityActivities.seedDefaultCommunityActivitiesIfEmpty(activeUserId)
                communityModule.loadInitialData(activeUserId)
                _deviceLocation.value = helper.deviceLocation.getDeviceLocation()
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
     * e integra cualquier cambio en SQLite local y en los módulos reactivos.
     */
    fun syncFromBackend(userId: String = userProfile.value.id) {
        repositoryScope.launch {
            if (com.fifo.voicepipeline.network.FifoBackendClient.checkHealth()) {
                _isBackendConnected.value = true
                val bundle = com.fifo.voicepipeline.network.FifoBackendClient.fetchUserBundle(userId)
                if (bundle != null) {
                    // 1. Persistir el bundle completo en SQLite local
                    dbHelper?.syncBundleWriter?.upsertBundle(userId, bundle)

                    // 2. Notificar a los módulos del dominio
                    userIdentityModule.setUserProfile(bundle.userProfile)
                    episodicMemoryModule.setDirectTastes(bundle.tastes)
                    if (bundle.tasteStories.isNotEmpty()) episodicMemoryModule.setDirectTasteStories(bundle.tasteStories)
                    if (bundle.socialPosts.isNotEmpty()) communityModule.setPosts(bundle.socialPosts)
                    if (bundle.memories.isNotEmpty()) episodicMemoryModule.setDirectMemories(bundle.memories)
                    if (bundle.reminders.isNotEmpty()) episodicMemoryModule.setDirectReminders(bundle.reminders)
                    if (bundle.pastConversations.isNotEmpty()) episodicMemoryModule.setDirectConversations(bundle.pastConversations)
                    if (bundle.conversationFragments.isNotEmpty()) episodicMemoryModule.setDirectFragments(bundle.conversationFragments)
                    bundle.relationalContacts?.let { if (it.isNotEmpty()) episodicMemoryModule.setDirectRelationalContacts(it) }
                    bundle.dailyRoutines?.let { if (it.isNotEmpty()) episodicMemoryModule.setDirectDailyRoutines(it) }
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
        return prefs.getString("active_user_id", userProfile.value.id) ?: userProfile.value.id
    }

    /**
     * Cambia el perfil de usuario activo en el teléfono, actualiza SharedPreferences,
     * recarga SQLite local y sincroniza en segundo plano con MySQL.
     */
    fun switchUserProfile(context: android.content.Context, newUserId: String) {
        val helper = dbHelper ?: return
        val prefs = context.applicationContext.getSharedPreferences("fifo_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("active_user_id", newUserId).apply()

        userIdentityModule.loadUser(newUserId)
        episodicMemoryModule.loadUserData(newUserId)
        helper.communityActivities.seedDefaultCommunityActivitiesIfEmpty(newUserId)
        communityModule.loadInitialData(newUserId)

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
                val ageVal = estimatedAge ?: (if (birthYear != null) 2026 - birthYear else 0)
                val yearVal = birthYear ?: (if (ageVal > 0) 2026 - ageVal else 0)
                val dateVal = birthDate ?: if (yearVal > 0) "Año $yearVal" else ""
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
                    val bio = if (initialTastes.isNotEmpty()) {
                        "Amante de ${initialTastes.take(3).joinToString(", ")}. Disfruta conversar con calma sobre sus pasatiempos favoritos."
                    } else {
                        "Disfruta conversar con calma con Fifo y compartir anécdotas de su día a día."
                    }
                    put("bio_ai", bio)
                    put("interest_tags", com.google.gson.Gson().toJson(initialTastes))
                }
                dbHelper?.writableDatabase?.insertWithOnConflict("user_profiles", null, cvProf, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                initialTastes.forEach { t -> dbHelper?.tastes?.addTaste(newUserId, t) }
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
            val localUserId = dbHelper?.userProfile?.findUserIdByIdentifier(identifier)
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
    val userProfile: StateFlow<UserProfileData> = userIdentityModule.userProfile

    // ── 2. Gustos e Intereses ──────────────────────────────────
    val tastes: StateFlow<List<String>> = episodicMemoryModule.tastes

    // ── 3. Historias y Artículos de Blog ("Gustos en detalle") ──
    val tasteStories: StateFlow<List<FifoTasteStory>> = episodicMemoryModule.tasteStories

    // ── 4. Publicaciones Sociales ("Fifo Amigos") ────
    val socialPosts: StateFlow<List<UserSocialPost>> = communityModule.socialPosts

    // ── 5. Recuerdos de Conversaciones ─────────────────────────
    val memories: StateFlow<List<FifoMemoryItem>> = episodicMemoryModule.memories

    // ── 6. Recordatorios y Alarmas del Sistema ──────────────────
    val reminders: StateFlow<List<FifoReminderItem>> = episodicMemoryModule.reminders

    // ── 7. Conversaciones Pasadas ───────────────────────────────
    val conversations: StateFlow<List<PastConversationItem>> = episodicMemoryModule.conversations

    // ── 8. Grafo Relacional de Contactos Cercanos ───────────────
    val relationalContacts: StateFlow<List<RelationalContact>> = episodicMemoryModule.relationalContacts

    // ── 9. Rutinas Diarias y Patrones de Actividad ───────────────
    val dailyRoutines: StateFlow<List<DailyRoutine>> = episodicMemoryModule.dailyRoutines

    // ── 10. Actividades y Encuentros Comunitarios ────────────────
    val communityActivities: StateFlow<List<CommunityActivityItem>> = communityModule.communityActivities

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
        val current = userProfile.value
        val newYear = birthYear ?: current.birthYear
        val cleanUsername = username?.trim()?.removePrefix("@")?.filter { it.isLetterOrDigit() || it == '_' }?.lowercase()

        userIdentityModule.updateDemographics(
            fullName = fullName,
            username = cleanUsername,
            birthDate = birthDate,
            birthYear = newYear,
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
            userIdentityModule.updateBio(clean)
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.putBio(userProfile.value.id, clean)
            }
        }
    }

    /**
     * Actualiza los tags de intereses específicos del usuario identificados por IA.
     */
    fun updateInterestTags(tags: List<String>) {
        if (tags.isNotEmpty()) {
            val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            userIdentityModule.updateInterestTags(cleanTags)
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.putInterestTags(userProfile.value.id, cleanTags)
            }
        }
    }

    /**
     * Agrega un nuevo gusto aprendido por Fifo.
     */
    fun addTaste(tasteName: String): Boolean {
        val clean = tasteName.trim()
        if (clean.isBlank()) return false
        val added = episodicMemoryModule.addTaste(userProfile.value.id, clean)
        if (added) {
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.postTaste(userProfile.value.id, "add", clean)
            }
        }
        return added
    }

    /**
     * Elimina un gusto a petición del usuario.
     */
    fun removeTaste(tasteName: String): Boolean {
        val clean = tasteName.trim()
        val removed = episodicMemoryModule.removeTaste(userProfile.value.id, clean)
        if (removed) {
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.postTaste(userProfile.value.id, "remove", clean)
            }
        }
        return removed
    }

    /**
     * Añade una historia de vida ("Gusto en detalle").
     */
    fun addTasteStory(story: FifoTasteStory) {
        episodicMemoryModule.addTasteStory(userProfile.value.id, story)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postTasteStory(userProfile.value.id, story)
        }
    }

    /**
     * Publica un mensaje en Fifo Amigos.
     */
    fun addSocialPost(content: String, category: String = "Bienestar"): UserSocialPost {
        val user = userProfile.value
        val author = user.fullName.split(" ").firstOrNull() ?: user.fullName
        val newPost = communityModule.addSocialPost(
            content = content,
            category = category,
            author = author,
            username = user.username,
            age = user.estimatedAge,
            userId = user.id
        )
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postSocialPost(user.id, content.trim(), category)
        }
        return newPost
    }

    /**
     * Añade un recuerdo aprendido por Fifo.
     */
    fun addMemory(emoji: String, title: String, detail: String) {
        val newMem = episodicMemoryModule.addMemory(userProfile.value.id, emoji, title, detail)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postMemory(userProfile.value.id, newMem.emoji, newMem.title, newMem.detail)
        }
    }

    /**
     * Programa un nuevo recordatorio en el sistema.
     */
    fun addReminder(title: String, timeStr: String, category: String = "medication"): FifoReminderItem {
        val item = episodicMemoryModule.addReminder(userProfile.value.id, title, timeStr, category)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postReminder(userProfile.value.id, item.title, item.timeStr, item.category)
        }
        return item
    }

    /**
     * Marca un recordatorio como completado.
     */
    fun completeReminder(id: String) {
        episodicMemoryModule.completeReminder(userProfile.value.id, id)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.patchReminderComplete(id)
        }
    }

    /**
     * Elimina un recordatorio del teléfono y lo sincroniza con el backend.
     */
    fun removeReminder(id: String) {
        episodicMemoryModule.removeReminder(userProfile.value.id, id)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.deleteReminder(id)
        }
    }

    /**
     * Guarda un recuerdo directamente en la memoria viva de celular y SQLite local
     * sin disparar llamada HTTP redundante al backend (útil post-consolidación de IA).
     */
    fun addMemoryDirect(item: FifoMemoryItem) {
        val current = memories.value
        if (!current.any { it.title.equals(item.title, ignoreCase = true) }) {
            episodicMemoryModule.setDirectMemories(listOf(item) + current)
            dbHelper?.memories?.addMemory(userProfile.value.id, item)
        }
    }

    /**
     * Guarda una historia de gusto detallada directamente en la memoria viva y SQLite local.
     */
    fun addTasteStoryDirect(story: FifoTasteStory) {
        val current = tasteStories.value
        if (!current.any { it.title.equals(story.title, ignoreCase = true) }) {
            episodicMemoryModule.setDirectTasteStories(listOf(story) + current)
            dbHelper?.tastes?.addTasteStory(userProfile.value.id, story)
        }
    }

    /**
     * Guarda un recordatorio directamente en la memoria viva y SQLite local.
     */
    fun addReminderDirect(reminder: FifoReminderItem) {
        val current = reminders.value
        if (!current.any { it.title.equals(reminder.title, ignoreCase = true) && it.timeStr == reminder.timeStr }) {
            episodicMemoryModule.setDirectReminders(listOf(reminder) + current)
            dbHelper?.reminders?.addReminder(userProfile.value.id, reminder)
        }
    }

    /**
     * Registra una conversación pasada en el historial.
     */
    fun addConversation(item: PastConversationItem) {
        episodicMemoryModule.addConversation(userProfile.value.id, item)
        repositoryScope.launch {
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

    /**
     * Elimina una conversación por id.
     */
    fun removeConversation(id: String) {
        episodicMemoryModule.removeConversation(userProfile.value.id, id)
    }

    /**
     * Elimina un recuerdo por id.
     */
    fun removeMemory(id: String) {
        episodicMemoryModule.removeMemory(userProfile.value.id, id)
    }

    /**
     * Elimina una publicación social por id.
     */
    fun removeSocialPost(id: String) {
        communityModule.removeSocialPost(id)
    }

    /**
     * Elimina una historia de gusto por id.
     */
    fun removeTasteStory(id: String) {
        episodicMemoryModule.removeTasteStory(userProfile.value.id, id)
    }

    // ══════════════════════════════════════════════════════════════
    //  GESTIÓN DEL GRAFO RELACIONAL Y PATRONES DE RUTINA
    // ══════════════════════════════════════════════════════════════

    /**
     * Agrega o actualiza un contacto en el grafo relacional de vínculos cercanos.
     */
    fun addOrUpdateRelationalContact(contact: RelationalContact) {
        episodicMemoryModule.addOrUpdateRelationalContact(userProfile.value.id, contact)
        if (!contact.phoneNumber.isNullOrBlank()) {
            dbHelper?.contactPhoneHints?.deleteForContact(userProfile.value.id, contact.id)
        }
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postRelationalContact(userProfile.value.id, contact)
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

        val list = relationalContacts.value

        // 1. Coincidencia exacta de apodo, rol o nombre completo
        list.firstOrNull { c ->
            val cName = java.text.Normalizer.normalize(c.contactName.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val cRole = java.text.Normalizer.normalize(c.relationshipRole.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val cAlias = c.alias?.let { java.text.Normalizer.normalize(it.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "") } ?: ""
            normalizedQ == cName || normalizedQ == cRole || normalizedQ == cAlias
        }?.let { return it }

        // 2. Coincidencia parcial o por tokens ordenada por mayor cercanía emocional y confianza
        return list.sortedByDescending { it.closenessScore }.firstOrNull { c ->
            val cName = java.text.Normalizer.normalize(c.contactName.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val cRole = java.text.Normalizer.normalize(c.relationshipRole.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            val cAlias = c.alias?.let { java.text.Normalizer.normalize(it.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "") } ?: ""

            val qTokens = normalizedQ.split(" ", "-", "_", ".").filter { it.length > 2 }
            val cTokens = (cName + " " + cRole + " " + cAlias).split(" ", "-", "_", ".").filter { it.length > 2 }

            cName.contains(normalizedQ) || normalizedQ.contains(cName) ||
            cRole.contains(normalizedQ) || normalizedQ.contains(cRole) ||
            (cAlias.isNotBlank() && (cAlias.contains(normalizedQ) || normalizedQ.contains(cAlias))) ||
            qTokens.any { qt -> cTokens.any { ct -> ct.contains(qt) || qt.contains(ct) } }
        }
    }

    /**
     * Devuelve las apariciones registradas de un contacto, es decir, dónde
     * apareció esa persona: libreta, FifoAmigos, conversación o llamada.
     *
     * La procedencia es local (`contact_appearances`) y no viaja al backend.
     */
    fun getContactAppearances(contactId: String): List<ContactAppearance> =
        dbHelper?.contactAppearances?.getAppearances(userProfile.value.id, contactId) ?: emptyList()

    /**
     * Devuelve todas las apariciones del usuario agrupadas por contacto.
     * Evita una consulta por tarjeta al mostrar la procedencia del grafo.
     */
    fun getContactAppearancesByContact(): Map<String, List<ContactAppearance>> =
        dbHelper?.contactAppearances?.getAllAppearances(userProfile.value.id)
            ?.groupBy { it.contactId }
            ?: emptyMap()

    /**
     * Devuelve los teléfonos mencionados que esperan confirmación, agrupados por
     * contacto. No están en `RelationalContact.phoneNumber` ni se sincronizan.
     */
    fun getPendingPhoneHintsByContact(): Map<String, List<ContactPhoneHint>> {
        // Un teléfono ya conocido invalida los indicios pendientes del contacto.
        val contactsWithPhone = relationalContacts.value
            .filter { !it.phoneNumber.isNullOrBlank() }
            .map { it.id }
            .toSet()
        return dbHelper?.contactPhoneHints?.getAllPending(userProfile.value.id)
            ?.filterNot { it.contactId in contactsWithPhone }
            ?.groupBy { it.contactId }
            ?: emptyMap()
    }

    /**
     * Confirma un teléfono mencionado: lo copia al contacto, lo persiste y lo
     * sincroniza. A partir de ahí deja de ser un indicio y pasa a ser el
     * teléfono del contacto.
     *
     * Devuelve `true` si el indicio existía y se aplicó.
     */
    fun confirmPhoneHint(hintId: String): Boolean {
        val userId = userProfile.value.id
        val hint = dbHelper?.contactPhoneHints?.getPendingById(userId, hintId) ?: return false
        val contact = relationalContacts.value.firstOrNull { it.id == hint.contactId } ?: return false

        val updated = contact.copy(phoneNumber = hint.phoneNumber)
        episodicMemoryModule.addOrUpdateRelationalContact(userId, updated)
        // Confirmar un teléfono invalida cualquier otro indicio del mismo contacto.
        dbHelper?.contactPhoneHints?.deleteForContact(userId, contact.id)

        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postRelationalContact(userId, updated)
        }
        return true
    }

    /**
     * Descarta un teléfono mencionado sin tocar el contacto. El indicio queda
     * `DISCARDED` para no volver a preguntarlo y no se sincroniza.
     */
    fun discardPhoneHint(hintId: String) {
        dbHelper?.contactPhoneHints?.markDiscarded(userProfile.value.id, hintId)
    }

    private val relationalGraph = com.fifo.voicepipeline.data.contacts.RelationalGraphCoordinator()

    private fun nowIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    /**
     * Ingesta y sincroniza la libreta real de contactos del teléfono celular en el Grafo Relacional de Fifo.
     * Preserva la memoria aprendida y puntuaciones de cercanía si el contacto ya existía.
     * La fusión y la procedencia las decide [relationalGraph].
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

                    val sanitizedId = "ctc_phone_" + name.replace(Regex("[^a-zA-Z0-9]"), "_").take(20).lowercase() + "_" + cleanPhone.takeLast(4)

                    phoneContacts.add(
                        RelationalContact(
                            id = sanitizedId,
                            contactName = name,
                            phoneNumber = cleanPhone.ifBlank { null },
                            relationshipRole = "Contacto del teléfono",
                            closenessScore = 0.50f,
                            trustTier = 3,
                            emotionalValence = "neutral",
                            contextualMemory = "Contacto guardado en la libreta del celular.",
                            mentionCount = 0,
                            lastMentionedAt = "",
                            alias = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FifoDataRepository", "Error leyendo contactos de Android: ${e.message}", e)
        }

        if (phoneContacts.isEmpty()) return 0

        // Fusión multi-fuente: conserva memoria, puntuaciones y procedencia.
        val merge = relationalGraph.mergePhoneContacts(
            current = relationalContacts.value,
            phoneContacts = phoneContacts,
            occurredAt = nowIso()
        )

        if (merge.changed.isEmpty() && merge.appearances.isEmpty()) {
            return phoneContacts.size
        }

        val userId = userProfile.value.id
        episodicMemoryModule.setDirectRelationalContacts(
            merge.contacts.sortedByDescending { it.closenessScore }
        )

        // Persistir en SQLite local: contactos y apariciones.
        dbHelper?.relationalContacts?.syncDeviceContactsBatch(userId, merge.changed)
        dbHelper?.contactAppearances?.recordAll(userId, merge.appearances)
        // Un teléfono de la libreta invalida cualquier indicio pendiente del contacto.
        merge.changed
            .filter { !it.phoneNumber.isNullOrBlank() }
            .forEach { dbHelper?.contactPhoneHints?.deleteForContact(userId, it.id) }

        // Sincronizar en segundo plano con el Backend MySQL
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.syncRelationalContactsBatch(userId, merge.changed)
        }

        android.util.Log.i("FifoDataRepository", "Sincronizados ${phoneContacts.size} contactos del celular al Grafo Relacional de Fifo.")
        return phoneContacts.size
    }

    /**
     * Ingesta perfiles de FifoAmigos como fuente del grafo relacional.
     *
     * Una recomendación no es un contacto conocido: entra con rol
     * "Amigo de FifoAmigos", confianza media y sin inventar parentesco.
     * Devuelve cuántos perfiles se procesaron.
     */
    fun syncFifoFriendsToRelationalGraph(
        friends: List<com.fifo.voicepipeline.ui.model.FriendRecommendation>
    ): Int {
        if (friends.isEmpty()) return 0

        val merge = relationalGraph.mergeFifoFriends(
            current = relationalContacts.value,
            friends = friends,
            occurredAt = nowIso()
        )

        if (merge.changed.isEmpty() && merge.appearances.isEmpty()) {
            return friends.size
        }

        val userId = userProfile.value.id
        episodicMemoryModule.setDirectRelationalContacts(
            merge.contacts.sortedByDescending { it.closenessScore }
        )
        dbHelper?.relationalContacts?.syncDeviceContactsBatch(userId, merge.changed)
        dbHelper?.contactAppearances?.recordAll(userId, merge.appearances)

        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.syncRelationalContactsBatch(userId, merge.changed)
        }
        return friends.size
    }

    /**
     * Procesa las personas mencionadas en una conversación reciente y las
     * vincula al grafo con su procedencia.
     *
     * La conversación (`conversationId`) queda en `linkedConversationIds` y en
     * `contact_appearances`, para poder responder dónde apareció cada persona.
     */
    fun updateContactsFromSessionMentions(
        mentions: List<com.fifo.voicepipeline.data.memory.MentionedPersonExtraction>,
        conversationId: String? = null
    ) {
        if (mentions.isEmpty()) return

        val merge = relationalGraph.mergeSessionMentions(
            current = relationalContacts.value,
            mentions = mentions,
            conversationId = conversationId,
            occurredAt = nowIso()
        )

        if (merge.changed.isEmpty() && merge.appearances.isEmpty() && merge.phoneHints.isEmpty()) return

        val userId = userProfile.value.id
        episodicMemoryModule.setDirectRelationalContacts(
            merge.contacts.sortedByDescending { it.closenessScore }
        )

        merge.changed.forEach { contact ->
            dbHelper?.relationalContacts?.addOrUpdateRelationalContact(userId, contact)
            repositoryScope.launch {
                com.fifo.voicepipeline.network.FifoBackendClient.postRelationalContact(userId, contact)
            }
        }
        dbHelper?.contactAppearances?.recordAll(userId, merge.appearances)
        dbHelper?.contactPhoneHints?.recordAll(userId, merge.phoneHints)
    }

    /**
     * Agrega o actualiza un patrón o rutina diaria del usuario.
     */
    fun addOrUpdateDailyRoutine(routine: DailyRoutine) {
        episodicMemoryModule.addOrUpdateDailyRoutine(userProfile.value.id, routine)
        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.postDailyRoutine(userProfile.value.id, routine)
        }
    }

    /**
     * Obtiene las rutinas activas para un período del día (morning, noon, afternoon, evening, night).
     */
    fun getRoutinesForCurrentTime(timePeriod: String? = null): List<DailyRoutine> {
        val currentList = dailyRoutines.value
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
     * - Servidor (MySQL/FastAPI): Guarda la transcripción COMPLETA de cada
     *   conversación con todos los detalles, turnos de voz y contexto íntegro.
     * - Celular (aquí): Solo almacena estos fragmentos livianos con temas clave,
     *   entidades nombradas, estado de ánimo y un resumen compacto de ≤150 palabras.
     *
     * VENTAJAS:
     * 1. Latencia mínima: el LLM recibe solo fragmentos compactos como contexto previo,
     *    reduciendo el tamaño del payload y acelerando la respuesta.
     * 2. Continuidad conversacional: Los temas clave y entidades evitan que Fifo se pierda
     *    cuando el usuario menciona datos de nicho o temas pasados.
     * 3. Si necesita más detalle → el skill `recall_past_context` busca la conversación
     *    completa en el servidor y retorna solo los extractos relevantes.
     */
    val conversationFragments: StateFlow<List<ConversationFragment>> = episodicMemoryModule.conversationFragments

    /**
     * Registra un nuevo fragmento compacto extraído del final de una conversación.
     * Se llama al cerrar cada sesión de voz con Fifo.
     */
    fun addConversationFragment(fragment: ConversationFragment) {
        episodicMemoryModule.addConversationFragment(userProfile.value.id, fragment)
    }

    /**
     * Construye un resumen ultra-compacto (<80 tokens) de la identidad y estado del usuario
     * para el prompt de sistema en vivo, previniendo sobrecosto de tokens y errores 429 en Groq.
     * Delegado a EpisodicMemoryModule (Capa 1 de memoria).
     */
    fun buildCompactContextWindow(maxFragments: Int = 1): String {
        return episodicMemoryModule.buildCompactContextWindow(userProfile.value, maxFragments)
    }

    /**
     * Busca contexto profundo en el servidor backend (Docker/MySQL) con fallback local.
     * Delegado a EpisodicMemoryModule (Capa 2 de memoria profunda).
     */
    fun searchDeepContext(query: String): DeepContextResult {
        return episodicMemoryModule.searchDeepContext(userProfile.value.id, query)
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
        dbHelper?.deviceLocation?.updateDeviceLocation(loc = updatedLoc, userId = userProfile.value.id)

        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.patchDeviceLocation(
                userId = userProfile.value.id,
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
        dbHelper?.deviceLocation?.updateDeviceLocation(loc = updatedLoc, userId = userProfile.value.id)

        repositoryScope.launch {
            com.fifo.voicepipeline.network.FifoBackendClient.patchDeviceLocation(
                userId = userProfile.value.id,
                isBeeping = beeping
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  10. Detección y Gestión de Llamadas Entrantes (Tipo Alexa)
    // ══════════════════════════════════════════════════════════════

    private val callState = com.fifo.voicepipeline.telephony.CallStateCoordinator()

    val incomingCall: StateFlow<IncomingCallInfo?> = callState.incomingCall

    // Callback para que la UI o los comandos por voz ejecuten contestar/colgar
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

    // ══════════════════════════════════════════════════════════════
    //  13. Gestión Dinámica de Actividades y Encuentros Comunitarios
    // ══════════════════════════════════════════════════════════════

    fun toggleJoinActivity(activityId: String) {
        val currentUserId = userProfile.value.id
        val target = communityActivities.value.find { it.id == activityId } ?: return
        communityModule.toggleJoinActivity(activityId, currentUserId)

        // Sincronizar en segundo plano con backend si está disponible
        repositoryScope.launch {
            try {
                com.fifo.voicepipeline.network.FifoBackendClient.toggleJoinActivity(
                    activityId = activityId,
                    userId = currentUserId,
                    join = !target.isJoined
                )
            } catch (e: Exception) {
                android.util.Log.d("FifoDataRepository", "Aviso en sync backend toggleJoinActivity: ${e.message}")
            }
        }
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
        val currentUserId = userProfile.value.id
        val resolvedOrganizerName = organizerName ?: userProfile.value.fullName.ifBlank { "Vecino de la comunidad" }
        val newId = "act_" + System.currentTimeMillis().toString().takeLast(6)

        val item = CommunityActivityItem(
            id = newId,
            title = title.trim(),
            description = description.trim(),
            spotsLabel = "Quedan ${spotsMax - 1} cupos",
            spotsMax = spotsMax,
            spotsTaken = 1, // el organizador queda inscrito automáticamente
            dateLabel = dateLabel.trim(),
            locationName = locationName.trim(),
            distanceLabel = "Cerca de tu sector",
            iconCategory = iconCategory,
            organizerId = currentUserId,
            organizerName = resolvedOrganizerName,
            organizerType = organizerType,
            targetTastes = targetTastes,
            isJoined = true
        )

        communityModule.createCommunityActivity(item)
        dbHelper?.communityActivities?.joinCommunityActivity(newId, currentUserId)

        // Sincronizar con el backend remoto en segundo plano
        repositoryScope.launch {
            try {
                com.fifo.voicepipeline.network.FifoBackendClient.createCommunityActivity(item)
            } catch (e: Exception) {
                android.util.Log.d("FifoDataRepository", "Aviso sync backend createCommunityActivity: ${e.message}")
            }
        }

        return newId
    }

    fun deleteCommunityActivity(activityId: String) {
        communityModule.deleteCommunityActivity(activityId)
    }

    fun suggestActivityWithFifo(ideaPrompt: String, userTastes: List<String>): CommunityActivityItem {
        val promptClean = ideaPrompt.trim()
        val lower = promptClean.lowercase()

        // Determinar categoría e icono acorde al contexto
        val (category, defaultTitle, defaultLoc) = when {
            lower.contains("pint") || lower.contains("arte") || lower.contains("dibuj") || lower.contains("acuarel") ->
                Triple("palette", "Encuentro de dibujo y pintura libre", "Centro Cultural o Plaza de las Esculturas")
            lower.contains("cocin") || lower.contains("recet") || lower.contains("pan") || lower.contains("comid") || lower.contains("dulc") ->
                Triple("coffee", "Compartir de cocina y recetas caseras", "Café vecinal o rincón del té")
            lower.contains("camin") || lower.contains("parque") || lower.contains("plant") || lower.contains("flor") || lower.contains("jard") ->
                Triple("nature", "Caminata y paseo por las áreas verdes", "Acceso principal del Parque Central")
            lower.contains("music") || lower.contains("cant") || lower.contains("guitarr") || lower.contains("bolero") ->
                Triple("music", "Tarde musical y canciones del recuerdo", "Sala de música del centro comunitario")
            lower.contains("libr") || lower.contains("le") || lower.contains("cuento") || lower.contains("histori") ->
                Triple("book", "Club de lectura y tertulia de historias", "Biblioteca vecinal")
            else ->
                Triple("nature", "Encuentro vecinal para compartir y conversar", "Café o parque cercano")
        }

        val tastesFound = mutableListOf<String>()
        if (lower.contains("pint") || lower.contains("arte")) tastesFound.add("Pintura y arte")
        if (lower.contains("cocin") || lower.contains("recet")) tastesFound.add("Cocina tradicional")
        if (lower.contains("camin") || lower.contains("parque")) tastesFound.add("Paseos al aire libre")
        if (lower.contains("music") || lower.contains("bolero")) tastesFound.add("Música clásica")
        if (lower.contains("libr") || lower.contains("histori")) tastesFound.add("Historias de vida")
        if (tastesFound.isEmpty() && userTastes.isNotEmpty()) {
            tastesFound.addAll(userTastes.take(2))
        }

        val generatedTitle = if (promptClean.length in 8..55) {
            promptClean.replaceFirstChar { it.uppercase() }
        } else {
            defaultTitle
        }

        val generatedDesc = if (promptClean.length > 20) {
            "Fifo organizó esta propuesta: $promptClean. Una oportunidad perfecta para compartir con vecinos que aprecian los mismos pasatiempos."
        } else {
            "Reunión comunitaria guiada por Fifo para disfrutar de una grata conversación y realizar actividades amenas en conjunto."
        }

        return CommunityActivityItem(
            id = "draft_suggested",
            title = generatedTitle,
            description = generatedDesc,
            spotsLabel = "Quedan 7 cupos",
            spotsMax = 8,
            spotsTaken = 1,
            dateLabel = "Próximo sábado · 16:00",
            locationName = defaultLoc,
            distanceLabel = "A 500 m",
            iconCategory = category,
            organizerId = userProfile.value.id,
            organizerName = userProfile.value.fullName.ifBlank { "Vecino" },
            organizerType = "user",
            targetTastes = tastesFound,
            isJoined = true
        )
    }
}

