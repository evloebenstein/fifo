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

    /**
     * Inicializa la base de datos local SQLite y carga los datos persistidos en el teléfono.
     */
    fun initialize(context: android.content.Context) {
        if (dbHelper != null) return
        val app = context.applicationContext
        val helper = com.fifo.voicepipeline.data.local.FifoDatabaseHelper.getInstance(app)
        dbHelper = helper

        try {
            _userProfile.value = helper.getUserProfile()
            val localTastes = helper.getTastes()
            if (localTastes.isNotEmpty()) _tastes.value = localTastes
            val localStories = helper.getTasteStories()
            if (localStories.isNotEmpty()) _tasteStories.value = localStories
            val localPosts = helper.getSocialPosts()
            if (localPosts.isNotEmpty()) _socialPosts.value = localPosts
            val localMemories = helper.getMemories()
            if (localMemories.isNotEmpty()) _memories.value = localMemories
            val localReminders = helper.getReminders()
            if (localReminders.isNotEmpty()) _reminders.value = localReminders
            _deviceLocation.value = helper.getDeviceLocation()
            android.util.Log.i("FifoDataRepository", "Base de datos local SQLite cargada correctamente")

            // Sincronizar en segundo plano con el backend Docker si está disponible
            repositoryScope.launch {
                com.fifo.voicepipeline.data.remote.FifoApiClient.syncAll(app)
            }
        } catch (e: Exception) {
            android.util.Log.e("FifoDataRepository", "Error inicializando base de datos SQLite: ${e.message}")
        }
    }

    // ── 1. Perfil del Usuario ──────────────────────────────────
    private val _userProfile = MutableStateFlow(
        UserProfileData(
            id = "usr_lucia_01",
            fullName = "Lucía González",
            birthDate = "14 de Mayo, 1958",
            birthYear = 1958,
            estimatedAge = 68,
            genderIdentity = "Mujer",
            city = "Santiago, Chile",
            bioAi = "Amante de las novelas de historia, la música clásica de piano y las mañanas tranquilas con café. Disfruta compartir con Fifo anécdotas de su familia, preparar recetas caseras y cuidar las orquídeas de su jardín.",
            emergencyContactName = "Carmen (Hija)",
            emergencyContactPhone = "+56987654321"
        )
    )
    val userProfile: StateFlow<UserProfileData> = _userProfile.asStateFlow()

    // ── 2. Gustos e Intereses ──────────────────────────────────
    private val _tastes = MutableStateFlow(
        listOf(
            "Lectura histórica",
            "Música clásica",
            "Paseos en el parque",
            "Jardinería",
            "Cocina tradicional"
        )
    )
    val tastes: StateFlow<List<String>> = _tastes.asStateFlow()

    // ── 3. Historias y Artículos de Blog ("Gustos en detalle") ──
    private val _tasteStories = MutableStateFlow(
        listOf(
            FifoTasteStory(
                id = "t_bday",
                title = "Cumpleaños y orígenes familiares",
                subtitle = "Nacida el 14 de Mayo de 1958 en Santiago",
                description = "Lucía nació en una fresca mañana de otoño en 1958 en el barrio tradicional de Santiago. Siempre recuerda cómo en su familia celebraban con torta de milhojas casera y música de fondo. Para Fifo es un honor acompañarla y tener siempre presente su día especial.",
                tags = listOf("Cumpleaños", "Familia", "Santiago"),
                iconCategory = "celebration",
                learnedFrom = "Charla inicial y configuración de perfil con Fifo"
            ),
            FifoTasteStory(
                id = "t_gardening",
                title = "Cuidado y amor por las orquídeas",
                subtitle = "Riego por inmersión y luz filtrada de mañana",
                description = "En sus charlas con Fifo, Lucía contó que tiene 4 maceteros en su balcón. Disfruta regarlas los miércoles y limpiar con cuidado sus hojas verdes con un paño húmedo mientras escucha la radio.",
                tags = listOf("Jardinería", "Plantas", "Paciencia"),
                iconCategory = "nature",
                learnedFrom = "Charla matutina del 2 de Octubre"
            ),
            FifoTasteStory(
                id = "t_music",
                title = "Conciertos de piano y música clásica",
                subtitle = "Chopin y las tardes tranquilas de otoño",
                description = "La música clásica le transmite una paz inmensa. Cuando le pide a Fifo acompañarla a descansar, las melodías de piano de la época romántica son sus predilectas para recordar su juventud.",
                tags = listOf("Música", "Relajo", "Piano"),
                iconCategory = "music",
                learnedFrom = "Conversación sobre melodías relajantes"
            ),
            FifoTasteStory(
                id = "t_food",
                title = "Recetas tradicionales de la abuela",
                subtitle = "Cazuela de ave y pan amasado con pebre",
                description = "Un gusto que Fifo descubrió con alegría: Lucía atesora un recetario escrito a mano con más de 40 años de antigüedad. Cocinar despacio y con ingredientes frescos es su forma de dar cariño.",
                tags = listOf("Cocina", "Recetas", "Tradición"),
                iconCategory = "restaurant",
                learnedFrom = "Charla de mediodía sobre el almuerzo"
            ),
            FifoTasteStory(
                id = "t_books",
                title = "Novelas históricas y biografías",
                subtitle = "Lecturas pausadas con té de manzanilla",
                description = "Le fascina la historia de Chile y las memorias del siglo XX. Le gusta comentarle a Fifo capítulos interesantes de libros que lee en su sillón favorito cerca de la ventana.",
                tags = listOf("Lectura", "Historia", "Tranquilidad"),
                iconCategory = "book",
                learnedFrom = "Charla nocturna de bienestar"
            ),
            FifoTasteStory(
                id = "t_park",
                title = "Caminatas respirando aire puro",
                subtitle = "Paseos de 20 minutos por plazas con sombra",
                description = "El hábito saludable que Fifo más motiva: salir por las mañanas a caminar a paso tranquilo, sintiendo el aire fresco y saludando a los vecinos del barrio.",
                tags = listOf("Salud", "Paseos", "Bienestar"),
                iconCategory = "walk",
                learnedFrom = "Plan de envejecimiento activo de Fifo"
            )
        )
    )
    val tasteStories: StateFlow<List<FifoTasteStory>> = _tasteStories.asStateFlow()

    // ── 4. Publicaciones Sociales de Lucía ("Fifo Amigos") ────
    private val _socialPosts = MutableStateFlow(
        listOf(
            UserSocialPost(
                id = "post_1",
                author = "Lucía",
                age = 68,
                category = "Jardinería",
                timeAgo = "Hoy, 10:30",
                content = "¡Qué felicidad! Hoy abrió la primera flor de mi orquídea blanca. Fifo me recordó regarla ayer por la tarde y valió toda la pena la paciencia.",
                likes = 14,
                commentsCount = 4,
                accentColorHex = 0xFF10B981
            ),
            UserSocialPost(
                id = "post_2",
                author = "Lucía",
                age = 68,
                category = "Música",
                timeAgo = "Ayer",
                content = "Escuchando los nocturnos de Chopin mientras me tomo un té tibio. Qué lindo es encontrar momentos de calma en la tarde.",
                likes = 19,
                commentsCount = 6,
                accentColorHex = 0xFF8B5CF6
            ),
            UserSocialPost(
                id = "post_3",
                author = "Lucía",
                age = 68,
                category = "Cocina",
                timeAgo = "Hace 3 días",
                content = "Preparé una sopita casera con zapallo camote y cilantro fresco de la feria. Quedó deliciosa para este día fresco.",
                likes = 23,
                commentsCount = 8,
                accentColorHex = 0xFFF59E0B
            )
        )
    )
    val socialPosts: StateFlow<List<UserSocialPost>> = _socialPosts.asStateFlow()

    // ── 5. Recuerdos de Conversaciones ─────────────────────────
    private val _memories = MutableStateFlow(
        listOf(
            FifoMemoryItem(
                id = "m1",
                emoji = "🌺",
                title = "Flor favorita: Orquídeas blancas",
                detail = "Le recuerdan el patio iluminado de su infancia en el sur.",
                learnedDate = "Aprendido hace 2 días"
            ),
            FifoMemoryItem(
                id = "m2",
                emoji = "☕",
                title = "Costumbre de café suave a las 11:00",
                detail = "Siempre con poca azúcar y una galleta de avena.",
                learnedDate = "Aprendido ayer"
            ),
            FifoMemoryItem(
                id = "m3",
                emoji = "📻",
                title = "Radio Beethoven por las tardes",
                detail = "Le acompaña a descansar mientras teje o lee.",
                learnedDate = "Aprendido hoy"
            )
        )
    )
    val memories: StateFlow<List<FifoMemoryItem>> = _memories.asStateFlow()

    // ── 6. Recordatorios y Alarmas del Sistema ──────────────────
    private val _reminders = MutableStateFlow(
        listOf(
            FifoReminderItem(
                id = "rem_1",
                title = "Tomar pastilla de la presión (Enalapril)",
                timeStr = "20:00",
                category = "medication",
                isCompleted = false
            ),
            FifoReminderItem(
                id = "rem_2",
                title = "Llamar a Carmen (Hija)",
                timeStr = "17:30",
                category = "family",
                isCompleted = false
            ),
            FifoReminderItem(
                id = "rem_3",
                title = "Paseo de 15 minutos por la plaza",
                timeStr = "11:00",
                category = "health",
                isCompleted = true
            )
        )
    )
    val reminders: StateFlow<List<FifoReminderItem>> = _reminders.asStateFlow()

    // ── 7. Conversaciones Pasadas ───────────────────────────────
    private val _conversations = MutableStateFlow(
        listOf(
            PastConversationItem(
                id = "c1",
                title = "Charla sobre orquídeas y flores",
                date = "Hoy",
                duration = "4 min",
                summary = "Lucía le contó a Fifo cómo cuida sus plantas en maceteros de greda y lo feliz que le hace verlas florecer.",
                tag = "Jardinería",
                iconName = "heart"
            ),
            PastConversationItem(
                id = "c2",
                title = "Planes de fin de semana y música",
                date = "Ayer",
                duration = "6 min",
                summary = "Conversaron sobre conciertos de piano clásico y la visita que le hará su nieto este domingo.",
                tag = "Música",
                iconName = "music"
            ),
            PastConversationItem(
                id = "c3",
                title = "Recetas de cocina tradicional",
                date = "Hace 3 días",
                duration = "5 min",
                summary = "Fifo aprendió sobre la cazuela de ave casera y el secreto de dorar la cebolla con comino suave.",
                tag = "Cocina",
                iconName = "restaurant"
            )
        )
    )
    val conversations: StateFlow<List<PastConversationItem>> = _conversations.asStateFlow()

    // ══════════════════════════════════════════════════════════
    //  MUTACIONES (Llamadas por los Skills de Fifo o por la UI)
    // ══════════════════════════════════════════════════════════

    /**
     * Actualiza la información demográfica del usuario.
     */
    fun updateDemographics(
        fullName: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        gender: String? = null,
        city: String? = null
    ) {
        val current = _userProfile.value
        val newYear = birthYear ?: current.birthYear
        val newAge = if (birthYear != null) (2026 - birthYear) else current.estimatedAge

        val updated = current.copy(
            fullName = fullName?.trim()?.ifBlank { current.fullName } ?: current.fullName,
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
            birthDate = birthDate,
            birthYear = newYear,
            estimatedAge = newAge,
            gender = gender,
            city = city
        )
    }

    /**
     * Actualiza la biografía creada por la IA a partir de las conversaciones.
     */
    fun updateBio(newBio: String) {
        if (newBio.isNotBlank()) {
            val clean = newBio.trim()
            _userProfile.value = _userProfile.value.copy(bioAi = clean)
            dbHelper?.updateBio(_userProfile.value.id, clean)
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
            dbHelper?.addTaste(name = clean)
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
            dbHelper?.removeTaste(name = clean)
            return true
        }
        return false
    }

    /**
     * Añade una historia de vida ("Gusto en detalle").
     */
    fun addTasteStory(story: FifoTasteStory) {
        _tasteStories.value = listOf(story) + _tasteStories.value
        dbHelper?.addTasteStory(story = story)
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
        dbHelper?.addSocialPost(post = newPost)
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
        dbHelper?.addMemory(item = newMem)
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
        dbHelper?.addReminder(reminder = item)
        return item
    }

    /**
     * Marca un recordatorio como completado.
     */
    fun completeReminder(id: String) {
        _reminders.value = _reminders.value.map {
            if (it.id == id) it.copy(isCompleted = true) else it
        }
        dbHelper?.completeReminder(reminderId = id, completed = true)
    }

    /**
     * Registra una conversación pasada en el historial.
     */
    fun addConversation(item: PastConversationItem) {
        _conversations.value = listOf(item) + _conversations.value
    }

    /**
     * Elimina una conversación por id.
     */
    fun removeConversation(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
    }

    /**
     * Elimina un recuerdo por id.
     */
    fun removeMemory(id: String) {
        _memories.value = _memories.value.filterNot { it.id == id }
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
    private val _conversationFragments = MutableStateFlow(
        listOf(
            ConversationFragment(
                id = "frag_01",
                serverConversationId = "srv_conv_001",
                keyTopics = listOf("orquídeas", "riego por inmersión", "balcón", "maceteros de greda"),
                namedEntities = listOf("Lucía", "Fifo"),
                detectedMood = "contenta",
                compactSummary = "Lucía contó que tiene 4 maceteros de orquídeas en su balcón. Las riega por inmersión los miércoles y limpia las hojas con un paño húmedo mientras escucha la radio. Se mostró alegre hablando de sus plantas.",
                primaryTag = "Jardinería",
                durationSeconds = 240,
                recordedAt = "2026-10-03T10:30:00Z"
            ),
            ConversationFragment(
                id = "frag_02",
                serverConversationId = "srv_conv_002",
                keyTopics = listOf("Chopin", "conciertos de piano", "nieto Tomás", "visita domingo"),
                namedEntities = listOf("Lucía", "Tomás", "Chopin"),
                detectedMood = "ilusionada",
                compactSummary = "Conversaron sobre la música clásica y los nocturnos de Chopin. Lucía mencionó que su nieto Tomás la visitará el domingo y quiere enseñarle a escuchar piano. Se notó ilusionada por la visita.",
                primaryTag = "Música",
                durationSeconds = 360,
                recordedAt = "2026-10-02T16:00:00Z"
            ),
            ConversationFragment(
                id = "frag_03",
                serverConversationId = "srv_conv_003",
                keyTopics = listOf("cazuela de ave", "comino", "recetario viejo", "feria del barrio"),
                namedEntities = listOf("Lucía", "abuela Rosa"),
                detectedMood = "nostálgica",
                compactSummary = "Fifo aprendió sobre la cazuela de ave casera con el secreto de dorar la cebolla con comino suave. Lucía habló del recetario manuscrito de su abuela Rosa con más de 40 años y de los ingredientes frescos de la feria.",
                primaryTag = "Cocina",
                durationSeconds = 300,
                recordedAt = "2026-09-30T12:15:00Z"
            )
        )
    )
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
    }

    /**
     * Construye la ventana de contexto compacto para inyectar como prefijo
     * en el System Prompt o en los mensajes enviados a Claude.
     *
     * Retorna un bloque de texto con los últimos N fragmentos, incluyendo:
     * - Temas clave mencionados
     * - Entidades nombradas (personas, lugares)
     * - Estado anímico detectado
     * - Resumen compacto de cada charla
     *
     * @param maxFragments Cantidad máxima de fragmentos recientes a incluir (default 5)
     * @return String listo para inyectar como contexto previo a Claude
     */
    fun buildCompactContextWindow(maxFragments: Int = 5): String {
        val fragments = _conversationFragments.value.take(maxFragments)
        if (fragments.isEmpty()) return ""

        val profile = _userProfile.value
        val tastes = _tastes.value

        return buildString {
            appendLine("=== CONTEXTO PREVIO DEL USUARIO (fragmentos compactos) ===")
            appendLine("Nombre: ${profile.fullName} | Edad: ${profile.estimatedAge} | Ciudad: ${profile.city}")
            appendLine("Gustos conocidos: ${tastes.joinToString(", ")}")
            appendLine()

            fragments.forEachIndexed { i, frag ->
                appendLine("--- Charla reciente ${i + 1} (${frag.primaryTag}) ---")
                appendLine("Temas: ${frag.keyTopics.joinToString(", ")}")
                appendLine("Personas mencionadas: ${frag.namedEntities.joinToString(", ")}")
                appendLine("Ánimo: ${frag.detectedMood}")
                appendLine("Resumen: ${frag.compactSummary}")
                appendLine()
            }

            appendLine("=== Si necesitas más detalle sobre un tema pasado, usa la herramienta recall_past_context ===")
        }
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
        val queryLower = query.lowercase().trim()
        val allFragments = _conversationFragments.value

        val matchingFragments = allFragments.filter { frag ->
            frag.keyTopics.any { it.lowercase().contains(queryLower) } ||
            frag.namedEntities.any { it.lowercase().contains(queryLower) } ||
            frag.compactSummary.lowercase().contains(queryLower) ||
            frag.primaryTag.lowercase().contains(queryLower)
        }

        if (matchingFragments.isEmpty()) {
            return DeepContextResult(
                relevantExcerpts = emptyList(),
                foundEntities = emptyList(),
                synthesizedContext = "No encontré conversaciones anteriores sobre '$query'.",
                conversationsSearched = allFragments.size
            )
        }

        val excerpts = matchingFragments.map { it.compactSummary }
        val entities = matchingFragments.flatMap { it.namedEntities }.distinct()
        val synthesized = buildString {
            append("Encontré ${matchingFragments.size} charla(s) donde se habló de '$query'. ")
            matchingFragments.forEach { frag ->
                append("En la charla sobre ${frag.primaryTag}: ${frag.compactSummary} ")
            }
        }

        return DeepContextResult(
            relevantExcerpts = excerpts,
            foundEntities = entities,
            synthesizedContext = synthesized.trim(),
            conversationsSearched = allFragments.size
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  9. Rastreo de Ubicación del Dispositivo Fifo (ESP32)
    // ══════════════════════════════════════════════════════════════

    private val _deviceLocation = MutableStateFlow(
        FifoDeviceLocation(
            isConnected = false,
            lastConnectedTime = "Hoy a las 18:30",
            lastKnownLatitude = -33.4255,
            lastKnownLongitude = -70.6143,
            lastKnownAddress = "Av. Providencia 1234, Providencia, Santiago",
            lastKnownRoom = "Cerca del Living / Mesa de noche",
            signalStrengthRssi = -64,
            isBeeping = false
        )
    )
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
        val updatedLoc = current.copy(
            isConnected = connected,
            lastConnectedTime = if (connected) "Conectado ahora" else "Hoy a las 18:30",
            lastKnownLatitude = latitude ?: current.lastKnownLatitude,
            lastKnownLongitude = longitude ?: current.lastKnownLongitude,
            lastKnownAddress = address ?: current.lastKnownAddress,
            lastKnownRoom = roomHint ?: current.lastKnownRoom,
            signalStrengthRssi = rssi ?: current.signalStrengthRssi,
            isBeeping = if (!connected) false else current.isBeeping
        )
        _deviceLocation.value = updatedLoc
        dbHelper?.updateDeviceLocation(loc = updatedLoc)
    }

    /**
     * Activa o desactiva la alarma sonora en el robot para encontrarlo cuando se pierde.
     */
    fun triggerDeviceBeep(beeping: Boolean) {
        val updatedLoc = _deviceLocation.value.copy(isBeeping = beeping)
        _deviceLocation.value = updatedLoc
        dbHelper?.updateDeviceLocation(loc = updatedLoc)
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

